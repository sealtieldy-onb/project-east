package com.east.app.retention;

import java.sql.Timestamp;
import java.time.Clock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Periodically purges expired encrypted cache and source-derived records. */
@Service
public class RetentionCleanupService {

    private final RetentionCleanupRepository repository;
    private final Clock clock;

    public RetentionCleanupService(
            RetentionCleanupRepository repository,
            Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000L)
    @Transactional
    public void purgeExpiredData() {
        Timestamp cutoff = Timestamp.from(clock.instant());
        repository.deleteExpiredSummaryRuns(cutoff);
        repository.deleteExpiredInsights(cutoff);
        repository.deleteExpiredAttachments(cutoff);
        repository.clearExpiredMessageBodies(cutoff);
        repository.clearExpiredThreadSnippets(cutoff);
    }
}
