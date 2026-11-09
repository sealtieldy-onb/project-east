package com.east.app.mailactions;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BulkThreadActionsRequest(
        @NotNull @Size(min = 1, max = 100) List<@NotNull UUID> threadIds,
        @NotNull ThreadAction action,
        @Size(max = 50) List<@NotNull UUID> labelIds) {
}
