package com.djavafactory.castor.gateway.filedrop.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The drop folder of the file channel: {@code <dir>/<source>/<name>} with a {@code <name>.sha256} next to it. A file is
 * moved to {@code archive/yyyy-mm-dd/} once its records are in the inbox, or to {@code rejected/} with a
 * {@code <name>.reason} file; the checksum file goes with it.
 */
public interface DropFolder {

    /** The names of the files waiting in the folder of the source, oldest name first; no checksum or reason files. */
    List<String> files(String source);

    /** The expected SHA-256 (hex) written in {@code <name>.sha256}, empty while that file is not there. */
    Optional<String> checksum(String source, String name);

    byte[] read(String source, String name);

    void archive(String source, String name, LocalDate day);

    void reject(String source, String name, String reason);
}
