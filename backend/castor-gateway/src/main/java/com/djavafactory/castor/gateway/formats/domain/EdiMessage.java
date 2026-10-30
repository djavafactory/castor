package com.djavafactory.castor.gateway.formats.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The EDIFACT-like text of source edic: segments end with an apostrophe, elements are separated by a plus sign.
 *
 * <pre>MSG+E-0001'CALL+EDI-0412'EVT+ARRIVED'DTM+202610290620'</pre>
 *
 * {@code MSG} is the message id, {@code CALL} the call reference of the sender, {@code EVT} the milestone and
 * {@code DTM} when it happened, in UTC as {@code yyyyMMddHHmm} (seconds optional, {@code 203:} before and {@code :203}
 * after tolerated, as EDIFACT writes it). Segments the format does not know are ignored; a known one twice is an error.
 */
public record EdiMessage(String id, String callRef, String event, Instant at) {

    private static final Pattern MESSAGE_ID = Pattern.compile("(?:^|')\\s*MSG\\+([^+']+)");
    private static final Pattern MESSAGE_START = Pattern.compile("(?m)(?=^\\s*MSG\\+)");
    private static final Pattern DATE_TIME = Pattern.compile("^(?:\\d{3}:)?(\\d{12}|\\d{14})Z?(?::\\d{3})?$");
    private static final DateTimeFormatter MINUTES = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** The id of the first MSG segment, or null when the text has none (enough to de-duplicate a message). */
    public static String messageId(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = MESSAGE_ID.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    /** The messages of a file or body: each one starts at a MSG segment at the start of a line. */
    public static List<String> split(String text) {
        List<String> messages = new ArrayList<>();
        for (String part : MESSAGE_START.split(text == null ? "" : text)) {
            if (!part.isBlank()) {
                messages.add(part.strip());
            }
        }
        return messages;
    }

    public static EdiMessage parse(String text) throws FormatException {
        String id = null;
        String call = null;
        String event = null;
        String at = null;
        for (String raw : text.split("'")) {
            String segment = raw.strip();
            if (segment.isEmpty()) {
                continue;
            }
            int plus = segment.indexOf('+');
            String tag = plus < 0 ? segment : segment.substring(0, plus);
            String value = plus < 0 ? "" : segment.substring(plus + 1).strip();
            switch (tag) {
                case "MSG" -> id = once(tag, id, value);
                case "CALL" -> call = once(tag, call, value);
                case "EVT" -> event = once(tag, event, value);
                case "DTM" -> at = once(tag, at, value);
                default -> {
                    // a segment this format does not use
                }
            }
        }
        return new EdiMessage(
                required("MSG", id), required("CALL", call), required("EVT", event), dateTime(required("DTM", at)));
    }

    private static String once(String tag, String previous, String value) throws FormatException {
        if (previous != null) {
            throw new FormatException("The " + tag + " segment appears twice");
        }
        return value;
    }

    private static String required(String tag, String value) throws FormatException {
        if (value == null || value.isEmpty()) {
            throw new FormatException("The " + tag + " segment is missing or empty");
        }
        return value;
    }

    private static Instant dateTime(String value) throws FormatException {
        Matcher m = DATE_TIME.matcher(value);
        if (!m.matches()) {
            throw new FormatException("DTM must be yyyyMMddHHmm in UTC, got '" + value + "'");
        }
        String digits = m.group(1);
        try {
            DateTimeFormatter format = digits.length() == 12 ? MINUTES : SECONDS;
            return LocalDateTime.parse(digits, format).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new FormatException("DTM is not a real date and time: '" + value + "'");
        }
    }
}
