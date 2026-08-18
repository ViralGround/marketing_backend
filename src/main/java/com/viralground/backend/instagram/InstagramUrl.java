package com.viralground.backend.instagram;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 인스타그램 permalink 에서 shortcode(릴스/게시물 식별자)를 추출한다.
 * 크리에이터가 제출한 릴스 URL과 Meta가 반환한 미디어 permalink를 shortcode 기준으로 매칭한다.
 *
 * <p>지원 형식: {@code /reel/{code}}, {@code /reels/{code}}, {@code /p/{code}}, {@code /tv/{code}}.
 * 쿼리스트링·트레일링 슬래시·www 유무에 무관하게 동작한다.
 */
public final class InstagramUrl {

    private static final Pattern SHORTCODE = Pattern.compile("/(?:reels|reel|p|tv)/([A-Za-z0-9_-]+)");

    private InstagramUrl() {}

    /** permalink 에서 shortcode 를 추출한다. 형식을 인식하지 못하면 {@code empty}. */
    public static Optional<String> shortcode(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        Matcher m = SHORTCODE.matcher(url);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
