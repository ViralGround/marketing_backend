package com.viralground.backend.instagram.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.viralground.backend.instagram.InstagramIntegrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.time.Clock;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/** Meta HTTP 경계를 한 곳에 모아 timeout, bounded retry, 안전한 오류 변환을 적용한다. */
public class MetaInstagramClient {

    private static final Logger log = LoggerFactory.getLogger(MetaInstagramClient.class);

    private final MetaInstagramProperties properties;
    private final RestClient oauthClient;
    private final RestClient graphClient;
    private final Sleeper sleeper;
    private final Clock clock;

    public MetaInstagramClient(MetaInstagramProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public MetaInstagramClient(MetaInstagramProperties properties, Clock clock) {
        properties.requireConfigured();
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.properties = properties;
        this.oauthClient = RestClient.builder()
                .baseUrl(properties.oauthBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.graphClient = RestClient.builder()
                .baseUrl(properties.graphBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.sleeper = Thread::sleep;
        this.clock = clock;
    }

    MetaInstagramClient(MetaInstagramProperties properties, RestClient oauthClient,
                        RestClient graphClient, Sleeper sleeper) {
        this(properties, oauthClient, graphClient, sleeper, Clock.systemUTC());
    }

    MetaInstagramClient(MetaInstagramProperties properties, RestClient oauthClient,
                        RestClient graphClient, Sleeper sleeper, Clock clock) {
        this.properties = properties;
        this.oauthClient = oauthClient;
        this.graphClient = graphClient;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    /** authorization code는 일회용이므로 자동 재시도하지 않는다. */
    public ShortToken exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.appId());
        form.add("client_secret", properties.appSecret());
        form.add("grant_type", "authorization_code");
        form.add("redirect_uri", properties.redirectUri());
        form.add("code", code);
        try {
            JsonNode body = oauthClient.post()
                    .uri("/oauth/access_token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            return new ShortToken(requiredText(body, "access_token"), requiredText(body, "user_id"));
        } catch (RestClientResponseException e) {
            throw upstream(e);
        }
    }

    public LongToken exchangeLongLived(String shortToken) {
        JsonNode body = retrying("long_token_exchange", () -> graphClient.get()
                .uri(uri -> uri.path("/access_token")
                        .queryParam("grant_type", "ig_exchange_token")
                        .queryParam("client_secret", properties.appSecret())
                        .queryParam("access_token", shortToken)
                        .build())
                .retrieve().body(JsonNode.class));
        return tokenFrom(body);
    }

    public LongToken refreshLongLived(String token) {
        JsonNode body = retrying("long_token_refresh", () -> graphClient.get()
                .uri(uri -> uri.path("/refresh_access_token")
                        .queryParam("grant_type", "ig_refresh_token")
                        .queryParam("access_token", token)
                        .build())
                .retrieve().body(JsonNode.class));
        return tokenFrom(body);
    }

    public Account fetchCurrentAccount(String accessToken) {
        JsonNode body = retrying("account_fetch", () -> graphClient.get()
                .uri(uri -> uri.path("/{version}/me")
                        .queryParam("fields", "id,user_id,username,account_type")
                        .build(properties.apiVersion()))
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve().body(JsonNode.class));
        String id = text(body, "user_id");
        if (id == null) {
            id = text(body, "id");
        }
        return new Account(required(id), requiredText(body, "username"), text(body, "account_type"));
    }

    public MediaPage fetchMedia(String accountId, String accessToken, String after) {
        JsonNode body = retrying("media_list", () -> graphClient.get()
                .uri(uri -> {
                    var builder = uri.path("/{version}/{accountId}/media")
                            .queryParam("fields", "id,permalink,media_type,timestamp")
                            .queryParam("limit", properties.mediaPageSize());
                    if (after != null && !after.isBlank()) {
                        builder.queryParam("after", after);
                    }
                    return builder.build(properties.apiVersion(), accountId);
                })
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve().body(JsonNode.class));

        List<Media> media = new ArrayList<>();
        if (body != null && body.path("data").isArray()) {
            for (JsonNode item : body.path("data")) {
                String id = text(item, "id");
                String permalink = text(item, "permalink");
                if (id != null && permalink != null) {
                    media.add(new Media(id, permalink, text(item, "media_type")));
                }
            }
        }
        String next = body == null ? null : text(body.path("paging").path("cursors"), "after");
        return new MediaPage(List.copyOf(media), next);
    }

    public MediaCounts fetchMediaCounts(String mediaId, String accessToken) {
        JsonNode body = retrying("media_counts", () -> graphClient.get()
                .uri(uri -> uri.path("/{version}/{mediaId}")
                        .queryParam("fields", "like_count,comments_count")
                        .build(properties.apiVersion(), mediaId))
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve().body(JsonNode.class));
        return new MediaCounts(nonNegativeLong(body, "like_count"), nonNegativeLong(body, "comments_count"));
    }

    public InsightCounts fetchMediaInsights(String mediaId, String accessToken) {
        JsonNode body = retrying("media_insights", () -> graphClient.get()
                .uri(uri -> uri.path("/{version}/{mediaId}/insights")
                        .queryParam("metric", "views,shares")
                        .build(properties.apiVersion(), mediaId))
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve().body(JsonNode.class));
        long views = 0;
        long shares = 0;
        if (body != null && body.path("data").isArray()) {
            for (JsonNode metric : body.path("data")) {
                String name = text(metric, "name");
                long value = metricValue(metric);
                if ("views".equals(name)) {
                    views = value;
                } else if ("shares".equals(name)) {
                    shares = value;
                }
            }
        }
        return new InsightCounts(views, shares);
    }

    public void revoke(String accountId, String accessToken) {
        retrying("permission_revoke", () -> graphClient.delete()
                .uri("/{version}/{accountId}/permissions", properties.apiVersion(), accountId)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve().body(JsonNode.class));
    }

    private JsonNode retrying(String operation, Supplier<JsonNode> supplier) {
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                return supplier.get();
            } catch (RestClientResponseException e) {
                boolean retryable = e.getStatusCode().value() == 429 || e.getStatusCode().is5xxServerError();
                if (!retryable || attempt == properties.maxAttempts()) {
                    throw translate(e);
                }
                log.warn("event=meta_instagram_retry operation={} attempt={} status={}",
                        operation, attempt, e.getStatusCode().value());
                sleep(attempt);
            } catch (RuntimeException e) {
                if (attempt == properties.maxAttempts()) {
                    throw upstream(e);
                }
                log.warn("event=meta_instagram_retry operation={} attempt={} status=network_error",
                        operation, attempt);
                sleep(attempt);
            }
        }
        throw upstream(null);
    }

    private void sleep(int attempt) {
        long millis = properties.retryBackoff().toMillis() * (1L << Math.min(attempt - 1, 3));
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw upstream(e);
        }
    }

