package com.viralground.backend.instagram;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InstagramUrlTest {

    @Test
    void reel_url_의_shortcode_를_추출한다() {
        assertThat(InstagramUrl.shortcode("https://www.instagram.com/reel/ABC123def/"))
                .contains("ABC123def");
    }

    @Test
    void 쿼리스트링과_트레일링슬래시가_있어도_추출한다() {
        assertThat(InstagramUrl.shortcode("https://instagram.com/reel/XyZ_-9/?igshid=abc"))
                .contains("XyZ_-9");
    }

    @Test
    void p_와_tv_와_reels_형식도_지원한다() {
        assertThat(InstagramUrl.shortcode("https://www.instagram.com/p/Post123/")).contains("Post123");
        assertThat(InstagramUrl.shortcode("https://www.instagram.com/tv/Tv456/")).contains("Tv456");
        assertThat(InstagramUrl.shortcode("https://www.instagram.com/reels/Reels7/")).contains("Reels7");
    }

    @Test
    void 형식을_인식하지_못하면_empty() {
        assertThat(InstagramUrl.shortcode("https://example.com/not-a-reel")).isEmpty();
        assertThat(InstagramUrl.shortcode("")).isEmpty();
        assertThat(InstagramUrl.shortcode(null)).isEmpty();
    }
}
