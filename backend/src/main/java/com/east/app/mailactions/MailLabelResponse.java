package com.east.app.mailactions;

import java.util.UUID;

public record MailLabelResponse(UUID id, UUID mailboxId, String name) {
}
