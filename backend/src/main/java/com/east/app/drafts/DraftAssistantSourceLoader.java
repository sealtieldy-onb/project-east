package com.east.app.drafts;

import java.util.UUID;

/** Loads a scoped reply source for explicit assistant drafting. */
public interface DraftAssistantSourceLoader {

    DraftSourceContext load(
            UUID appUserId,
            UUID mailboxId,
            UUID sourceThreadId,
            UUID sourceMessageId,
            DraftKind kind);
}
