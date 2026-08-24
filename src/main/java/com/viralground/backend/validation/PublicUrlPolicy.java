package com.viralground.backend.validation;

import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/** 공개 프로필에 저장되어 브라우저 링크로 노출되는 외부 URL 정책. */
public final class PublicUrlPolicy {
    public static final int MAX_URL_LENGTH = 500;

    private static final Pattern IPV4 = Pattern.compile("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$");
    private static final Pattern PUBLIC_HOSTNAME = Pattern.compile(
            "^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
                    + "(?:[a-z]{2,63}|xn--[a-z0-9-]{2,59})$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("[\\p{Cntrl}]");

    private PublicUrlPolicy() {
    }

    /** null/공백은 미설정(null)으로 정규화하고, 그 외 값은 검증 후 앞뒤 공백을 제거한다. */
    public static String normalizeOptional(String rawUrl) {
        if (rawUrl == null) return null;
        if (CONTROL_CHARACTER.matcher(rawUrl).find()) throw invalid();

        String normalized = rawUrl.trim();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > MAX_URL_LENGTH) throw invalid();

        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }

        String host = uri.getHost();
        if (!uri.isAbsolute()
                || !"https".equals(uri.getScheme())
                || host == null
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || uri.getPort() < -1
                || uri.getPort() > 65_535
                || !isPublicHost(host)) {
            throw invalid();
        }
        return normalized;
    }

    /**
     * 사용자 제출물처럼 반드시 존재해야 하는 공개 URL. 선택형 홈페이지와 달리 앞뒤
     * 공백도 입력 오류로 거부해 서명·중복 판단과 브라우저 노출값을 하나로 유지한다.
     */
    public static String normalizeRequired(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank() || !rawUrl.equals(rawUrl.trim())) throw invalid();
        String normalized = normalizeOptional(rawUrl);
        if (normalized == null) throw invalid();
        return normalized;
    }

    private static boolean isPublicHost(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.equals("localhost") || host.equals("::") || host.equals("::1")
                || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal") || host.endsWith(".test")
                || host.endsWith(".example") || host.endsWith(".invalid")) {
            return false;
        }
        if (host.contains(":")) {
            // IP literal은 사설/예약 대역 판단 실수가 없도록 공개 홈페이지에 허용하지 않는다.
            return false;
        }
        if (IPV4.matcher(host).matches()) {
            return isPublicIpv4(host);
        }
        return PUBLIC_HOSTNAME.matcher(host).matches();
    }

    private static boolean isPublicIpv4(String host) {
        String[] pieces = host.split("\\.");
        int[] octets = new int[4];
        for (int i = 0; i < pieces.length; i++) {
            try {
                octets[i] = Integer.parseInt(pieces[i]);
            } catch (NumberFormatException invalidNumber) {
                return false;
            }
            if (octets[i] > 255) return false;
        }
        int first = octets[0];
        int second = octets[1];
        return first != 0 && first != 10 && first != 127 && first < 224
                && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 169 && second == 254)
                && !(first == 172 && second >= 16 && second <= 31)
                && !(first == 192 && second == 0)
                && !(first == 192 && second == 88)
                && !(first == 192 && second == 168)
                && !(first == 198 && (second == 18 || second == 19 || second == 51))
                && !(first == 203 && second == 0 && octets[2] == 113);
    }

    private static AppException invalid() {
        return new AppException(ErrorCode.INVALID_PUBLIC_URL);
    }
}
