package com.east.app.drafts;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DraftWriteRequest(
        @NotNull UUID mailboxId,
        DraftKind kind,
        UUID sourceThreadId,
        UUID sourceMessageId,
        @Size(max = 100) List<@Size(max = 320) String> to,
        @Size(max = 100) List<@Size(max = 320) String> cc,
        @Size(max = 100) List<@Size(max = 320) String> bcc,
        @Size(max = 500) String subject,
        @Size(max = 262_144) String plainTextBody,
        @Size(max = 262_144) String richTextHtml) {
}
