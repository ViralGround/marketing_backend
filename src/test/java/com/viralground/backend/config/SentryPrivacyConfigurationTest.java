package com.viralground.backend.config;

import io.sentry.Breadcrumb;
import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.User;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SentryPrivacyConfigurationTest {

    @Test
    void removesTransportSecretsAndPersonalContextBeforeSending() {
        SentryEvent event = new SentryEvent();
        Request request = new Request();
        request.setUrl("https://bucket.example/video.mp4?X-Amz-Signature=secret#fragment");
        request.setQueryString("X-Amz-Signature=secret");
        request.setFragment("fragment");
        request.setCookies("session=secret");
        request.setHeaders(Map.of("Authorization", "Bearer secret"));
        request.setData(Map.of("email", "person@example.com"));
        event.setRequest(request);

        User user = new User();
        user.setEmail("person@example.com");
        event.setUser(user);
        event.setExtras(Map.of("rawBody", "secret"));

        Message message = new Message();
        message.setMessage("failed https://api.example/items?token=secret#part");
        event.setMessage(message);

        Breadcrumb breadcrumb = new Breadcrumb("PUT upload");
        breadcrumb.setData("url", "https://bucket.example/video.mp4?signature=secret");
        breadcrumb.setData("method", "PUT");
        breadcrumb.setData("request_body", "secret");
        event.addBreadcrumb(breadcrumb);

        SentryPrivacyConfiguration.sanitizeEvent(event);

        assertThat(event.getUser()).isNull();
        assertThat(event.getExtras()).isNullOrEmpty();
        assertThat(event.getRequest().getUrl()).isEqualTo("https://bucket.example/video.mp4");
        assertThat(event.getRequest().getQueryString()).isNull();
        assertThat(event.getRequest().getCookies()).isNull();
        assertThat(event.getRequest().getHeaders()).isNull();
        assertThat(event.getRequest().getData()).isNull();
        assertThat(event.getMessage().getMessage()).isEqualTo("failed https://api.example/items");
        assertThat(event.getBreadcrumbs()).singleElement().satisfies(sanitized -> {
            assertThat(sanitized.getData("url")).isEqualTo("https://bucket.example/video.mp4");
            assertThat(sanitized.getData("method")).isEqualTo("PUT");
            assertThat(sanitized.getData("request_body")).isNull();
        });
    }

    @Test
    void keepsOrdinaryDiagnosticTextWhileRemovingEachAbsoluteUrlSecret() {
        assertThat(SentryPrivacyConfiguration.stripUrlsInText(
                "first https://a.example/x?k=1 then https://b.example/y#secret"))
                .isEqualTo("first https://a.example/x then https://b.example/y");
        assertThat(SentryPrivacyConfiguration.stripUrlsInText("ordinary error"))
                .isEqualTo("ordinary error");
        assertThat(SentryPrivacyConfiguration.stripUrlsInText(
                "email person@example.com Bearer abc.def password=hunter2 token=secret"))
                .isEqualTo("email <redacted-email> Bearer <redacted> password=<redacted> token=<redacted>");
    }
}
