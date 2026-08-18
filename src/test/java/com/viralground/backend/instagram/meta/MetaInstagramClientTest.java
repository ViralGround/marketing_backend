package com.viralground.backend.instagram.meta;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MetaInstagramClientTest {

    private final MetaInstagramProperties properties = new MetaInstagramProperties(
            "app-id", "app-secret", "https://api.example/callback", "https://web.example/result",
            "key", "verify", "v25.0", null, "https://oauth.example", "https://graph.example",
            List.of(), Duration.ofMinutes(10), Duration.ofSeconds(3), Duration.ofSeconds(8),
            Duration.ZERO, Duration.ofDays(7), 2, 50, 3, 14);

    @Test
    void exchangesOneTimeCodeUsingFormBody() {
        RestClient.Builder oauthBuilder = RestClient.builder().baseUrl("https://oauth.example");
        RestClient.Builder graphBuilder = RestClient.builder().baseUrl("https://graph.example");
        MockRestServiceServer oauth = MockRestServiceServer.bindTo(oauthBuilder).build();
        MockRestServiceServer graph = MockRestServiceServer.bindTo(graphBuilder).build();
        oauth.expect(once(), requestTo("https://oauth.example/oauth/access_token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("client_id=app-id"),
                        org.hamcrest.Matchers.containsString("client_secret=app-secret"),
                        org.hamcrest.Matchers.containsString("code=one-time-code"))))
                .andRespond(withSuccess("{\"access_token\":\"short-secret\",\"user_id\":\"1789\"}",
                        MediaType.APPLICATION_JSON));
        MetaInstagramClient client = new MetaInstagramClient(
                properties, oauthBuilder.build(), graphBuilder.build(), ignored -> {});

        MetaInstagramClient.ShortToken token = client.exchangeCode("one-time-code");

        assertThat(token.accountId()).isEqualTo("1789");
        assertThat(token.accessToken()).isEqualTo("short-secret");
        oauth.verify();
        graph.verify();
    }

    @Test
    void retriesRetryableGraphFailureOnlyWithinConfiguredBound() {
        RestClient.Builder oauthBuilder = RestClient.builder().baseUrl("https://oauth.example");
        RestClient.Builder graphBuilder = RestClient.builder().baseUrl("https://graph.example");
        MockRestServiceServer oauth = MockRestServiceServer.bindTo(oauthBuilder).build();
        MockRestServiceServer graph = MockRestServiceServer.bindTo(graphBuilder).build();
        String uri = "https://graph.example/v25.0/me?fields=id,user_id,username,account_type";
        graph.expect(once(), requestTo(uri)).andRespond(withServerError());
        graph.expect(once(), requestTo(uri)).andRespond(withSuccess(
                "{\"id\":\"1789\",\"username\":\"creator\",\"account_type\":\"CREATOR\"}",
                MediaType.APPLICATION_JSON));
        MetaInstagramClient client = new MetaInstagramClient(
                properties, oauthBuilder.build(), graphBuilder.build(), ignored -> {});

        MetaInstagramClient.Account account = client.fetchCurrentAccount("bearer-secret");

        assertThat(account.id()).isEqualTo("1789");
        assertThat(account.username()).isEqualTo("creator");
        oauth.verify();
        graph.verify();
    }
}
