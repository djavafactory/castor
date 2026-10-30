package com.djavafactory.castor.gateway.intake.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.core.instrument.MeterRegistry;

import com.djavafactory.castor.gateway.formats.domain.EdiMessage;
import com.djavafactory.castor.gateway.intake.application.IntakeResult.Item;
import com.djavafactory.castor.gateway.intake.domain.ApiKeys;
import com.djavafactory.castor.gateway.intake.domain.InboxRepository;
import com.djavafactory.castor.gateway.intake.domain.InboxRepository.NewRecord;
import com.djavafactory.castor.gateway.intake.domain.InboxRepository.Stored;
import com.djavafactory.castor.gateway.intake.domain.IntakeException;
import com.djavafactory.castor.gateway.intake.domain.IntakeException.Field;
import com.djavafactory.castor.gateway.intake.domain.IntakeException.Kind;
import com.djavafactory.castor.gateway.intake.domain.IntegrationSource;
import com.djavafactory.castor.gateway.intake.domain.RecordKeys;
import com.djavafactory.castor.gateway.intake.domain.RequestSignature;
import com.djavafactory.castor.gateway.intake.domain.SecretResolver;
import com.djavafactory.castor.gateway.intake.domain.Source;
import com.djavafactory.castor.gateway.intake.domain.SourceRegistry;
import com.djavafactory.castor.gateway.shared.domain.Json;
import com.djavafactory.castor.gateway.shared.domain.Uuid7;
import com.djavafactory.castor.gateway.shared.infrastructure.CastorConfig;

/**
 * Durable acceptance. The checks run in this order and the first failure answers: key (401), source enabled (403),
 * size (413), signature (401), JSON (400), then per record sourceEventId and occurredAt (422). Valid records are inserted
 * into ingest.inbox in one transaction that is committed when this method returns: whoever answers 202 afterwards
 * answers for data that is in the database.
 */
@ApplicationScoped
public class ReceiveEvents {

    private final SourceRegistry sources;
    private final InboxRepository inbox;
    private final SecretResolver secrets;
    private final CastorConfig.Intake config;
    private final InstantSource clock;
    private final Uuid7.Generator ids;
    private final MeterRegistry meters;

    public ReceiveEvents(
            SourceRegistry sources,
            InboxRepository inbox,
            SecretResolver secrets,
            CastorConfig castor,
            InstantSource clock,
            Uuid7.Generator ids,
            MeterRegistry meters) {
        this.sources = sources;
        this.inbox = inbox;
        this.secrets = secrets;
        this.config = castor.intake();
        this.clock = clock;
        this.ids = ids;
        this.meters = meters;
    }

    public IntakeResult receive(IntakeRequest request) {
        try {
            IntegrationSource source = authenticate(request);
            if (!source.enabled()) {
                throw new IntakeException(
                        Kind.FORBIDDEN,
                        "The source " + source.source().code() + " is disabled for port " + source.portId());
            }
            byte[] body = readBody(request);
            checkSignature(source, request, body);
            JsonNode tree = parse(source.source(), body);
            IntakeResult result = tree.isArray() ? batch(source, tree) : single(source, tree);
            count(result);
            return result;
        } catch (IntakeException e) {
            meters.counter("castor.gateway.intake", "outcome", "rejected").increment();
            throw e;
        }
    }

    private IntegrationSource authenticate(IntakeRequest request) {
        IntakeException refused = new IntakeException(Kind.UNAUTHORIZED, "Send a valid API key in X-Castor-Key");
        String key = request.key();
        if (key == null || key.isBlank()) {
            throw refused;
        }
        byte[] hash = ApiKeys.hash(key);
        return Source.fromCode(request.source())
                .flatMap(source -> sources.find(request.portId(), source))
                .filter(found -> sources.hasActiveKey(found.id(), hash))
                .orElseThrow(() -> refused);
    }

    private byte[] readBody(IntakeRequest request) {
        long max = config.maxBodyBytes();
        IntakeException tooLarge =
                new IntakeException(Kind.TOO_LARGE, "The body must be at most " + max / 1024 + " KB");
        if (request.declaredLength() > max) {
            throw tooLarge;
        }
        try {
            byte[] body = request.body().readNBytes((int) max + 1);
            if (body.length > max) {
                throw tooLarge;
            }
            return body;
        } catch (IOException e) {
            throw new IntakeException(Kind.BAD_JSON, "The request body could not be read");
        }
    }

