package com.viralground.backend.config;

import io.sentry.Breadcrumb;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Final privacy boundary before an event leaves the process.
 *
 * <p>Presigned object-storage URLs are bearer credentials until they expire. Request metadata can
 * also contain session cookies, authorization headers, form fields, and personal information. The
 * application logs safe identifiers (for example requestId and entity ids) explicitly; Sentry does
 * not need raw transport data to diagnose an exception.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SentryPrivacyConfiguration {

    private static final Pattern ABSOLUTE_URL = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9.-])");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)\\b(token|secret|password|authorization|cookie|code|signature)\\s*[=:]\\s*[^\\s,;&]+");
    private static final Set<String> SAFE_BREADCRUMB_DATA = Set.of(
            "method", "status_code", "statusCode", "url", "from", "to");

    @Bean
    SentryOptions.BeforeSendCallback sentryBeforeSendPrivacyFilter() {
        return (event, hint) -> sanitizeEvent(event);
    }

    @Bean
    SentryOptions.BeforeBreadcrumbCallback sentryBeforeBreadcrumbPrivacyFilter() {
        return (breadcrumb, hint) -> sanitizeBreadcrumb(breadcrumb);
    }

    static SentryEvent sanitizeEvent(SentryEvent event) {
        event.setUser(null);
        event.setExtras(null);

        Request request = event.getRequest();
        if (request != null) {
            request.setUrl(stripQueryAndFragment(request.getUrl()));
            request.setQueryString(null);
            request.setFragment(null);
            request.setCookies(null);
            request.setHeaders(null);
            request.setData(null);
            request.setEnvs(null);
            request.setOthers(null);
            request.setApiTarget(null);
        }

        Message message = event.getMessage();
        if (message != null) {
            message.setMessage(stripUrlsInText(message.getMessage()));
            message.setFormatted(stripUrlsInText(message.getFormatted()));
            if (message.getParams() != null) {
                message.setParams(message.getParams().stream()
                        .map(SentryPrivacyConfiguration::stripUrlsInText)
                        .toList());
            }
        }

        List<SentryException> exceptions = event.getExceptions();
        if (exceptions != null) {
            exceptions.forEach(exception -> exception.setValue(stripUrlsInText(exception.getValue())));
        }

        List<Breadcrumb> breadcrumbs = event.getBreadcrumbs();
        if (breadcrumbs != null) {
            List<Breadcrumb> sanitized = new ArrayList<>(breadcrumbs.size());
            for (Breadcrumb breadcrumb : breadcrumbs) {
                sanitized.add(sanitizeBreadcrumb(breadcrumb));
            }
            event.setBreadcrumbs(sanitized);
        }
        return event;
    }

    static Breadcrumb sanitizeBreadcrumb(Breadcrumb breadcrumb) {
        breadcrumb.setMessage(stripUrlsInText(breadcrumb.getMessage()));
        Map<String, Object> data = breadcrumb.getData();
        if (data == null || data.isEmpty()) {
            return breadcrumb;
        }

        for (String key : List.copyOf(data.keySet())) {
            if (!SAFE_BREADCRUMB_DATA.contains(key)) {
                breadcrumb.removeData(key);
                continue;
            }
            Object value = data.get(key);
            if (value instanceof String stringValue) {
                String lowerKey = key.toLowerCase(Locale.ROOT);
                breadcrumb.setData(key, lowerKey.equals("method")
                        ? stringValue
                        : stripUrlsInText(stringValue));
            }
        }
        return breadcrumb;
    }

    static String stripUrlsInText(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        Matcher matcher = ABSOLUTE_URL.matcher(value);
        StringBuilder sanitized = new StringBuilder(value.length());
        while (matcher.find()) {
            matcher.appendReplacement(sanitized,
                    Matcher.quoteReplacement(stripQueryAndFragment(matcher.group())));
        }
        matcher.appendTail(sanitized);
        String redacted = EMAIL.matcher(sanitized).replaceAll("<redacted-email>");
        redacted = BEARER.matcher(redacted).replaceAll("Bearer <redacted>");
        redacted = JWT.matcher(redacted).replaceAll("<redacted-token>");
        return NAMED_SECRET.matcher(redacted).replaceAll("$1=<redacted>");
    }

    static String stripQueryAndFragment(String value) {
        if (value == null) {
            return null;
        }
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        int cut = value.length();
        if (query >= 0) {
            cut = Math.min(cut, query);
        }
        if (fragment >= 0) {
            cut = Math.min(cut, fragment);
        }
        return value.substring(0, cut);
    }
}
