package com.viralground.backend.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 최대 limit 바이트까지만 읽고, 그 이상이면 {@link LimitExceededException} 을 던지는 InputStream 래퍼.
 * 업로드 수신 시 Content-Length 가 신뢰되지 않는(chunked 등) 경우에도 본문이 제한을 넘기면 즉시 끊기 위해 사용.
 */
public final class BoundedInputStream extends FilterInputStream {

    private final long limit;
    private long read;

    public BoundedInputStream(InputStream in, long limit) {
        super(in);
        this.limit = limit;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b == -1) return -1;
        if (++read > limit) throw new LimitExceededException(limit);
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n <= 0) return n;
        read += n;
        if (read > limit) throw new LimitExceededException(limit);
        return n;
    }

    public static class LimitExceededException extends IOException {
        public LimitExceededException(long limit) {
            super("업로드 크기가 상한(" + limit + " bytes)을 초과했습니다");
        }
    }
}
