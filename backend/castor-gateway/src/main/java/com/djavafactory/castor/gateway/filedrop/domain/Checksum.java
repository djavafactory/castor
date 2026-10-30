package com.djavafactory.castor.gateway.filedrop.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** The checksum rule of the drop folder: the first word of {@code <name>.sha256} is the SHA-256 of the file in hex. */
public final class Checksum {

    private Checksum() {}

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The hash of a {@code sha256sum} style file ("hash  name" or just the hash), lower case; empty text gives "". */
    public static String expected(String sidecar) {
        String[] words = sidecar.strip().split("\\s+");
        return words[0].toLowerCase(Locale.ROOT);
    }
}
