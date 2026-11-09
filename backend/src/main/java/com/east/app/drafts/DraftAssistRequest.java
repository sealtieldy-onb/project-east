package com.east.app.drafts;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record DraftAssistRequest(
        @NotNull UUID mailboxId,
        @NotNull DraftKind kind,
        UUID sourceThreadId,
        UUID sourceMessageId,
        @Size(max = 100) List<@Size(max = 320) String> to,
        @Size(max = 100) List<@Size(max = 320) String> cc,
        @Size(max = 100) List<@Size(max = 320) String> bcc,
        @Size(max = 500) String subject,
        @Size(max = 8000) String instructions,
        boolean providerDisclosureConfirmed,
        @jakarta.validation.constraints.NotBlank
        @jakarta.validation.constraints.Pattern(regexp = "[a-f0-9]{64}") String providerConfigurationVersion) {

    @Override
    public String toString() {
        return "DraftAssistRequest[mailboxId=" + mailboxId + ", kind=" + kind
                + ", sourceThreadId=" + sourceThreadId + ", sourceMessageId=" + sourceMessageId
                + ", instructions=REDACTED, providerDisclosureConfirmed=" + providerDisclosureConfirmed
                + ", providerConfigurationVersion=REDACTED]";
    }
}
