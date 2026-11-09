package com.east.app.mailactions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.east.app.auth.AuthFlowException;
import com.east.app.auth.EastUserPrincipal;
import com.east.app.auth.GoogleAccessTokenService;
import com.east.app.gmail.GmailApiClient;
import com.east.app.gmail.GmailRequestGuard;
import com.east.app.vault.VaultService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MailActionsService {

    private static final int MAX_BULK_THREADS = 100;
    private static final int MAX_LABELS_PER_ACTION = 50;

    private final MailActionsRepository repository;
    private final GmailApiClient gmailApiClient;
    private final GoogleAccessTokenService accessTokenService;

    public MailActionsService(
            MailActionsRepository repository,
            GmailApiClient gmailApiClient,
            GoogleAccessTokenService accessTokenService) {
        this.repository = repository;
        this.gmailApiClient = gmailApiClient;
        this.accessTokenService = accessTokenService;
    }

    public void performThreadAction(EastUserPrincipal principal, UUID threadId, ThreadActionRequest request) {
        ThreadAction action = requireAction(request.action());
        List<UUID> requestedLabels = validateLabelIds(request.labelIds());
        applyOne(principal.appUserId(), threadId, action, requestedLabels);
    }

    public BulkThreadActionsResponse performBulkAction(EastUserPrincipal principal, BulkThreadActionsRequest request) {
        List<UUID> threadIds = validateThreadIds(request.threadIds());
        ThreadAction action = requireAction(request.action());
        List<UUID> requestedLabels = validateLabelIds(request.labelIds());

        List<ThreadActionOutcome> outcomes = new ArrayList<>(threadIds.size());
        for (UUID threadId : threadIds) {
            try {
                applyOne(principal.appUserId(), threadId, action, requestedLabels);
                outcomes.add(ThreadActionOutcome.success(threadId));
            } catch (MailActionsException exception) {
                outcomes.add(ThreadActionOutcome.failure(threadId, exception.errorCode()));
            } catch (GmailApiClient.GmailApiException exception) {
                outcomes.add(ThreadActionOutcome.gmailFailure(
                        threadId, exception.getHttpStatus(), exception.getGoogleErrorCode()));
            } catch (AuthFlowException exception) {
                outcomes.add(ThreadActionOutcome.failure(threadId, "AUTHORIZATION_REQUIRED"));
            } catch (VaultService.VaultLockedException exception) {
                outcomes.add(ThreadActionOutcome.failure(threadId, "VAULT_LOCKED"));
            } catch (RuntimeException exception) {
                // Return a stable, content-free outcome while allowing other selected threads to proceed.
                outcomes.add(ThreadActionOutcome.failure(threadId, "ACTION_FAILED"));
            }
        }
        return new BulkThreadActionsResponse(outcomes);
    }

    public MailLabelResponse createLabel(EastUserPrincipal principal, UUID mailboxId, MailLabelRequest request) {
        requireOwnedMailbox(principal.appUserId(), mailboxId);
        String name = normalizedLabelName(request.name());
        String accessToken = getAccessToken(principal.appUserId(), mailboxId);
        JsonNode created = GmailRequestGuard.call(
                accessTokenService,
                principal.appUserId(),
                mailboxId,
                accessToken,
                () -> gmailApiClient.createLabel(accessToken, java.util.Map.of("name", name)));
        String gmailLabelId = created.path("id").asText(null);
        if (!StringUtils.hasText(gmailLabelId)) {
            throw upstreamResponseInvalid();
        }

        String savedName = created.path("name").asText(null);
        if (!StringUtils.hasText(savedName)) {
            savedName = name;
        }
        UUID localId = UUID.randomUUID();
        try {
            repository.insertUserLabel(localId, mailboxId, gmailLabelId, savedName);
        } catch (RuntimeException exception) {
            throw localSyncFailure();
        }
        return new MailLabelResponse(localId, mailboxId, savedName);
    }

    public MailLabelResponse renameLabel(
            EastUserPrincipal principal, UUID mailboxId, UUID labelId, MailLabelRequest request) {
        MailActionsRepository.UserLabel label = requireOwnedUserLabel(principal.appUserId(), mailboxId, labelId);
        String name = normalizedLabelName(request.name());
        String accessToken = getAccessToken(principal.appUserId(), mailboxId);
        JsonNode updated = GmailRequestGuard.call(
                accessTokenService,
                principal.appUserId(),
                mailboxId,
                accessToken,
                () -> gmailApiClient.updateLabel(
                        accessToken, label.gmailLabelId(), java.util.Map.of("name", name)));
        String savedName = updated.path("name").asText(null);
        if (!StringUtils.hasText(savedName)) {
            savedName = name;
        }
        int changed;
        try {
            changed = repository.renameOwnedUserLabel(principal.appUserId(), mailboxId, label.id(), savedName);
        } catch (RuntimeException exception) {
            throw localSyncFailure();
        }
        if (changed != 1) {
            throw localSyncFailure();
        }
        return new MailLabelResponse(label.id(), mailboxId, savedName);
    }

    public void deleteLabel(EastUserPrincipal principal, UUID mailboxId, UUID labelId) {
        MailActionsRepository.UserLabel label = requireOwnedUserLabel(principal.appUserId(), mailboxId, labelId);
        String accessToken = getAccessToken(principal.appUserId(), mailboxId);
        GmailRequestGuard.run(
                accessTokenService,
                principal.appUserId(),
                mailboxId,
                accessToken,
                () -> gmailApiClient.deleteLabel(accessToken, label.gmailLabelId()));
        try {
            if (repository.deleteOwnedUserLabel(principal.appUserId(), mailboxId, label.id()) != 1) {
                throw localSyncFailure();
            }
        } catch (MailActionsException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw localSyncFailure();
        }
    }

    private void applyOne(UUID appUserId, UUID threadId, ThreadAction action, List<UUID> requestedLabelIds) {
        MailActionsRepository.OwnedThread thread = repository.findOwnedThread(appUserId, threadId);
        if (thread == null) {
            throw notFound();
        }

        List<String> addLabels = new ArrayList<>();
        List<String> removeLabels = new ArrayList<>();
        List<MailActionsRepository.UserLabel> userLabels = List.of();
        switch (action) {
            case ARCHIVE -> removeLabels.add("INBOX");
            case MOVE_TO_INBOX -> addLabels.add("INBOX");
            case TRASH -> {
                addLabels.add("TRASH");
                removeLabels.add("INBOX");
            }
            case UNTRASH -> {
                removeLabels.add("TRASH");
                addLabels.add("INBOX");
            }
            case MARK_READ -> removeLabels.add("UNREAD");
            case MARK_UNREAD -> addLabels.add("UNREAD");
            case SPAM -> {
                addLabels.add("SPAM");
                removeLabels.add("INBOX");
            }
            case NOT_SPAM -> {
                removeLabels.add("SPAM");
                addLabels.add("INBOX");
            }
            case STAR -> addLabels.add("STARRED");
            case UNSTAR -> removeLabels.add("STARRED");
            case ADD_LABELS, REMOVE_LABELS -> {
                if (requestedLabelIds.isEmpty()) {
                    throw badRequest("Select at least one user label for this action.");
                }
                userLabels = repository.findOwnedUserLabels(appUserId, thread.mailboxId(), requestedLabelIds);
                if (userLabels.size() != requestedLabelIds.size()) {
                    throw new MailActionsException(
                            HttpStatus.NOT_FOUND, "LABEL_NOT_FOUND", "One or more selected user labels were not found.");
                }
                List<String> gmailLabelIds = userLabels.stream()
                        .map(MailActionsRepository.UserLabel::gmailLabelId)
                        .toList();
                (action == ThreadAction.ADD_LABELS ? addLabels : removeLabels).addAll(gmailLabelIds);
            }
        }
        if (action != ThreadAction.ADD_LABELS && action != ThreadAction.REMOVE_LABELS
                && !requestedLabelIds.isEmpty()) {
            throw badRequest("Label IDs are only valid for add-labels or remove-labels actions.");
        }

        String accessToken = getAccessToken(appUserId, thread.mailboxId());
        if (action == ThreadAction.TRASH) {
            GmailRequestGuard.run(
                    accessTokenService,
                    appUserId,
                    thread.mailboxId(),
                    accessToken,
                    () -> gmailApiClient.trashThread(accessToken, thread.gmailThreadId()));
        } else if (action == ThreadAction.UNTRASH) {
            GmailRequestGuard.run(
                    accessTokenService,
                    appUserId,
                    thread.mailboxId(),
                    accessToken,
                    () -> gmailApiClient.untrashThread(accessToken, thread.gmailThreadId()));
        } else {
            GmailRequestGuard.run(
                    accessTokenService,
                    appUserId,
                    thread.mailboxId(),
                    accessToken,
                    () -> gmailApiClient.modifyThreadLabels(
                            accessToken,
                            thread.gmailThreadId(),
                            List.copyOf(addLabels),
                            List.copyOf(removeLabels)));
        }

        Boolean unread = switch (action) {
            case MARK_READ -> Boolean.FALSE;
            case MARK_UNREAD -> Boolean.TRUE;
            default -> null;
        };
        Boolean starred = switch (action) {
            case STAR -> Boolean.TRUE;
            case UNSTAR -> Boolean.FALSE;
            default -> null;
        };
        try {
            repository.synchronizeThreadState(
                    thread.id(), thread.mailboxId(), addLabels, removeLabels, unread, starred);
        } catch (RuntimeException exception) {
            throw localSyncFailure();
        }
    }

    private List<UUID> validateThreadIds(List<UUID> threadIds) {
        if (threadIds == null || threadIds.isEmpty() || threadIds.size() > MAX_BULK_THREADS
                || threadIds.stream().anyMatch(id -> id == null)) {
            throw badRequest("Select between 1 and 100 threads.");
        }
        if (new HashSet<>(threadIds).size() != threadIds.size()) {
            throw badRequest("A thread may appear only once in a bulk action.");
        }
        return List.copyOf(threadIds);
    }

    private List<UUID> validateLabelIds(List<UUID> labelIds) {
        if (labelIds == null || labelIds.isEmpty()) {
            return List.of();
        }
        if (labelIds.size() > MAX_LABELS_PER_ACTION || labelIds.stream().anyMatch(id -> id == null)) {
            throw badRequest("Select up to 50 valid user labels.");
        }
        if (new HashSet<>(labelIds).size() != labelIds.size()) {
            throw badRequest("A user label may appear only once in an action.");
        }
        return List.copyOf(labelIds);
    }

    private static ThreadAction requireAction(ThreadAction action) {
        if (action == null) {
            throw badRequest("Choose a supported thread action.");
        }
        return action;
    }

    private void requireOwnedMailbox(UUID appUserId, UUID mailboxId) {
        if (!repository.mailboxIsOwned(appUserId, mailboxId)) {
            throw notFound();
        }
    }

    private MailActionsRepository.UserLabel requireOwnedUserLabel(UUID appUserId, UUID mailboxId, UUID labelId) {
        requireOwnedMailbox(appUserId, mailboxId);
        MailActionsRepository.UserLabel label = repository.findOwnedUserLabel(appUserId, mailboxId, labelId);
        if (label == null) {
            throw notFound();
        }
        return label;
    }

    private String getAccessToken(UUID appUserId, UUID mailboxId) {
        String accessToken = accessTokenService.getValidAccessToken(appUserId, mailboxId);
        if (!StringUtils.hasText(accessToken)) {
            throw new MailActionsException(
                    HttpStatus.BAD_GATEWAY,
                    "ACCESS_TOKEN_UNAVAILABLE",
                    "Gmail authorization is unavailable for this mailbox.");
        }
        return accessToken;
    }

    private static String normalizedLabelName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty() || normalized.length() > 225) {
            throw badRequest("Enter a label name between 1 and 225 characters.");
        }
        return normalized;
    }

    private static MailActionsException notFound() {
        return new MailActionsException(HttpStatus.NOT_FOUND, "NOT_FOUND", "The requested mailbox resource was not found.");
    }

    private static MailActionsException badRequest(String message) {
        return new MailActionsException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    private static MailActionsException upstreamResponseInvalid() {
        return new MailActionsException(
                HttpStatus.BAD_GATEWAY, "GMAIL_RESPONSE_INVALID", "Gmail did not return the created label details.");
    }

    private static MailActionsException localSyncFailure() {
        return new MailActionsException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "LOCAL_SYNC_FAILED",
                "Gmail accepted the action, but East could not update its local mailbox data.");
    }
}
