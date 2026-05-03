package com.viralground.backend.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "files")
@Getter
@Setter
public class FileStorageProperties {

    /** 스토리지 구현 선택: local | s3 */
    private String storage = "local";

    /** 클라이언트가 접근할 때 사용할 베이스 URL (presigned URL 생성에 이용). */
    private String publicBaseUrl = "http://localhost:8080";

    /** 최대 업로드 크기 (bytes). 기본 500MB. */
    private long maxSizeBytes = 524_288_000L;

    /** 허용 콘텐츠 타입 화이트리스트. */
    private List<String> allowedContentTypes = List.of("video/mp4", "video/quicktime", "video/webm");

    /** 이미지 업로드 허용 콘텐츠 타입. */
    private List<String> allowedImageContentTypes = List.of("image/jpeg", "image/png", "image/webp");

    /** 이미지 업로드 최대 크기 (bytes). 기본 10MB. */
    private long maxImageSizeBytes = 10_485_760L;

    /** 서명 URL 유효 시간 (초). 기본 15분. */
    private long signingTtlSeconds = 900;

    private Local local = new Local();

    @Getter
    @Setter
    public static class Local {
        /** 로컬 스토리지 루트 디렉토리. */
        private String directory = "./uploads";
    }
}
