package it.replica8.castor.core.identity.infrastructure;

import java.sql.SQLException;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** The CHECK constraints and unique index of security.user_account. */
@QuarkusTest
class SecurityTablesIT {

    @Inject
    DataSource dataSource;

    private void insert(String username, String hash) throws SQLException {
        try (var c = dataSource.getConnection();
                var s = c.prepareStatement(
                        "insert into security.user_account (username, display_name, password_hash) values (?, ?, ?)")) {
            s.setString(1, username);
            s.setString(2, "Test");
            s.setString(3, hash);
            s.executeUpdate();
        }
    }

    @Test
    void plaintextPasswordIsRejected() {
        assertThrows(SQLException.class, () -> insert("plain.user", "correct horse battery staple"));
    }

    @Test
    void bcryptCost10IsRejected() {
        assertThrows(SQLException.class, () -> insert("cheap.user", BcryptUtil.bcryptHash("secret", 10)));
    }

    @Test
    void upperCaseOrDuplicateUsernameIsRejected() throws SQLException {
        String hash = BcryptUtil.bcryptHash("secret", 12);
        insert("dup.user", hash);

        assertThrows(SQLException.class, () -> insert("dup.user", hash));
        assertThrows(SQLException.class, () -> insert("DUP.USER", hash));
    }

    @Test
    void auditLogAcceptsAnEntry() throws SQLException {
        try (var c = dataSource.getConnection();
                var s = c.createStatement()) {
            s.executeUpdate("insert into audit.audit_log (actor, action, entity_kind, after) "
                    + "values ('admin', 'PUBLISH', 'LAYOUT_VERSION', '{\"n\":1}'::jsonb)");
        }
    }
}
