package com.djavafactory.castor.core.identity.infrastructure;

import java.util.HashSet;
import java.util.Set;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import jakarta.persistence.EntityManager;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

import com.djavafactory.castor.core.identity.domain.DemoUser;
import com.djavafactory.castor.core.identity.domain.Role;
import com.djavafactory.castor.core.shared.application.DataSeedStep;
import com.djavafactory.castor.core.shared.infrastructure.CastorConfig;

/**
 * Makes sure there is a first admin and, where demo login is active, the demo users. Safe to run on every start:
 * it only creates what is missing and never changes an existing user.
 */
@ApplicationScoped
public class AccountBootstrap implements DataSeedStep {

    static final int MIN_ADMIN_PASSWORD_LENGTH = 14;
    private static final Logger LOG = Logger.getLogger(AccountBootstrap.class);

    private final EntityManager em;
    private final CastorConfig castor;

    public AccountBootstrap(EntityManager em, CastorConfig castor) {
        this.em = em;
        this.castor = castor;
    }

    void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_AFTER + 100) StartupEvent event) {
        ensureAccounts();
    }

    /** Called by the reset hook after the schemas are recreated. Creates no assets. */
    @Override
    public int seed() {
        ensureAccounts();
        return 0;
    }

    void ensureAccounts() {
        if (!castor.security().bootstrapAccounts()) {
            return;
        }
        QuarkusTransaction.requiringNew().run(() -> {
            // Demo users first: where demo login is on, the demo admin is the first admin
            if (castor.demoLoginActive()) {
                DemoUser.ALL.forEach(this::ensureDemoUser);
            }
            ensureAdmin();
        });
    }

    private void ensureDemoUser(DemoUser demo) {
        if (!exists(demo.username())) {
            create(demo.username(), demo.label(), demo.username(), demo.roles());
            LOG.infof("Demo user %s created", demo.username());
        }
    }

    private void ensureAdmin() {
        Long admins = em.createQuery("select count(u) from UserAccount u join u.roles r where r = :admin", Long.class)
                .setParameter("admin", Role.ADMIN)
                .getSingleResult();
        if (admins > 0) {
            return;
        }
        String username = castor.admin().username().toLowerCase();
        String password = castor.admin().password();
        if (password.length() < MIN_ADMIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("No ADMIN user exists and CASTOR_ADMIN_PASSWORD has fewer than "
                    + MIN_ADMIN_PASSWORD_LENGTH + " characters, so the first admin cannot be created");
        }
        if (exists(username)) {
            throw new IllegalStateException("No ADMIN user exists but the user '" + username
                    + "' does, so CASTOR_ADMIN_USERNAME cannot be created; choose another name");
        }
        create(username, "Administrator", password, Set.of(Role.ADMIN, Role.PUBLISHER, Role.EDITOR, Role.VIEWER));
        LOG.infof("First admin '%s' created from CASTOR_ADMIN_USERNAME", username);
    }

    private boolean exists(String username) {
        return em.createQuery("select count(u) from UserAccount u where lower(u.username) = :name", Long.class)
                        .setParameter("name", username.toLowerCase())
                        .getSingleResult()
                > 0;
    }

    private void create(String username, String displayName, String password, Set<Role> roles) {
        var account = new UserAccount();
        account.username = username;
        account.displayName = displayName;
        account.passwordHash = BcryptUtil.bcryptHash(password, DatabaseIdentityProvider.BCRYPT_COST);
        account.roles = new HashSet<>(roles);
        em.persist(account);
    }
}
