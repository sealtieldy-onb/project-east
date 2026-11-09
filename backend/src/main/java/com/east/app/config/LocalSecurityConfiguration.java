package com.east.app.config;

import java.util.function.Supplier;

import com.east.app.vault.VaultService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

@Configuration
public class LocalSecurityConfiguration {

    private static final String VAULT_STATUS = "/api/v1/vault/status";
    private static final String VAULT_UNLOCK = "/api/v1/vault/unlock";
    private static final String VAULT_RESET = "/api/v1/vault/reset";
    private static final String GOOGLE_OAUTH_CALLBACK = "/login/oauth2/code/google";
    private static final String AUTH_CONFIGURATION = "/api/v1/auth/configuration";
    private static final String GOOGLE_SIGN_IN_START = "/api/v1/auth/google/start";
    private static final String AUTH_LOGOUT = "/api/v1/auth/logout";

    @Bean
    UserDetailsService noLocalPasswordAccounts() {
        return username -> {
            throw new UsernameNotFoundException("East does not configure local password accounts.");
        };
    }

    @Bean
    SecurityContextRepository eastSecurityContextRepository() {
        HttpSessionSecurityContextRepository repository = new HttpSessionSecurityContextRepository();
        repository.setDisableUrlRewriting(true);
        return repository;
    }

    @Bean
    SessionAuthenticationStrategy eastSessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    SecurityFilterChain localSecurityFilterChain(
            HttpSecurity http,
            VaultService vaultService,
            SecurityContextRepository securityContextRepository) throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository = new CookieCsrfTokenRepository();
        csrfTokenRepository.setCookieName("EAST-XSRF-TOKEN");
        csrfTokenRepository.setHeaderName("X-EAST-XSRF-TOKEN");
        csrfTokenRepository.setCookieCustomizer(cookie -> cookie
                .path("/")
                .httpOnly(false)
                .secure(false)
                .sameSite("Strict"));

        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository)
                        .requireExplicitSave(true))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, VAULT_STATUS).permitAll()
                        .requestMatchers(HttpMethod.GET, GOOGLE_OAUTH_CALLBACK).permitAll()
                        .requestMatchers(HttpMethod.GET, AUTH_CONFIGURATION, GOOGLE_SIGN_IN_START).permitAll()
                        .requestMatchers(HttpMethod.POST,
                                VAULT_UNLOCK,
                                VAULT_RESET,
                                AUTH_LOGOUT).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .addFilterAfter(new VaultAvailabilityFilter(vaultService), AuthorizationFilter.class);

        return http.build();
    }

    /**
     * Spring Security masks request attributes with XOR encoding. The SPA reads
     * the raw value from its same-site, JavaScript-readable CSRF cookie and sends
     * it in the configured header, so header resolution must use the plain handler.
     */
    private static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {
        private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(
                HttpServletRequest request,
                HttpServletResponse response,
                Supplier<CsrfToken> deferredCsrfToken) {
            xor.handle(request, response, deferredCsrfToken);
            deferredCsrfToken.get();
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            String headerValue = request.getHeader(csrfToken.getHeaderName());
            if (StringUtils.hasText(headerValue)) {
                return plain.resolveCsrfTokenValue(request, csrfToken);
            }
            return xor.resolveCsrfTokenValue(request, csrfToken);
        }
    }
}
