package it.replica8.castor.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VersionFileTest {

    @Test
    void pomVersionMatchesRootVersionFile() throws IOException {
        String fromFile = Files.readString(Path.of("..", "..", "VERSION")).trim();

        assertEquals(fromFile, System.getProperty("castor.version"));
    }
}
