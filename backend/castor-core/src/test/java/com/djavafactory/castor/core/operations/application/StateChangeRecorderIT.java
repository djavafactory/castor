package com.djavafactory.castor.core.operations.application;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import javax.sql.DataSource;

import jakarta.inject.Inject;
import jakarta.transaction.TransactionalException;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.operations.domain.StateChangeRepository;
import com.djavafactory.castor.core.support.LayoutTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The state change log has one gap-free sequence per port, however many writers race and whatever rolls back. */
@QuarkusTest
class StateChangeRecorderIT {

    private static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");

    @Inject
    DataSource dataSource;

    @Inject
    StateChangeRecorder recorder;

    @Inject
    StateChangeRepository changes;

    private long port;

    @BeforeEach
    void setUp() throws Exception {
        LayoutTestSupport.clear(dataSource);
        try (Connection c = dataSource.getConnection()) {
            port = LayoutTestSupport.portId(c);
        }
    }

    @AfterEach
    void tearDown() {
        LayoutTestSupport.clear(dataSource);
    }

    private void record(int n) {
        QuarkusTransaction.requiringNew()
                .run(() -> recorder.record(port, "equipment", "STS" + n, n, T0, Map.of("n", n)));
    }

    private List<Long> stored() {
        return changes.after(port, 0, 1000).stream().map(c -> c.sequence()).toList();
    }

    @Test
    void fiftyConcurrentTransactionsGiveSequencesOneToFifty() throws Exception {
        int writers = 50;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                int n = i;
                results.add(pool.submit(() -> {
                    go.await();
                    return QuarkusTransaction.requiringNew()
                            .call(() -> recorder.record(port, "equipment", "STS" + n, n, T0, Map.of("n", n))
                                    .sequence());
                }));
            }
            go.countDown();
            List<Long> given = new ArrayList<>();
            for (Future<Long> f : results) {
                given.add(f.get(60, TimeUnit.SECONDS));
            }
            Collections.sort(given);
            assertEquals(LongStream.rangeClosed(1, writers).boxed().toList(), given);
            assertEquals(given, stored());
            assertEquals(writers, changes.lastSequence(port));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aRolledBackTransactionLeavesNoGap() {
        IntStream.range(0, 3).forEach(this::record);

        assertThrows(
                IllegalStateException.class,
                () -> QuarkusTransaction.requiringNew().run(() -> {
                    long seq = recorder.record(port, "equipment", "STS9", 9, T0, Map.of())
                            .sequence();
                    assertEquals(4, seq);
                    throw new IllegalStateException("handler failed after recording");
                }));
        assertEquals(List.of(1L, 2L, 3L), stored());
        assertEquals(3, changes.lastSequence(port));

        record(4);
        assertEquals(List.of(1L, 2L, 3L, 4L), stored());
    }

    @Test
    void sequencesAreIndependentPerPort() throws Exception {
        long other;
        try (Connection c = dataSource.getConnection();
                var s = c.createStatement()) {
            s.execute(
                    "insert into ref.port (code, name, city, origin, frame_rot_deg, extent) select 'otherport', 'Other', "
                            + "'Other', origin, frame_rot_deg, extent from ref.port where code = 'westports'");
            var rs = s.executeQuery("select id from ref.port where code = 'otherport'");
            rs.next();
            other = rs.getLong(1);
        }
        try {
            record(1);
            QuarkusTransaction.requiringNew().run(() -> recorder.record(other, "equipment", "X", 1, T0, Map.of()));
            assertEquals(1, changes.lastSequence(port));
            assertEquals(1, changes.lastSequence(other));
        } finally {
            LayoutTestSupport.clear(dataSource);
            try (Connection c = dataSource.getConnection();
                    var s = c.createStatement()) {
                s.execute("delete from ref.port where code = 'otherport'");
            }
        }
    }

    @Test
    void recordingOutsideATransactionIsRefused() {
        assertThrows(TransactionalException.class, () -> recorder.record(port, "equipment", "STS1", 1, T0, Map.of()));
        assertEquals(0, changes.lastSequence(port));
    }

    @Test
    void anIncompleteChangeIsRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> QuarkusTransaction.requiringNew().run(() -> recorder.record(port, " ", "STS1", 1, T0, Map.of())));
    }
}
