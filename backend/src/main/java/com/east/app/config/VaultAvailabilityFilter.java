package com.east.app.config;

import java.io.IOException;

import com.east.app.vault.VaultService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/** Blocks application APIs until an existing local vault completes its one-time key migration. */
final class VaultAvailabilityFilter extends OncePerRequestFilter {

    private static final String VAULT_STATUS_PATH = "/api/v1/vault/status";
    private static final String VAULT_UNLOCK_PATH = "/api/v1/vault/unlock";
    private static final String VAULT_RESET_PATH = "/api/v1/vault/reset";
    private static final String VAULT_LOCKED_PROBLEM = "{\"title\":\"Vault migration required\",\"status\":423,\"detail\":\"Complete the one-time local key migration to continue.\"}";

    private final VaultService vaultService;

    VaultAvailabilityFilter(VaultService vaultService) {
        this.vaultService = vaultService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean migrationEndpoint = VAULT_STATUS_PATH.equals(path)
                || VAULT_UNLOCK_PATH.equals(path)
                || VAULT_RESET_PATH.equals(path);
        if (path.startsWith("/api/") && !migrationEndpoint) {
            if (!vaultService.isUnlocked()) {
                response.setStatus(HttpStatus.LOCKED.value());
                response.setContentType("application/problem+json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(VAULT_LOCKED_PROBLEM);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
