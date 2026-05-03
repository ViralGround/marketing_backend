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

class LocalFileStorageImageTest {

    private static final String SECRET = "test-secret-key-at-least-32-characters-long";
    private static final Instant NOW = Instant.parse("2026-05-02T10:00:00Z");

    @TempDir
    Path tempDir;

    private FileStorageProperties props;
    private LocalFileStorage storage;

    @BeforeEach
    void setUp() {
        // given
        props = new FileStorageProperties();
        props.setPublicBaseUrl("http://localhost:8080");
        props.setMaxSizeBytes(1024 * 1024);
        props.setAllowedContentTypes(List.of("video/mp4", "video/quicktime", "video/webm"));
        props.setMaxImageSizeBytes(1024 * 1024);
        props.setAllowedImageContentTypes(List.of("image/jpeg", "image/png", "image/webp"));
        props.setSigningTtlSeconds(900);
        props.getLocal().setDirectory(tempDir.toString());
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        storage = new LocalFileStorage(props, fixedClock, SECRET);
        storage.initDirectory();
    }

    @Test
    void should_thumbnails_prefix_when_image_png() {
        // when
        PresignedUpload result = storage.presignImageUpload("image/png", 10_000L);

        // then
        assertThat(result.fileKey()).startsWith("thumbnails/").endsWith(".png");
        assertThat(result.uploadUrl())
                .startsWith("http://localhost:8080/files/upload/" + result.fileKey())
                .contains("sig=");
    }

    @Test
    void should_INVALID_IMAGE_FORMAT_when_image_gif() {
        // when & then
        assertThatThrownBy(() -> storage.presignImageUpload("image/gif", 10_000L))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_IMAGE_FORMAT);
    }

    @Test
    void should_INVALID_IMAGE_FORMAT_when_video_타입을_image_업로드로() {
        // 영상 타입을 이미지 업로드 메서드로 보내면 거부 — 룰 분리 검증
        assertThatThrownBy(() -> storage.presignImageUpload("video/mp4", 10_000L))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_IMAGE_FORMAT);
    }

    @Test
    void should_IMAGE_TOO_LARGE_when_size_초과() {
        // when & then
        assertThatThrownBy(() -> storage.presignImageUpload("image/png", props.getMaxImageSizeBytes() + 1))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.IMAGE_TOO_LARGE);
    }

    @Test
    void should_업로드_수락_및_저장_when_올바른_이미지_서명() throws Exception {
        // given
        PresignedUpload presigned = storage.presignImageUpload("image/png", 10_000L);
        Map<String, String> q = parseQuery(presigned.uploadUrl());
        byte[] payload = "fake-png-bytes".getBytes();

        // when
        storage.acceptUpload(presigned.fileKey(), q.get("sig"), Long.parseLong(q.get("exp")),
                new ByteArrayInputStream(payload), "image/png", payload.length);

        // then
        Path stored = tempDir.resolve(presigned.fileKey());
        assertThat(stored).exists();
        assertThat(Files.readAllBytes(stored)).isEqualTo(payload);
    }

    @Test
    void should_다운로드_컨텐츠타입_image_png_when_확장자_png() throws Exception {
        // given
        PresignedUpload presigned = storage.presignImageUpload("image/png", 10_000L);
        Map<String, String> up = parseQuery(presigned.uploadUrl());
        byte[] payload = "x".getBytes();
        storage.acceptUpload(presigned.fileKey(), up.get("sig"), Long.parseLong(up.get("exp")),
                new ByteArrayInputStream(payload), "image/png", payload.length);
        Map<String, String> down = parseQuery(presigned.downloadUrl());

        // when
        StoredFile served = storage.serveDownload(presigned.fileKey(),
                down.get("sig"), Long.parseLong(down.get("exp")));

        // then
        assertThat(served.contentType()).isEqualTo("image/png");
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
