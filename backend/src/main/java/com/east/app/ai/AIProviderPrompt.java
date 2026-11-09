package com.east.app.ai;

import java.util.List;
import java.util.Objects;

/** A bounded, text-only request prepared by an explicitly invoked application workflow. */
public record AIProviderPrompt(List<Message> messages) {

    private static final int MAX_MESSAGES = 32;
    private static final int MAX_TOTAL_CHARACTERS = 750_000;

    public AIProviderPrompt {
        Objects.requireNonNull(messages, "messages");
        messages = List.copyOf(messages);
        if (messages.isEmpty() || messages.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException("The model request message count is invalid.");
        }
        if (messages.getFirst().role() != Role.SYSTEM
                || messages.stream().noneMatch(message -> message.role() == Role.USER)) {
            throw new IllegalArgumentException("The model request needs system and user messages.");
        }
        long totalCharacters = messages.stream().mapToLong(message -> message.content().length()).sum();
        if (totalCharacters > MAX_TOTAL_CHARACTERS) {
            throw new IllegalArgumentException("The model request exceeds the local input limit.");
        }
    }

    public record Message(Role role, String content) {
        public Message {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(content, "content");
            if (content.isBlank()) {
                throw new IllegalArgumentException("Model request messages cannot be blank.");
            }
        }
    }

    public enum Role {
        SYSTEM("system"),
        USER("user");

        private final String wireValue;

        Role(String wireValue) {
            this.wireValue = wireValue;
        }

        String wireValue() {
            return wireValue;
        }
    }
}
