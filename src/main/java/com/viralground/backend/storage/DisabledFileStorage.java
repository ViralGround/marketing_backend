package com.viralground.backend.storage;

import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 저장소 공급자 확정 전 비거래형 출시에서 모든 upload mutation을 fail-closed한다. */
@Service
@ConditionalOnProperty(name = "files.storage", havingValue = "disabled")
public class DisabledFileStorage implements FileStorage {

    @Override
    public PresignedUpload presignUpload(String contentType, long sizeBytes) {
        throw disabled();
    }

    @Override
    public PresignedUpload presignImageUpload(String contentType, long sizeBytes) {
        throw disabled();
    }

    @Override
    public String signedDownloadUrl(String fileKey) {
        return null;
    }

    @Override
    public void delete(String fileKey) {
        // disabled 환경은 외부 저장소에 접근하지 않는다.
    }

    @Override
    public void verifyUploadedObject(String fileKey, String expectedContentType, long expectedSizeBytes) {
        throw disabled();
    }

    @Override
    public boolean exists(String fileKey) {
        return false;
    }

    private AppException disabled() {
        return new AppException(ErrorCode.UPLOAD_FEATURE_DISABLED);
    }
}
