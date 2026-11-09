package com.east.app.mailactions;

import java.util.List;

public record BulkThreadActionsResponse(List<ThreadActionOutcome> outcomes) {
    public BulkThreadActionsResponse {
        outcomes = List.copyOf(outcomes);
    }
}
