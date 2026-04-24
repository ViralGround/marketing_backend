package com.viralground.backend.storage;

import org.springframework.core.io.Resource;

public record StoredFile(Resource resource, String contentType, long sizeBytes) {}
