package com.djavafactory.castor.gateway.formats.domain;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The CSV of source scss: a header row with the field names, then one record per row. Comma separated, a field may be
 * quoted with double quotes (a doubled quote inside is one quote), blank rows are skipped. Every value is text.
 */
public final class Csv {

    private Csv() {}

    public static List<ObjectNode> records(String text) throws FormatException {
        List<List<String>> rows = rows(text);
        if (rows.isEmpty()) {
            throw new FormatException("The CSV has no header row");
        }
        List<String> header = rows.get(0).stream().map(String::strip).toList();
        if (header.stream().anyMatch(String::isEmpty)) {
            throw new FormatException("The CSV header has an empty column name");
        }
        if (header.stream().distinct().count() != header.size()) {
            throw new FormatException("The CSV header repeats a column name");
        }
        List<ObjectNode> records = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() != header.size()) {
                throw new FormatException(
                        "Row " + (i + 1) + " has " + row.size() + " columns, the header has " + header.size());
            }
            ObjectNode record = JsonNodeFactory.instance.objectNode();
            for (int c = 0; c < header.size(); c++) {
                record.put(header.get(c), row.get(c).strip());
            }
            records.add(record);
        }
        return records;
    }

    private static List<List<String>> rows(String text) throws FormatException {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        String source = text.startsWith("﻿") ? text.substring(1) : text;
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < source.length() && source.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"' && field.toString().isBlank()) {
                quoted = true;
                wasQuoted = true;
                field.setLength(0);
            } else if (ch == ',') {
                row.add(field.toString());
                field.setLength(0);
                wasQuoted = false;
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < source.length() && source.charAt(i + 1) == '\n') {
                    i++;
                }
                row = endRow(rows, row, field, wasQuoted);
                wasQuoted = false;
            } else {
                field.append(ch);
            }
        }
        if (quoted) {
            throw new FormatException("A quoted CSV field is not closed");
        }
        endRow(rows, row, field, wasQuoted);
        return rows;
    }

    /** Closes the row being read (a blank row is dropped) and returns the empty row to fill next. */
    private static List<String> endRow(
            List<List<String>> rows, List<String> row, StringBuilder field, boolean wasQuoted) {
        if (!(row.isEmpty() && field.toString().isBlank() && !wasQuoted)) {
            row.add(field.toString());
            rows.add(row);
        }
        field.setLength(0);
        return new ArrayList<>();
    }
}
