package com.east.app.attachments;

import com.east.app.auth.GoogleAccessTokenService;
import com.east.app.gmail.GmailApiClient;
import com.east.app.gmail.GmailApiClient.InlineAttachmentPayload;
import com.east.app.gmail.GmailRequestGuard;
import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.VaultCryptoService;
import com.east.app.vault.VaultService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Reads one user-requested Gmail attachment and keeps only an encrypted, expiring local cache. */
@Service
class AttachmentContentService {

    private static final long MAX_ATTACHMENT_BYTES = 50L * 1024 * 1024;
    private static final byte CACHE_PAYLOAD_VERSION = 1;
    private static final String INLINE_ATTACHMENT_PREFIX = "inline:";
    private static final Pattern SAFE_MEDIA_TYPE = Pattern.compile(
            "^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$");

    private final AttachmentRepository repository;
    private final GoogleAccessTokenService accessTokenService;
    private final GmailApiClient gmailApiClient;
    private final VaultService vaultService;
    private final Clock clock;

    AttachmentContentService(
            AttachmentRepository repository,
            GoogleAccessTokenService accessTokenService,
            GmailApiClient gmailApiClient,
            VaultService vaultService,
            Clock clock) {
        this.repository = repository;
        this.accessTokenService = accessTokenService;
        this.gmailApiClient = gmailApiClient;
        this.vaultService = vaultService;
        this.clock = clock;
    }

    AttachmentContent load(
            UUID appUserId,
            UUID threadId,
            UUID messageId,
            UUID attachmentId) {
        vaultService.requireUnlocked();
        AttachmentRepository.AttachmentAccessRecord record = repository
                .findOwnedAttachment(appUserId, threadId, messageId, attachmentId)
                .orElseThrow(AttachmentContentService::notFound);
        byte[] associatedData = AttachmentAad.forAttachment(
                appUserId,
                record.mailboxId(),
                record.gmailThreadId(),
                record.gmailMessageId(),
                record.gmailAttachmentId());

        try {
            Instant now = clock.instant();
            boolean cacheWithinSourceLifetime = record.cacheIsWithinSourceLifetime(now);
            if (record.hasCachedContent()
                    && record.expiresAt().isAfter(now)
                    && cacheWithinSourceLifetime) {
                return readCached(record, associatedData);
            }

            if (record.hasCachedContent()
                    && (!record.expiresAt().isAfter(now) || !cacheWithinSourceLifetime)) {
                // A source-body expiry invalidates bytes fetched before it, even before the
                // periodic purge runs. This explicit user click may fetch a fresh copy.
                if (repository.clearExpiredContent(record, now) != 1) {
                    throw notFound();
                }
            }
            if (record.byteSize() != null) rejectOversized(record.byteSize().longValue());

            String accessToken = accessTokenService.getValidAccessToken(appUserId, record.mailboxId());
            vaultService.requireUnlocked();
            byte[] fetchedBytes = null;
            InlineAttachmentPayload inlinePayload = null;
            try {
                if (record.gmailAttachmentId().startsWith(INLINE_ATTACHMENT_PREFIX)) {
                    String partId = record.gmailAttachmentId().substring(INLINE_ATTACHMENT_PREFIX.length());
                    try {
                        inlinePayload = GmailRequestGuard.call(
                                accessTokenService,
                                appUserId,
                                record.mailboxId(),
                                accessToken,
                                () -> gmailApiClient.getInlineAttachment(
                                                accessToken, record.gmailMessageId(), partId)
                                        .orElseThrow(AttachmentContentService::notFound));
                    } catch (GmailApiClient.GmailResponseTooLargeException exception) {
                        throw tooLarge();
                    }
                    fetchedBytes = inlinePayload.bytes();
                    validateInlineMetadata(record, inlinePayload, fetchedBytes.length);
                } else {
                    try {
                        fetchedBytes = GmailRequestGuard.call(
                                accessTokenService,
                                appUserId,
                                record.mailboxId(),
                                accessToken,
                                () -> gmailApiClient.getAttachmentBytes(
                                        accessToken,
                                        record.gmailMessageId(),
                                        record.gmailAttachmentId()));
                    } catch (GmailApiClient.GmailResponseTooLargeException exception) {
                        throw tooLarge();
                    }
                }
                rejectOversized(fetchedBytes.length);
                vaultService.requireUnlocked();
                cacheFetchedBytes(record, associatedData, fetchedBytes);
                return new AttachmentContent(
                        Arrays.copyOf(fetchedBytes, fetchedBytes.length),
                        safeFilename(record.filename()),
                        safeMediaType(record.mimeType()));
            } finally {
                wipe(fetchedBytes);
                if (inlinePayload != null) inlinePayload.destroy();
            }
        } finally {
            record.destroy();
            wipe(associatedData);
        }
    }

