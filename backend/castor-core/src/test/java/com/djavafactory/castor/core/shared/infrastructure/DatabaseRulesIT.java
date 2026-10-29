package com.djavafactory.castor.core.shared.infrastructure;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.support.LayoutTestSupport;

import static com.djavafactory.castor.core.support.LayoutTestSupport.asset;
import static com.djavafactory.castor.core.support.LayoutTestSupport.localRect;
import static com.djavafactory.castor.core.support.LayoutTestSupport.revision;
import static com.djavafactory.castor.core.support.LayoutTestSupport.version;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Database rule suite (SETUP-09): one failing case per rule, each expecting the database itself (constraint or
 * trigger) to reject the statement, whatever the application does.
 */
@QuarkusTest
class DatabaseRulesIT {

    private static final String HALL = localRect(100, 50, 140, 70);
    private static final String GOOD_HASH = BcryptUtil.bcryptHash("secret", 12);

    @Inject
    DataSource dataSource;

    @BeforeEach
    @AfterEach
    void clear() throws SQLException {
        LayoutTestSupport.clear(dataSource);
        run("delete from security.user_account where lower(username) like 'rules.%'");
    }

    private void run(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
                var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private void user(String username, String hash) throws SQLException {
        try (Connection c = dataSource.getConnection();
                var s = c.prepareStatement(
                        "insert into security.user_account (username, display_name, password_hash) values (?, ?, ?)")) {
            s.setString(1, username);
            s.setString(2, "Rules");
            s.setString(3, hash);
            s.executeUpdate();
        }
    }

    /** A draft with one building, for the cases that change a revision. */
    private UUID draftWithHall(Connection c) throws SQLException {
        UUID draft = version(c, 1, "DRAFT");
        revision(c, asset(c, "WH1", "BUILDING"), draft, "Hall", HALL);
        return draft;
    }

    @Test
    void berthGivenAPointIsRejected() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            UUID draft = version(c, 1, "DRAFT");
            UUID berth = asset(c, "B1", "BERTH");
            var e = assertThrows(
                    SQLException.class, () -> revision(c, berth, draft, "Berth", "POINT(101.3066304 2.9534057)"));
            assertTrue(e.getMessage().contains("must be a LineString"), e.getMessage());
        }
    }

    @Test
    void assetOutsideTheExtentIsRejected() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            UUID draft = version(c, 1, "DRAFT");
            UUID building = asset(c, "WH1", "BUILDING");
            var e = assertThrows(
                    SQLException.class,
                    () -> revision(c, building, draft, "Far", "POLYGON((10 10, 10.001 10, 10.001 10.001, 10 10))"));
            assertTrue(e.getMessage().contains("outside the port extent"), e.getMessage());
        }
    }

    @Test
    void selfIntersectingPolygonIsRejected() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            draftWithHall(c);
        }
        // A bow tie: the second and fourth corners swapped
        assertThrows(
                SQLException.class,
                () -> run("update layout.asset_revision set geom = ST_GeomFromText('POLYGON((101.31 2.95, "
                        + "101.311 2.951, 101.311 2.95, 101.31 2.951, 101.31 2.95))', 4326)"));
    }

    @Test
    void geometryWithSrid3857IsRejected() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            draftWithHall(c);
        }
        assertThrows(
                SQLException.class,
                () -> run("update layout.asset_revision set geom = ST_Transform(geom, 3857), "
                        + "anchor = ST_Transform(anchor, 3857)"));
    }

    @Test
    void duplicateAssetCodeIsRejected() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            asset(c, "WH1", "BUILDING");
            assertThrows(SQLException.class, () -> asset(c, "WH1", "BUILDING"));
        }
    }

    @Test
    void headingOf360IsRejected() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            draftWithHall(c);
        }
        assertThrows(SQLException.class, () -> run("update layout.asset_revision set heading_deg = 360"));
    }

    @Test
    void updateOfAPublishedRevisionIsRejected() throws SQLException {
        UUID draft;
        try (Connection c = dataSource.getConnection()) {
            draft = draftWithHall(c);
        }
        run("update layout.layout_version set status = 'PUBLISHED', published_by = 'x', published_at = now() "
                + "where id = '" + draft + "'");
        var e = assertThrows(SQLException.class, () -> run("update layout.asset_revision set name = 'Changed'"));
        assertTrue(e.getMessage().contains("layout version is not a draft"), e.getMessage());
    }

    @Test
    void secondDraftForAPortIsRejected() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            version(c, 1, "DRAFT");
            assertThrows(SQLException.class, () -> version(c, 2, "DRAFT"));
        }
    }

    @Test
    void plaintextPasswordIsRejected() {
        assertThrows(SQLException.class, () -> user("rules.plain", "correct horse battery staple"));
    }

    @Test
    void bcryptCost10IsRejected() {
        assertThrows(SQLException.class, () -> user("rules.cheap", BcryptUtil.bcryptHash("secret", 10)));
    }

    @Test
    void duplicateUpperCaseUsernameIsRejected() throws SQLException {
        user("rules.dup", GOOD_HASH);
        assertThrows(SQLException.class, () -> user("rules.dup", GOOD_HASH));
        assertThrows(SQLException.class, () -> user("RULES.DUP", GOOD_HASH));
    }
}
