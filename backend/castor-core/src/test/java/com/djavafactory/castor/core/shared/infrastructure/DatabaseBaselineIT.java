package com.djavafactory.castor.core.shared.infrastructure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applies the migrations to an empty PostGIS database prepared by db/init/01-roles.sql and checks the rights of the
 * application role. Uses its own container because Dev Services connects as a superuser.
 */
class DatabaseBaselineIT {

    private static final String[] SCHEMAS = {"ref", "layout", "audit", "security"};

    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void migrate() throws Exception {
        var initScript = Path.of("..", "..", "db", "init", "01-roles.sql")
                .toAbsolutePath()
                .normalize();
        postgres = new PostgreSQLContainer(
                        DockerImageName.parse("postgis/postgis:18-3.6").asCompatibleSubstituteFor("postgres"))
                .withCopyToContainer(
                        Transferable.of(Files.readAllBytes(initScript)), "/docker-entrypoint-initdb.d/01-roles.sql");
        postgres.start();

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), "castor_flyway", "castor_flyway_dev")
                .locations("classpath:db/migration")
                .schemas(SCHEMAS)
                .load()
                .migrate();

        // ref.probe shows what the default privileges give the application
        try (var c = connect("castor_flyway", "castor_flyway_dev");
                Statement s = c.createStatement()) {
            s.execute("CREATE TABLE ref.probe (id bigint PRIMARY KEY, name text)");
        }
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), user, password);
    }

    private static Connection app() throws SQLException {
        return connect("castor_app", "castor_app_dev");
    }

    @Test
    void migrationsCreateExtensionAndSchemasOwnedByFlywayRole() throws Exception {
        try (var c = app();
                Statement s = c.createStatement()) {
            assertTrue(
                    s.executeQuery("SELECT 1 FROM pg_extension WHERE extname = 'postgis'")
                            .next(),
                    "postgis");

            var owners = s.executeQuery("SELECT nspname, pg_get_userbyid(nspowner) FROM pg_namespace "
                    + "WHERE nspname IN ('ref','layout','audit','security')");
            int count = 0;
            while (owners.next()) {
                assertEquals("castor_flyway", owners.getString(2), owners.getString(1));
                count++;
            }
            assertEquals(4, count);
        }
    }

    @Test
    void appRoleReadsAndWritesData() throws Exception {
        try (var c = app();
                Statement s = c.createStatement()) {
            s.execute("INSERT INTO ref.probe VALUES (1, 'a')");
            s.execute("UPDATE ref.probe SET name = 'b' WHERE id = 1");
            s.execute("DELETE FROM ref.probe WHERE id = 1");
            s.execute("INSERT INTO audit.audit_log (actor, action, entity_kind) VALUES ('a', 'x', 'k')");
            assertTrue(s.executeQuery("SELECT * FROM audit.audit_log").next());
        }
    }

    @Test
    void appRoleCannotRunDdl() throws Exception {
        try (var c = app();
                Statement s = c.createStatement()) {
            for (String schema : SCHEMAS) {
                assertThrows(SQLException.class, () -> s.execute("CREATE TABLE " + schema + ".nope (id int)"));
            }
            assertThrows(SQLException.class, () -> s.execute("CREATE TABLE public.nope (id int)"));
            assertThrows(SQLException.class, () -> s.execute("DROP TABLE ref.probe"));
            assertThrows(SQLException.class, () -> s.execute("CREATE SCHEMA nope"));
        }
    }

    @Test
    void auditLogIsAppendOnlyForAppRole() throws Exception {
        try (var c = app();
                Statement s = c.createStatement()) {
            assertThrows(SQLException.class, () -> s.execute("UPDATE audit.audit_log SET action = 'y'"));
            assertThrows(SQLException.class, () -> s.execute("DELETE FROM audit.audit_log"));
            assertThrows(SQLException.class, () -> s.execute("TRUNCATE audit.audit_log"));
        }
    }
}
