package com.djavafactory.castor.gateway.normalize.domain;

import java.util.function.Function;

/** The inbox rows waiting to be normalized and the places their results go. */
public interface NormalizationStore {

    /**
     * @param normalized records that now have outbox rows
     * @param quarantined records that now have a quarantine entry
     */
    record Result(int normalized, int quarantined) {

        public int total() {
            return normalized + quarantined;
        }
    }

    /**
     * In one transaction: takes up to {@code batchSize} RECEIVED rows (oldest first, rows another worker holds are
     * skipped), asks {@code decider} about each, writes the outbox rows and sets NORMALIZED, or writes the quarantine
     * entry and sets QUARANTINED, and commits. If anything fails nothing of the batch is kept and the rows stay RECEIVED.
     */
    Result process(int batchSize, Function<InboxRecord, Decision> decider);
}
