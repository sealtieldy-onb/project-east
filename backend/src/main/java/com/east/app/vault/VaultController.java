package com.east.app.vault;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vault")
public class VaultController {

    private final VaultService vaultService;

    public VaultController(VaultService vaultService) {
        this.vaultService = vaultService;
    }

    @GetMapping("/status")
    public VaultStatusResponse status() {
        return vaultService.status();
    }

    /** Existing encrypted installations enter here once to migrate from a passphrase-derived key. */
    @PostMapping("/unlock")
    public VaultStatusResponse unlock(@Valid @RequestBody VaultUnlockRequest request) {
        return vaultService.unlock(request.passphrase());
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset(
            @Valid @RequestBody VaultResetRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        vaultService.reset(request.confirmation());
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(servletRequest, servletResponse, authentication);
        return ResponseEntity.noContent().build();
    }
}
