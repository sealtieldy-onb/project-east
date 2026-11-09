package com.east.app.drafts;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SendDraftRequest(
        @NotNull Boolean confirmed,
        @NotNull Instant expectedUpdatedAt,
        @NotNull @Size(min = 43, max = 43) @Pattern(regexp = "[A-Za-z0-9_-]{43}") String reviewFingerprint) {
}