    private AttachmentContent readCached(
            AttachmentRepository.AttachmentAccessRecord record,
            byte[] associatedData) {
        byte[] nonce = record.contentNonce();
        byte[] ciphertext = record.contentCiphertext();
        EncryptedPayload encrypted = null;
        byte[] plaintext = null;
        try {
            if (nonce == null || ciphertext == null) {
                throw new VaultCryptoService.InvalidVaultCiphertextException();
            }
            encrypted = new EncryptedPayload(nonce, ciphertext);
            plaintext = vaultService.decryptLocal(encrypted, associatedData);
            if (plaintext.length == 0 || plaintext[0] != CACHE_PAYLOAD_VERSION) {
                throw invalidCachedPayload();
            }
            int contentLength = plaintext.length - 1;
            rejectOversized(contentLength);
            return new AttachmentContent(
                    Arrays.copyOfRange(plaintext, 1, plaintext.length),
                    safeFilename(record.filename()),
                    safeMediaType(record.mimeType()));
        } catch (VaultCryptoService.InvalidVaultCiphertextException exception) {
            throw invalidCachedPayload();
        } finally {
            wipe(nonce);
            wipe(ciphertext);
            wipe(plaintext);
            if (encrypted != null) encrypted.destroy();
        }
    }

    private void cacheFetchedBytes(
            AttachmentRepository.AttachmentAccessRecord record,
            byte[] associatedData,
            byte[] plaintext) {
        Instant fetchedAt = clock.instant();
        Instant expiresAt = fetchedAt.plus(Duration.ofDays(repository.currentCacheTtlDays(record.appUserId())));
        EncryptedPayload encrypted = null;
        byte[] nonce = null;
        byte[] ciphertext = null;
        byte[] cachePlaintext = new byte[plaintext.length + 1];
        try {
            cachePlaintext[0] = CACHE_PAYLOAD_VERSION;
            System.arraycopy(plaintext, 0, cachePlaintext, 1, plaintext.length);
            encrypted = vaultService.encryptLocal(cachePlaintext, associatedData);
            nonce = encrypted.nonce();
            ciphertext = encrypted.ciphertext();
            repository.cacheContent(
                    record,
                    plaintext.length,
                    fetchedAt,
                    expiresAt,
                    nonce,
                    ciphertext);
        } finally {
            wipe(nonce);
            wipe(ciphertext);
            wipe(cachePlaintext);
            if (encrypted != null) encrypted.destroy();
        }
    }

    private static void validateInlineMetadata(
            AttachmentRepository.AttachmentAccessRecord record,
            InlineAttachmentPayload inlinePayload,
            int actualSize) {
        String currentFilename = AttachmentFilenameSanitizer.sanitize(inlinePayload.filename());
        if (!record.filename().equals(currentFilename)
                || (record.byteSize() != null && record.byteSize() != actualSize)
                || (inlinePayload.declaredSize() != null && inlinePayload.declaredSize() != actualSize)) {
            throw notFound();
        }
        String currentMimeType = safeMediaType(inlinePayload.mimeType()).toString();
        String storedMimeType = safeMediaType(record.mimeType()).toString();
        if (!storedMimeType.equals(currentMimeType)) throw notFound();
    }

    private static AttachmentContentException invalidCachedPayload() {
        return new AttachmentContentException(
                HttpStatus.BAD_GATEWAY,
                "ATTACHMENT_CACHE_UNAVAILABLE",
                "East could not verify the cached attachment. Refresh the conversation and try again.");
    }

    private static void rejectOversized(long size) {
        if (size > MAX_ATTACHMENT_BYTES) {
            throw tooLarge();
        }
    }

    private static AttachmentContentException tooLarge() {
        return new AttachmentContentException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "ATTACHMENT_TOO_LARGE",
                "This attachment is too large for East to preview or download.");
    }

    private static AttachmentContentException notFound() {
        return new AttachmentContentException(
                HttpStatus.NOT_FOUND,
                "ATTACHMENT_NOT_FOUND",
                "The requested attachment is no longer available. Refresh the conversation and try again.");
    }

    private static String safeFilename(String filename) {
        return AttachmentFilenameSanitizer.sanitize(filename);
    }

    private static MediaType safeMediaType(String value) {
        if (!StringUtils.hasText(value)) return MediaType.APPLICATION_OCTET_STREAM;
        String normalized = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!SAFE_MEDIA_TYPE.matcher(normalized).matches()) return MediaType.APPLICATION_OCTET_STREAM;
        try {
            return MediaType.parseMediaType(normalized);
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static void wipe(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    record AttachmentContent(byte[] body, String filename, MediaType mediaType) {
        @Override
        public String toString() {
            return "AttachmentContent[body=REDACTED, filename=REDACTED, mediaType=" + mediaType + "]";
        }
    }
}
