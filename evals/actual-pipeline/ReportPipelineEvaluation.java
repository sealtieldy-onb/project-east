package com.east.app.ai;

import com.east.app.EastApplication;
import com.east.app.gmail.GmailCacheAad;
import com.east.app.gmail.GmailSyncService;
import com.east.app.gmail.GmailSyncService.ReportMessageDescriptor;
import com.east.app.reports.ReportModels;
import com.east.app.reports.ReportRepository;
import com.east.app.reports.ReportService;
import com.east.app.settings.AIProviderUpdateRequest;
import com.east.app.settings.SettingsService;
import com.east.app.vault.EncryptedPayload;
import com.east.app.vault.VaultService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * Evaluation-only runner. It starts East's real Spring services against a disposable database,
 * seeds only synthetic messages, and writes filtered traces under evals/results.
 */
public final class ReportPipelineEvaluation {

    private static final double INPUT_USD_PER_MILLION = 0.10;
    private static final double CACHED_INPUT_USD_PER_MILLION = 0.01;
    private static final double OUTPUT_USD_PER_MILLION = 0.50;

    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000099");
    private static final UUID MAILBOX_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_MAILBOX_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final String SYNTHETIC_ONLY = "SYNTHETIC_RETRIEVAL_SENTINEL_";

    private ReportPipelineEvaluation() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Pass fixture JSON and output JSONL paths.");
        }
        Path fixturePath = Path.of(args[0]).toAbsolutePath().normalize();
        Path outputPath = Path.of(args[1]).toAbsolutePath().normalize();
        int limit = args.length > 2 ? Integer.parseInt(args[2]) : Integer.MAX_VALUE;
        String modelId = requiredEnvironment("EAST_EVAL_MODEL");
        String apiKey = requiredEnvironment("EAST_EVAL_API_KEY");
        String baseUrl = System.getenv("EAST_EVAL_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://api.openai.com/v1";
        String providerType = baseUrl.startsWith("https://api.openai.com/")
                ? "openai" : "openai-compatible";
        String configuredBaseUrl = providerType.equals("openai") ? null : baseUrl;

        ObjectMapper mapper;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(EastApplication.class)
                .web(WebApplicationType.SERVLET)
                .run(
                        "--spring.datasource.url=jdbc:postgresql://127.0.0.1:25432/east_eval",
                        "--spring.datasource.username=east",
                        "--spring.datasource.password=",
                        "--server.port=0",
                        "--east.vault.key-file=/private/tmp/east-capstone-pg-eval-20261110-a/vault.key",
                        "--east.gmail.sync-initial-delay-ms=600000",
                        "--east.reports.daily-initial-delay-ms=600000",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=ERROR")) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            VaultService vault = context.getBean(VaultService.class);
            mapper = context.getBean(ObjectMapper.class);
            SettingsService settings = context.getBean(SettingsService.class);
            settings.putAIProvider(new AIProviderUpdateRequest(providerType, configuredBaseUrl, modelId, apiKey));

            RecordingTransport transport = new RecordingTransport(mapper);
            ClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
            RestClient.Builder clientBuilder = RestClient.builder().requestInterceptor(transport);
            AIProviderClient actualClient = new AIProviderClient(
                    jdbc, vault, clientBuilder, mapper,
                    context.getBean(StructuredOutputValidator.class), requestFactory);
            RecordingGateway gateway = new RecordingGateway(actualClient, transport, mapper);
            TransactionTemplate transactions =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            TransactionTemplate readOnlyTransactions =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            readOnlyTransactions.setReadOnly(true);
            runEvaluation(fixturePath, outputPath, limit, jdbc, vault, mapper, gateway,
                    transactions, readOnlyTransactions);
            settings.deleteAIProvider();
        } finally {
            Path keyFile = Path.of("/private/tmp/east-capstone-pg-eval-20261110-a/vault.key");
            Files.deleteIfExists(keyFile);
        }
    }

    private static void runEvaluation(
            Path fixturePath,
            Path outputPath,
            int limit,
            JdbcTemplate jdbc,
            VaultService vault,
            ObjectMapper mapper,
            RecordingGateway gateway,
            TransactionTemplate transactions,
            TransactionTemplate readOnlyTransactions) throws Exception {
        JsonNode fixture = mapper.readTree(fixturePath.toFile());
        ArrayNode cases = (ArrayNode) fixture.path("cases");
        Files.createDirectories(outputPath.getParent());
        restrictOwnerOnly(outputPath.getParent(), true);
        Files.createFile(outputPath,
                PosixFilePermissions.asFileAttribute(
                        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        try (var writer = Files.newBufferedWriter(outputPath)) {
            writer.write("{\"record_type\":\"run_metadata\",\"synthetic_only\":true,"
                    + "\"pipeline\":\"ReportService.generate -> ReportRepository SQL -> AIProviderClient\","
                    + "\"fixture_version\":" + mapper.writeValueAsString(fixture.path("version").asText())
                    + ",\"model_id\":" + mapper.writeValueAsString(gateway.providerMetadata().modelId())
                    + ",\"case_count\":" + Math.min(limit, cases.size()) + "}");
            writer.newLine();
            seedUsersAndMailboxes(jdbc);
            int succeeded = 0;
            int failed = 0;
            for (int index = 0; index < cases.size() && index < limit; index++) {
                JsonNode testCase = cases.get(index);
                clearCase(jdbc);
                List<FixtureMessage> messages = parseMessages(testCase.path("messages"));
                LocalDate start = messages.stream().map(message -> message.sentAt().atZone(ZoneOffset.UTC).toLocalDate())
                        .min(LocalDate::compareTo).orElseThrow();
                LocalDate end = messages.stream().map(message -> message.sentAt().atZone(ZoneOffset.UTC).toLocalDate())
                        .max(LocalDate::compareTo).orElseThrow();
                Map<UUID, String> originalSourceByMessageId = new HashMap<>();
                List<ReportMessageDescriptor> descriptors = new ArrayList<>();
                for (FixtureMessage message : messages) {
                    UUID messageId = stableId(testCase.path("id").asText() + ":" + message.sourceId());
                    UUID threadId = stableId("thread:" + testCase.path("id").asText() + ":" + message.sourceId());
                    String gmailMessageId = gmailMessageId(testCase.path("id").asText(), message.sourceId());
                    String gmailThreadId = "thread-" + testCase.path("id").asText() + "-" + message.sourceId();
                    insertMessage(jdbc, vault, OWNER_ID, MAILBOX_ID, messageId, threadId,
                            gmailMessageId, gmailThreadId, message, Instant.now().plusSeconds(7 * 86_400L));
                    originalSourceByMessageId.put(messageId, message.sourceId());
                    descriptors.add(new ReportMessageDescriptor(gmailMessageId, gmailThreadId));
                }

                FakeGmailSyncService gmail = new FakeGmailSyncService(descriptors, mapper);
                TracingRepository repository = new TracingRepository(jdbc);
                ReportService reports = new ReportService(
                        repository, gmail, gateway, vault, mapper, Clock.systemUTC());
                int callIndex = gateway.records().size();
                long startNanos = System.nanoTime();
                ObjectNode record = mapper.createObjectNode();
                record.put("record_type", "pipeline_case");
                record.put("case_id", testCase.path("id").asText());
                record.put("split", testCase.path("split").asText());
                record.put("category", testCase.path("category").asText());
                record.put("question", testCase.path("question").asText());
                record.set("reference_answer", testCase.path("referenceAnswer").deepCopy());
                record.set("expected", testCase.path("expected").deepCopy());
                record.set("relevant_source_ids", testCase.path("relevantSourceIds").deepCopy());
                record.put("period_start", start.toString());
                record.put("period_end", end.toString());
                record.put("fixture_message_count", messages.size());
                try {
                    ReportModels.GenerateReportRequest request = new ReportModels.GenerateReportRequest(
                            start, end, List.of(MAILBOX_ID), true,
                            gateway.providerMetadata().configurationVersion(), descriptors.size(), 0, null);
                    ReportModels.GenerateReportResponse response = transactions.execute(
                            status -> reports.generate(OWNER_ID, request));
                    record.put("backend_total_ms", elapsedMillis(startNanos));
                    record.put("generation_status", response.status().name());
                    record.put("coverage_status", response.coverage().status().name());
                    record.put("selected_message_count", response.coverage().selectedMessageCount());
                    record.set("answer", answerNode(response, originalSourceByMessageId, mapper));
                    record.put("answer_text", answerText(response, originalSourceByMessageId, mapper));
                    record.put("report_id", response.summary() == null ? "" : response.summary().id().toString());
                    if (response.status() != ReportModels.GenerationStatus.COMPLETED) {
                        record.put("failure", "generation did not complete");
                        failed++;
                    } else {
                        succeeded++;
                    }
                } catch (RuntimeException exception) {
                    record.put("backend_total_ms", elapsedMillis(startNanos));
                    record.put("failure", exception.getClass().getSimpleName());
                    failed++;
                }
                ArrayNode retrievalEvents = mapper.createArrayNode();
                repository.events().forEach(event -> retrievalEvents.add(event.deepCopy()));
                record.set("retrieval_trace", retrievalEvents);
                record.put("gmail_metadata_calls", gmail.listCalls);
                record.put("gmail_body_fetch_attempts", gmail.bodyFetchAttempts);
                if (gateway.records().size() > callIndex) {
                    record.set("provider_trace", gateway.records().get(callIndex).deepCopy());
                    record.set("retrieved_contexts", gateway.records().get(callIndex).path("retrieved_contexts").deepCopy());
                } else {
                    record.put("provider_trace", mapper.createObjectNode().put("status", "not_called"));
                    record.set("retrieved_contexts", mapper.createArrayNode());
                }
                writer.write(mapper.writeValueAsString(record));
                writer.newLine();
                writer.flush();
                System.out.println("case " + (index + 1) + "/" + Math.min(limit, cases.size())
                        + " " + testCase.path("id").asText()
                        + " " + (record.path("failure").isMissingNode() ? "complete" : "failed")
                        + " total_ms=" + record.path("backend_total_ms").asLong());
            }
            writer.write(mapper.writeValueAsString(runScopeProbe(
                    cases.get(0), jdbc, vault, mapper, transactions)));
            writer.newLine();
            writer.write(mapper.writeValueAsString(runReuseBenchmark(
                    cases.get(0), jdbc, vault, mapper, gateway, transactions, readOnlyTransactions)));
            writer.newLine();
            ObjectNode summary = mapper.createObjectNode();
            summary.put("record_type", "run_summary");
            summary.put("attempted", Math.min(limit, cases.size()));
            summary.put("completed", succeeded);
            summary.put("failed", failed);
            summary.put("provider_calls", gateway.records().size());
            writer.write(mapper.writeValueAsString(summary));
            writer.newLine();
        }
        restrictOwnerOnly(outputPath, false);
        System.out.println("evaluation complete: cases=" + Math.min(limit, cases.size())
                + " provider_calls=" + gateway.records().size());
    }

    private static void seedUsersAndMailboxes(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM app_user WHERE id = ?", OWNER_ID);
        jdbc.update("INSERT INTO app_user (id, google_subject, email, display_name) VALUES (?, ?, ?, ?)",
                OWNER_ID, "synthetic-capstone-evaluation-owner", "owner@example.test", "Synthetic owner");
        jdbc.update("""
                INSERT INTO connected_mailbox
                    (id, app_user_id, google_subject, email, display_name, account_color_index)
                VALUES (?, ?, ?, ?, ?, 0)
                """, MAILBOX_ID, OWNER_ID, "synthetic-capstone-evaluation-mailbox",
                "mailbox@example.test", "Synthetic mailbox");
        jdbc.update("""
                INSERT INTO connected_mailbox
                    (id, app_user_id, google_subject, email, display_name, account_color_index)
                VALUES (?, ?, ?, ?, ?, 1)
                """, OTHER_MAILBOX_ID, OWNER_ID, "synthetic-capstone-evaluation-other-mailbox",
                "other-mailbox@example.test", "Synthetic other mailbox");
    }

    private static ObjectNode runScopeProbe(
            JsonNode testCase,
            JdbcTemplate jdbc,
            VaultService vault,
            ObjectMapper mapper,
            TransactionTemplate transactions) throws Exception {
        clearCase(jdbc);
        List<FixtureMessage> messages = parseMessages(testCase.path("messages"));
        LocalDate start = messages.stream().map(message -> message.sentAt().atZone(ZoneOffset.UTC).toLocalDate())
                .min(LocalDate::compareTo).orElseThrow();
        LocalDate end = messages.stream().map(message -> message.sentAt().atZone(ZoneOffset.UTC).toLocalDate())
                .max(LocalDate::compareTo).orElseThrow();
        Instant startInclusive = start.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endExclusive = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<String> validIds = new ArrayList<>();
        for (FixtureMessage message : messages) {
            UUID messageId = stableId(testCase.path("id").asText() + ":" + message.sourceId());
            UUID threadId = stableId("thread:" + testCase.path("id").asText() + ":" + message.sourceId());
            String gmailMessageId = gmailMessageId(testCase.path("id").asText(), message.sourceId());
            String gmailThreadId = "thread-" + testCase.path("id").asText() + "-" + message.sourceId();
            insertMessage(jdbc, vault, OWNER_ID, MAILBOX_ID, messageId, threadId,
                    gmailMessageId, gmailThreadId, message, Instant.now().plusSeconds(7 * 86_400L));
            validIds.add(gmailMessageId);
        }

        Instant probeAt = messages.getFirst().sentAt();
        FixtureMessage expired = new FixtureMessage(
                "expired", probeAt, "expired@example.test", "Expired synthetic sender",
                "Expired synthetic distractor", SYNTHETIC_ONLY + "EXPIRED");
        String expiredId = "east-eval-" + testCase.path("id").asText() + "-expired";
        insertMessage(jdbc, vault, OWNER_ID, MAILBOX_ID,
                stableId(testCase.path("id").asText() + ":expired"),
                stableId("thread:" + testCase.path("id").asText() + ":expired"),
                expiredId, "thread-" + testCase.path("id").asText() + "-expired",
                expired, Instant.now().minusSeconds(86_400L),
                Instant.now().minusSeconds(8 * 86_400L));

        FixtureMessage outsideWindow = new FixtureMessage(
                "outside-window", startInclusive.minusSeconds(60), "outside@example.test", "Outside synthetic sender",
                "Outside-window synthetic distractor", SYNTHETIC_ONLY + "OUTSIDE_WINDOW");
        String outsideId = "east-eval-" + testCase.path("id").asText() + "-outside";
        insertMessage(jdbc, vault, OWNER_ID, MAILBOX_ID,
                stableId(testCase.path("id").asText() + ":outside"),
                stableId("thread:" + testCase.path("id").asText() + ":outside"),
                outsideId, "thread-" + testCase.path("id").asText() + "-outside",
                outsideWindow, Instant.now().plusSeconds(7 * 86_400L));

        FixtureMessage otherMailbox = new FixtureMessage(
                "other-mailbox", probeAt, "other@example.test", "Other synthetic sender",
                "Other-mailbox synthetic distractor", SYNTHETIC_ONLY + "OTHER_MAILBOX");
        String otherMailboxId = "east-eval-" + testCase.path("id").asText() + "-other-mailbox";
        insertMessage(jdbc, vault, OWNER_ID, OTHER_MAILBOX_ID,
                stableId(testCase.path("id").asText() + ":other-mailbox"),
                stableId("thread:" + testCase.path("id").asText() + ":other-mailbox"),
                otherMailboxId, "thread-" + testCase.path("id").asText() + "-other-mailbox",
                otherMailbox, Instant.now().plusSeconds(7 * 86_400L));

        List<String> candidates = new ArrayList<>(validIds);
        candidates.add(expiredId);
        candidates.add(outsideId);
        candidates.add(otherMailboxId);
        ReportRepository repository = new ReportRepository(jdbc);
        List<ReportRepository.ReportInputMessage> ownerRows = transactions.execute(status ->
                repository.findReportInputs(OWNER_ID, MAILBOX_ID, candidates,
                        startInclusive, endExclusive, Instant.now()));
        List<String> returnedIds = new ArrayList<>();
        ownerRows.forEach(row -> {
            returnedIds.add(row.gmailMessageId());
            row.destroy();
        });
        List<ReportRepository.ReportInputMessage> wrongOwnerRows = transactions.execute(status ->
                repository.findReportInputs(OTHER_OWNER_ID, MAILBOX_ID, validIds,
                        startInclusive, endExclusive, Instant.now()));
        int wrongOwnerCount = wrongOwnerRows.size();
        wrongOwnerRows.forEach(ReportRepository.ReportInputMessage::destroy);
        List<ReportRepository.ReportInputMessage> wrongMailboxRows = transactions.execute(status ->
                repository.findReportInputs(OWNER_ID, OTHER_MAILBOX_ID, validIds,
                        startInclusive, endExclusive, Instant.now()));
        int wrongMailboxCount = wrongMailboxRows.size();
        wrongMailboxRows.forEach(ReportRepository.ReportInputMessage::destroy);

        if (returnedIds.size() != validIds.size() || wrongOwnerCount != 0 || wrongMailboxCount != 0
                || returnedIds.contains(expiredId) || returnedIds.contains(outsideId)
                || returnedIds.contains(otherMailboxId)) {
            throw new IllegalStateException("The synthetic PostgreSQL retrieval-scope probe did not match expectations.");
        }
        ObjectNode probe = mapper.createObjectNode();
        probe.put("record_type", "retrieval_scope_probe");
        probe.put("candidate_count", candidates.size());
        probe.put("returned_count", returnedIds.size());
        probe.put("wrong_owner_returned_count", wrongOwnerCount);
        probe.put("wrong_mailbox_returned_count", wrongMailboxCount);
        probe.put("expired_excluded", true);
        probe.put("outside_window_excluded", true);
        probe.put("other_mailbox_excluded", true);
        ArrayNode returned = probe.putArray("returned_message_ids");
        returnedIds.forEach(returned::add);
        return probe;
    }

    private static ObjectNode runReuseBenchmark(
            JsonNode testCase,
            JdbcTemplate jdbc,
            VaultService vault,
            ObjectMapper mapper,
            RecordingGateway gateway,
            TransactionTemplate transactions,
            TransactionTemplate readOnlyTransactions) throws Exception {
        clearCase(jdbc);
        List<FixtureMessage> messages = parseMessages(testCase.path("messages"));
        LocalDate start = messages.stream().map(message -> message.sentAt().atZone(ZoneOffset.UTC).toLocalDate())
                .min(LocalDate::compareTo).orElseThrow();
        LocalDate end = messages.stream().map(message -> message.sentAt().atZone(ZoneOffset.UTC).toLocalDate())
                .max(LocalDate::compareTo).orElseThrow();
        Map<UUID, String> sourceIds = new HashMap<>();
        List<ReportMessageDescriptor> descriptors = new ArrayList<>();
        for (FixtureMessage message : messages) {
            UUID messageId = stableId(testCase.path("id").asText() + ":" + message.sourceId());
            UUID threadId = stableId("thread:" + testCase.path("id").asText() + ":" + message.sourceId());
            String gmailMessageId = gmailMessageId(testCase.path("id").asText(), message.sourceId());
            String gmailThreadId = "thread-" + testCase.path("id").asText() + "-" + message.sourceId();
            insertMessage(jdbc, vault, OWNER_ID, MAILBOX_ID, messageId, threadId,
                    gmailMessageId, gmailThreadId, message, Instant.now().plusSeconds(7 * 86_400L));
            sourceIds.put(messageId, message.sourceId());
            descriptors.add(new ReportMessageDescriptor(gmailMessageId, gmailThreadId));
        }
        FakeGmailSyncService gmail = new FakeGmailSyncService(descriptors, mapper);
        TracingRepository repository = new TracingRepository(jdbc);
        ReportService reports = new ReportService(repository, gmail, gateway, vault, mapper, Clock.systemUTC());
        ReportModels.GenerateReportRequest exactRequest = new ReportModels.GenerateReportRequest(
                start, end, List.of(MAILBOX_ID), true,
                gateway.providerMetadata().configurationVersion(), descriptors.size(), 0, null);
        ObjectNode benchmark = mapper.createObjectNode();
        benchmark.put("record_type", "saved_report_reuse_benchmark");
        benchmark.put("case_id", testCase.path("id").asText());
        benchmark.put("request_scope", "same owner, mailbox, exact date range, and cached synthetic messages");
        int firstCall = gateway.records().size();
        long generationStarted = System.nanoTime();
        try {
            ReportModels.GenerateReportResponse first = transactions.execute(
                    status -> reports.generate(OWNER_ID, exactRequest));
            benchmark.put("initial_generation_status", first.status().name());
            benchmark.put("initial_generation_ms", elapsedMillis(generationStarted));
            benchmark.put("initial_generation_provider_calls", gateway.records().size() - firstCall);
            benchmark.set("initial_generation_usage", usageSummary(mapper, gateway.records(), firstCall));
            benchmark.put("initial_generation_report_id",
                    first.summary() == null ? "" : first.summary().id().toString());
            if (first.status() != ReportModels.GenerationStatus.COMPLETED || first.summary() == null) {
                benchmark.put("benchmark_status", "saved report unavailable after initial generation");
                return benchmark;
            }

            List<Long> readLatencies = new ArrayList<>();
            int beforeReads = gateway.records().size();
            UUID expectedReportId = first.summary().id();
            boolean allReadsHit = true;
            for (int index = 0; index < 30; index++) {
                long started = System.nanoTime();
                ReportModels.DashboardResponse dashboard = readOnlyTransactions.execute(status ->
                        reports.getDashboard(OWNER_ID, start, end, MAILBOX_ID));
                readLatencies.add(elapsedMillis(started));
                allReadsHit &= dashboard.summary() != null && dashboard.summary().id().equals(expectedReportId);
            }
            benchmark.put("saved_report_reads", readLatencies.size());
            benchmark.put("saved_report_reads_all_hit", allReadsHit);
            benchmark.put("saved_report_read_p50_ms", percentile(readLatencies, 0.50));
            benchmark.put("saved_report_read_p95_ms", percentile(readLatencies, 0.95));
            benchmark.put("saved_report_read_min_ms", percentile(readLatencies, 0.00));
            benchmark.put("saved_report_read_max_ms", percentile(readLatencies, 1.00));
            benchmark.put("saved_report_read_provider_calls", gateway.records().size() - beforeReads);
            benchmark.set("saved_report_read_usage", usageSummary(mapper, gateway.records(), beforeReads));

            int beforeRepeat = gateway.records().size();
            long repeatStarted = System.nanoTime();
            ReportModels.GenerateReportResponse repeat = transactions.execute(
                    status -> reports.generate(OWNER_ID, exactRequest));
            benchmark.put("identical_generation_status", repeat.status().name());
            benchmark.put("identical_generation_ms", elapsedMillis(repeatStarted));
            benchmark.put("identical_generation_provider_calls", gateway.records().size() - beforeRepeat);
            benchmark.set("identical_generation_usage", usageSummary(mapper, gateway.records(), beforeRepeat));
            benchmark.put("identical_generation_new_report_id",
                    repeat.summary() == null ? "" : repeat.summary().id().toString());
            benchmark.put("identical_generation_body_fetch_attempts", gmail.bodyFetchAttempts);

            LocalDate similarEnd = end.plusDays(1);
            int beforeSimilarRead = gateway.records().size();
            long similarReadStarted = System.nanoTime();
            ReportModels.DashboardResponse similarDashboard = readOnlyTransactions.execute(status ->
                    reports.getDashboard(OWNER_ID, start, similarEnd, MAILBOX_ID));
            benchmark.put("similar_saved_read_ms", elapsedMillis(similarReadStarted));
            benchmark.put("similar_saved_read_hit", similarDashboard.summary() != null);
            benchmark.put("similar_saved_read_provider_calls", gateway.records().size() - beforeSimilarRead);
            benchmark.set("similar_saved_read_usage", usageSummary(mapper, gateway.records(), beforeSimilarRead));

            ReportModels.GenerateReportRequest similarRequest = new ReportModels.GenerateReportRequest(
                    start, similarEnd, List.of(MAILBOX_ID), true,
                    gateway.providerMetadata().configurationVersion(), descriptors.size(), 0, null);
            int beforeSimilarGeneration = gateway.records().size();
            long similarGenerationStarted = System.nanoTime();
            ReportModels.GenerateReportResponse similar = transactions.execute(
                    status -> reports.generate(OWNER_ID, similarRequest));
            benchmark.put("similar_generation_status", similar.status().name());
            benchmark.put("similar_generation_ms", elapsedMillis(similarGenerationStarted));
            benchmark.put("similar_generation_provider_calls", gateway.records().size() - beforeSimilarGeneration);
            benchmark.set("similar_generation_usage", usageSummary(mapper, gateway.records(), beforeSimilarGeneration));
            benchmark.put("similar_generation_new_report_id",
                    similar.summary() == null ? "" : similar.summary().id().toString());
            benchmark.put("benchmark_status", "complete");
        } catch (RuntimeException exception) {
            benchmark.put("benchmark_status", "failed");
            benchmark.put("failure_type", exception.getClass().getSimpleName());
        }
        return benchmark;
    }

    private static long percentile(List<Long> values, double quantile) {
        if (values.isEmpty()) return 0L;
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int index = (int) Math.ceil(quantile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static ObjectNode usageSummary(ObjectMapper mapper, List<ObjectNode> providerRecords, int fromIndex) {
        long inputTokens = 0;
        long cachedInputTokens = 0;
        long outputTokens = 0;
        boolean usageAvailable = true;
        boolean cachedInputReported = true;
        List<ObjectNode> records = providerRecords.subList(Math.max(0, Math.min(fromIndex, providerRecords.size())),
                providerRecords.size());
        for (ObjectNode record : records) {
            JsonNode transport = record.path("transport");
            JsonNode input = transport.path("input_tokens");
            JsonNode cachedInput = transport.path("cached_input_tokens");
            JsonNode output = transport.path("output_tokens");
            if (!input.canConvertToLong() || !output.canConvertToLong()) {
                usageAvailable = false;
                continue;
            }
            inputTokens += input.asLong();
            cachedInputTokens += cachedInput.canConvertToLong() ? cachedInput.asLong() : 0;
            cachedInputReported &= cachedInput.canConvertToLong();
            outputTokens += output.asLong();
        }
        ObjectNode usage = mapper.createObjectNode();
        usage.put("provider_calls", records.size());
        usage.put("pricing_basis", "OpenAI standard short-context GPT-6 Luna rates; estimate excludes taxes and any account-level adjustments");
        usage.put("input_usd_per_million", INPUT_USD_PER_MILLION);
        usage.put("cached_input_usd_per_million", CACHED_INPUT_USD_PER_MILLION);
        usage.put("output_usd_per_million", OUTPUT_USD_PER_MILLION);
        usage.put("cached_input_accounting", cachedInputReported
                ? "reported_by_provider" : "unreported_cached_tokens_assumed_zero");
        if (!usageAvailable) {
            usage.putNull("input_tokens");
            usage.putNull("cached_input_tokens");
            usage.putNull("uncached_input_tokens");
            usage.putNull("output_tokens");
            usage.putNull("estimated_cost_usd");
            usage.put("usage_status", "incomplete");
            return usage;
        }
        usage.put("input_tokens", inputTokens);
        usage.put("cached_input_tokens", cachedInputTokens);
        usage.put("uncached_input_tokens", Math.max(0, inputTokens - cachedInputTokens));
        usage.put("output_tokens", outputTokens);
        double estimatedCost = ((inputTokens - cachedInputTokens) * INPUT_USD_PER_MILLION
                + cachedInputTokens * CACHED_INPUT_USD_PER_MILLION
                + outputTokens * OUTPUT_USD_PER_MILLION) / 1_000_000.0;
        usage.put("estimated_cost_usd", estimatedCost);
        usage.put("usage_status", "complete");
        return usage;
    }

    private static void clearCase(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM summary_run WHERE app_user_id = ?", OWNER_ID);
        jdbc.update("DELETE FROM mail_thread WHERE mailbox_id IN (?, ?)", MAILBOX_ID, OTHER_MAILBOX_ID);
    }

    private static List<FixtureMessage> parseMessages(JsonNode messages) {
        List<FixtureMessage> result = new ArrayList<>();
        for (JsonNode message : messages) {
            result.add(new FixtureMessage(
                    message.path("sourceId").asText(),
                    Instant.parse(message.path("sentAt").asText()),
                    message.path("from").asText(),
                    message.path("senderName").asText(),
                    message.path("subject").asText(),
                    message.path("bodyText").asText()));
        }
        return List.copyOf(result);
    }

    private static void insertMessage(
            JdbcTemplate jdbc,
            VaultService vault,
            UUID appUserId,
            UUID mailboxId,
            UUID messageId,
            UUID threadId,
            String gmailMessageId,
            String gmailThreadId,
            FixtureMessage message,
            Instant expiresAt) throws Exception {
        insertMessage(jdbc, vault, appUserId, mailboxId, messageId, threadId,
                gmailMessageId, gmailThreadId, message, expiresAt, Instant.now());
    }

    private static void insertMessage(
            JdbcTemplate jdbc,
            VaultService vault,
            UUID appUserId,
            UUID mailboxId,
            UUID messageId,
            UUID threadId,
            String gmailMessageId,
            String gmailThreadId,
            FixtureMessage message,
            Instant expiresAt,
            Instant cachedAt) throws Exception {
        jdbc.update("""
                INSERT INTO mail_thread (id, mailbox_id, gmail_thread_id, subject, last_message_at, message_count)
                VALUES (?, ?, ?, ?, ?, 1)
                """, threadId, mailboxId, gmailThreadId, message.subject(),
                Timestamp.from(message.sentAt()));
        ObjectNode content = new ObjectMapper().createObjectNode();
        content.put("bodyText", message.bodyText());
        byte[] plaintext = new ObjectMapper().writeValueAsBytes(content);
        byte[] aad = GmailCacheAad.message(appUserId, mailboxId, gmailThreadId, gmailMessageId);
        EncryptedPayload encrypted = null;
        byte[] nonce = null;
        byte[] ciphertext = null;
        try {
            encrypted = vault.encryptLocal(plaintext, aad);
            nonce = encrypted.nonce();
            ciphertext = encrypted.ciphertext();
            jdbc.update("""
                    INSERT INTO mail_message (
                        id, mailbox_id, thread_id, gmail_message_id, sender_email, sender_name, sent_at,
                        body_mime_type, body_nonce, body_ciphertext, body_cached_at, body_expires_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'application/json', ?, ?, ?, ?)
                    """, messageId, mailboxId, threadId, gmailMessageId, message.from(),
                    message.senderName(), Timestamp.from(message.sentAt()), nonce, ciphertext, Timestamp.from(cachedAt),
                    Timestamp.from(expiresAt));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(aad, (byte) 0);
            if (nonce != null) Arrays.fill(nonce, (byte) 0);
            if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
            if (encrypted != null) encrypted.destroy();
        }
    }

    private static ObjectNode answerNode(
            ReportModels.GenerateReportResponse response,
            Map<UUID, String> originalSourceByMessageId,
            ObjectMapper mapper) {
        ObjectNode answer = mapper.createObjectNode();
        answer.put("narrative", response.summary() == null ? "" : response.summary().narrative());
        ArrayNode insights = answer.putArray("insights");
        for (ReportModels.InsightResponse insight : response.insights()) {
            ObjectNode item = insights.addObject();
            item.put("kind", insight.kind());
            item.put("text", insight.text());
            item.put("confidence", insight.confidence());
            putNullable(item, "urgency", insight.urgency());
            if (insight.dueAt() == null) item.putNull("deadline");
            else item.put("deadline", insight.dueAt().toString());
            putNullable(item, "transactionType", insight.transactionType());
            if (insight.amount() == null) item.putNull("amount");
            else item.put("amount", insight.amount());
            putNullable(item, "currency", insight.currency());
            if (insight.transactionDate() == null) item.putNull("transactionDate");
            else item.put("transactionDate", insight.transactionDate().toString());
            ArrayNode sources = item.putArray("sourceIds");
            for (ReportModels.ReportSource source : insight.sources()) {
                String sourceId = originalSourceByMessageId.get(source.messageId());
                if (sourceId != null) sources.add(sourceId);
            }
        }
        return answer;
    }

    private static String answerText(
            ReportModels.GenerateReportResponse response,
            Map<UUID, String> originalSourceByMessageId,
            ObjectMapper mapper) throws Exception {
        return mapper.writeValueAsString(answerNode(response, originalSourceByMessageId, mapper));
    }

    private static void putNullable(ObjectNode target, String field, String value) {
        if (value == null) target.putNull(field);
        else target.put(field, value);
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String gmailMessageId(String caseId, String sourceId) {
        return "east-eval-" + caseId + "-" + sourceId;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment configuration is missing: " + name);
        }
        return value;
    }

    private static void restrictOwnerOnly(Path path, boolean directory) throws IOException {
        try {
            Set<PosixFilePermission> permissions = directory
                    ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE)
                    : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // The result still stays in the user's local workspace.
        }
    }

    private record FixtureMessage(
            String sourceId, Instant sentAt, String from, String senderName, String subject, String bodyText) { }

    private static final class FakeGmailSyncService extends GmailSyncService {
        private final List<ReportMessageDescriptor> descriptors;
        private int listCalls;
        private int bodyFetchAttempts;

        private FakeGmailSyncService(List<ReportMessageDescriptor> descriptors, ObjectMapper mapper) {
            super(null, null, null, null, mapper, Clock.systemUTC());
            this.descriptors = List.copyOf(descriptors);
        }

        @Override
        public List<ReportMessageDescriptor> listReportMessages(
                UUID appUserId, UUID mailboxId, Instant startInclusive, Instant endExclusive) {
            listCalls++;
            return descriptors;
        }

        @Override
        public void cacheReportMessageBody(UUID appUserId, UUID mailboxId, ReportMessageDescriptor descriptor) {
            bodyFetchAttempts++;
        }
    }

    private static final class TracingRepository extends ReportRepository {
        private final List<ObjectNode> events = new CopyOnWriteArrayList<>();
        private final ObjectMapper mapper = new ObjectMapper();

        private TracingRepository(JdbcTemplate jdbc) {
            super(jdbc);
        }

        @Override
        public List<ReportModels.MailboxScope> findOwnedMailboxes(UUID appUserId, List<UUID> requestedIds) {
            long started = System.nanoTime();
            List<ReportModels.MailboxScope> rows = super.findOwnedMailboxes(appUserId, requestedIds);
            ObjectNode event = mapper.createObjectNode();
            event.put("operation", "find_owned_mailboxes");
            event.put("elapsed_ms", elapsedMillis(started));
            event.put("row_count", rows.size());
            events.add(event);
            return rows;
        }

        @Override
        public List<ReportInputMessage> findReportInputs(
                UUID appUserId, UUID mailboxId, List<String> gmailMessageIds,
                Instant startInclusive, Instant endExclusive, Instant now) {
            long started = System.nanoTime();
            List<ReportInputMessage> rows = super.findReportInputs(
                    appUserId, mailboxId, gmailMessageIds, startInclusive, endExclusive, now);
            ObjectNode event = mapper.createObjectNode();
            event.put("operation", "find_report_inputs");
            event.put("elapsed_ms", elapsedMillis(started));
            event.put("candidate_count", gmailMessageIds.size());
            event.put("returned_count", rows.size());
            ArrayNode ids = event.putArray("returned_message_ids");
            rows.forEach(row -> ids.add(row.gmailMessageId()));
            events.add(event);
            return rows;
        }

        @Override
        public java.util.Optional<ReportRunRow> findLatestReport(
                UUID appUserId, Instant startInclusive, Instant endExclusive,
                LocalDate requestedStartDate, LocalDate requestedEndDate,
                List<UUID> requestedMailboxIds, Instant now) {
            long started = System.nanoTime();
            java.util.Optional<ReportRunRow> result = super.findLatestReport(
                    appUserId, startInclusive, endExclusive, requestedStartDate,
                    requestedEndDate, requestedMailboxIds, now);
            ObjectNode event = mapper.createObjectNode();
            event.put("operation", "find_latest_saved_report");
            event.put("elapsed_ms", elapsedMillis(started));
            event.put("hit", result.isPresent());
            events.add(event);
            return result;
        }

        private List<ObjectNode> events() {
            return List.copyOf(events);
        }
    }

    private static final class RecordingGateway implements AIProviderGateway {
        private final AIProviderClient delegate;
        private final RecordingTransport transport;
        private final ObjectMapper mapper;
        private final List<ObjectNode> records = new CopyOnWriteArrayList<>();

        private RecordingGateway(AIProviderClient delegate, RecordingTransport transport, ObjectMapper mapper) {
            this.delegate = delegate;
            this.transport = transport;
            this.mapper = mapper;
        }

        @Override
        public AIProviderClient.ProviderMetadata providerMetadata() {
            return delegate.providerMetadata();
        }

        @Override
        public <T> AIProviderClient.Completion<T> completeWithMetadata(
                AIProviderPrompt prompt, StructuredOutputContract<T> contract, String expectedConfigurationVersion) {
            long started = System.nanoTime();
            int transportIndex = transport.records().size();
            ObjectNode trace = mapper.createObjectNode();
            trace.put("status", "started");
            trace.set("retrieved_contexts", extractContexts(prompt));
            try {
                AIProviderClient.Completion<T> completion = delegate.completeWithMetadata(
                        prompt, contract, expectedConfigurationVersion);
                trace.put("status", "completed");
                trace.put("model_id", completion.provider().modelId());
                trace.put("provider_type", completion.provider().providerType());
                trace.put("service_call_ms", elapsedMillis(started));
                if (transport.records().size() > transportIndex) {
                    trace.set("transport", transport.records().get(transportIndex).deepCopy());
                }
                records.add(trace);
                return completion;
            } catch (RuntimeException exception) {
                trace.put("status", "failed");
                trace.put("failure_type", exception.getClass().getSimpleName());
                if (exception instanceof AIProviderException providerException) {
                    trace.put("failure_reason", providerException.reason().name());
                }
                trace.put("service_call_ms", elapsedMillis(started));
                if (transport.records().size() > transportIndex) {
                    trace.set("transport", transport.records().get(transportIndex).deepCopy());
                }
                records.add(trace);
                throw exception;
            }
        }

        private ArrayNode extractContexts(AIProviderPrompt prompt) {
            ArrayNode result = mapper.createArrayNode();
            try {
                JsonNode report = mapper.readTree(prompt.messages().getLast().content());
                for (JsonNode message : report.path("messages")) {
                    ObjectNode context = (ObjectNode) message.deepCopy();
                    context.put("context_text", mapper.writeValueAsString(message));
                    result.add(context);
                }
            } catch (Exception exception) {
                ObjectNode failure = result.addObject();
                failure.put("trace_error", exception.getClass().getSimpleName());
            }
            return result;
        }

        private List<ObjectNode> records() {
            return List.copyOf(records);
        }
    }

    private static final class RecordingTransport implements ClientHttpRequestInterceptor {
        private final ObjectMapper mapper;
        private final List<ObjectNode> records = new CopyOnWriteArrayList<>();

        private RecordingTransport(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public ClientHttpResponse intercept(
                org.springframework.http.HttpRequest request, byte[] body,
                ClientHttpRequestExecution execution) throws IOException {
            long started = System.nanoTime();
            ClientHttpResponse response = execution.execute(request, body);
            byte[] responseBytes = response.getBody().readAllBytes();
            ObjectNode event = mapper.createObjectNode();
            event.put("elapsed_ms", elapsedMillis(started));
            event.put("request_bytes", body.length);
            event.put("response_bytes", responseBytes.length);
            event.put("http_status", response.getStatusCode().value());
            try {
                JsonNode responseJson = mapper.readTree(responseBytes);
                JsonNode usage = responseJson.path("usage");
                putTokenCount(event, "input_tokens", firstLong(usage, "prompt_tokens", "input_tokens"));
                putTokenCount(event, "cached_input_tokens", firstLong(usage.path("prompt_tokens_details"), "cached_tokens"));
                putTokenCount(event, "output_tokens", firstLong(usage, "completion_tokens", "output_tokens"));
                putTokenCount(event, "total_tokens", firstLong(usage, "total_tokens"));
                JsonNode content = responseJson.path("choices").path(0).path("message").path("content");
                if (content.isTextual()) event.put("provider_output", content.asText());
            } catch (Exception ignored) {
                event.put("usage_status", "unavailable");
            }
            records.add(event);
            return new BufferedResponse(response, responseBytes);
        }

        private static Long firstLong(JsonNode node, String... fields) {
            for (String field : fields) {
                if (node.path(field).canConvertToLong()) return node.path(field).asLong();
            }
            return null;
        }

        private static void putTokenCount(ObjectNode target, String field, Long value) {
            if (value == null) target.putNull(field);
            else target.put(field, value);
        }

        private List<ObjectNode> records() {
            return List.copyOf(records);
        }
    }

    private static final class BufferedResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final byte[] body;

        private BufferedResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override public HttpStatusCode getStatusCode() throws IOException { return delegate.getStatusCode(); }
        @Override public String getStatusText() throws IOException { return delegate.getStatusText(); }
        @Override public HttpHeaders getHeaders() { return delegate.getHeaders(); }
        @Override public InputStream getBody() { return new java.io.ByteArrayInputStream(body); }
        @Override public void close() { delegate.close(); }
    }
}
