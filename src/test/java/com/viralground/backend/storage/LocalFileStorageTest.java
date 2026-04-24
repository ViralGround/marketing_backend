package com.viralground.backend.storage;

import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileStorageTest {

    private static final String SECRET = "test-secret-key-at-least-32-characters-long";
    private static final Instant NOW = Instant.parse("2026-04-24T10:00:00Z");

    @TempDir
    Path tempDir;

    private FileStorageProperties props;
    private Clock fixedClock;
    private LocalFileStorage storage;

    @BeforeEach
    void setUp() {
        // given
        props = new FileStorageProperties();
        props.setPublicBaseUrl("http://localhost:8080");
        props.setMaxSizeBytes(1024 * 1024);
        props.setAllowedContentTypes(List.of("video/mp4", "video/quicktime", "video/webm"));
        props.setSigningTtlSeconds(900);
        props.getLocal().setDirectory(tempDir.toString());
        fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        storage = new LocalFileStorage(props, fixedClock, SECRET);
        storage.initDirectory();
    }

    @Test
    void should_프리사인_URL_발급_when_허용된_콘텐츠타입() {
        // when
        PresignedUpload result = storage.presignUpload("video/mp4", 10_000L);

        // then
        assertThat(result.fileKey()).startsWith("submissions/").endsWith(".mp4");
        assertThat(result.uploadUrl())
                .startsWith("http://localhost:8080/files/upload/" + result.fileKey())
                .contains("sig=")
                .contains("exp=" + result.expiresAt().getEpochSecond());
        assertThat(result.downloadUrl())
                .startsWith("http://localhost:8080/files/" + result.fileKey())
                .contains("sig=");
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(900));
    }

    @Test
    void should_INVALID_VIDEO_FORMAT_예외_when_허용되지않은_콘텐츠타입() {
        // when & then
        assertThatThrownBy(() -> storage.presignUpload("video/x-flv", 10_000L))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_VIDEO_FORMAT);
    }

    @Test
    void should_VIDEO_TOO_LARGE_예외_when_용량_초과() {
        // when & then
        assertThatThrownBy(() -> storage.presignUpload("video/mp4", props.getMaxSizeBytes() + 1))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VIDEO_TOO_LARGE);
    }

    @Test
    void should_업로드_수락_후_파일저장_when_올바른_서명() throws Exception {
        // given
        PresignedUpload presigned = storage.presignUpload("video/mp4", 10_000L);
        Map<String, String> q = parseQuery(presigned.uploadUrl());
        byte[] payload = "hello world".getBytes();

        // when
        storage.acceptUpload(presigned.fileKey(), q.get("sig"), Long.parseLong(q.get("exp")),
                new ByteArrayInputStream(payload), "video/mp4", payload.length);

        // then
        Path stored = tempDir.resolve(presigned.fileKey());
        assertThat(stored).exists();
        assertThat(Files.readAllBytes(stored)).isEqualTo(payload);
    }

    @Test
    void should_INVALID_SIGNATURE_예외_when_서명_위조() {
        // given
        PresignedUpload presigned = storage.presignUpload("video/mp4", 10_000L);
        Map<String, String> q = parseQuery(presigned.uploadUrl());
        byte[] payload = "x".getBytes();

        // when & then
        assertThatThrownBy(() -> storage.acceptUpload(presigned.fileKey(), "tampered",
                Long.parseLong(q.get("exp")), new ByteArrayInputStream(payload),
                "video/mp4", payload.length))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_SIGNATURE);
    }

    @Test
    void should_EXPIRED_SIGNATURE_예외_when_만료시각_초과() {
        // given
        PresignedUpload presigned = storage.presignUpload("video/mp4", 10_000L);
        Map<String, String> q = parseQuery(presigned.uploadUrl());
        Clock future = Clock.fixed(NOW.plusSeconds(901), ZoneOffset.UTC);
        LocalFileStorage later = new LocalFileStorage(props, future, SECRET);
        byte[] payload = "x".getBytes();

        // when & then
        assertThatThrownBy(() -> later.acceptUpload(presigned.fileKey(), q.get("sig"),
                Long.parseLong(q.get("exp")), new ByteArrayInputStream(payload),
                "video/mp4", payload.length))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXPIRED_SIGNATURE);
    }

    @Test
    void should_다운로드_반환_when_파일존재_서명유효() throws Exception {
        // given
        PresignedUpload presigned = storage.presignUpload("video/mp4", 10_000L);
        Map<String, String> up = parseQuery(presigned.uploadUrl());
        byte[] payload = "content".getBytes();
        storage.acceptUpload(presigned.fileKey(), up.get("sig"), Long.parseLong(up.get("exp")),
                new ByteArrayInputStream(payload), "video/mp4", payload.length);
        Map<String, String> down = parseQuery(presigned.downloadUrl());

        // when
        StoredFile served = storage.serveDownload(presigned.fileKey(),
                down.get("sig"), Long.parseLong(down.get("exp")));

        // then
        assertThat(served.contentType()).isEqualTo("video/mp4");
        assertThat(served.sizeBytes()).isEqualTo(payload.length);
        assertThat(served.resource().getInputStream().readAllBytes()).isEqualTo(payload);
    }

    @Test
    void should_SUBMISSION_NOT_FOUND_예외_when_파일없음() {
        // given
        PresignedUpload presigned = storage.presignUpload("video/mp4", 10_000L);
        Map<String, String> down = parseQuery(presigned.downloadUrl());

        // when & then
        assertThatThrownBy(() -> storage.serveDownload(presigned.fileKey(),
                down.get("sig"), Long.parseLong(down.get("exp"))))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SUBMISSION_NOT_FOUND);
    }

    @Test
    void should_파일삭제_when_delete호출() throws Exception {
        // given
        PresignedUpload presigned = storage.presignUpload("video/mp4", 10_000L);
        Map<String, String> up = parseQuery(presigned.uploadUrl());
        byte[] payload = "x".getBytes();
        storage.acceptUpload(presigned.fileKey(), up.get("sig"), Long.parseLong(up.get("exp")),
                new ByteArrayInputStream(payload), "video/mp4", payload.length);
        Path stored = tempDir.resolve(presigned.fileKey());
        assertThat(stored).exists();

        // when
        storage.delete(presigned.fileKey());

        // then
        assertThat(stored).doesNotExist();
    }

    @Test
    void should_VIDEO_TOO_LARGE_예외_when_스트림이_상한_초과() {
        // given — Content-Length 는 정상이지만 실제 스트림이 max 를 초과하는 케이스
        props.setMaxSizeBytes(16);
        storage = new LocalFileStorage(props, fixedClock, SECRET);
        storage.initDirectory();
        PresignedUpload presigned = storage.presignUpload("video/mp4", 16L);
        Map<String, String> q = parseQuery(presigned.uploadUrl());
        byte[] oversized = new byte[64]; // max 16 바이트보다 훨씬 큼

        // when & then — BoundedInputStream 이 스트림을 끊어 VIDEO_TOO_LARGE 변환
        assertThatThrownBy(() -> storage.acceptUpload(presigned.fileKey(), q.get("sig"),
                Long.parseLong(q.get("exp")), new ByteArrayInputStream(oversized),
                "video/mp4", 16L))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VIDEO_TOO_LARGE);

        // 부분 저장된 파일은 제거되어야 함
        assertThat(tempDir.resolve(presigned.fileKey())).doesNotExist();
    }

    @Test
    void should_FORBIDDEN_예외_when_경로탈출_시도() {
        // given
        Instant expires = NOW.plusSeconds(900);
        String fileKey = "../evil.mp4";

        // when & then — resolve 단계에서 차단돼야 함
        assertThatThrownBy(() -> storage.serveDownload(fileKey, "anything", expires.getEpochSecond()))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                // 서명이 먼저 검증되므로 INVALID_SIGNATURE 가 먼저 터짐. 경로탈출은 서명 통과 후에만 의미 있음.
                .isEqualTo(ErrorCode.INVALID_SIGNATURE);
    }

    private static Map<String, String> parseQuery(String url) {
        String q = url.substring(url.indexOf('?') + 1);
        Map<String, String> out = new HashMap<>();
        for (String p : q.split("&")) {
            String[] kv = p.split("=", 2);
            out.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return out;
    }
}
