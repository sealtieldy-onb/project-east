package com.east.app.config;

import java.util.Objects;

import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;

@Configuration
public class SessionCookieRecoveryConfiguration {

    @Bean
    DefaultCookieSerializer eastSessionCookieSerializer(ServerProperties serverProperties) {
        Cookie cookie = serverProperties.getServlet().getSession().getCookie();
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(Objects.requireNonNullElse(cookie.getName(), "SESSION"));
        serializer.setUseBase64Encoding(true);
        if (cookie.getDomain() != null) {
            serializer.setDomainName(cookie.getDomain());
        }
        if (cookie.getPath() != null) {
            serializer.setCookiePath(cookie.getPath());
        }
        if (cookie.getHttpOnly() != null) {
            serializer.setUseHttpOnlyCookie(cookie.getHttpOnly());
        }
        if (cookie.getSecure() != null) {
            serializer.setUseSecureCookie(cookie.getSecure());
        }
        if (cookie.getMaxAge() != null) {
            serializer.setCookieMaxAge(Math.toIntExact(cookie.getMaxAge().getSeconds()));
        }
        if (cookie.getSameSite() != null) {
            serializer.setSameSite(cookie.getSameSite().attributeValue());
        }
        if (cookie.getPartitioned() != null) {
            serializer.setPartitioned(cookie.getPartitioned());
        }
        return serializer;
    }

    @Bean
    FilterRegistrationBean<EastSessionCookieRecoveryFilter> eastSessionCookieRecoveryFilter(
            ServerProperties serverProperties,
            DefaultCookieSerializer cookieSerializer) {
        String cookieName = Objects.requireNonNullElse(
                serverProperties.getServlet().getSession().getCookie().getName(), "SESSION");
        FilterRegistrationBean<EastSessionCookieRecoveryFilter> registration =
                new FilterRegistrationBean<>(new EastSessionCookieRecoveryFilter(cookieName, cookieSerializer));
        registration.setName("eastSessionCookieRecoveryFilter");
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER - 1);
        return registration;
    }
}
