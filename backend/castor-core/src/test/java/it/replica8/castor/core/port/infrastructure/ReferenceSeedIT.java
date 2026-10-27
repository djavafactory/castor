package it.replica8.castor.core.port.infrastructure;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The committed Westports reference file is imported at start-up: 127 buildings and 504 road lines. */
@QuarkusTest
@TestProfile(ReferenceSeedIT.ImportOn.class)
class ReferenceSeedIT {

    public static class ImportOn implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("castor.reference.import-on-start", "true");
        }
    }

    @Inject
    DataSource dataSource;

    private long count(String kind) throws SQLException {
        try (Connection c = dataSource.getConnection();
                ResultSet rs = c.createStatement()
                        .executeQuery("select count(*) from ref.reference_feature where kind = '" + kind + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void importsTheCommittedFile() throws SQLException {
        Assumptions.assumeTrue(
                OsmImportJob.exists("seed/westports/osm-reference.geojson"),
                "seed/westports/osm-reference.geojson is not in the repository yet");

        assertEquals(127, count("BUILDING"));
        assertEquals(504, count("ROAD"));
    }
}
