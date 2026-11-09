package com.east.app.settings;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record RetentionSettingsRequest(@Min(1) @Max(365) int bodyCacheDays) {
}
