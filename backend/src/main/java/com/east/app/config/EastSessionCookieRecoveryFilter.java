package com.east.app.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.CookieSerializer.CookieValue;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects stale or malformed East session cookies before Spring Session resolves an ID.
 * Spring Session's configured Base64 serializer and JDBC generator use encoded UUID IDs;
 * decoding locally also avoids the default serializer's debug log of malformed raw values.
 */
final class EastSessionCookieRecoveryFilter extends OncePerRequestFilter {

    private final String cookieName;
    private final CookieSerializer cookieSerializer;

    EastSessionCookieRecoveryFilter(String cookieName, CookieSerializer cookieSerializer) {
        this.cookieName = cookieName;
        this.cookieSerializer = cookieSerializer;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            filterChain.doFilter(request, response);
            return;
        }

        List<Cookie> sessionCookies = new ArrayList<>();
        List<Cookie> otherCookies = new ArrayList<>();
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                sessionCookies.add(cookie);
            } else {
                otherCookies.add(cookie);
            }
        }

        if (sessionCookies.isEmpty()
                || (sessionCookies.size() == 1 && isValidCookieValue(sessionCookies.getFirst().getValue()))) {
            filterChain.doFilter(request, response);
            return;
        }

        CookieValue expiredCookie = new CookieValue(request, response, "");
        expiredCookie.setCookieMaxAge(0);
        cookieSerializer.writeCookieValue(expiredCookie);

        Cookie[] forwardedCookies = otherCookies.toArray(Cookie[]::new);
        HttpServletRequest sanitizedRequest = new HttpServletRequestWrapper(request) {
            @Override
            public Cookie[] getCookies() {
                return forwardedCookies.length == 0 ? null : forwardedCookies.clone();
            }
        };
        filterChain.doFilter(sanitizedRequest, response);
    }

    private static boolean isValidCookieValue(String cookieValue) {
        if (cookieValue == null || cookieValue.length() != 48) {
            return false;
        }
        byte[] decoded = null;
        try {
            decoded = Base64.getDecoder().decode(cookieValue);
            if (!Base64.getEncoder().encodeToString(decoded).equals(cookieValue)) {
                return false;
            }
            return isUuidSessionId(decoded);
        } catch (IllegalArgumentException exception) {
            return false;
        } finally {
            if (decoded != null) {
                Arrays.fill(decoded, (byte) 0);
            }
        }
    }

    private static boolean isUuidSessionId(byte[] value) {
        if (value.length != 36) {
            return false;
        }
        if (value[14] != '4' || (value[19] != '8' && value[19] != '9'
                && value[19] != 'a' && value[19] != 'b')) {
            return false;
        }
        for (int index = 0; index < value.length; index++) {
            byte character = value[index];
            if (index == 8 || index == 13 || index == 18 || index == 23) {
                if (character != '-') {
                    return false;
                }
            } else if (!isHexDigit(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHexDigit(byte value) {
        return (value >= '0' && value <= '9')
                || (value >= 'a' && value <= 'f');
    }
}
