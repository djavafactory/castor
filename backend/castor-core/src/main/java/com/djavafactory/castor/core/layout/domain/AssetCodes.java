package com.djavafactory.castor.core.layout.domain;

import java.util.Collection;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Codes the server hands out: type prefix + next number of the port, for example WH-0007. */
public final class AssetCodes {

    private static final Map<String, String> PREFIXES = Map.of(
            "WAREHOUSE", "WH",
            "BERTH", "B",
            "QUAY_CRANE_RAIL", "QC",
            "STORAGE_PAD", "PAD",
            "CONTAINER_YARD", "CY",
            "STS_CRANE", "STS");

    private AssetCodes() {}

    /** The prefix of a type: a fixed one for the common types, else the initials of its words (three letters for one word). */
    public static String prefix(String typeCode) {
        String fixed = PREFIXES.get(typeCode);
        if (fixed != null) {
            return fixed;
        }
        String[] words = typeCode.split("_");
        if (words.length == 1) {
            return typeCode.substring(0, Math.min(3, typeCode.length()));
        }
        var sb = new StringBuilder();
        for (String w : words) {
            sb.append(w.charAt(0));
        }
        return sb.toString();
    }

    /** The next free code of the type: one above the highest PREFIX-number among the codes of the port. */
    public static String next(String typeCode, Collection<String> existingCodes) {
        String prefix = prefix(typeCode);
        Pattern p = Pattern.compile("^" + Pattern.quote(prefix) + "-(\\d{1,9})$");
        int max = 0;
        for (String code : existingCodes) {
            Matcher m = p.matcher(code);
            if (m.matches()) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        return prefix + "-" + String.format("%04d", max + 1);
    }
}
