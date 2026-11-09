package com.east.app.config;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** Fails closed and keeps the embedded East API bound to IPv4 loopback. */
@Configuration(proxyBeanMethods = false)
public class LoopbackServerConfiguration {

    private static final String REQUIRED_SERVER_ADDRESS = "127.0.0.1";

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> eastLoopbackOnlyServerCustomizer(
            @Value("${server.address:127.0.0.1}") String configuredServerAddress) {
        return factory -> {
            if (!REQUIRED_SERVER_ADDRESS.equals(configuredServerAddress)) {
                throw new IllegalStateException("East API must bind only to 127.0.0.1");
            }
            factory.setAddress(ipv4Loopback());
        };
    }

    private static InetAddress ipv4Loopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException exception) {
            // A four-byte literal address never requires name resolution; fail closed if that invariant changes.
            throw new IllegalStateException("Unable to bind East to IPv4 loopback", exception);
        }
    }
}
