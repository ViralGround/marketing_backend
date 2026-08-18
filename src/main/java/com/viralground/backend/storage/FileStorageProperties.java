package com.viralground.backend.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
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

    /** AWS S3 또는 S3 호환(R2 등) 객체 저장소 설정. */
    private S3 s3 = new S3();

    @Getter
    @Setter
    public static class Local {
        /** 로컬 스토리지 루트 디렉토리. */
        private String directory = "./uploads";
    }

    @Getter
    @Setter
    public static class S3 {
        /** 비워두면 AWS SDK의 리전별 기본 S3 endpoint를 사용한다. */
        private String endpoint = "";

        /** AWS region. Cloudflare R2는 통상 auto를 사용한다. */
        private String region = "";

        private String bucket = "";

        /** static | default-chain (IRSA/ECS/EC2 환경 권장). */
        private String credentialsMode = "static";

        private String accessKey = "";
        private String secretKey = "";
        private String sessionToken = "";

        /** R2/MinIO 등 S3 호환 서비스에서 필요한 path-style 주소 방식. */
        private boolean pathStyle = true;

        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(10);
    }
}
