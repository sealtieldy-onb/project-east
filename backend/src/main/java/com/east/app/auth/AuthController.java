package com.east.app.auth;

import java.net.URI;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final GoogleOAuthService googleOAuthService;
    private final AppSessionService appSessionService;
    private final MailboxService mailboxService;

    public AuthController(
            GoogleOAuthService googleOAuthService,
            AppSessionService appSessionService,
            MailboxService mailboxService) {
        this.googleOAuthService = googleOAuthService;
        this.appSessionService = appSessionService;
        this.mailboxService = mailboxService;
    }

    @GetMapping("/auth/configuration")
    public boolean configuration() {
        return googleOAuthService.isConfigured();
    }

    @GetMapping("/auth/google/start")
    public ResponseEntity<Void> startGoogleSignIn(HttpServletRequest request) {
        String authorizationUrl = googleOAuthService.startSignIn(request);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(authorizationUrl)).build();
    }

    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        AppUser user = appSessionService.requireCurrentUser(authentication);
        List<MailboxSummary> mailboxes = mailboxService.listOwned(user.id());
        return new MeResponse(user.id(), user.email(), user.displayName(), mailboxes);
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        appSessionService.logout(authentication, request, response);
        return ResponseEntity.noContent().build();
    }
}
