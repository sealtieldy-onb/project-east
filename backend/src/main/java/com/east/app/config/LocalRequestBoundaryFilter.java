package com.east.app.config;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalRequestBoundaryFilter extends OncePerRequestFilter {

    private static final String GOOGLE_OAUTH_CALLBACK = "/login/oauth2/code/google";
    private static final Set<String> ALLOWED_ORIGINS = Set.of(
            "http://localhost:5173",
            "http://localhost:4173");

    private final int serverPort;

    public LocalRequestBoundaryFilter(@Value("${server.port:8080}") int serverPort) {
        this.serverPort = serverPort;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        addSecurityHeaders(response);
        boolean oauthCallback = HttpMethod.GET.matches(request.getMethod())
                && GOOGLE_OAUTH_CALLBACK.equals(request.getRequestURI());

        if (!isAllowedHost(request.getHeader("Host"))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Local host required");
            return;
        }

        String origin = request.getHeader("Origin");
        if (origin != null && !isAllowedOrigin(origin) && !oauthCallback) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Local origin required");
            return;
        }

        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if ("cross-site".equalsIgnoreCase(fetchSite) && !oauthCallback) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Cross-site request rejected");
            return;
        }

        if (isStateChangingMethod(request.getMethod()) && (origin == null || !isAllowedOrigin(origin))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "A trusted local origin is required");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAllowedHost(String hostHeader) {
        if (hostHeader == null) {
            return false;
        }
        String host = hostHeader.trim().toLowerCase(Locale.ROOT);
        return host.equals("localhost:" + serverPort) || host.equals("127.0.0.1:" + serverPort);
    }

    private boolean isAllowedOrigin(String origin) {
        if (origin == null || !ALLOWED_ORIGINS.contains(origin)) {
            return false;
        }
        try {
            URI uri = new URI(origin);
            String host = uri.getHost();
            return "http".equals(uri.getScheme())
                    && host != null
                    && (host.toLowerCase(Locale.ROOT).equals("localhost") || host.equals("127.0.0.1"))
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private boolean isStateChangingMethod(String method) {
        return HttpMethod.POST.matches(method)
                || HttpMethod.PUT.matches(method)
                || HttpMethod.PATCH.matches(method)
                || HttpMethod.DELETE.matches(method);
    }

    private void addSecurityHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }
}
