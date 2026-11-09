package com.east.app.auth;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.east.app.vault.VaultService;

@RestController
@RequestMapping("/api/v1/mailboxes")
public class MailboxController {

    private final MailboxService mailboxService;
    private final GoogleOAuthService googleOAuthService;
    private final AppSessionService appSessionService;
    private final VaultService vaultService;

    public MailboxController(
            MailboxService mailboxService,
            GoogleOAuthService googleOAuthService,
            AppSessionService appSessionService,
            VaultService vaultService) {
        this.mailboxService = mailboxService;
        this.googleOAuthService = googleOAuthService;
        this.appSessionService = appSessionService;
        this.vaultService = vaultService;
    }

    @GetMapping
    public MailboxListResponse list(Authentication authentication) {
        AppUser user = appSessionService.requireCurrentUser(authentication);
        return new MailboxListResponse(mailboxService.listOwned(user.id()));
    }

    @PostMapping("/connect")
    public AuthorizationUrlResponse connect(Authentication authentication, HttpServletRequest request) {
        vaultService.requireUnlocked();
        AppUser user = appSessionService.requireCurrentUser(authentication);
        return new AuthorizationUrlResponse(googleOAuthService.startMailboxLink(request, user.id()));
    }

    @DeleteMapping("/{mailboxId}")
    public ResponseEntity<Void> disconnect(
            Authentication authentication,
            @PathVariable UUID mailboxId) {
        AppUser user = appSessionService.requireCurrentUser(authentication);
        mailboxService.disconnect(user.id(), mailboxId);
        return ResponseEntity.noContent().build();
    }
}
