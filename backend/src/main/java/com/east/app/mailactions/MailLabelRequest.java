package com.east.app.mailactions;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MailLabelRequest(@NotBlank @Size(max = 225) String name) {
}
