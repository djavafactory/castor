package com.djavafactory.castor.gateway.filedrop.application;

import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.jboss.logging.Logger;

import com.djavafactory.castor.gateway.filedrop.domain.Checksum;
import com.djavafactory.castor.gateway.filedrop.domain.DropFolder;
import com.djavafactory.castor.gateway.filedrop.domain.DropFormats;
import com.djavafactory.castor.gateway.formats.domain.FormatException;
import com.djavafactory.castor.gateway.intake.application.IntakeResult.Item;
import com.djavafactory.castor.gateway.intake.application.ReceiveEvents;
import com.djavafactory.castor.gateway.intake.domain.IntakeException.Field;
import com.djavafactory.castor.gateway.intake.domain.IntegrationSource;
import com.djavafactory.castor.gateway.intake.domain.RecordKeys;
import com.djavafactory.castor.gateway.intake.domain.Source;
import com.djavafactory.castor.gateway.intake.domain.SourceRegistry;
import com.djavafactory.castor.gateway.shared.infrastructure.CastorConfig;

/**
 * The file channel. One run looks in the folder of every enabled source for files that are complete (their
 * {@code .sha256} is there) and takes each in this order: the checksum must match, the file must be readable in the
 * format of its source, and every record must carry what the inbox needs to recognise a repeat. A file that fails a step
 * goes to {@code rejected/} with the reason and nothing of it is stored. A good file's records are stored like those of
 * the REST endpoint (same de-duplication, one transaction), and only then does the file move to
 * {@code archive/yyyy-mm-dd/}. A crash between the two leaves the file where it was, and taking it again stores nothing
 * twice, which is also why dropping an archived file again is safe.
 */
@ApplicationScoped
public class ProcessDrops {

    private static final Logger LOG = Logger.getLogger(ProcessDrops.class);

    /** What one run did. */
    public record Result(int archived, int rejected, int records, int duplicates) {}

    private final DropFolder folder;
    private final SourceRegistry sources;
    private final ReceiveEvents receive;
    private final InstantSource clock;
    private final MeterRegistry meters;
    private final String portId;

    public ProcessDrops(
            DropFolder folder,
            SourceRegistry sources,
            ReceiveEvents receive,
            InstantSource clock,
            MeterRegistry meters,
            CastorConfig castor) {
        this.folder = folder;
        this.sources = sources;
        this.receive = receive;
        this.clock = clock;
        this.meters = meters;
        this.portId = castor.portCode();
    }

    public Result runOnce() {
        int archived = 0;
        int rejected = 0;
        int records = 0;
        int duplicates = 0;
        for (Source source : Source.values()) {
            Optional<IntegrationSource> configured =
                    sources.find(portId, source).filter(IntegrationSource::enabled);
            if (configured.isEmpty()) {
                continue;
            }
            for (String name : folder.files(source.code())) {
                Optional<String> expected = folder.checksum(source.code(), name);
                if (expected.isEmpty()) {
                    continue; // still being written: its checksum file comes last
                }
                Outcome outcome;
                try {
                    outcome = take(configured.get(), name, expected.get());
                } catch (RuntimeException e) {
                    // a file that cannot be read now (it moved, the volume hiccuped) must not hold up the others
                    LOG.errorf(e, "Dropped file %s/%s could not be processed, trying again", source.code(), name);
                    continue;
                }
                if (outcome.reason() != null) {
                    folder.reject(source.code(), name, outcome.reason());
                    LOG.warnf("Dropped file %s/%s rejected: %s", source.code(), name, outcome.reason());
                    meters.counter("castor.gateway.drop", "outcome", "rejected").increment();
                    rejected++;
                } else {
                    folder.archive(source.code(), name, LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
                    meters.counter("castor.gateway.drop", "outcome", "archived").increment();
                    archived++;
                    records += outcome.records();
                    duplicates += outcome.duplicates();
                }
            }
        }
        return new Result(archived, rejected, records, duplicates);
    }

    private record Outcome(String reason, int records, int duplicates) {

        static Outcome rejected(String reason) {
            return new Outcome(reason, 0, 0);
        }
    }

    private Outcome take(IntegrationSource source, String name, String expected) {
        byte[] content = folder.read(source.source().code(), name);
        String actual = Checksum.sha256(content);
        if (!actual.equals(Checksum.expected(expected))) {
            return Outcome.rejected(
                    "The SHA-256 of the file is " + actual + ", its .sha256 file says " + Checksum.expected(expected));
        }
        List<JsonNode> records;
        try {
            records = DropFormats.records(source.source(), name, content);
        } catch (FormatException e) {
            return Outcome.rejected(e.getMessage());
        }
        for (int i = 0; i < records.size(); i++) {
            List<Field> problems = RecordKeys.problems(source.source(), records.get(i));
            if (!problems.isEmpty()) {
                return Outcome.rejected("Record " + (i + 1) + ": "
                        + problems.stream()
                                .map(p -> (p.pointer().isEmpty() ? "" : p.pointer() + " ") + p.message())
                                .collect(Collectors.joining("; ")));
            }
        }
        List<Item> items = receive.store(source, records);
        int duplicates = 0;
        for (Item item : items) {
            if (item.status() >= 400) {
                return Outcome.rejected("Record " + (item.index() + 1) + ": "
                        + item.errors().stream().map(Field::message).collect(Collectors.joining("; ")));
            }
            if (item.duplicate()) {
                duplicates++;
            }
        }
        return new Outcome(null, items.size(), duplicates);
    }
}