    private void checkSignature(IntegrationSource source, IntakeRequest request, byte[] body) {
        Optional<String> secret = Optional.ofNullable(source.hmacSecretRef()).flatMap(secrets::resolve);
        RequestSignature.check(
                source.requireSignature(),
                secret,
                request.timestamp(),
                request.signature(),
                body,
                clock.instant(),
                config.maxClockSkew());
    }

    private static JsonNode parse(Source source, byte[] body) {
        if (source == Source.EDIC) {
            JsonNode messages = ediText(body);
            if (messages != null) {
                return messages;
            }
        }
        try {
            JsonNode tree = Json.parse(body);
            if (tree == null || tree.isMissingNode()) {
                throw new IntakeException(Kind.BAD_JSON, "The request body is empty");
            }
            if (!tree.isObject() && !tree.isArray()) {
                throw new IntakeException(Kind.BAD_JSON, "The body must be a JSON object or an array of objects");
            }
            return tree;
        } catch (JsonProcessingException e) {
            throw new IntakeException(Kind.BAD_JSON, "The request body is not valid JSON");
        }
    }

    /**
     * Source edic may post the EDI text itself instead of {"edi": "..."}: one message is one record, several are an
     * array. Null when the body is not EDI text.
     */
    private static JsonNode ediText(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).strip();
        if (!text.startsWith("MSG+")) {
            return null;
        }
        List<String> messages = EdiMessage.split(text);
        List<JsonNode> records = messages.stream()
                .<JsonNode>map(message -> JsonNodeFactory.instance.objectNode().put("edi", message))
                .toList();
        return records.size() == 1
                ? records.get(0)
                : JsonNodeFactory.instance.arrayNode().addAll(records);
    }

    private IntakeResult single(IntegrationSource source, JsonNode record) {
        Item item = store(source, List.of(record)).get(0);
        return switch (item.status()) {
            case 422 -> throw new IntakeException(Kind.UNPROCESSABLE, "The record is not valid", item.errors());
            case 400 ->
                throw new IntakeException(Kind.BAD_JSON, item.errors().get(0).message());
            default -> new IntakeResult.Single(item);
        };
    }

    private IntakeResult batch(IntegrationSource source, JsonNode records) {
        if (records.isEmpty()) {
            throw new IntakeException(
                    Kind.UNPROCESSABLE, "The array has no records", List.of(new Field("EMPTY", "", "No records")));
        }
        if (records.size() > config.maxBatchSize()) {
            throw new IntakeException(
                    Kind.TOO_LARGE, "At most " + config.maxBatchSize() + " records per request, got " + records.size());
        }
        List<JsonNode> list = new ArrayList<>(records.size());
        records.forEach(list::add);
        return new IntakeResult.Batch(store(source, list));
    }

    /**
     * Checks every record, stores the valid ones in one transaction and answers all of them in order. The file channel
     * stores its records the same way, so a record has the same fate whichever door it came through.
     */
    public List<Item> store(IntegrationSource source, List<JsonNode> records) {
        Instant receivedAt = clock.instant();
        Item[] items = new Item[records.size()];
        List<NewRecord> accepted = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            JsonNode record = records.get(i);
            List<Field> problems = RecordKeys.problems(source.source(), record);
            if (problems.isEmpty()) {
                UUID receptionId = ids.next(receivedAt);
                accepted.add(new NewRecord(
                        receptionId,
                        RecordKeys.sourceEventId(source.source(), record),
                        receivedAt,
                        Json.write(record)));
                positions.add(i);
            } else {
                items[i] = new Item(i, 422, null, false, problems);
            }
        }
        if (!accepted.isEmpty()) {
            List<Stored> stored = inbox.store(source.portId(), source.source(), accepted);
            for (int n = 0; n < stored.size(); n++) {
                int index = positions.get(n);
                items[index] = switch (stored.get(n)) {
                    case Stored.Accepted a -> new Item(index, 202, a.receptionId(), false, List.of());
                    case Stored.Duplicate d -> new Item(index, 200, d.receptionId(), true, List.of());
                    case Stored.Unstorable u ->
                        new Item(index, 400, null, false, List.of(new Field("UNSTORABLE", "", u.reason())));
                };
            }
        }
        return List.of(items);
    }

    private void count(IntakeResult result) {
        List<Item> items = switch (result) {
            case IntakeResult.Single s -> List.of(s.item());
            case IntakeResult.Batch b -> b.items();
        };
        for (Item item : items) {
            String outcome = switch (item.status()) {
                case 202 -> "accepted";
                case 200 -> "duplicate";
                default -> "rejected";
            };
            meters.counter("castor.gateway.intake", "outcome", outcome).increment();
        }
    }
}
