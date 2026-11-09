package com.east.app.auth;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Service;

@Service
public class AppSessionService {

    private final AuthRepository authRepository;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    public AppSessionService(
            AuthRepository authRepository,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy) {
        this.authRepository = authRepository;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
    }

    public EastUserPrincipal requirePrincipal(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof EastUserPrincipal principal)) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "Sign in with Google to continue.");
        }
        return principal;
    }

    public AppUser requireCurrentUser(Authentication authentication) {
        EastUserPrincipal principal = requirePrincipal(authentication);
        AppUser user = authRepository.findById(principal.appUserId())
                .orElseThrow(() -> new AuthFlowException(HttpStatus.UNAUTHORIZED, "Sign in with Google to continue."));
        if (!user.googleSubject().equals(principal.googleSubject())) {
            throw new AuthFlowException(HttpStatus.UNAUTHORIZED, "Sign in with Google to continue.");
        }
        return user;
    }

    public void establishGoogleSession(
            AppUser user,
            HttpServletRequest request,
            HttpServletResponse response) {
        EastUserPrincipal principal = new EastUserPrincipal(
                user.id(), user.googleSubject(), user.email(), user.displayName());
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    public void logout(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }
}
