package com.viralground.backend.instagram.phyllo;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link PhylloClient#fetchContents} 가 GET /v1/social/contents 응답을 파싱하는지 검증.
 * 실제 Phyllo 서버 호출 없이 MockRestServiceServer 로 응답을 고정한다.
 */
class PhylloClientContentsTest {

    @Test
    void fetchContents_가_콘텐츠와_인게이지먼트를_파싱한다() {
        // given
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PhylloClient client = new PhylloClient(builder.build());

        String body = """
                {
                  "data": [
                    {
                      "url": "https://www.instagram.com/reel/ABC123/",
                      "external_id": "media-1",
                      "format": "VIDEO",
                      "engagement": {
                        "like_count": 100,
                        "comment_count": 20,
                        "view_count": 1000,
                        "share_count": 8,
                        "save_count": 3
                      }
                    }
                  ],
                  "metadata": { "offset": 0, "limit": 100 }
                }
                """;

        server.expect(requestTo(containsString("/v1/social/contents")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("account_id", "acc-1"))
                .andExpect(queryParam("limit", "100"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        // when
        List<PhylloContent> contents = client.fetchContents("acc-1");

        // then
        assertThat(contents).hasSize(1);
        PhylloContent c = contents.get(0);
        assertThat(c.url()).isEqualTo("https://www.instagram.com/reel/ABC123/");
        assertThat(c.engagement().viewCount()).isEqualTo(1000L);
        assertThat(c.engagement().likeCount()).isEqualTo(100L);
        assertThat(c.engagement().commentCount()).isEqualTo(20L);
        assertThat(c.engagement().shareCount()).isEqualTo(8L);
        server.verify();
    }

    @Test
    void fetchUsername_이_연결계정_username_을_파싱한다() {
        // given
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PhylloClient client = new PhylloClient(builder.build());

        String body = """
                { "id": "acc-1", "username": "creator.ig", "platform_username": "creator.ig" }
                """;
        server.expect(requestTo(containsString("/v1/accounts/acc-1")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        // when / then
        assertThat(client.fetchUsername("acc-1")).isEqualTo("creator.ig");
        server.verify();
    }

    @Test
    void findConnectedAccountId_는_CONNECTED_계정만_반환한다() {
        // given
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PhylloClient client = new PhylloClient(builder.build());

        String body = """
                { "data": [
                    { "id": "acc-old", "status": "NOT_CONNECTED" },
                    { "id": "acc-live", "status": "CONNECTED" }
                ] }
                """;
        server.expect(requestTo(containsString("/v1/accounts")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("user_id", "user-1"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        // when / then — CONNECTED 상태인 계정 id 만 반환
        assertThat(client.findConnectedAccountId("user-1")).contains("acc-live");
        server.verify();
    }
}
