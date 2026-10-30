package com.djavafactory.castor.gateway.intake.infrastructure;

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
 * The real role of the real stacks: db/init/01-roles.sql on an empty PostGIS database, then the gateway migration run
 * as castor_gateway, which owns schema ingest and has no other right. Dev Services connects as a superuser, so this is
 * the only place the least-privilege setup of the compose stacks is proven, including the clean that the dev reset runs.
 */
class IntakeRolesIT {

    private static PostgreSQLContainer postgres;

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), "castor_gateway", "castor_gateway_dev")
                .locations("classpath:db/migration")
                .schemas("ingest")
                .cleanDisabled(false)
                .load();
    }

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
        flyway().migrate();
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static Connection gateway() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "castor_gateway", "castor_gateway_dev");
    }

    @Test
    void theGatewayRoleOwnsSchemaIngestAndItsFlywayHistoryLivesThere() throws Exception {
        try (var c = gateway();
                Statement s = c.createStatement()) {
            var owner = s.executeQuery("select pg_get_userbyid(nspowner) from pg_namespace where nspname = 'ingest'");
            assertTrue(owner.next());
            assertEquals("castor_gateway", owner.getString(1));

            var tables = s.executeQuery(
                    "select string_agg(tablename, ',' order by tablename) from pg_tables where schemaname = 'ingest'");
            assertTrue(tables.next());
            assertEquals(
                    "api_key,flyway_schema_history,inbox,integration_source,outbox,quarantine", tables.getString(1));

            var versions = s.executeQuery("select string_agg(version, ',') from ingest.flyway_schema_history "
                    + "where version is not null and success");
            assertTrue(versions.next());
            assertEquals("1", versions.getString(1));
        }
    }

    @Test
    void theGatewayRoleHasNoRightOutsideItsSchema() throws Exception {
        try (var c = gateway();
                Statement s = c.createStatement()) {
            assertThrows(SQLException.class, () -> s.execute("create table public.nope (id int)"));
            assertThrows(SQLException.class, () -> s.execute("create schema nope"));
            assertThrows(SQLException.class, () -> s.execute("create extension if not exists hstore"));
            var roles = s.executeQuery(
                    "select rolsuper, rolcreatedb, rolcreaterole from pg_roles " + "where rolname = 'castor_gateway'");
            assertTrue(roles.next());
            assertEquals(false, roles.getBoolean(1));
            assertEquals(false, roles.getBoolean(2));
            assertEquals(false, roles.getBoolean(3));
        }
    }

    @Test
    void theCoreRoleCannotReadTheIngestSchema() throws Exception {
        try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), "castor_app", "castor_app_dev");
                Statement s = c.createStatement()) {
            assertThrows(SQLException.class, () -> s.execute("select * from ingest.inbox"));
        }
    }

    @Test
    void theDevResetWorksWithThatRoleAlone() throws Exception {
        try (var c = gateway();
                Statement s = c.createStatement()) {
            s.execute("insert into ingest.integration_source (port_id, code, created_at, updated_at) "
                    + "values ('westports', 'canonical', now(), now())");
        }

        // What clean-at-start does in the dev stack: empty the schema, then migrate again
        flyway().clean();
        flyway().migrate();

        try (var c = gateway();
                Statement s = c.createStatement()) {
            var rows = s.executeQuery("select count(*) from ingest.integration_source");
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1), "the reset left schema ingest empty");
            var owner = s.executeQuery("select pg_get_userbyid(nspowner) from pg_namespace where nspname = 'ingest'");
            assertTrue(owner.next());
            assertEquals("castor_gateway", owner.getString(1), "the schema itself was kept");
        }
    }
}