    private LongToken tokenFrom(JsonNode body) {
        String token = requiredText(body, "access_token");
        long expiresIn = body == null ? 0 : body.path("expires_in").asLong(0);
        if (expiresIn <= 0) {
            throw upstream(null);
        }
        return new LongToken(token, clock.instant().plusSeconds(expiresIn));
    }

    private static long metricValue(JsonNode metric) {
        JsonNode total = metric.path("total_value").path("value");
        if (total.isNumber()) {
            return Math.max(0, total.asLong());
        }
        JsonNode values = metric.path("values");
        if (values.isArray() && !values.isEmpty()) {
            return Math.max(0, values.get(0).path("value").asLong(0));
        }
        return 0;
    }

    private static long nonNegativeLong(JsonNode body, String field) {
        return body == null ? 0 : Math.max(0, body.path(field).asLong(0));
    }

    private static String requiredText(JsonNode node, String field) {
        return required(text(node, field));
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw upstream(null);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText(null);
    }

    private static InstagramIntegrationException upstream(Throwable cause) {
        return new InstagramIntegrationException("INSTAGRAM_PROVIDER_UNAVAILABLE",
                "인스타그램과 통신하지 못했습니다. 잠시 후 다시 시도해 주세요.",
                SERVICE_UNAVAILABLE, cause);
    }

    private static InstagramIntegrationException translate(RestClientResponseException error) {
        String body = error.getResponseBodyAsString();
        boolean invalidToken = error.getStatusCode().value() == 401
                || body.matches("(?s).*\\\"code\\\"\\s*:\\s*190(?:\\D.*|$)");
        if (invalidToken) {
            return new InstagramIntegrationException("INSTAGRAM_RECONNECT_REQUIRED",
                    "인스타그램 연결이 만료되었습니다. 다시 연결해 주세요.",
                    org.springframework.http.HttpStatus.CONFLICT, error);
        }
        return upstream(error);
    }

    public record ShortToken(String accessToken, String accountId) {}
    public record LongToken(String accessToken, Instant expiresAt) {}
    public record Account(String id, String username, String accountType) {}
    public record Media(String id, String permalink, String mediaType) {}
    public record MediaPage(List<Media> media, String after) {}
    public record MediaCounts(long likes, long comments) {}
    public record InsightCounts(long views, long shares) {}

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
