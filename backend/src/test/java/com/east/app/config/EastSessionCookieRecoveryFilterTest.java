package com.east.app.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

class EastSessionCookieRecoveryFilterTest {

    private static final String COOKIE_NAME = "EASTSESSION";
    private static final String SYNTHETIC_SESSION_ID = "00000000-0000-4000-8000-000000000001";

    @Test
    void nullBearingCookieIsRemovedBeforeSessionResolutionAndExpired() throws Exception {
        byte[] nullBearingSessionId = SYNTHETIC_SESSION_ID.getBytes(StandardCharsets.UTF_8);
        nullBearingSessionId[0] = 0;
        String nullBearingValue;
        try {
            nullBearingValue = Base64.getEncoder().encodeToString(nullBearingSessionId);
        } finally {
            Arrays.fill(nullBearingSessionId, (byte) 0);
        }
        HttpServletRequest request = request(
                new Cookie(COOKIE_NAME, nullBearingValue),
                new Cookie("EAST-XSRF-TOKEN", "synthetic-csrf-cookie"));
        HttpServletResponse response = response();
        AtomicReference<HttpServletRequest> forwardedRequest = new AtomicReference<>();
        EastSessionCookieRecoveryFilter filter = new EastSessionCookieRecoveryFilter(COOKIE_NAME, cookieSerializer());

        filter.doFilterInternal(request, response, (forwarded, ignored) ->
                forwardedRequest.set((HttpServletRequest) forwarded));

        assertFalse(hasCookie(forwardedRequest.get(), COOKIE_NAME));
        assertTrue(hasCookie(forwardedRequest.get(), "EAST-XSRF-TOKEN"));
        assertTrue(hasExpiredSessionCookie(response));
    }

    @Test
    void legacyNonUuidCookieIsRemovedAndExpired() throws Exception {
        HttpServletRequest request = request(new Cookie(COOKIE_NAME, "legacy-session-value"));
        HttpServletResponse response = response();
        AtomicReference<HttpServletRequest> forwardedRequest = new AtomicReference<>();
        EastSessionCookieRecoveryFilter filter = new EastSessionCookieRecoveryFilter(COOKIE_NAME, cookieSerializer());

        filter.doFilterInternal(request, response, (forwarded, ignored) ->
                forwardedRequest.set((HttpServletRequest) forwarded));

        assertFalse(hasCookie(forwardedRequest.get(), COOKIE_NAME));
        assertTrue(hasExpiredSessionCookie(response));
    }

    @Test
    void validUuidCookiePassesThroughWithoutExpiry() throws Exception {
        String validCookieValue = Base64.getEncoder()
                .encodeToString(SYNTHETIC_SESSION_ID.getBytes(StandardCharsets.UTF_8));
        HttpServletRequest request = request(new Cookie(COOKIE_NAME, validCookieValue));
        HttpServletResponse response = response();
        AtomicReference<HttpServletRequest> forwardedRequest = new AtomicReference<>();
        EastSessionCookieRecoveryFilter filter = new EastSessionCookieRecoveryFilter(COOKIE_NAME, cookieSerializer());

        filter.doFilterInternal(request, response, (forwarded, ignored) ->
                forwardedRequest.set((HttpServletRequest) forwarded));

        assertSame(request, forwardedRequest.get());
        assertTrue(hasCookie(forwardedRequest.get(), COOKIE_NAME));
        assertFalse(hasExpiredSessionCookie(response));
    }

    private static CookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(COOKIE_NAME);
        serializer.setCookiePath("/");
        serializer.setUseSecureCookie(false);
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setCookieMaxAge(60 * 60 * 24 * 60);
        return serializer;
    }

    private static HttpServletRequest request(Cookie... cookies) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, arguments) -> method.getName().equals("getCookies") ? cookies : defaultValue(method.getReturnType()));
    }

    private static HttpServletResponse response() {
        List<String> setCookieHeaders = new ArrayList<>();
        return (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("addHeader") && "Set-Cookie".equals(arguments[0])) {
                        setCookieHeaders.add((String) arguments[1]);
                    }
                    if (method.getName().equals("getHeaders") && "Set-Cookie".equals(arguments[0])) {
                        return setCookieHeaders;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static boolean hasCookie(HttpServletRequest request, String name) {
        if (request == null || request.getCookies() == null) {
            return false;
        }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static boolean hasExpiredSessionCookie(HttpServletResponse response) {
        List<String> headers = (List<String>) response.getHeaders("Set-Cookie");
        return headers.stream().anyMatch(header -> header.startsWith(COOKIE_NAME + "=")
                && header.contains("Max-Age=0")
                && header.contains("HttpOnly")
                && header.contains("SameSite=Lax"));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        return null;
    }
}
