package com.east.app.drafts;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DraftReconciliationRequest(
        @NotNull Boolean confirmed,
        @NotNull @Pattern(regexp = "CONFIRMED_COMPLETED|CONFIRMED_NOT_COMPLETED") String resolution,
        @Size(max = 256) @Pattern(regexp = "[A-Za-z0-9_-]{1,256}") String gmailDraftId) {
}
