package com.east.app.auth;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GoogleOAuthCallbackController {

    private static final URI FRONTEND_HOME = URI.create("http://localhost:5173/");

    private final GoogleOAuthService googleOAuthService;
    private final AuthRepository authRepository;
    private final AppSessionService appSessionService;
    private final MailboxService mailboxService;

    public GoogleOAuthCallbackController(
            GoogleOAuthService googleOAuthService,
            AuthRepository authRepository,
            AppSessionService appSessionService,
            MailboxService mailboxService) {
        this.googleOAuthService = googleOAuthService;
        this.authRepository = authRepository;
        this.appSessionService = appSessionService;
        this.mailboxService = mailboxService;
    }

    @GetMapping("/login/oauth2/code/google")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (error != null) {
            googleOAuthService.consumeDeniedCallback(state, request);
        }

        OAuthCompletion completion = googleOAuthService.completeCallback(code, state, request);
        if (completion.pendingAuthorization().intent() == AuthIntent.SIGN_IN) {
            AppUser user = authRepository.findOrCreateAnchoredUser(completion.identity());
            appSessionService.establishGoogleSession(user, request, response);
        } else {
            AppUser user = appSessionService.requireCurrentUser(authentication);
            UUID intendedAppUserId = completion.pendingAuthorization().appUserId();
            if (!Objects.equals(user.id(), intendedAppUserId)) {
                throw new AuthFlowException(
                        HttpStatus.FORBIDDEN,
                        "The mailbox authorization no longer matches this signed-in session. Start again.");
            }
            mailboxService.connect(user.id(), completion.identity(), completion.refreshToken());
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(FRONTEND_HOME).build();
    }
}
