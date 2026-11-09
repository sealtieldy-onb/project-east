package com.east.app.drafts;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.east.app.auth.EastUserPrincipal;
import com.east.app.auth.GoogleAccessTokenService;
import com.east.app.gmail.GmailApiClient;
import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.VaultCryptoService;
import com.east.app.vault.VaultService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DraftService {

    private static final Pattern SIMPLE_EMAIL = Pattern.compile(
            "^[A-Za-z0-9_+\\-][A-Za-z0-9._%+\\-]*@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$");
    private static final int MAX_RECIPIENTS_TOTAL = 100;
    private static final int MAX_BODY_UTF8_BYTES = 1024 * 1024;
    private static final int MAX_SUBJECT_UTF8_BYTES = 1000;

    private final DraftRepository repository;
    private final VaultService vaultService;
    private final GoogleAccessTokenService accessTokenService;
    private final GmailApiClient gmailApiClient;
    private final ObjectMapper objectMapper;
    private final DraftHtmlSanitizer htmlSanitizer;
    private final DraftSourceService sourceService;
    private final DraftGmailReviewParser gmailReviewParser;
    private final DraftMimeBuilder mimeBuilder;
    private final Clock clock;
    private final TransactionTemplate independentTransactions;
    private final Set<UUID> activeDraftSideEffects = ConcurrentHashMap.newKeySet();

    public DraftService(
            DraftRepository repository,
            VaultService vaultService,
            GoogleAccessTokenService accessTokenService,
            GmailApiClient gmailApiClient,
            ObjectMapper objectMapper,
            DraftHtmlSanitizer htmlSanitizer,
            DraftSourceService sourceService,
            DraftGmailReviewParser gmailReviewParser,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.vaultService = vaultService;
        this.accessTokenService = accessTokenService;
        this.gmailApiClient = gmailApiClient;
        this.objectMapper = objectMapper;
        this.htmlSanitizer = htmlSanitizer;
        this.sourceService = sourceService;
        this.gmailReviewParser = gmailReviewParser;
        this.mimeBuilder = new DraftMimeBuilder();
        this.clock = clock;
        this.independentTransactions = new TransactionTemplate(transactionManager);
        this.independentTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(readOnly = true)
    public List<DraftListItem> list(EastUserPrincipal principal) {
        UUID appUserId = requirePrincipal(principal);
        vaultService.requireUnlocked();
        return repository.listOwned(appUserId);
    }

    @Transactional
    public DraftResponse create(EastUserPrincipal principal, DraftWriteRequest request) {
        UUID appUserId = requirePrincipal(principal);
        vaultService.requireUnlocked();
        DraftKind kind = request == null || request.kind() == null ? DraftKind.NEW : request.kind();
        validateSourceSelection(request, kind);
        DraftSourceContext source = kind == DraftKind.NEW
                ? null
                : sourceService.load(
                        appUserId,
                        request.mailboxId(),
                        request.sourceThreadId(),
                        request.sourceMessageId(),
                        kind);
        DraftPayload payload;
        try {
            payload = prepareInitialPayload(appUserId, request, kind, source);
            requireOwnedMailbox(appUserId, request.mailboxId());
        } catch (RuntimeException exception) {
            if (source != null) {
                wipeOutboundAttachments(source.forwardAttachments());
            }
            throw exception;
        }
        UUID draftId = UUID.randomUUID();
        EncryptedPayload encrypted = null;
        byte[] plaintext = null;
        byte[] nonce = null;
        byte[] ciphertext = null;
        try {
            plaintext = serialize(payload);
            encrypted = vaultService.encryptLocal(
                    plaintext,
                    DraftAad.payload(appUserId, draftId, request.mailboxId()));
            nonce = encrypted.nonce();
            ciphertext = encrypted.ciphertext();
            repository.insertLocal(
                    draftId,
                    appUserId,
                    request.mailboxId(),
                    source == null ? null : source.sourceThreadId(),
                    kind,
                    nonce,
                    ciphertext);
            if (source != null && kind == DraftKind.FORWARD) {
                storeForwardAttachments(appUserId, draftId, source.forwardAttachments());
            }
        } finally {
            wipe(plaintext);
            wipe(nonce);
            wipe(ciphertext);
            destroy(encrypted);
            if (source != null) {
                wipeOutboundAttachments(source.forwardAttachments());
            }
        }
        return get(principal, draftId);
    }

    @Transactional
    public DraftResponse update(EastUserPrincipal principal, UUID draftId, DraftWriteRequest request) {
        UUID appUserId = requirePrincipal(principal);
        vaultService.requireUnlocked();
        DraftRepository.DraftRow row = requireOwnedDraft(appUserId, draftId, true);
        try {
            requireLocal(row);
            if (request == null) {
                throw DraftException.badRequest("Draft details are required.");
            }
            DraftKind rowKind = DraftKind.valueOf(row.kind());
            DraftKind requestedKind = request.kind() == null ? rowKind : request.kind();
            UUID requestedThreadId = request.sourceThreadId() == null
                    ? row.sourceThreadId()
                    : request.sourceThreadId();
            DraftPayload existingPayload = decryptPayload(row);
            if (requestedKind != rowKind
                    || !java.util.Objects.equals(requestedThreadId, row.sourceThreadId())
                    || (request.sourceMessageId() != null
                        && !java.util.Objects.equals(request.sourceMessageId(), existingPayload.sourceMessageId()))) {
                throw DraftException.conflict("Draft type and source conversation cannot be changed after creation.");
            }
            if (row.sourceThreadId() != null && !row.mailboxId().equals(request.mailboxId())) {
                throw DraftException.conflict("A reply or forward must stay with the mailbox that owns its source thread.");
            }
            DraftWriteRequest normalizedRequest = new DraftWriteRequest(
                    request.mailboxId(),
                    rowKind,
                    row.sourceThreadId(),
                    existingPayload.sourceMessageId(),
                    request.to(),
                    request.cc(),
                    request.bcc(),
                    request.subject(),
                    request.plainTextBody(),
                    request.richTextHtml());
            DraftPayload editable = validateAndNormalize(normalizedRequest);
            DraftPayload payload = new DraftPayload(
                    rowKind,
                    existingPayload.sourceMessageId(),
                    existingPayload.gmailThreadId(),
                    existingPayload.inReplyTo(),
                    existingPayload.references(),
                    editable.to(),
                    editable.cc(),
                    editable.bcc(),
                    editable.subject(),
                    editable.plainTextBody(),
                    editable.richTextHtml());
            requireOwnedMailbox(appUserId, request.mailboxId());
            byte[] plaintext = null;
            byte[] nonce = null;
            byte[] ciphertext = null;
            EncryptedPayload encrypted = null;
            try {
                plaintext = serialize(payload);
                encrypted = vaultService.encryptLocal(
                        plaintext,
                        DraftAad.payload(appUserId, draftId, request.mailboxId()));
                nonce = encrypted.nonce();
                ciphertext = encrypted.ciphertext();
                if (repository.updateLocal(appUserId, draftId, request.mailboxId(), nonce, ciphertext) != 1) {
                    throw DraftException.conflict("This draft changed state. Reload it before editing.");
                }
            } finally {
                wipe(plaintext);
                wipe(nonce);
                wipe(ciphertext);
                destroy(encrypted);
            }
        } finally {
            row.destroyPayloadBuffers();
        }
        return get(principal, draftId);
    }

    @Transactional(readOnly = true)
    public DraftResponse get(EastUserPrincipal principal, UUID draftId) {
        UUID appUserId = requirePrincipal(principal);
        vaultService.requireUnlocked();
        DraftRepository.DraftRow row = requireOwnedDraft(appUserId, draftId, false);
        try {
            String fromAddress = requireOwnedMailboxEmail(appUserId, row.mailboxId());
            DraftPayload payload;
            List<DraftAttachmentSummary> attachments;
            List<String> attachmentContentDigests;
            if ("LOCAL".equals(row.state())) {
                payload = decryptPayload(row);
                attachments = repository.listAttachmentSummaries(appUserId, draftId);
                List<DraftMimeBuilder.OutboundAttachment> plaintextAttachments =
                        loadOutboundAttachments(appUserId, draftId);
                try {
                    attachmentContentDigests = attachmentContentDigests(plaintextAttachments);
                } finally {
                    wipeOutboundAttachments(plaintextAttachments);
                }
            } else if ("SAVED_TO_GMAIL".equals(row.state())) {
                DraftGmailReviewParser.ParsedDraft saved = loadSavedGmailDraft(appUserId, row, fromAddress);
                payload = saved.payload();
                attachments = saved.attachments();
                attachmentContentDigests = saved.attachmentContentDigests();
                fromAddress = saved.fromAddress();
            } else {
                throw reconciliationConflict();
            }
            String reviewFingerprint = reviewFingerprint(
                    row, fromAddress, payload, attachments, attachmentContentDigests);
            return toResponse(row, fromAddress, payload, attachments, reviewFingerprint);
        } finally {
            row.destroyPayloadBuffers();
        }
    }

    @Transactional
    public DraftAttachmentSummary addAttachment(
            EastUserPrincipal principal,
            UUID draftId,
            MultipartFile file) {
        UUID appUserId = requirePrincipal(principal);
        vaultService.requireUnlocked();
        DraftRepository.DraftRow row = requireOwnedDraft(appUserId, draftId, true);
        try {
            requireLocal(row);
            if (file == null || file.isEmpty()
                    || file.getSize() > DraftAttachmentPolicy.MAX_FILE_BYTES) {
                throw DraftException.badRequest("Choose a non-empty file attachment no larger than 10 MiB.");
            }
            String filename = DraftAttachmentPolicy.validateFilename(file.getOriginalFilename());
            String mimeType = DraftAttachmentPolicy.normalizeUploadedMimeType(file.getContentType());
            DraftRepository.AttachmentUsage usage = repository.attachmentUsage(appUserId, draftId);
            if (usage.count() >= DraftAttachmentPolicy.MAX_ATTACHMENTS_PER_DRAFT) {
                throw DraftException.badRequest("A draft can contain at most 10 attachments.");
            }
            if (usage.totalBytes() > DraftAttachmentPolicy.MAX_TOTAL_BYTES - file.getSize()) {
                throw DraftException.badRequest("Attachments in one draft cannot exceed 16 MiB total.");
            }

            UUID attachmentId = UUID.randomUUID();
            byte[] plaintext = null;
            try {
                plaintext = file.getBytes();
                storeAttachmentBytes(appUserId, draftId, attachmentId, filename, mimeType, plaintext);
                repository.touchLocalDraft(appUserId, draftId);
            } catch (IOException exception) {
                throw DraftException.badRequest("The selected attachment could not be read.");
            } finally {
                wipe(plaintext);
            }
            return new DraftAttachmentSummary(attachmentId, filename, mimeType, file.getSize());
        } finally {
            row.destroyPayloadBuffers();
        }
    }

    @Transactional
    public void deleteAttachment(EastUserPrincipal principal, UUID draftId, UUID attachmentId) {
        UUID appUserId = requirePrincipal(principal);
        vaultService.requireUnlocked();
        DraftRepository.DraftRow row = requireOwnedDraft(appUserId, draftId, true);
        try {
            requireLocal(row);
            if (!repository.deleteAttachment(appUserId, draftId, attachmentId)) {
                throw DraftException.notFound();
            }
            repository.touchLocalDraft(appUserId, draftId);
        } finally {
            row.destroyPayloadBuffers();
        }
    }

    public DraftSaveResponse saveToGmail(EastUserPrincipal principal, UUID draftId) {
        UUID appUserId = requirePrincipal(principal);
        vaultService.requireUnlocked();
        if (!activeDraftSideEffects.add(draftId)) {
            throw DraftException.conflict("A Gmail operation for this draft is still running. Wait before reconciling it.");
        }
        try {
            PreparedSave prepared = prepareSaveToGmail(appUserId, draftId);
            try {
                JsonNode saved;
                try {
                    saved = gmailApiClient.createDraft(prepared.accessToken(), prepared.message());
                } catch (RuntimeException exception) {
                    throw DraftException.reconciliationRequired(
                            "Gmail may have saved this draft. Check Gmail Drafts, then use Reconcile before retrying.");
                }
                String gmailDraftId = saved == null ? null : saved.path("id").asText(null);
                if (!StringUtils.hasText(gmailDraftId)) {
                    throw DraftException.reconciliationRequired(
                            "Gmail may have saved this draft. Check Gmail Drafts, then use Reconcile before retrying.");
                }
                try {
                    Integer updated = independentTransactions.execute(status ->
                            repository.markSavedToGmail(appUserId, draftId, gmailDraftId));
                    if (updated == null || updated != 1) {
                        throw new IllegalStateException("The saved draft state did not transition.");
                    }
                } catch (RuntimeException exception) {
                    throw DraftException.reconciliationRequired(
                            "Gmail saved the draft, but East could not verify its local state. Check Gmail Drafts, then use Reconcile.");
                }
                return new DraftSaveResponse(draftId, "SAVED_TO_GMAIL", gmailDraftId, clock.instant());
            } finally {
                prepared.destroy();
            }
        } finally {
            activeDraftSideEffects.remove(draftId);
        }
    }

    public DraftSendResponse send(
            EastUserPrincipal principal,
            UUID draftId,
            SendDraftRequest request) {
        UUID appUserId = requirePrincipal(principal);
        vaultService.requireUnlocked();
        if (request == null || !Boolean.TRUE.equals(request.confirmed())) {
            throw DraftException.badRequest("Confirm that this exact draft is ready to send.");
        }
        if (request.expectedUpdatedAt() == null || request.reviewFingerprint() == null) {
            throw DraftException.badRequest("Reload and review the draft before sending.");
        }
        if (!activeDraftSideEffects.add(draftId)) {
            throw DraftException.conflict("A Gmail operation for this draft is still running. Wait before reconciling it.");
        }
        try {
            PreparedSend prepared = prepareSend(appUserId, draftId, request);
            try {
                JsonNode sent;
                try {
                    sent = prepared.gmailDraftId() == null
                            ? gmailApiClient.sendMessage(prepared.accessToken(), prepared.message())
                            : gmailApiClient.sendDraft(
                                    prepared.accessToken(), prepared.gmailDraftId(), prepared.message());
                } catch (RuntimeException exception) {
                    throw DraftException.reconciliationRequired(
                            "Gmail may have sent this message. Check Gmail Sent, then use Reconcile before retrying.");
                }
                String gmailMessageId = sent == null ? null : sent.path("id").asText(null);
                if (!StringUtils.hasText(gmailMessageId)) {
                    throw DraftException.reconciliationRequired(
                            "Gmail may have sent this message. Check Gmail Sent, then use Reconcile before retrying.");
                }
                try {
                    Boolean deleted = independentTransactions.execute(status ->
                            repository.deletePendingSend(appUserId, draftId));
                    if (!Boolean.TRUE.equals(deleted)) {
                        throw new IllegalStateException("The sent draft did not transition locally.");
                    }
                } catch (RuntimeException exception) {
                    throw DraftException.reconciliationRequired(
                            "Gmail sent the message, but East could not verify its local state. Check Gmail Sent, then use Reconcile.");
                }
                return new DraftSendResponse(draftId, "SENT", gmailMessageId, clock.instant());
            } finally {
                prepared.destroy();
            }
        } finally {
            activeDraftSideEffects.remove(draftId);
        }
    }

    private PreparedSave prepareSaveToGmail(UUID appUserId, UUID draftId) {
        AtomicReference<PreparedSave> preparedHolder = new AtomicReference<>();
        try {
            return Objects.requireNonNull(independentTransactions.execute(status -> {
                DraftRepository.DraftRow row = requireOwnedDraft(appUserId, draftId, true);
                List<DraftMimeBuilder.OutboundAttachment> attachments = null;
                byte[] mime = null;
                try {
                    requireLocal(row);
                    String accessToken = accessTokenService.getValidAccessToken(appUserId, row.mailboxId());
                    String fromAddress = requireOwnedMailboxEmail(appUserId, row.mailboxId());
                    DraftPayload payload = decryptPayload(row);
                    attachments = loadOutboundAttachments(appUserId, draftId);
                    mime = mimeBuilder.build(fromAddress, payload, attachments);
                    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(mime);
                    Map<String, Object> message = mimeBuilder.gmailMessage(raw, payload);
                    if (repository.markSideEffectPending(appUserId, draftId, "LOCAL", "SAVE_TO_GMAIL") != 1) {
                        throw DraftException.conflict("This draft changed state. Reload it before saving to Gmail.");
                    }
                    PreparedSave prepared = new PreparedSave(accessToken, message, attachments);
                    preparedHolder.set(prepared);
                    attachments = null;
                    return prepared;
                } finally {
                    row.destroyPayloadBuffers();
                    wipe(mime);
                    if (attachments != null) wipeOutboundAttachments(attachments);
                }
            }));
        } catch (RuntimeException exception) {
            PreparedSave prepared = preparedHolder.get();
            if (prepared != null) prepared.destroy();
            throw exception;
        }
    }

    private PreparedSend prepareSend(UUID appUserId, UUID draftId, SendDraftRequest request) {
        AtomicReference<PreparedSend> preparedHolder = new AtomicReference<>();
        try {
            return Objects.requireNonNull(independentTransactions.execute(status -> {
                DraftRepository.DraftRow row = requireOwnedDraft(appUserId, draftId, true);
                List<DraftMimeBuilder.OutboundAttachment> attachments = null;
                byte[] mime = null;
                try {
                    if (!"LOCAL".equals(row.state()) && !"SAVED_TO_GMAIL".equals(row.state())) {
                        throw reconciliationConflict();
                    }
                    if (!row.updatedAt().equals(request.expectedUpdatedAt())) {
                        throw DraftException.conflict(
                                "This draft changed after review. Reload and review it again before sending.");
                    }
                    String accessToken = accessTokenService.getValidAccessToken(appUserId, row.mailboxId());
                    String fromAddress = requireOwnedMailboxEmail(appUserId, row.mailboxId());
                    if ("SAVED_TO_GMAIL".equals(row.state())) {
                        if (!StringUtils.hasText(row.gmailDraftId())) {
                            throw DraftException.conflict("The saved Gmail draft is no longer available in East.");
                        }
                        SavedDraftSendSnapshot snapshot = loadSavedGmailDraftForSend(
                                appUserId, row, fromAddress, accessToken);
                        DraftGmailReviewParser.ParsedDraft saved = snapshot.draft();
                        attachments = snapshot.attachments();
                        validateCanSend(saved.payload());
                        requireMatchingReviewFingerprint(
                                request.reviewFingerprint(),
                                reviewFingerprint(
                                        row,
                                        saved.fromAddress(),
                                        saved.payload(),
                                        saved.attachments(),
                                        saved.attachmentContentDigests()));
                        mime = mimeBuilder.build(saved.fromAddress(), saved.payload(), attachments);
                        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(mime);
                        Map<String, Object> message = mimeBuilder.gmailMessage(raw, saved.payload());
                        if (repository.markSideEffectPending(
                                        appUserId, draftId, "SAVED_TO_GMAIL", "SEND")
                                != 1) {
                            throw DraftException.conflict("This draft changed state. Reload it before sending.");
                        }
                        PreparedSend prepared = new PreparedSend(
                                accessToken, row.gmailDraftId(), message, attachments);
                        preparedHolder.set(prepared);
                        attachments = null;
                        return prepared;
                    }

                    requireLocal(row);
                    DraftPayload payload = decryptPayload(row);
                    validateCanSend(payload);
                    List<DraftAttachmentSummary> summaries = repository.listAttachmentSummaries(appUserId, draftId);
                    attachments = loadOutboundAttachments(appUserId, draftId);
                    List<String> contentDigests = attachmentContentDigests(attachments);
                    requireMatchingReviewFingerprint(
                            request.reviewFingerprint(),
                            reviewFingerprint(row, fromAddress, payload, summaries, contentDigests));
                    mime = mimeBuilder.build(fromAddress, payload, attachments);
                    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(mime);
                    Map<String, Object> message = mimeBuilder.gmailMessage(raw, payload);
                    if (repository.markSideEffectPending(appUserId, draftId, "LOCAL", "SEND") != 1) {
                        throw DraftException.conflict("This draft changed state. Reload it before sending.");
                    }
                    PreparedSend prepared = new PreparedSend(accessToken, null, message, attachments);
                    preparedHolder.set(prepared);
                    attachments = null;
                    return prepared;
                } finally {
                    row.destroyPayloadBuffers();
                    wipe(mime);
                    if (attachments != null) wipeOutboundAttachments(attachments);
                }
            }));
        } catch (RuntimeException exception) {
            PreparedSend prepared = preparedHolder.get();
            if (prepared != null) prepared.destroy();
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public DraftReconciliationStatus reconciliationStatus(EastUserPrincipal principal, UUID draftId) {
        UUID appUserId = requirePrincipal(principal);
        vaultService.requireUnlocked();
        DraftRepository.DraftRow row = requireOwnedDraft(appUserId, draftId, false);
        try {
            boolean required = isPendingState(row.state());
            String previousState = row.gmailDraftId() == null ? "LOCAL" : "SAVED_TO_GMAIL";
            return new DraftReconciliationStatus(
                    draftId,
                    required,
                    required && activeDraftSideEffects.contains(draftId),
                    required ? row.sideEffectOperation() : null,
                    row.state(),
                    required ? previousState : null,
                    required ? row.sideEffectStartedAt() : null);
        } finally {
            row.destroyPayloadBuffers();
        }
    }

    public DraftReconciliationResponse reconcile(
            EastUserPrincipal principal,
            UUID draftId,
            DraftReconciliationRequest request) {
        UUID appUserId = requirePrincipal(principal);
        vaultService.requireUnlocked();
        if (request == null || !Boolean.TRUE.equals(request.confirmed())) {
            throw DraftException.badRequest(
                    "Confirm that you checked Gmail for this draft operation before reconciling it.");
        }
        DraftRepository.DraftRow row = Objects.requireNonNull(independentTransactions.execute(status -> {
            DraftRepository.DraftRow current = requireOwnedDraft(appUserId, draftId, true);
            if (!isPendingState(current.state())) {
                current.destroyPayloadBuffers();
                throw DraftException.conflict("This draft does not need reconciliation.");
            }
            return current;
        }));
        try {
            if (activeDraftSideEffects.contains(draftId)) {
                throw DraftException.conflict(
                        "The Gmail operation is still running in East. Wait for it to finish before reconciling.");
            }
            String operation = row.sideEffectOperation();
            if ("CONFIRMED_COMPLETED".equals(request.resolution())) {
                if ("SAVE_TO_GMAIL".equals(operation)) {
                    reconcileCompletedSave(appUserId, row, request.gmailDraftId());
                    return new DraftReconciliationResponse(
                            draftId, "SAVED_TO_GMAIL", operation, clock.instant());
                }
                if ("SEND".equals(operation)) {
                    Boolean deleted = independentTransactions.execute(status ->
                            repository.deletePendingSend(appUserId, draftId));
                    if (!Boolean.TRUE.equals(deleted)) {
                        throw DraftException.conflict("The draft reconciliation state changed. Reload and check again.");
                    }
                    return new DraftReconciliationResponse(draftId, "SENT", operation, clock.instant());
                }
            }
            if ("CONFIRMED_NOT_COMPLETED".equals(request.resolution())) {
                if ("SEND".equals(operation) && row.gmailDraftId() != null) {
                    String accessToken = accessTokenService.getValidAccessToken(appUserId, row.mailboxId());
                    String mailboxEmail = requireOwnedMailboxEmail(appUserId, row.mailboxId());
                    // A saved Gmail draft may only be restored if Gmail still has that same draft.
                    loadSavedGmailDraft(appUserId, row, mailboxEmail, accessToken);
                }
                Integer restored = independentTransactions.execute(status ->
                        repository.restorePendingSideEffect(appUserId, draftId, operation));
                if (restored == null || restored != 1) {
                    throw DraftException.conflict("The draft reconciliation state changed. Reload and check again.");
                }
                String state = row.gmailDraftId() == null ? "LOCAL" : "SAVED_TO_GMAIL";
                return new DraftReconciliationResponse(draftId, state, operation, clock.instant());
            }
            throw DraftException.badRequest("Choose a supported reconciliation outcome.");
        } finally {
            row.destroyPayloadBuffers();
        }
    }

    private void reconcileCompletedSave(UUID appUserId, DraftRepository.DraftRow row, String gmailDraftId) {
        if (!"SAVE_TO_GMAIL".equals(row.sideEffectOperation())) {
            throw DraftException.conflict("This draft is not waiting for a Gmail save reconciliation.");
        }
        String accessToken = accessTokenService.getValidAccessToken(appUserId, row.mailboxId());
        String mailboxEmail = requireOwnedMailboxEmail(appUserId, row.mailboxId());
        DraftPayload localPayload = decryptPayload(row);
        List<DraftAttachmentSummary> localSummaries = repository.listAttachmentSummaries(appUserId, row.id());
        List<DraftMimeBuilder.OutboundAttachment> localAttachments = loadOutboundAttachments(appUserId, row.id());
        try {
            String localFingerprint = reviewFingerprint(
                    row,
                    mailboxEmail,
                    localPayload,
                    localSummaries,
                    attachmentContentDigests(localAttachments));
            String verifiedDraftId = findMatchingSavedGmailDraft(
                    appUserId, row, mailboxEmail, accessToken, gmailDraftId, localPayload,
                    localSummaries, localFingerprint);
            Integer updated = independentTransactions.execute(status ->
                    repository.markSavedToGmail(appUserId, row.id(), verifiedDraftId));
            if (updated == null || updated != 1) {
                throw DraftException.conflict("The draft reconciliation state changed. Reload and check again.");
            }
        } finally {
            wipeOutboundAttachments(localAttachments);
        }
    }

    private String findMatchingSavedGmailDraft(
            UUID appUserId,
            DraftRepository.DraftRow row,
            String mailboxEmail,
            String accessToken,
            String requestedDraftId,
            DraftPayload localPayload,
            List<DraftAttachmentSummary> localSummaries,
            String localFingerprint) {
        if (StringUtils.hasText(requestedDraftId)) {
            if (!requestedDraftId.matches("[A-Za-z0-9_-]{1,256}")) {
                throw DraftException.badRequest("The Gmail draft ID is invalid.");
            }
            DraftGmailReviewParser.ParsedDraft candidate = loadSavedGmailDraft(
                    appUserId, row, mailboxEmail, requestedDraftId, accessToken);
            String candidateFingerprint = reviewFingerprint(
                    row,
                    candidate.fromAddress(),
                    candidate.payload(),
                    candidate.attachments(),
                    candidate.attachmentContentDigests());
            requireMatchingReviewFingerprint(localFingerprint, candidateFingerprint);
            return requestedDraftId;
        }

        JsonNode page;
        try {
            String query = gmailDraftSearchQuery(mailboxEmail, localPayload.subject());
            page = gmailApiClient.listDrafts(accessToken, query, 50, null, false);
        } catch (GmailApiClient.GmailApiException exception) {
            throw DraftException.upstreamFailure();
        }
        JsonNode drafts = page == null ? null : page.path("drafts");
        if (drafts == null || !drafts.isArray()) {
            throw DraftException.conflict(
                    "East could not find a verifiable matching Gmail draft. Keep this operation pending and check Gmail Drafts.");
        }
        String match = null;
        boolean ambiguous = false;
        for (JsonNode draft : drafts) {
            String candidateId = draft.path("id").asText(null);
            if (!StringUtils.hasText(candidateId) || !candidateId.matches("[A-Za-z0-9_-]{1,256}")) {
                continue;
            }
            DraftGmailReviewParser.ParsedDraft candidate;
            try {
                candidate = loadSavedGmailDraft(
                        appUserId, row, mailboxEmail, candidateId, accessToken);
            } catch (DraftException exception) {
                if (exception.status().is5xxServerError()) {
                    throw exception;
                }
                // A candidate with unsafe or non-matching content cannot verify this save.
                continue;
            }
            String candidateFingerprint = reviewFingerprint(
                    row,
                    candidate.fromAddress(),
                    candidate.payload(),
                    candidate.attachments(),
                    candidate.attachmentContentDigests());
            if (reviewFingerprintsMatch(localFingerprint, candidateFingerprint)) {
                if (match != null) {
                    ambiguous = true;
                    break;
                }
                match = candidateId;
            }
        }
        if (ambiguous) {
            throw DraftException.conflict(
                    "East found multiple matching Gmail drafts. Leave this operation pending and choose the exact draft in Gmail before reconciling.");
        }
        if (StringUtils.hasText(page.path("nextPageToken").asText(null))) {
            throw DraftException.conflict(
                    "Gmail has more drafts than East can compare safely in one reconciliation. Leave this operation pending and provide the exact draft ID from Gmail's address bar, or confirm no matching draft was saved.");
        }
        if (match == null) {
            throw DraftException.conflict(
                    "East found no matching draft in Gmail. Check Gmail Drafts, then provide the matching draft ID from its address bar or confirm the save did not complete.");
        }
        return match;
    }

    private static String gmailDraftSearchQuery(String mailboxEmail, String subject) {
        StringBuilder query = new StringBuilder("from:").append(mailboxEmail);
        if (StringUtils.hasText(subject)) {
            String escaped = subject.replace("\\", "\\\\").replace("\"", "\\\"");
            query.append(" subject:\"").append(escaped).append('\"');
        }
        return query.toString();
    }

    @Transactional
    public void delete(EastUserPrincipal principal, UUID draftId) {
        UUID appUserId = requirePrincipal(principal);
        vaultService.requireUnlocked();
        DraftRepository.DraftRow row = requireOwnedDraft(appUserId, draftId, true);
        try {
            if (isPendingState(row.state())) {
                throw reconciliationConflict();
            }
            if ("SAVED_TO_GMAIL".equals(row.state())) {
                String accessToken = accessTokenService.getValidAccessToken(appUserId, row.mailboxId());
                try {
                    gmailApiClient.deleteDraft(accessToken, row.gmailDraftId());
                } catch (GmailApiClient.GmailApiException exception) {
                    if (exception.getHttpStatus() != 404) {
                        throw DraftException.upstreamFailure();
                    }
                }
            }
            if (!repository.deleteOwned(appUserId, draftId)) {
                throw DraftException.notFound();
            }
        } finally {
            row.destroyPayloadBuffers();
        }
    }

    private DraftGmailReviewParser.ParsedDraft loadSavedGmailDraft(
            UUID appUserId,
            DraftRepository.DraftRow row,
            String mailboxEmail) {
        String accessToken = accessTokenService.getValidAccessToken(appUserId, row.mailboxId());
        return loadSavedGmailDraft(appUserId, row, mailboxEmail, row.gmailDraftId(), accessToken);
    }

    private DraftGmailReviewParser.ParsedDraft loadSavedGmailDraft(
            UUID appUserId,
            DraftRepository.DraftRow row,
            String mailboxEmail,
            String accessToken) {
        return loadSavedGmailDraft(appUserId, row, mailboxEmail, row.gmailDraftId(), accessToken);
    }

    private DraftGmailReviewParser.ParsedDraft loadSavedGmailDraft(
            UUID appUserId,
            DraftRepository.DraftRow row,
            String mailboxEmail,
            String gmailDraftId,
            String accessToken) {
        JsonNode saved = readSavedGmailDraftResource(accessToken, gmailDraftId);
        DraftKind kind = DraftKind.valueOf(row.kind());
        String expectedThreadId = expectedGmailThreadId(appUserId, row, kind);
        DraftGmailReviewParser.ParsedDraft parsed = gmailReviewParser.parse(
                row.id(), kind, mailboxEmail, expectedThreadId, saved);
        return hashSavedGmailAttachments(accessToken, parsed);
    }

    private SavedDraftSendSnapshot loadSavedGmailDraftForSend(
            UUID appUserId,
            DraftRepository.DraftRow row,
            String mailboxEmail,
            String accessToken) {
        JsonNode saved = readSavedGmailDraftResource(accessToken, row.gmailDraftId());
        DraftKind kind = DraftKind.valueOf(row.kind());
        String expectedThreadId = expectedGmailThreadId(appUserId, row, kind);
        DraftGmailReviewParser.ParsedDraft parsed = gmailReviewParser.parse(
                row.id(), kind, mailboxEmail, expectedThreadId, saved, true);
        List<DraftMimeBuilder.OutboundAttachment> outbound = new ArrayList<>(parsed.reviewedAttachments().size());
        List<String> digests = new ArrayList<>(parsed.reviewedAttachments().size());
        long totalBytes = 0;
        try {
            for (DraftGmailReviewParser.ParsedAttachment attachment : parsed.reviewedAttachments()) {
                DraftAttachmentSummary summary = attachment.summary();
                totalBytes += summary.byteSize();
                if (totalBytes > DraftAttachmentPolicy.MAX_TOTAL_BYTES) {
                    throw DraftException.conflict("The saved Gmail draft attachments exceed East's review limits.");
                }
                byte[] bytes = attachment.inlineContentBytes();
                boolean transferred = false;
                try {
                    if (bytes == null) {
                        String gmailAttachmentId = attachment.gmailAttachmentId();
                        if (!StringUtils.hasText(gmailAttachmentId)
                                || !gmailAttachmentId.matches("[A-Za-z0-9_-]{1,512}")) {
                            throw DraftException.conflict(
                                    "An attachment in the saved Gmail draft could not be verified.");
                        }
                        bytes = gmailApiClient.getAttachmentBytes(
                                accessToken, parsed.gmailMessageId(), gmailAttachmentId);
                    }
                    if (bytes.length != summary.byteSize() || bytes.length > DraftAttachmentPolicy.MAX_FILE_BYTES) {
                        throw DraftException.conflict(
                                "An attachment in the saved Gmail draft has an inconsistent size.");
                    }
                    digests.add(sha256Base64Url(bytes));
                    outbound.add(new DraftMimeBuilder.OutboundAttachment(
                            summary.filename(), summary.mimeType(), bytes));
                    transferred = true;
                } finally {
                    if (!transferred) wipe(bytes);
                }
            }
            return new SavedDraftSendSnapshot(parsed.withAttachmentContentDigests(digests), outbound);
        } catch (RuntimeException exception) {
            wipeOutboundAttachments(outbound);
            parsed.wipeInlineAttachmentBytes();
            throw exception;
        }
    }

    private String expectedGmailThreadId(
            UUID appUserId, DraftRepository.DraftRow row, DraftKind kind) {
        if (kind != DraftKind.REPLY && kind != DraftKind.REPLY_ALL) return null;
        return repository.ownedGmailThreadId(appUserId, row.mailboxId(), row.sourceThreadId())
                .orElseThrow(() -> DraftException.conflict(
                        "The reply's source conversation is no longer available locally."));
    }

    private JsonNode readSavedGmailDraftResource(String accessToken, String gmailDraftId) {
        if (!StringUtils.hasText(gmailDraftId) || !gmailDraftId.matches("[A-Za-z0-9_-]{1,256}")) {
            throw DraftException.conflict("The selected Gmail draft could not be verified for review.");
        }
        JsonNode saved;
        try {
            saved = gmailApiClient.getDraft(accessToken, gmailDraftId, "full");
        } catch (GmailApiClient.GmailApiException exception) {
            if (exception.getHttpStatus() == 404) {
                throw DraftException.conflict(
                        "This Gmail draft no longer exists. Refresh Gmail Drafts before continuing.");
            }
            throw DraftException.upstreamFailure();
        }
        if (saved == null || !gmailDraftId.equals(saved.path("id").asText(null))) {
            throw DraftException.conflict("The selected Gmail draft could not be verified for review.");
        }
        return saved;
    }

    private DraftGmailReviewParser.ParsedDraft hashSavedGmailAttachments(
            String accessToken,
            DraftGmailReviewParser.ParsedDraft parsed) {
        List<String> digests = new ArrayList<>(parsed.reviewedAttachments().size());
        long totalBytes = 0;
        for (DraftGmailReviewParser.ParsedAttachment attachment : parsed.reviewedAttachments()) {
            DraftAttachmentSummary summary = attachment.summary();
            totalBytes += summary.byteSize();
            if (totalBytes > DraftAttachmentPolicy.MAX_TOTAL_BYTES) {
                throw DraftException.conflict("The saved Gmail draft attachments exceed East's review limits.");
            }
            if (attachment.contentDigest() != null) {
                digests.add(attachment.contentDigest());
                continue;
            }
            String gmailAttachmentId = attachment.gmailAttachmentId();
            if (!StringUtils.hasText(gmailAttachmentId)
                    || !gmailAttachmentId.matches("[A-Za-z0-9_-]{1,512}")) {
                throw DraftException.conflict("An attachment in the saved Gmail draft could not be verified.");
            }
            byte[] bytes = null;
            try {
                bytes = gmailApiClient.getAttachmentBytes(
                        accessToken, parsed.gmailMessageId(), gmailAttachmentId);
                if (bytes.length != summary.byteSize() || bytes.length > DraftAttachmentPolicy.MAX_FILE_BYTES) {
                    throw DraftException.conflict("An attachment in the saved Gmail draft has changed size.");
                }
                digests.add(sha256Base64Url(bytes));
            } catch (GmailApiClient.GmailApiException exception) {
                if (exception.getHttpStatus() == 404) {
                    throw DraftException.conflict("An attachment in the saved Gmail draft is no longer available.");
                }
                throw DraftException.upstreamFailure();
            } finally {
                wipe(bytes);
            }
        }
        return parsed.withAttachmentContentDigests(digests);
    }

    private String reviewFingerprint(
            DraftRepository.DraftRow row,
            String fromAddress,
            DraftPayload payload,
            List<DraftAttachmentSummary> attachments,
            List<String> attachmentContentDigests) {
        if (attachments.size() != attachmentContentDigests.size()
                || attachmentContentDigests.stream().anyMatch(digest -> digest == null || digest.length() != 43)) {
            throw DraftException.conflict("The draft attachments could not be bound to the reviewed content.");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateFingerprintField(digest, row.mailboxId().toString());
            updateFingerprintField(digest, fromAddress);
            updateFingerprintField(digest, row.kind());
            updateFingerprintField(digest, row.sourceThreadId() == null ? null : row.sourceThreadId().toString());
            // East's source message UUID is local metadata and is not encoded in Gmail's saved MIME.
            // Bind replies to the immutable East thread and the Gmail threading headers below instead.
            boolean threadedReply = "REPLY".equals(row.kind()) || "REPLY_ALL".equals(row.kind());
            updateFingerprintField(digest, threadedReply ? payload.gmailThreadId() : null);
            updateFingerprintField(digest, payload.inReplyTo());
            updateFingerprintField(digest, payload.references());
            updateFingerprintList(digest, payload.to());
            updateFingerprintList(digest, payload.cc());
            updateFingerprintList(digest, payload.bcc());
            updateFingerprintField(digest, payload.subject());
            updateFingerprintField(digest, payload.plainTextBody());
            updateFingerprintField(digest, payload.richTextHtml());
            updateFingerprintField(digest, Integer.toString(attachments.size()));
            for (int index = 0; index < attachments.size(); index++) {
                DraftAttachmentSummary attachment = attachments.get(index);
                updateFingerprintField(digest, attachment.filename());
                updateFingerprintField(digest, attachment.mimeType());
                updateFingerprintField(digest, Long.toString(attachment.byteSize()));
                updateFingerprintField(digest, attachmentContentDigests.get(index));
            }
            byte[] fingerprint = digest.digest();
            try {
                return Base64.getUrlEncoder().withoutPadding().encodeToString(fingerprint);
            } finally {
                wipe(fingerprint);
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for draft review verification.");
        }
    }

    private static List<String> attachmentContentDigests(
            List<DraftMimeBuilder.OutboundAttachment> attachments) {
        return attachments.stream().map(attachment -> sha256Base64Url(attachment.bytes())).toList();
    }

    private static String sha256Base64Url(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            try {
                return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            } finally {
                wipe(digest);
            }
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for attachment review.");
        }
    }

    private static void updateFingerprintList(MessageDigest digest, List<String> values) {
        updateFingerprintField(digest, Integer.toString(values.size()));
        values.forEach(value -> updateFingerprintField(digest, value));
    }

    private static void updateFingerprintField(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(new byte[] {0});
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] length = new byte[] {
            (byte) (bytes.length >>> 24),
            (byte) (bytes.length >>> 16),
            (byte) (bytes.length >>> 8),
            (byte) bytes.length
        };
        try {
            digest.update((byte) 1);
            digest.update(length);
            digest.update(bytes);
        } finally {
            wipe(bytes);
            wipe(length);
        }
    }

    private static void requireMatchingReviewFingerprint(String submitted, String current) {
        if (!reviewFingerprintsMatch(submitted, current)) {
            throw DraftException.conflict(
                    "The draft changed after review. Reload it and review the current message before sending.");
        }
    }

    private static boolean reviewFingerprintsMatch(String submitted, String current) {
        byte[] submittedBytes = null;
        byte[] currentBytes = null;
        try {
            submittedBytes = Base64.getUrlDecoder().decode(submitted);
            currentBytes = Base64.getUrlDecoder().decode(current);
            return MessageDigest.isEqual(submittedBytes, currentBytes);
        } catch (IllegalArgumentException exception) {
            throw DraftException.badRequest("Reload and review the draft before sending.");
        } finally {
            wipe(submittedBytes);
            wipe(currentBytes);
        }
    }

    private DraftPayload decryptPayload(DraftRepository.DraftRow row) {
        byte[] nonce = row.payloadNonce();
        byte[] ciphertext = row.payloadCiphertext();
        if (nonce == null || ciphertext == null) {
            throw DraftException.conflict("The local draft content is no longer available.");
        }
        EncryptedPayload encrypted = new EncryptedPayload(nonce, ciphertext);
        byte[] plaintext = null;
        try {
            plaintext = vaultService.decryptLocal(
                    encrypted,
                    DraftAad.payload(row.appUserId(), row.id(), row.mailboxId()));
            DraftPayload decoded = objectMapper.readValue(plaintext, DraftPayload.class);
            DraftKind kind = DraftKind.valueOf(row.kind());
            if (decoded.kind() != kind
                    || (kind == DraftKind.NEW
                        && (decoded.sourceMessageId() != null || row.sourceThreadId() != null))
                    || (kind != DraftKind.NEW
                        && (decoded.sourceMessageId() == null || row.sourceThreadId() == null))) {
                throw DraftException.conflict("This draft's source details could not be verified.");
            }
            DraftPayload normalized = validateAndNormalize(new DraftWriteRequest(
                    row.mailboxId(),
                    kind,
                    row.sourceThreadId(),
                    decoded.sourceMessageId(),
                    decoded.to(),
                    decoded.cc(),
                    decoded.bcc(),
                    decoded.subject(),
                    decoded.plainTextBody(),
                    decoded.richTextHtml()));
            String gmailThreadId = validateGmailThreadId(kind, decoded.gmailThreadId());
            String inReplyTo = validateReplyHeader(decoded.inReplyTo());
            String references = validateReplyHeader(decoded.references());
            if ((kind == DraftKind.REPLY || kind == DraftKind.REPLY_ALL)
                    && (inReplyTo == null || references == null)) {
                throw DraftException.conflict("This reply draft's Gmail threading headers could not be verified.");
            }
            if (kind != DraftKind.REPLY && kind != DraftKind.REPLY_ALL
                    && (inReplyTo != null || references != null)) {
                throw DraftException.conflict("This draft contains unexpected Gmail reply headers.");
            }
            return new DraftPayload(
                    kind,
                    decoded.sourceMessageId(),
                    gmailThreadId,
                    inReplyTo,
                    references,
                    normalized.to(),
                    normalized.cc(),
                    normalized.bcc(),
                    normalized.subject(),
                    normalized.plainTextBody(),
                    normalized.richTextHtml());
        } catch (VaultCryptoService.InvalidVaultCiphertextException | IOException exception) {
            throw DraftException.conflict("This local draft could not be decrypted. Delete it and create a new draft.");
        } finally {
            encrypted.destroy();
            wipe(nonce);
            wipe(ciphertext);
            wipe(plaintext);
        }
    }

    private List<DraftMimeBuilder.OutboundAttachment> loadOutboundAttachments(UUID appUserId, UUID draftId) {
        List<DraftRepository.AttachmentRow> stored = repository.listAttachments(appUserId, draftId);
        try {
            return decryptAttachments(appUserId, draftId, stored);
        } finally {
            stored.forEach(DraftRepository.AttachmentRow::destroyPayloadBuffers);
        }
    }

    private List<DraftMimeBuilder.OutboundAttachment> decryptAttachments(
            UUID appUserId,
            UUID draftId,
            List<DraftRepository.AttachmentRow> storedAttachments) {
        List<DraftMimeBuilder.OutboundAttachment> result = new ArrayList<>(storedAttachments.size());
        boolean complete = false;
        try {
            for (DraftRepository.AttachmentRow attachment : storedAttachments) {
                byte[] nonce = attachment.contentNonce();
                byte[] ciphertext = attachment.contentCiphertext();
                EncryptedPayload encrypted = new EncryptedPayload(nonce, ciphertext);
                try {
                    byte[] plaintext = vaultService.decryptLocal(
                            encrypted,
                            DraftAad.attachment(appUserId, draftId, attachment.id()));
                    if (plaintext.length != attachment.byteSize()) {
                        wipe(plaintext);
                        throw DraftException.conflict("A staged attachment could not be verified.");
                    }
                    result.add(new DraftMimeBuilder.OutboundAttachment(
                            attachment.filename(), attachment.mimeType(), plaintext));
                } finally {
                    encrypted.destroy();
                    wipe(nonce);
                    wipe(ciphertext);
                }
            }
            complete = true;
            return result;
        } catch (VaultCryptoService.InvalidVaultCiphertextException exception) {
            throw DraftException.conflict("A staged attachment could not be verified. Remove it and try again.");
        } finally {
            if (!complete) {
                wipeOutboundAttachments(result);
            }
        }
    }

    private DraftPayload validateAndNormalize(DraftWriteRequest request) {
        if (request == null || request.mailboxId() == null) {
            throw DraftException.badRequest("Choose a sending mailbox.");
        }
        DraftKind kind = request.kind() == null ? DraftKind.NEW : request.kind();
        List<String> to = validateRecipients(request.to());
        List<String> cc = validateRecipients(request.cc());
        List<String> bcc = validateRecipients(request.bcc());
        if (to.size() + cc.size() + bcc.size() > MAX_RECIPIENTS_TOTAL) {
            throw DraftException.badRequest("A draft can contain at most 100 recipients.");
        }
        String subject = validateSubject(request.subject() == null ? "" : request.subject());
        String plainText = request.plainTextBody() == null ? "" : request.plainTextBody();
        String html = htmlSanitizer.sanitize(request.richTextHtml());
        if (html.isBlank() && !plainText.isBlank()) {
            html = "<p>" + escapeHtml(plainText).replace("\r\n", "<br>").replace("\n", "<br>") + "</p>";
        }
        if (plainText.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_UTF8_BYTES
                || html.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_UTF8_BYTES) {
            throw DraftException.badRequest("Each message body must be no larger than 1 MiB.");
        }
        return new DraftPayload(
                kind,
                request.sourceMessageId(),
                null,
                null,
                null,
                to,
                cc,
                bcc,
                subject,
                plainText,
                html);
    }

    private DraftPayload prepareInitialPayload(
            UUID appUserId,
            DraftWriteRequest request,
            DraftKind kind,
            DraftSourceContext source) {
        DraftWriteRequest normalizedRequest = new DraftWriteRequest(
                request.mailboxId(),
                kind,
                request.sourceThreadId(),
                request.sourceMessageId(),
                request.to(),
                request.cc(),
                request.bcc(),
                request.subject(),
                request.plainTextBody(),
                request.richTextHtml());
        DraftPayload normalized = validateAndNormalize(normalizedRequest);
        if (source == null) {
            return normalized;
        }

        List<String> to = normalized.to();
        List<String> cc = normalized.cc();
        if (kind == DraftKind.REPLY || kind == DraftKind.REPLY_ALL) {
            List<String> replyRecipients = source.replyTo().isEmpty()
                    ? (source.senderEmail() == null || source.senderEmail().isBlank()
                        ? List.of()
                        : List.of(source.senderEmail()))
                    : source.replyTo();
            if (request.to() == null || request.to().isEmpty()) {
                to = validateRecipients(replyRecipients);
            }
            if (kind == DraftKind.REPLY_ALL && (request.cc() == null || request.cc().isEmpty())) {
                cc = defaultReplyAllCc(
                        source,
                        to,
                        repository.ownedMailboxEmail(appUserId, source.mailboxId()).orElse(""));
            }
        }

        String subject = normalized.subject();
        if (subject.isBlank()) {
            subject = validateSubject(defaultSubject(kind, source.threadSubject()));
        }
        String plainText = normalized.plainTextBody();
        String richHtml = normalized.richTextHtml();
        if (kind == DraftKind.FORWARD) {
            plainText = appendBody(plainText, forwardedPlainText(source));
            richHtml = appendHtmlBody(richHtml, forwardedHtml(source));
        } else {
            plainText = appendBody(plainText, quotedPlainText(source));
            richHtml = appendHtmlBody(richHtml, quotedHtml(source));
        }
        if (plainText.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_UTF8_BYTES
                || richHtml.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_UTF8_BYTES) {
            throw DraftException.badRequest("The quoted message makes this draft larger than 1 MiB.");
        }
        String gmailThreadId = kind == DraftKind.REPLY || kind == DraftKind.REPLY_ALL
                ? validateGmailThreadId(kind, source.gmailThreadId())
                : null;
        return new DraftPayload(
                kind,
                source.sourceMessageId(),
                gmailThreadId,
                kind == DraftKind.REPLY || kind == DraftKind.REPLY_ALL ? source.inReplyTo() : null,
                kind == DraftKind.REPLY || kind == DraftKind.REPLY_ALL ? source.references() : null,
                to,
                cc,
                normalized.bcc(),
                subject,
                plainText,
                htmlSanitizer.sanitize(richHtml));
    }

    private void validateSourceSelection(DraftWriteRequest request, DraftKind kind) {
        if (request == null || request.mailboxId() == null) {
            throw DraftException.badRequest("Choose a sending mailbox.");
        }
        if (kind == DraftKind.NEW) {
            if (request.sourceThreadId() != null || request.sourceMessageId() != null) {
                throw DraftException.badRequest("A new message cannot include source conversation IDs.");
            }
            return;
        }
        if (request.sourceThreadId() == null || request.sourceMessageId() == null) {
            throw DraftException.badRequest("Choose the source message for this reply or forward.");
        }
    }

    private static List<String> defaultReplyAllCc(
            DraftSourceContext source,
            List<String> to,
            String sendingAccount) {
        Set<String> excluded = new java.util.HashSet<>();
        excluded.add(sendingAccount.toLowerCase(Locale.ROOT));
        to.forEach(address -> excluded.add(address.toLowerCase(Locale.ROOT)));
        Set<String> recipients = new java.util.LinkedHashSet<>();
        java.util.stream.Stream.concat(source.to().stream(), source.cc().stream())
                .map(String::trim)
                .filter(address -> !address.isBlank())
                .filter(address -> !excluded.contains(address.toLowerCase(Locale.ROOT)))
                .forEach(recipients::add);
        return List.copyOf(recipients);
    }

    private static String defaultSubject(DraftKind kind, String sourceSubject) {
        String subject = sourceSubject == null ? "" : sourceSubject.trim();
        String prefix = kind == DraftKind.FORWARD ? "Fwd: " : "Re: ";
        if (kind == DraftKind.FORWARD && subject.matches("(?i)^(fwd?|forward):\\s*.*")) return subject;
        if (kind != DraftKind.FORWARD && subject.matches("(?i)^re:\\s*.*")) return subject;
        return prefix + subject;
    }

    private String quotedPlainText(DraftSourceContext source) {
        String sender = source.senderName() == null || source.senderName().isBlank()
                ? source.senderEmail()
                : source.senderName() + " <" + source.senderEmail() + ">";
        return "On " + source.sentAt() + ", " + safeQuotedHeader(sender) + " wrote:\n"
                + (source.bodyText().isBlank() ? htmlSanitizer.sanitize(source.bodyHtml()) : source.bodyText());
    }

    private String quotedHtml(DraftSourceContext source) {
        String sender = source.senderName() == null || source.senderName().isBlank()
                ? source.senderEmail()
                : source.senderName() + " <" + source.senderEmail() + ">";
        String quotedBody = source.bodyHtml().isBlank()
                ? "<p>" + escapeHtml(source.bodyText()) + "</p>"
                : source.bodyHtml();
        return "<div><p>On " + escapeHtml(source.sentAt().toString()) + ", "
                + escapeHtml(safeQuotedHeader(sender)) + " wrote:</p>" + quotedBody + "</div>";
    }

    private String forwardedPlainText(DraftSourceContext source) {
        String headers = "---------- Forwarded message ----------\n"
                + "From: " + safeQuotedHeader(source.senderName() == null || source.senderName().isBlank()
                        ? source.senderEmail()
                        : source.senderName() + " <" + source.senderEmail() + ">") + "\n"
                + "Date: " + source.sentAt() + "\n"
                + "Subject: " + safeQuotedHeader(source.threadSubject()) + "\n"
                + (source.to().isEmpty() ? "" : "To: " + String.join(", ", source.to()) + "\n")
                + (source.cc().isEmpty() ? "" : "Cc: " + String.join(", ", source.cc()) + "\n")
                + "\n";
        String body = source.bodyText().isBlank() ? org.jsoup.Jsoup.parse(source.bodyHtml()).text() : source.bodyText();
        return headers + body;
    }

    private String forwardedHtml(DraftSourceContext source) {
        String sender = source.senderName() == null || source.senderName().isBlank()
                ? source.senderEmail()
                : source.senderName() + " &lt;" + source.senderEmail() + "&gt;";
        String body = source.bodyHtml().isBlank()
                ? "<p>" + escapeHtml(source.bodyText()) + "</p>"
                : source.bodyHtml();
        return "<div><p>---------- Forwarded message ----------<br>From: " + sender
                + "<br>Date: " + escapeHtml(source.sentAt().toString())
                + "<br>Subject: " + escapeHtml(safeQuotedHeader(source.threadSubject()))
                + (source.to().isEmpty() ? "" : "<br>To: " + escapeHtml(String.join(", ", source.to())))
                + (source.cc().isEmpty() ? "" : "<br>Cc: " + escapeHtml(String.join(", ", source.cc())))
                + "</p>" + body + "</div>";
    }

    private static String appendBody(String prefix, String quote) {
        if (quote == null || quote.isBlank()) return prefix;
        if (prefix == null || prefix.isBlank()) return quote;
        return prefix.stripTrailing() + "\n\n" + quote;
    }

    private static String appendHtmlBody(String prefix, String quote) {
        if (quote == null || quote.isBlank()) return prefix;
        if (prefix == null || prefix.isBlank()) return quote;
        return prefix + "<br><br>" + quote;
    }

    private void storeForwardAttachments(
            UUID appUserId,
            UUID draftId,
            List<DraftMimeBuilder.OutboundAttachment> attachments) {
        if (attachments.size() > DraftAttachmentPolicy.MAX_ATTACHMENTS_PER_DRAFT) {
            throw DraftException.badRequest("A forward can contain at most 10 attachments.");
        }
        long totalBytes = 0;
        for (DraftMimeBuilder.OutboundAttachment attachment : attachments) {
            byte[] bytes = attachment.bytes();
            if (bytes.length == 0 || bytes.length > DraftAttachmentPolicy.MAX_FILE_BYTES
                    || totalBytes > DraftAttachmentPolicy.MAX_TOTAL_BYTES - bytes.length) {
                throw DraftException.badRequest("Forwarded attachments exceed East's staging limits.");
            }
            totalBytes += bytes.length;
            storeAttachmentBytes(
                    appUserId,
                    draftId,
                    UUID.randomUUID(),
                    DraftAttachmentPolicy.normalizeSourceFilename(attachment.filename()),
                    DraftAttachmentPolicy.normalizeSourceMimeType(attachment.mimeType()),
                    bytes);
        }
        if (!attachments.isEmpty()) {
            repository.touchLocalDraft(appUserId, draftId);
        }
    }

    private void storeAttachmentBytes(
            UUID appUserId,
            UUID draftId,
            UUID attachmentId,
            String filename,
            String mimeType,
            byte[] plaintext) {
        if (plaintext == null || plaintext.length == 0
                || plaintext.length > DraftAttachmentPolicy.MAX_FILE_BYTES) {
            throw DraftException.badRequest("Choose a non-empty attachment no larger than 10 MiB.");
        }
        EncryptedPayload encrypted = null;
        byte[] nonce = null;
        byte[] ciphertext = null;
        try {
            encrypted = vaultService.encryptLocal(
                    plaintext,
                    DraftAad.attachment(appUserId, draftId, attachmentId));
            nonce = encrypted.nonce();
            ciphertext = encrypted.ciphertext();
            repository.insertAttachment(
                    attachmentId,
                    draftId,
                    filename,
                    mimeType,
                    plaintext.length,
                    nonce,
                    ciphertext);
        } finally {
            wipe(nonce);
            wipe(ciphertext);
            destroy(encrypted);
        }
    }

    private static String validateGmailThreadId(DraftKind kind, String gmailThreadId) {
        if (kind != DraftKind.REPLY && kind != DraftKind.REPLY_ALL) {
            if (gmailThreadId != null) {
                throw DraftException.conflict("This draft contains an unexpected Gmail thread reference.");
            }
            return null;
        }
        if (gmailThreadId == null || !gmailThreadId.matches("[A-Za-z0-9_-]{1,256}")) {
            throw DraftException.conflict("The source conversation has an invalid Gmail thread reference.");
        }
        return gmailThreadId;
    }

    private static String validateReplyHeader(String value) {
        if (value == null) return null;
        if (value.length() > 7000 || value.chars().anyMatch(Character::isISOControl)
                || !value.matches("(?:<[^<>\\s\\p{Cntrl}]{1,250}>\\s*)+")) {
            throw DraftException.conflict("The source message has invalid reply headers.");
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private static String safeQuotedHeader(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("[\\p{Cntrl}]", " ").trim();
    }

    private static String escapeHtml(String value) {
        return new org.jsoup.nodes.TextNode(value == null ? "" : value).outerHtml();
    }


    private List<String> validateRecipients(List<String> submitted) {
        if (submitted == null || submitted.isEmpty()) {
            return List.of();
        }
        if (submitted.size() > MAX_RECIPIENTS_TOTAL) {
            throw DraftException.badRequest("A draft can contain at most 100 recipients.");
        }
        List<String> validated = new ArrayList<>(submitted.size());
        for (String value : submitted) {
            if (value == null) {
                throw DraftException.badRequest("Enter valid email addresses for recipients.");
            }
            String address = value.trim();
            rejectHeaderControls(address, "recipient");
            if (address.length() > 320 || !SIMPLE_EMAIL.matcher(address).matches()) {
                throw DraftException.badRequest("Enter a valid email address for each recipient.");
            }
            int at = address.indexOf('@');
            String localPart = address.substring(0, at);
            if (localPart.startsWith(".") || localPart.endsWith(".") || localPart.contains("..")) {
                throw DraftException.badRequest("Enter a valid email address for each recipient.");
            }
            validated.add(address);
        }
        return List.copyOf(validated);
    }

    private void validateCanSend(DraftPayload payload) {
        if (payload.to().isEmpty() && payload.cc().isEmpty() && payload.bcc().isEmpty()) {
            throw DraftException.badRequest("Add at least one To, CC, or BCC recipient before sending.");
        }
        if (payload.plainTextBody().isEmpty() && payload.richTextHtml().isEmpty()) {
            throw DraftException.badRequest("Add a message before sending.");
        }
    }

    private static void rejectHeaderControls(String value, String field) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw DraftException.badRequest("The " + field + " cannot contain line breaks or control characters.");
            }
        }
    }

    private static String validateSubject(String subject) {
        rejectHeaderControls(subject, "subject");
        byte[] encoded = subject.getBytes(StandardCharsets.UTF_8);
        try {
            if (subject.length() > 500 || encoded.length > MAX_SUBJECT_UTF8_BYTES) {
                throw DraftException.badRequest("Subject must be at most 500 characters.");
            }
        } finally {
            wipe(encoded);
        }
        return subject;
    }

    private DraftRepository.DraftRow requireOwnedDraft(UUID appUserId, UUID draftId, boolean lock) {
        return repository.findOwned(appUserId, draftId, lock).orElseThrow(DraftException::notFound);
    }

    private void requireOwnedMailbox(UUID appUserId, UUID mailboxId) {
        if (!repository.mailboxIsOwned(appUserId, mailboxId)) {
            throw DraftException.notFound();
        }
    }

    private String requireOwnedMailboxEmail(UUID appUserId, UUID mailboxId) {
        return repository.ownedMailboxEmail(appUserId, mailboxId).orElseThrow(DraftException::notFound);
    }

    private void requireLocal(DraftRepository.DraftRow row) {
        if (!"LOCAL".equals(row.state())) {
            throw DraftException.conflict("This draft has already been saved to Gmail and cannot be edited in East.");
        }
    }

    private static boolean isPendingState(String state) {
        return "SAVE_PENDING".equals(state) || "SEND_PENDING".equals(state);
    }

    private static DraftException reconciliationConflict() {
        return DraftException.conflict(
                "A Gmail save or send may still have completed. Check Gmail and use the draft reconciliation endpoint before continuing.");
    }

    private record PreparedSave(
            String accessToken,
            Map<String, Object> message,
            List<DraftMimeBuilder.OutboundAttachment> attachments) {
        void destroy() {
            wipeOutboundAttachments(attachments);
        }
    }

    private record PreparedSend(
            String accessToken,
            String gmailDraftId,
            Map<String, Object> message,
            List<DraftMimeBuilder.OutboundAttachment> attachments) {
        void destroy() {
            wipeOutboundAttachments(attachments);
        }
    }

    private record SavedDraftSendSnapshot(
            DraftGmailReviewParser.ParsedDraft draft,
            List<DraftMimeBuilder.OutboundAttachment> attachments) {
        private SavedDraftSendSnapshot {
            attachments = List.copyOf(attachments);
        }
    }

    private UUID requirePrincipal(EastUserPrincipal principal) {
        if (principal == null || principal.appUserId() == null) {
            throw DraftException.unauthorized();
        }
        return principal.appUserId();
    }

    private byte[] serialize(DraftPayload payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encrypt draft content.");
        }
    }

    private DraftResponse toResponse(
            DraftRepository.DraftRow row,
            String fromAddress,
            DraftPayload payload,
            List<DraftAttachmentSummary> attachments,
            String reviewFingerprint) {
        return new DraftResponse(
                row.id(),
                row.mailboxId(),
                fromAddress,
                row.kind(),
                row.state(),
                row.sourceThreadId(),
                payload == null ? null : payload.sourceMessageId(),
                payload == null ? null : payload.to(),
                payload == null ? null : payload.cc(),
                payload == null ? null : payload.bcc(),
                payload == null ? null : payload.subject(),
                payload == null ? null : payload.plainTextBody(),
                payload == null ? null : payload.richTextHtml(),
                attachments,
                row.createdAt(),
                row.updatedAt(),
                row.savedAt(),
                reviewFingerprint);
    }

    static void wipeOutboundAttachments(List<DraftMimeBuilder.OutboundAttachment> attachments) {
        attachments.forEach(attachment -> wipe(attachment.bytes()));
    }

    private static void destroy(EncryptedPayload encrypted) {
        if (encrypted != null) {
            encrypted.destroy();
        }
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
