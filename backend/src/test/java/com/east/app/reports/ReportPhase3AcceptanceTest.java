package com.east.app.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.east.app.ai.AIProviderClient;
import com.east.app.ai.AIProviderGateway;
import com.east.app.ai.AIProviderPrompt;
import com.east.app.ai.StructuredOutputContract;
import com.east.app.gmail.GmailCacheAad;
import com.east.app.gmail.GmailSyncService;
import com.east.app.gmail.GmailSyncService.ReportMessageDescriptor;
import com.east.app.reports.ReportModels.ExpansionDecision;
import com.east.app.reports.ReportModels.DashboardResponse;
import com.east.app.reports.ReportModels.DailyReportSettingsRequest;
import com.east.app.reports.ReportModels.GenerateReportRequest;
import com.east.app.reports.ReportModels.GenerateReportResponse;
import com.east.app.reports.ReportModels.GenerationStatus;
import com.east.app.reports.ReportModels.MailboxScope;
import com.east.app.reports.ReportModels.ReportSource;
import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.LocalVaultKeyStore;
import com.east.app.vault.VaultCryptoService;
import com.east.app.vault.VaultRecord;
import com.east.app.vault.VaultRepository;
import com.east.app.vault.VaultService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

class ReportPhase3AcceptanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID MAILBOX_A = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID MAILBOX_B = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final String DISCLOSURE_VERSION = "a".repeat(64);
    private static final String ATTACHMENT_SENTINEL = "SYNTHETIC_ATTACHMENT_CONTENT_SENTINEL";

    @TempDir
    Path tempDir;

    @Test
    void dailyScheduleStaysOffUntilDisclosureAndFlagsProviderChanges() {
        FakeProvider provider = new FakeProvider();
        FakeSettingsRepository schedules = new FakeSettingsRepository();
        DailyReportSettingsService settings = new DailyReportSettingsService(schedules, provider);

        var defaults = settings.get(OWNER_ID);
        assertFalse(defaults.enabled());
        assertEquals(LocalTime.of(8, 0), defaults.runAtLocal());
        assertEquals("UTC", defaults.timezoneId());
        assertFalse(defaults.providerDisclosureRequired());

        assertThrows(ReportService.ReportRequestException.class,
                () -> settings.update(OWNER_ID, new DailyReportSettingsRequest(
                        true, LocalTime.of(7, 30), "America/Los_Angeles", false, null)));
        assertEquals(0, schedules.saveCalls);

        var enabled = settings.update(OWNER_ID, new DailyReportSettingsRequest(
                true, LocalTime.of(7, 30), "America/Los_Angeles", true, DISCLOSURE_VERSION));
        assertTrue(enabled.enabled());
        assertEquals("America/Los_Angeles", enabled.timezoneId());
        assertFalse(enabled.providerDisclosureRequired());
        assertEquals(1, schedules.saveCalls);

        provider.metadata = new AIProviderClient.ProviderMetadata(
                "OPENAI_COMPATIBLE", "http://127.0.0.1:43127/v1", "changed-model", "e".repeat(64));
        var changedDisclosure = settings.get(OWNER_ID);
        assertFalse(changedDisclosure.enabled());
        assertTrue(changedDisclosure.providerDisclosureRequired());
        assertEquals(0, provider.calls);
    }

    @Test
    void dashboardReadUsesOnlySavedLocalReportsAndNeverInvokesGmailOrProviderCompletion() {
        Fixture fixture = new Fixture(tempDir.resolve("dashboard-read"), List.of(mailbox(MAILBOX_A)));
        try {
            DashboardResponse dashboard = fixture.reports.getDashboard(
                    OWNER_ID, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2), MAILBOX_A);

            assertTrue(dashboard.aiConfigured());
            assertNull(dashboard.summary());
            assertEquals(0, fixture.provider.calls);
            assertEquals(0, fixture.gmail.listCallCount);
            assertEquals(0, fixture.gmail.bodyReadCount);
        } finally {
            fixture.destroy();
        }
    }

    @Test
    void manualReportRequiresPreviewAndExpansionApprovalAndPersistsEncryptedPartialReport() throws Exception {
        Fixture fixture = new Fixture(tempDir.resolve("manual"), List.of(mailbox(MAILBOX_A)));
        try {
            LocalDate start = LocalDate.of(2026, 11, 1);
            LocalDate end = LocalDate.of(2026, 11, 2);
            for (int index = 0; index < 350; index++) {
                fixture.repository.addMessage(MAILBOX_A, index, index + 1,
                        start.plusDays(0).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(index), false);
            }

            GenerateReportResponse preview = fixture.reports.generate(OWNER_ID,
                    request(start, end, false, null, null, null));
            assertEquals(GenerationStatus.DISCLOSURE_REQUIRED, preview.status());
            assertEquals(350, preview.coverage().eligibleMessageCount());
            assertEquals(30, preview.coverage().selectedMessageCount());
            assertEquals(320, preview.coverage().additionalMessageCount());
            assertEquals(0, fixture.gmail.bodyReadCount);
            assertEquals(0, fixture.provider.calls);

            GenerateReportResponse expansion = fixture.reports.generate(OWNER_ID,
                    request(start, end, true, 350, 320, null));
            assertEquals(GenerationStatus.EXPANSION_REQUIRED, expansion.status());
            assertEquals(240, expansion.maxAdditionalMessageCount());
            assertEquals(0, fixture.gmail.bodyReadCount);
            assertEquals(0, fixture.provider.calls);

            GenerateReportResponse completed = fixture.reports.generate(OWNER_ID,
                    request(start, end, true, 350, 320, ExpansionDecision.ALLOW));

            assertEquals(GenerationStatus.COMPLETED, completed.status());
            assertEquals("Synthetic report narrative.", completed.summary().narrative());
            assertEquals("PARTIAL", completed.coverage().status().name());
            assertEquals(270, completed.coverage().selectedMessageCount());
            assertEquals(320, completed.coverage().additionalMessageCount());
            assertEquals(1, completed.insights().size());
            assertEquals("A synthetic reply is pending.", completed.insights().getFirst().text());
            assertEquals(1, completed.insights().getFirst().sources().size());
            assertEquals(270, fixture.gmail.bodyReadCount,
                    "The allowance is 30 initial bodies plus no more than 240 additional bodies.");
            assertEquals(1, fixture.provider.calls);

            JsonNode prompt = MAPPER.readTree(fixture.provider.lastUserMessage());
            assertEquals(270, prompt.path("messages").size());
            assertTrue(prompt.toString().contains("Synthetic message 350"));
            assertFalse(prompt.toString().contains(ATTACHMENT_SENTINEL));

            FakeReportRepository.StoredReport stored = fixture.repository.onlyReport();
            assertEquals("MANUAL", stored.purpose());
            assertEquals(270, stored.selectedMessageCount());
            assertEquals(270, stored.sourceCount());
            assertTrue(stored.expansionApproved());
            assertEquals(1, stored.mailboxIds().size());
            assertEquals(NOW.plusSeconds(7 * 86_400L), stored.expiresAt());
            assertFalse(new String(stored.narrativeCiphertext(), StandardCharsets.UTF_8)
                    .contains("Synthetic report narrative."));
            FakeReportRepository.StoredInsight storedInsight = fixture.repository.onlyInsight();
            assertFalse(new String(storedInsight.contentCiphertext(), StandardCharsets.UTF_8)
                    .contains("A synthetic reply is pending."));
            assertEquals(1, storedInsight.sources().size());
        } finally {
            fixture.destroy();
        }
    }

    @Test
    void dailyCatchUpUsesPreviousLocalCalendarDayAndGlobalCachedBodyLimitWithoutGmailReads() {
        Instant schedulerNow = Instant.parse("2026-11-03T06:30:00Z");
        ZoneId timezone = ZoneId.of("America/Los_Angeles");
        LocalDate reportDate = LocalDate.of(2026, 11, 1);
        Fixture fixture = new Fixture(tempDir.resolve("daily"), List.of(mailbox(MAILBOX_A), mailbox(MAILBOX_B)), schedulerNow);
        try {
            Instant startOfLocalDay = reportDate.atStartOfDay(timezone).toInstant();
            for (int index = 0; index < 300; index++) {
                UUID mailboxId = index % 2 == 0 ? MAILBOX_A : MAILBOX_B;
                Instant sentAt = startOfLocalDay.plusSeconds((index / 2) * 60L);
                fixture.repository.addMessage(mailboxId, index / 2, index + 1, sentAt, true);
            }
            FakeScheduleRepository schedules = new FakeScheduleRepository(
                    new DailyReportScheduleRepository.ScheduleRow(
                            OWNER_ID, true, LocalTime.of(9, 0), timezone.getId(), DISCLOSURE_VERSION,
                            null, null, schedulerNow.minusSeconds(3 * 86_400L)));
            DailyReportScheduler scheduler = new DailyReportScheduler(
                    schedules, fixture.repository, fixture.reports, fixture.provider, fixture.vault,
                    Clock.fixed(schedulerNow, ZoneOffset.UTC));

            scheduler.runDueDailyReports();
            scheduler.runDueDailyReports();

            assertEquals(1, fixture.repository.reportCount(), "The same catch-up date must not be generated twice.");
            assertEquals(1, fixture.provider.calls);
            assertEquals(0, fixture.gmail.listCallCount);
            assertEquals(0, fixture.gmail.bodyReadCount);
            assertEquals(240, fixture.repository.lastCachedInputLimit);
            assertEquals(Instant.parse("2026-11-01T07:00:00Z"), fixture.repository.lastCountStart);
            assertEquals(Instant.parse("2026-11-02T08:00:00Z"), fixture.repository.lastCountEnd,
                    "The local day crosses the daylight-saving transition and lasts 25 hours.");

            FakeReportRepository.StoredReport stored = fixture.repository.onlyReport();
            assertEquals("CATCH_UP", stored.purpose());
            assertEquals(reportDate, stored.scheduledReportDate());
            assertEquals(300, stored.eligibleMessageCount());
            assertEquals(240, stored.selectedMessageCount());
            assertEquals(60, stored.additionalMessageCount());
            assertEquals("PARTIAL", stored.coverageStatus());
            assertEquals(reportDate, schedules.lastSuccessfulReportDate);
            assertEquals(2, schedules.claimCount);
        } finally {
            fixture.destroy();
        }
    }

    private static GenerateReportRequest request(
            LocalDate start,
            LocalDate end,
            boolean confirmed,
            Integer eligible,
            Integer additional,
            ExpansionDecision decision) {
        return new GenerateReportRequest(start, end, List.of(MAILBOX_A), confirmed,
                DISCLOSURE_VERSION, eligible, additional, decision);
    }

    private static MailboxScope mailbox(UUID id) {
        return new MailboxScope(id, "synthetic-" + id.toString().substring(id.toString().length() - 1)
                + "@example.invalid", "Synthetic mailbox", 0);
    }

    private static final class Fixture {
        private final MemoryVaultRepository vaultRepository = new MemoryVaultRepository();
        private final VaultService vault;
        private final FakeReportRepository repository;
        private final FakeProvider provider = new FakeProvider();
        private final FakeGmailSyncService gmail;
        private final ReportService reports;

        private Fixture(Path keyDirectory, List<MailboxScope> scopes) {
            this(keyDirectory, scopes, NOW);
        }

        private Fixture(Path keyDirectory, List<MailboxScope> scopes, Instant now) {
            vault = new VaultService(vaultRepository, new VaultCryptoService(), null,
                    new LocalVaultKeyStore(keyDirectory.resolve("vault.key").toString()));
            vault.initializeForLocalUse();
            Clock clock = Clock.fixed(now, ZoneOffset.UTC);
            repository = new FakeReportRepository(vault, scopes, clock);
            gmail = new FakeGmailSyncService(repository, clock);
            reports = new ReportService(repository, gmail, provider, vault, MAPPER, clock);
        }

        private void destroy() {
            repository.destroy();
        }
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
            assertEquals(DISCLOSURE_VERSION, expectedConfigurationVersion);
            calls++;
            prompts.add(prompt);
            ObjectNode output = MAPPER.createObjectNode();
            output.put("narrative", "Synthetic report narrative.");
            ObjectNode insight = output.putArray("insights").addObject();
            insight.put("kind", "AWAITING_REPLY");
            insight.put("text", "A synthetic reply is pending.");
            insight.put("confidence", 0.92);
            insight.putArray("sourceIds").add("m1");
            insight.putNull("urgency");
            insight.putNull("deadline");
            insight.putNull("transactionType");
            insight.putNull("amount");
            insight.putNull("currency");
            insight.putNull("transactionDate");
            return new AIProviderClient.Completion<>(contract.validate(output), metadata);
        }

        private String lastUserMessage() {
            List<AIProviderPrompt.Message> messages = prompts.getLast().messages();
            return messages.getLast().content();
        }
    }

    private static final class FakeGmailSyncService extends GmailSyncService {
        private final FakeReportRepository repository;
        private final Clock clock;
        private int listCallCount;
        private int bodyReadCount;

        private FakeGmailSyncService(FakeReportRepository repository, Clock clock) {
            super(null, null, null, null, MAPPER, clock);
            this.repository = repository;
            this.clock = clock;
        }

        @Override
        public List<ReportMessageDescriptor> listReportMessages(
                UUID appUserId, UUID mailboxId, Instant startInclusive, Instant endExclusive) {
            listCallCount++;
            return repository.descriptors(mailboxId, startInclusive, endExclusive);
        }

        @Override
        public void cacheReportMessageBody(UUID appUserId, UUID mailboxId, ReportMessageDescriptor descriptor) {
            bodyReadCount++;
            repository.markCached(mailboxId, descriptor.gmailMessageId(), clock.instant());
        }
    }

    private static final class FakeReportRepository extends ReportRepository {
        private final VaultService vault;
        private final List<MailboxScope> scopes;
        private final Clock clock;
        private final Map<String, StoredMessage> messages = new LinkedHashMap<>();
        private final Map<UUID, StoredReport> reports = new LinkedHashMap<>();
        private final Map<UUID, StoredInsight> insights = new LinkedHashMap<>();
        private Instant lastCountStart;
        private Instant lastCountEnd;
        private int lastCachedInputLimit;

        private FakeReportRepository(VaultService vault, List<MailboxScope> scopes, Clock clock) {
            super(null);
            this.vault = vault;
            this.scopes = List.copyOf(scopes);
            this.clock = clock;
        }

        private void addMessage(UUID mailboxId, int localIndex, int globalIndex, Instant sentAt, boolean cached) {
            String gmailMessageId = "synthetic-message-" + globalIndex;
            String gmailThreadId = "synthetic-thread-" + globalIndex;
            UUID messageId = new UUID(0x4000000000000000L, globalIndex);
            UUID threadId = new UUID(0x3000000000000000L, globalIndex);
            String text = "Synthetic message " + String.format("%03d", globalIndex);
            ObjectNode json = MAPPER.createObjectNode();
            json.put("bodyText", text);
            json.put("attachmentText", ATTACHMENT_SENTINEL);
            byte[] plaintext = null;
            byte[] aad = GmailCacheAad.message(OWNER_ID, mailboxId, gmailThreadId, gmailMessageId);
            EncryptedPayload encrypted = null;
            try {
                plaintext = MAPPER.writeValueAsBytes(json);
                encrypted = vault.encryptLocal(plaintext, aad);
                messages.put(gmailMessageId, new StoredMessage(
                        new ReportInputMessage(messageId, mailboxId, scope(mailboxId).email(), threadId,
                                gmailThreadId, "Synthetic subject", gmailMessageId,
                                "sender@example.invalid", "Synthetic sender", sentAt,
                                encrypted.nonce(), encrypted.ciphertext(), clock.instant().plusSeconds(7 * 86_400L)),
                        cached));
            } catch (Exception exception) {
                throw new IllegalStateException("Could not prepare synthetic report input.", exception);
            } finally {
                wipe(plaintext);
                wipe(aad);
                if (encrypted != null) encrypted.destroy();
            }
        }

        private List<ReportMessageDescriptor> descriptors(UUID mailboxId, Instant start, Instant end) {
            return messages.values().stream()
                    .map(StoredMessage::row)
                    .filter(row -> row.mailboxId().equals(mailboxId)
                            && !row.sentAt().isBefore(start) && row.sentAt().isBefore(end))
                    .sorted(Comparator.comparing(ReportInputMessage::sentAt).reversed())
                    .map(row -> new ReportMessageDescriptor(row.gmailMessageId(), row.gmailThreadId()))
                    .toList();
        }

        private void markCached(UUID mailboxId, String gmailMessageId, Instant cachedAt) {
            StoredMessage row = messages.get(gmailMessageId);
            if (row == null || !row.row().mailboxId().equals(mailboxId)) {
                throw new AssertionError("A synthetic Gmail body read was outside the selected mailbox scope.");
            }
            row.cached(true);
        }

        @Override
        public List<MailboxScope> findOwnedMailboxes(UUID appUserId, List<UUID> requestedIds) {
            if (!OWNER_ID.equals(appUserId)) return List.of();
            if (requestedIds.isEmpty()) return scopes;
            return scopes.stream().filter(scope -> requestedIds.contains(scope.mailboxId())).toList();
        }

        @Override
        public boolean hasMailboxWithoutReport(UUID appUserId, List<UUID> mailboxIds) {
            return false;
        }

        @Override
        public Optional<ReportRunRow> findLatestReport(
                UUID appUserId,
                Instant startInclusive,
                Instant endExclusive,
                LocalDate requestedStartDate,
                LocalDate requestedEndDate,
                List<UUID> requestedMailboxIds,
                Instant now) {
            return Optional.empty();
        }

        @Override
        public List<ReportInputMessage> findReportInputs(
                UUID appUserId,
                UUID mailboxId,
                List<String> gmailMessageIds,
                Instant startInclusive,
                Instant endExclusive,
                Instant now) {
            return gmailMessageIds.stream()
                    .map(messages::get)
                    .filter(row -> row != null && row.cached() && row.row().mailboxId().equals(mailboxId)
                            && !row.row().sentAt().isBefore(startInclusive) && row.row().sentAt().isBefore(endExclusive)
                            && row.row().bodyExpiresAt().isAfter(now))
                    .map(row -> copy(row.row()))
                    .toList();
        }

        @Override
        public int countMessageMetadata(UUID appUserId, List<UUID> mailboxIds, Instant startInclusive, Instant endExclusive) {
            lastCountStart = startInclusive;
            lastCountEnd = endExclusive;
            return (int) messages.values().stream()
                    .map(StoredMessage::row)
                    .filter(row -> mailboxIds.contains(row.mailboxId())
                            && !row.sentAt().isBefore(startInclusive) && row.sentAt().isBefore(endExclusive))
                    .count();
        }

        @Override
        public List<ReportInputMessage> findCachedReportInputs(
                UUID appUserId,
                List<UUID> mailboxIds,
                Instant startInclusive,
                Instant endExclusive,
                Instant now,
                int limit) {
            lastCachedInputLimit = limit;
            Map<UUID, List<StoredMessage>> byMailbox = new LinkedHashMap<>();
            for (UUID mailboxId : mailboxIds) byMailbox.put(mailboxId, new ArrayList<>());
            for (StoredMessage message : messages.values()) {
                ReportInputMessage row = message.row();
                if (message.cached() && byMailbox.containsKey(row.mailboxId())
                        && !row.sentAt().isBefore(startInclusive) && row.sentAt().isBefore(endExclusive)
                        && row.bodyExpiresAt().isAfter(now)) {
                    byMailbox.get(row.mailboxId()).add(message);
                }
            }
            byMailbox.values().forEach(list -> list.sort(Comparator.comparing(
                    item -> item.row().sentAt(), Comparator.reverseOrder())));
            List<ReportInputMessage> selected = new ArrayList<>();
            int offset = 0;
            while (selected.size() < limit) {
                boolean added = false;
                for (List<StoredMessage> mailboxRows : byMailbox.values()) {
                    if (offset < mailboxRows.size() && selected.size() < limit) {
                        selected.add(copy(mailboxRows.get(offset).row()));
                        added = true;
                    }
                }
                if (!added) break;
                offset++;
            }
            return List.copyOf(selected);
        }

        @Override
        public boolean hasMailboxAtInitialSyncLimit(
                UUID appUserId, List<UUID> mailboxIds, Instant startInclusive, Instant endExclusive, int initialLimit) {
            return false;
        }

        @Override
        public int bodyCacheTtlDays(UUID appUserId) {
            return 7;
        }

        @Override
        public boolean hasScheduledReport(UUID appUserId, LocalDate reportDate) {
            return reports.values().stream().anyMatch(report -> report.appUserId().equals(appUserId)
                    && report.scheduledReportDate() != null && report.scheduledReportDate().equals(reportDate));
        }

        @Override
        public void insertReport(ReportWrite report) {
            byte[] nonce = report.narrative().nonce();
            byte[] ciphertext = report.narrative().ciphertext();
            reports.put(report.id(), new StoredReport(
                    report.id(), report.appUserId(), report.periodStart(), report.periodEnd(),
                    report.expiresAt(), report.purpose(), report.coverageStatus(),
                    report.eligibleMessageCount(), report.selectedMessageCount(), report.additionalMessageCount(),
                    report.readExpansionApproved(), report.provider() == null ? null : report.provider().providerType(),
                    report.provider() == null ? null : report.provider().modelId(), report.scheduledReportDate(),
                    report.mailboxIds(), report.sources().size(), nonce, ciphertext, clock.instant()));
            wipe(nonce);
            wipe(ciphertext);
            for (InsightWrite insight : report.insights()) {
                byte[] contentNonce = insight.content().nonce();
                byte[] contentCiphertext = insight.content().ciphertext();
                insights.put(insight.id(), new StoredInsight(
                        insight.id(), report.id(), report.appUserId(), insight.kind(), insight.confidence(),
                        insight.expiresAt(), contentNonce, contentCiphertext, insight.sources()));
                wipe(contentNonce);
                wipe(contentCiphertext);
            }
        }

        @Override
        public Optional<ReportRunRow> findOwnedReport(UUID appUserId, UUID reportId, Instant now) {
            StoredReport report = reports.get(reportId);
            if (report == null || !report.appUserId().equals(appUserId) || !report.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            return Optional.of(new ReportRunRow(
                    report.id(), "COMPLETED", report.periodStart(), report.periodEnd(),
                    report.narrativeNonce(), report.narrativeCiphertext(), report.expiresAt(), report.createdAt(),
                    report.coverageStatus(), report.eligibleMessageCount(), report.selectedMessageCount(),
                    report.additionalMessageCount(), report.providerType(), report.providerModelId(),
                    report.scheduledReportDate()));
        }

        @Override
        public List<MailboxScope> findRunMailboxes(UUID appUserId, UUID reportId) {
            StoredReport report = reports.get(reportId);
            if (report == null || !report.appUserId().equals(appUserId)) return List.of();
            return scopes.stream().filter(scope -> report.mailboxIds().contains(scope.mailboxId())).toList();
        }

        @Override
        public List<InsightRow> findInsights(UUID appUserId, UUID reportId) {
            return insights.values().stream()
                    .filter(insight -> insight.appUserId().equals(appUserId) && insight.reportId().equals(reportId))
                    .map(insight -> new InsightRow(
                            insight.id(), insight.kind(), ReportModels.InsightStatus.OPEN, insight.confidence(),
                            insight.contentNonce(), insight.contentCiphertext(), insight.expiresAt()))
                    .toList();
        }

        @Override
        public List<ReportSource> findInsightSources(UUID appUserId, UUID insightId) {
            StoredInsight insight = insights.get(insightId);
            if (insight == null || !insight.appUserId().equals(appUserId)) return List.of();
            return insight.sources().stream().map(source -> messages.values().stream()
                    .map(StoredMessage::row)
                    .filter(row -> row.id().equals(source.messageId()) && row.mailboxId().equals(source.mailboxId()))
                    .findFirst()
                    .map(row -> new ReportSource(
                            row.mailboxId(), row.threadId(), row.id(), row.accountEmail(), row.sentAt()))
                    .orElseThrow()).toList();
        }

        private StoredReport onlyReport() {
            assertEquals(1, reports.size());
            return reports.values().iterator().next();
        }

        private StoredInsight onlyInsight() {
            assertEquals(1, insights.size());
            return insights.values().iterator().next();
        }

        private int reportCount() {
            return reports.size();
        }

        private MailboxScope scope(UUID mailboxId) {
            return scopes.stream().filter(value -> value.mailboxId().equals(mailboxId)).findFirst().orElseThrow();
        }

        private static ReportInputMessage copy(ReportInputMessage row) {
            return new ReportInputMessage(row.id(), row.mailboxId(), row.accountEmail(), row.threadId(),
                    row.gmailThreadId(), row.subject(), row.gmailMessageId(), row.senderEmail(), row.senderName(),
                    row.sentAt(), row.bodyNonce().clone(), row.bodyCiphertext().clone(), row.bodyExpiresAt());
        }

        private void destroy() {
            for (StoredMessage message : messages.values()) message.destroy();
            for (StoredReport report : reports.values()) report.destroy();
            for (StoredInsight insight : insights.values()) insight.destroy();
            messages.clear();
            reports.clear();
            insights.clear();
        }

        private record StoredReport(
                UUID id,
                UUID appUserId,
                Instant periodStart,
                Instant periodEnd,
                Instant expiresAt,
                String purpose,
                String coverageStatus,
                int eligibleMessageCount,
                int selectedMessageCount,
                int additionalMessageCount,
                boolean expansionApproved,
                String providerType,
                String providerModelId,
                LocalDate scheduledReportDate,
                List<UUID> mailboxIds,
                int sourceCount,
                byte[] narrativeNonce,
                byte[] narrativeCiphertext,
                Instant createdAt) {
            private StoredReport {
                mailboxIds = List.copyOf(mailboxIds);
                narrativeNonce = narrativeNonce.clone();
                narrativeCiphertext = narrativeCiphertext.clone();
            }

            @Override public byte[] narrativeNonce() { return narrativeNonce.clone(); }
            @Override public byte[] narrativeCiphertext() { return narrativeCiphertext.clone(); }
            private void destroy() { wipe(narrativeNonce); wipe(narrativeCiphertext); }
        }

        private record StoredInsight(
                UUID id,
                UUID reportId,
                UUID appUserId,
                String kind,
                double confidence,
                Instant expiresAt,
                byte[] contentNonce,
                byte[] contentCiphertext,
                List<InsightSourceWrite> sources) {
            private StoredInsight {
                contentNonce = contentNonce.clone();
                contentCiphertext = contentCiphertext.clone();
                sources = List.copyOf(sources);
            }

            @Override public byte[] contentNonce() { return contentNonce.clone(); }
            @Override public byte[] contentCiphertext() { return contentCiphertext.clone(); }
            private void destroy() { wipe(contentNonce); wipe(contentCiphertext); }
        }

        private static final class StoredMessage {
            private final ReportInputMessage row;
            private boolean cached;

            private StoredMessage(ReportInputMessage row, boolean cached) {
                this.row = copy(row);
                this.cached = cached;
            }

            private ReportInputMessage row() { return row; }
            private boolean cached() { return cached; }
            private void cached(boolean value) { cached = value; }
            private void destroy() { wipe(row.bodyNonce()); wipe(row.bodyCiphertext()); }
        }
    }

    private static final class FakeScheduleRepository extends DailyReportScheduleRepository {
        private final DailyReportScheduleRepository.ScheduleRow schedule;
        private final List<LocalDate> claimed = new ArrayList<>();
        private LocalDate lastSuccessfulReportDate;
        private int claimCount;

        private FakeScheduleRepository(DailyReportScheduleRepository.ScheduleRow schedule) {
            super(null);
            this.schedule = schedule;
        }

        @Override
        public List<DailyReportScheduleRepository.ScheduleRow> findEnabled() {
            return schedule.enabled() ? List.of(schedule) : List.of();
        }

        @Override
        public boolean claimAttempt(UUID appUserId, LocalDate reportDate, Instant attemptedAt) {
            claimCount++;
            if (claimed.contains(reportDate)) return false;
            claimed.add(reportDate);
            return true;
        }

        @Override
        public void recordSuccess(UUID appUserId, LocalDate reportDate, Instant succeededAt) {
            lastSuccessfulReportDate = reportDate;
        }

        @Override
        public void pause(UUID appUserId) {
            throw new AssertionError("A correctly disclosed synthetic schedule should not be paused.");
        }
    }

    private static final class FakeSettingsRepository extends DailyReportScheduleRepository {
        private DailyReportScheduleRepository.ScheduleRow saved;
        private int saveCalls;

        private FakeSettingsRepository() {
            super(null);
        }

        @Override
        public Optional<DailyReportScheduleRepository.ScheduleRow> find(UUID appUserId) {
            return saved == null || !saved.appUserId().equals(appUserId) ? Optional.empty() : Optional.of(saved);
        }

        @Override
        public void save(
                UUID appUserId,
                boolean enabled,
                LocalTime runAtLocal,
                String timezoneId,
                String providerDisclosureVersion) {
            saveCalls++;
            String retainedVersion = enabled
                    ? providerDisclosureVersion
                    : saved == null ? null : saved.providerDisclosureVersion();
            saved = new DailyReportScheduleRepository.ScheduleRow(
                    appUserId, enabled, runAtLocal, timezoneId, retainedVersion,
                    saved == null ? null : saved.lastAttemptedReportDate(),
                    saved == null ? null : saved.lastSuccessfulReportDate(), NOW);
        }
    }

    private static final class MemoryVaultRepository extends VaultRepository {
        private VaultRecord state;

        private MemoryVaultRepository() {
            super(null);
        }

        @Override
        public synchronized Optional<VaultRecord> find() {
            if (state == null) return Optional.empty();
            return Optional.of(new VaultRecord(state.salt(), state.encryptedVerificationMarker()));
        }

        @Override
        public synchronized boolean isInitialized() {
            return state != null;
        }

        @Override
        public synchronized void create(byte[] salt, byte[] encryptedVerificationMarker) {
            if (state != null) throw new DuplicateKeyException("Synthetic vault is already initialized.");
            state = new VaultRecord(salt, encryptedVerificationMarker);
        }
    }

    private static byte[] wipe(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
        return value;
    }
}
