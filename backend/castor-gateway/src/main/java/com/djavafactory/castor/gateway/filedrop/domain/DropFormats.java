package com.djavafactory.castor.gateway.filedrop.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import com.djavafactory.castor.gateway.formats.domain.Csv;
import com.djavafactory.castor.gateway.formats.domain.EdiMessage;
import com.djavafactory.castor.gateway.formats.domain.FormatException;
import com.djavafactory.castor.gateway.intake.domain.Source;
import com.djavafactory.castor.gateway.shared.domain.Json;

/**
 * The records of a dropped file, by source and file extension:
 *
 * <ul>
 *   <li>{@code .json}: one record or an array of records, for every source
 *   <li>{@code .jsonl}: one JSON record per line, for every source
 *   <li>{@code .csv}: scss only, a header row and one record per row
 *   <li>{@code .edi}, {@code .txt}: edic only, EDI messages ({@code {"edi": text}} each)
 * </ul>
 *
 * A file is read whole or not at all: the first thing wrong with it is the reason it is rejected.
 */
public final class DropFormats {

    private DropFormats() {}

    public static List<JsonNode> records(Source source, String fileName, byte[] content) throws FormatException {
        String text = decode(content);
        String name = fileName.toLowerCase(Locale.ROOT);
        List<JsonNode> records;
        if (name.endsWith(".json")) {
            records = json(text);
        } else if (name.endsWith(".jsonl")) {
            records = jsonLines(text);
        } else if (name.endsWith(".csv") && source == Source.SCSS) {
            records = new ArrayList<>(Csv.records(text));
        } else if ((name.endsWith(".edi") || name.endsWith(".txt")) && source == Source.EDIC) {
            records = new ArrayList<>(edi(text));
        } else {
            throw new FormatException(
                    "A " + source.code() + " file cannot be named " + fileName + " (" + accepted(source) + ")");
        }
        if (records.isEmpty()) {
            throw new FormatException("The file has no records");
        }
        return records;
    }

    private static String accepted(Source source) {
        return switch (source) {
            case SCSS -> ".csv, .json or .jsonl";
            case EDIC -> ".edi, .txt, .json or .jsonl";
            default -> ".json or .jsonl";
        };
    }

    private static String decode(byte[] content) throws FormatException {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new FormatException("The file is not UTF-8 text");
        }
    }

    private static List<JsonNode> json(String text) throws FormatException {
        try {
            JsonNode tree = Json.parse(text);
            if (tree == null || tree.isMissingNode()) {
                throw new FormatException("The file is empty");
            }
            if (tree.isArray()) {
                List<JsonNode> records = new ArrayList<>();
                tree.forEach(records::add);
                return records;
            }
            if (tree.isObject()) {
                return List.of(tree);
            }
            throw new FormatException("The file must hold a JSON object or an array of objects");
        } catch (JsonProcessingException e) {
            throw new FormatException("The file is not valid JSON: " + e.getOriginalMessage());
        }
    }

    private static List<JsonNode> jsonLines(String text) throws FormatException {
        List<JsonNode> records = new ArrayList<>();
        int number = 0;
        for (String line : text.split("\\R")) {
            number++;
            if (line.isBlank()) {
                continue;
            }
            try {
                records.add(Json.parse(line));
            } catch (JsonProcessingException e) {
                throw new FormatException("Line " + number + " is not valid JSON: " + e.getOriginalMessage());
            }
        }
        return records;
    }

    private static List<JsonNode> edi(String text) throws FormatException {
        List<String> messages = EdiMessage.split(text);
        List<JsonNode> records = new ArrayList<>();
        for (String message : messages) {
            records.add(JsonNodeFactory.instance.objectNode().put("edi", message));
        }
        return records;
    }
}
