package com.east.app.auth;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.VaultService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MailboxService {

    private final MailboxRepository mailboxRepository;
    private final VaultService vaultService;
    private final GoogleAccessTokenService accessTokenService;

    public MailboxService(
            MailboxRepository mailboxRepository,
            VaultService vaultService,
            GoogleAccessTokenService accessTokenService) {
        this.mailboxRepository = mailboxRepository;
        this.vaultService = vaultService;
        this.accessTokenService = accessTokenService;
    }

    @Transactional
    public MailboxSummary connect(UUID appUserId, GoogleIdentity identity, String refreshToken) {
        vaultService.requireUnlocked();
        mailboxRepository.lockAppUser(appUserId);

        ConnectedMailbox existing = mailboxRepository
                .findByOwnerAndGoogleSubject(appUserId, identity.subject())
                .orElse(null);
        UUID mailboxId = existing == null ? UUID.randomUUID() : existing.id();
        int colorIndex = existing == null
                ? mailboxRepository.nextAvailableColorIndex(appUserId, identity.subject())
                        .orElseThrow(() -> new AuthFlowException(
                                HttpStatus.CONFLICT,
                                "East could not allocate a distinct color index for this mailbox. Disconnect an existing mailbox and try again."))
                : existing.colorIndex();

        boolean hasNewRefreshToken = StringUtils.hasText(refreshToken);
        if (!hasNewRefreshToken
                && (existing == null
                    || existing.reauthorizationRequired()
                    || existing.refreshTokenNonce() == null
                    || existing.refreshTokenCiphertext() == null)) {
            throw new AuthFlowException(
                    HttpStatus.BAD_GATEWAY,
                    "Google did not return a refresh token for this mailbox. Revoke East's access in Google and try connecting again.");
        }

        byte[] encryptedNonce = null;
        byte[] encryptedCiphertext = null;
        byte[] refreshTokenBytes = null;
        try {
            if (hasNewRefreshToken) {
                refreshTokenBytes = refreshToken.getBytes(StandardCharsets.UTF_8);
                EncryptedPayload encrypted = vaultService.encryptLocal(
                        refreshTokenBytes,
                        MailboxTokenAad.refreshToken(appUserId, mailboxId, identity.subject()));
                try {
                    encryptedNonce = encrypted.nonce();
                    encryptedCiphertext = encrypted.ciphertext();
                } finally {
                    encrypted.destroy();
                }
            }

            List<String> scopes = identity.grantedScopes().stream().sorted(Comparator.naturalOrder()).toList();
            mailboxRepository.upsertMailbox(
                    mailboxId,
                    appUserId,
                    identity.subject(),
                    identity.email(),
                    identity.displayName(),
                    colorIndex,
                    scopes,
                    encryptedNonce,
                    encryptedCiphertext);
            accessTokenService.invalidate(mailboxId);
            return mailboxRepository.findByOwnerAndGoogleSubject(appUserId, identity.subject())
                    .map(MailboxService::toSummary)
                    .orElseThrow(AuthNotFoundException::new);
        } finally {
            wipe(encryptedNonce);
            wipe(encryptedCiphertext);
            wipe(refreshTokenBytes);
        }
    }

    public List<MailboxSummary> listOwned(UUID appUserId) {
        vaultService.requireUnlocked();
        return mailboxRepository.listOwnedSummaries(appUserId);
    }

    @Transactional
    public void disconnect(UUID appUserId, UUID mailboxId) {
        vaultService.requireUnlocked();
        mailboxRepository.lockAppUser(appUserId);
        mailboxRepository.deleteSummaryRunsForMailbox(appUserId, mailboxId);
        if (!mailboxRepository.deleteOwned(appUserId, mailboxId)) {
            throw new AuthNotFoundException();
        }
        accessTokenService.invalidate(mailboxId);
    }

    private static MailboxSummary toSummary(ConnectedMailbox mailbox) {
        return new MailboxSummary(
                mailbox.id(),
                mailbox.email(),
                mailbox.displayName(),
                mailbox.colorIndex(),
                mailbox.reauthorizationRequired(),
                mailbox.lastSyncAt(),
                List.of());
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
