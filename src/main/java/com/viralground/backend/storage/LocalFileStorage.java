package com.viralground.backend.storage;

import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * 로컬 디스크 기반 파일 스토리지. HMAC-SHA256 서명 URL 로 업로드/다운로드 권한을 제어.
 * 콘텐츠 타입/용량 화이트리스트는 업로드 시점·수신 시점 양쪽에서 검증.
 */
@Component
@ConditionalOnProperty(name = "files.storage", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStorage {

    private static final Map<String, String> EXT_BY_TYPE = Map.of(
            "video/mp4", "mp4",
            "video/quicktime", "mov",
            "video/webm", "webm"
    );

    private final FileStorageProperties props;
    private final Clock clock;
    private final String signingSecret;

    public LocalFileStorage(FileStorageProperties props,
                            Clock clock,
                            @Value("${jwt.secret}") String signingSecret) {
        this.props = props;
        this.clock = clock;
        this.signingSecret = signingSecret;
    }

    @PostConstruct
    void initDirectory() {
        try {
            Files.createDirectories(root());
        } catch (IOException e) {
            throw new IllegalStateException("업로드 디렉토리 생성 실패: " + props.getLocal().getDirectory(), e);
        }
    }

    @Override
    public PresignedUpload presignUpload(String contentType, long sizeBytes) {
        validateUpload(contentType, sizeBytes);
        String ext = EXT_BY_TYPE.get(contentType);
        String fileKey = "submissions/" + UUID.randomUUID() + "." + ext;
        Instant expires = clock.instant().plusSeconds(props.getSigningTtlSeconds());

        String uploadUrl = buildSignedUrl("/files/upload/", fileKey, "PUT", expires);
        String downloadUrl = buildSignedUrl("/files/", fileKey, "GET", expires);
        return new PresignedUpload(fileKey, uploadUrl, downloadUrl, expires);
    }

    @Override
    public String signedDownloadUrl(String fileKey) {
        Instant expires = clock.instant().plusSeconds(props.getSigningTtlSeconds());
        return buildSignedUrl("/files/", fileKey, "GET", expires);
    }

    @Override
    public void delete(String fileKey) {
        try {
            Files.deleteIfExists(resolve(fileKey));
        } catch (IOException e) {
            throw new IllegalStateException("파일 삭제 실패: " + fileKey, e);
        }
    }

    /**
     * 로컬 구현 전용 업로드 수신. 서명·콘텐츠 타입·용량을 검증하고 디스크에 저장.
     */
    public void acceptUpload(String fileKey, String sig, long exp, InputStream in,
                             String contentType, long sizeBytes) {
        verify(fileKey, "PUT", sig, exp);
        validateUpload(contentType, sizeBytes);

        Path target = resolve(fileKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(target) > props.getMaxSizeBytes()) {
                Files.deleteIfExists(target);
                throw new AppException(ErrorCode.VIDEO_TOO_LARGE);
            }
        } catch (IOException e) {
            throw new IllegalStateException("파일 저장 실패: " + fileKey, e);
        }
    }

    /**
     * 로컬 구현 전용 다운로드. 서명 검증 후 스트리밍 리소스 반환.
     */
    public StoredFile serveDownload(String fileKey, String sig, long exp) {
        verify(fileKey, "GET", sig, exp);
        Path path = resolve(fileKey);
        if (!Files.exists(path)) {
            throw new AppException(ErrorCode.SUBMISSION_NOT_FOUND);
        }
        try {
            long size = Files.size(path);
            InputStream stream = Files.newInputStream(path);
            return new StoredFile(new InputStreamResource(stream), inferContentType(fileKey), size);
        } catch (IOException e) {
            throw new IllegalStateException("파일 읽기 실패: " + fileKey, e);
        }
    }

    private void validateUpload(String contentType, long sizeBytes) {
        if (contentType == null || !props.getAllowedContentTypes().contains(contentType)
                || !EXT_BY_TYPE.containsKey(contentType)) {
            throw new AppException(ErrorCode.INVALID_VIDEO_FORMAT);
        }
        if (sizeBytes <= 0 || sizeBytes > props.getMaxSizeBytes()) {
            throw new AppException(ErrorCode.VIDEO_TOO_LARGE);
        }
    }

    private String buildSignedUrl(String pathPrefix, String fileKey, String op, Instant expires) {
        String sig = sign(fileKey, op, expires);
        return props.getPublicBaseUrl() + pathPrefix + fileKey
                + "?sig=" + URLEncoder.encode(sig, StandardCharsets.UTF_8)
                + "&exp=" + expires.getEpochSecond();
    }

    private String sign(String fileKey, String op, Instant expires) {
        return hmac(fileKey + "|" + op + "|" + expires.getEpochSecond());
    }

    private void verify(String fileKey, String op, String sig, long exp) {
        if (sig == null || clock.instant().isAfter(Instant.ofEpochSecond(exp))) {
            throw new AppException(ErrorCode.EXPIRED_SIGNATURE);
        }
        String expected = hmac(fileKey + "|" + op + "|" + exp);
        if (!constantTimeEquals(expected, sig)) {
            throw new AppException(ErrorCode.INVALID_SIGNATURE);
        }
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("서명 생성 실패", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }

    private Path root() {
        return Path.of(props.getLocal().getDirectory()).toAbsolutePath().normalize();
    }

    private Path resolve(String fileKey) {
        Path root = root();
        Path target = root.resolve(fileKey).normalize();
        if (!target.startsWith(root)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
        return target;
    }

    private static String inferContentType(String fileKey) {
        int dot = fileKey.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        return switch (fileKey.substring(dot + 1).toLowerCase()) {
            case "mp4" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "webm" -> "video/webm";
            default -> "application/octet-stream";
        };
    }
}
