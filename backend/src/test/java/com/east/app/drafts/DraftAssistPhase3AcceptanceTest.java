package com.east.app.drafts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.east.app.ai.AIProviderClient;
import com.east.app.ai.AIProviderGateway;
import com.east.app.ai.AIProviderPrompt;
import com.east.app.ai.StructuredOutputContract;
import com.east.app.auth.EastUserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DraftAssistPhase3AcceptanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID MAILBOX_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID THREAD_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID MESSAGE_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final String DISCLOSURE_VERSION = "b".repeat(64);
    private static final String ATTACHMENT_SENTINEL = "SYNTHETIC_ATTACHMENT_CONTENT_SENTINEL";

    @Test
    void replyAssistRequiresDisclosureAndSendsOnlyTextToProviderThenCreatesLocalDraft() throws Exception {
        FakeProvider provider = new FakeProvider();
        FakeDraftStore drafts = new FakeDraftStore();
        FakeSourceLoader sources = new FakeSourceLoader(ATTACHMENT_SENTINEL);
        DraftAssistService service = new DraftAssistService(provider, drafts, sources, MAPPER);
        DraftAssistRequest undisclosed = request(false, DISCLOSURE_VERSION);

        DraftException disclosureError = assertThrows(DraftException.class,
                () -> service.generate(principal(), undisclosed));
        assertEquals(HttpStatus.BAD_REQUEST, disclosureError.status());
        assertEquals(0, drafts.validationCount);
        assertEquals(0, provider.calls);
        assertEquals(0, sources.loadCount);

        DraftResponse generated = service.generate(principal(), request(true, DISCLOSURE_VERSION));

        assertEquals(1, drafts.validationCount);
        assertEquals(1, drafts.localCreateCount);
        assertEquals(1, provider.calls);
        assertEquals(1, sources.loadCount);
        assertEquals("LOCAL", generated.state());
        assertEquals("Synthetic assistant draft.", generated.plainTextBody());
        assertEquals("Synthetic source subject", drafts.receivedSource.threadSubject());

        JsonNode userInput = MAPPER.readTree(provider.lastUserMessage());
        assertEquals("REPLY", userInput.path("draftType").asText());
        assertEquals("Synthetic source body.", userInput.path("sourceMessage").path("bodyText").asText());
        assertFalse(userInput.toString().contains(ATTACHMENT_SENTINEL));
        assertFalse(userInput.toString().contains("forwardAttachments"));
    }

    @Test
    void providerConfigurationChangeStopsBeforeLoadingReplyContent() {
        FakeProvider provider = new FakeProvider();
        provider.metadata = new AIProviderClient.ProviderMetadata(
                "OPENAI_COMPATIBLE", "http://127.0.0.1:43127/v1", "changed-model", "c".repeat(64));
        FakeDraftStore drafts = new FakeDraftStore();
        FakeSourceLoader sources = new FakeSourceLoader(ATTACHMENT_SENTINEL);
        DraftAssistService service = new DraftAssistService(provider, drafts, sources, MAPPER);

        DraftException changed = assertThrows(DraftException.class,
                () -> service.generate(principal(), request(true, DISCLOSURE_VERSION)));

        assertEquals(HttpStatus.CONFLICT, changed.status());
        assertEquals("DISCLOSURE_CHANGED", changed.errorCode());
        assertEquals(1, drafts.validationCount);
        assertEquals(0, sources.loadCount);
        assertEquals(0, provider.calls);
        assertEquals(0, drafts.localCreateCount);
    }

    private static DraftAssistRequest request(boolean confirmed, String configurationVersion) {
        return new DraftAssistRequest(
                MAILBOX_ID,
                DraftKind.REPLY,
                THREAD_ID,
                MESSAGE_ID,
                List.of("recipient@example.invalid"),
                null,
                null,
                null,
                "Please reply to the synthetic project update.",
                confirmed,
                configurationVersion);
    }

    private static EastUserPrincipal principal() {
        return new EastUserPrincipal(OWNER_ID, "synthetic-subject", "owner@example.invalid", "Synthetic owner");
    }

    private static final class FakeProvider implements AIProviderGateway {
        private AIProviderClient.ProviderMetadata metadata = new AIProviderClient.ProviderMetadata(
                "OPENAI_COMPATIBLE", "http://127.0.0.1:43127/v1", "synthetic-model", DISCLOSURE_VERSION);
        private final List<AIProviderPrompt> prompts = new ArrayList<>();
        private int calls;

        @Override
        public AIProviderClient.ProviderMetadata providerMetadata() {
            return metadata;
        }

        @Override
        public <T> AIProviderClient.Completion<T> completeWithMetadata(
                AIProviderPrompt prompt,
                StructuredOutputContract<T> contract,
                String expectedConfigurationVersion) {
            assertEquals(metadata.configurationVersion(), expectedConfigurationVersion);
            calls++;
            prompts.add(prompt);
            ObjectNode output = MAPPER.createObjectNode();
            output.put("subject", "Synthetic subject");
            output.put("body", "Synthetic assistant draft.");
            return new AIProviderClient.Completion<>(contract.validate(output), metadata);
        }

        private String lastUserMessage() {
            return prompts.getLast().messages().getLast().content();
        }
    }

    private static final class FakeDraftStore implements DraftAssistantStore {
        private int validationCount;
        private int localCreateCount;
        private DraftSourceContext receivedSource;

        @Override
        public void validateAssistantRequest(EastUserPrincipal principal, DraftWriteRequest request) {
            validationCount++;
            assertEquals(OWNER_ID, principal.appUserId());
            assertEquals(MAILBOX_ID, request.mailboxId());
        }

        @Override
        public DraftResponse createAssistedDraft(
                EastUserPrincipal principal,
                DraftWriteRequest request,
                DraftSourceContext source) {
            localCreateCount++;
            receivedSource = source;
            Instant now = Instant.parse("2026-11-10T12:00:00Z");
            return new DraftResponse(
                    UUID.fromString("50000000-0000-4000-8000-000000000001"),
                    request.mailboxId(), "owner@example.invalid", request.kind().name(), "LOCAL",
                    request.sourceThreadId(), request.sourceMessageId(), request.to(), request.cc(), request.bcc(),
                    "Re: Synthetic source subject", request.plainTextBody(), null, List.of(), now, now, null, null);
        }
    }

    private static final class FakeSourceLoader implements DraftAssistantSourceLoader {
        private final byte[] attachmentBytes;
        private int loadCount;

        private FakeSourceLoader(String attachmentSentinel) {
            attachmentBytes = attachmentSentinel.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public DraftSourceContext load(
                UUID appUserId,
                UUID mailboxId,
                UUID sourceThreadId,
                UUID sourceMessageId,
                DraftKind kind) {
            loadCount++;
            assertEquals(OWNER_ID, appUserId);
            assertEquals(MAILBOX_ID, mailboxId);
            assertEquals(DraftKind.REPLY, kind);
            return new DraftSourceContext(
                    sourceThreadId, sourceMessageId, mailboxId,
                    "synthetic-gmail-thread", "synthetic-gmail-message", "Synthetic source subject",
                    "sender@example.invalid", "Synthetic sender", Instant.parse("2026-11-09T10:00:00Z"),
                    "Synthetic source body.", null, List.of("owner@example.invalid"), List.of(), List.of(),
                    "<synthetic-source@example.invalid>", "<synthetic-source@example.invalid>",
                    List.of(new DraftMimeBuilder.OutboundAttachment(
                            "synthetic.bin", "application/octet-stream", attachmentBytes)));
        }
    }
}
