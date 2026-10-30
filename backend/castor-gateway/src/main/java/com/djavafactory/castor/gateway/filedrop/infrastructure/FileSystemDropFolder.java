package com.djavafactory.castor.gateway.filedrop.infrastructure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.gateway.filedrop.domain.DropFolder;
import com.djavafactory.castor.gateway.shared.infrastructure.CastorConfig;

/** {@code ${GATEWAY_DROP_DIR}/<source>/} on the file system; moves are renames inside the volume. */
@ApplicationScoped
public class FileSystemDropFolder implements DropFolder {

    static final String CHECKSUM_SUFFIX = ".sha256";
    static final String REASON_SUFFIX = ".reason";

    private final Optional<Path> root;

    public FileSystemDropFolder(CastorConfig castor) {
        this.root = castor.drop().dir().filter(dir -> !dir.isBlank()).map(Path::of);
    }

    /** False when GATEWAY_DROP_DIR is not set: the file channel is off. */
    public boolean enabled() {
        return root.isPresent();
    }

    @Override
    public List<String> files(String source) {
        Path dir = sourceDir(source);
        try {
            Files.createDirectories(dir);
            try (Stream<Path> entries = Files.list(dir)) {
                return entries.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> !name.startsWith(".")
                                && !name.endsWith(CHECKSUM_SUFFIX)
                                && !name.endsWith(REASON_SUFFIX))
                        .sorted()
                        .toList();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<String> checksum(String source, String name) {
        Path file = sourceDir(source).resolve(name + CHECKSUM_SUFFIX);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] read(String source, String name) {
        try {
            return Files.readAllBytes(sourceDir(source).resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void archive(String source, String name, LocalDate day) {
        Path target = sourceDir(source).resolve("archive").resolve(day.toString());
        moveWithChecksum(source, name, target);
    }

    @Override
    public void reject(String source, String name, String reason) {
        Path target = sourceDir(source).resolve("rejected");
        Path moved = moveWithChecksum(source, name, target);
        try {
            Files.writeString(
                    moved.resolveSibling(moved.getFileName() + REASON_SUFFIX), reason + System.lineSeparator());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Moves the file and its checksum file into the folder; a name taken there already (a re-drop) gets a number. */
    private Path moveWithChecksum(String source, String name, Path folder) {
        try {
            Files.createDirectories(folder);
            Path from = sourceDir(source).resolve(name);
            Path to = free(folder, name);
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            Path sum = sourceDir(source).resolve(name + CHECKSUM_SUFFIX);
            if (Files.exists(sum)) {
                Files.move(
                        sum,
                        to.resolveSibling(to.getFileName() + CHECKSUM_SUFFIX),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return to;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path free(Path folder, String name) {
        Path candidate = folder.resolve(name);
        for (int n = 1; Files.exists(candidate); n++) {
            candidate = folder.resolve(name + "." + n);
        }
        return candidate;
    }

    private Path sourceDir(String source) {
        return root.orElseThrow(() -> new IllegalStateException("GATEWAY_DROP_DIR is not set"))
                .resolve(source);
    }
}
