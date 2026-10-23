package it.replica8.castor.core.identity.infrastructure;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import org.jboss.logging.Logger;

import it.replica8.castor.core.identity.domain.AuthOutcome;
import it.replica8.castor.core.identity.domain.LockoutPolicy;
import it.replica8.castor.core.shared.infrastructure.CastorConfig;

/**
 * Checks HTTP Basic credentials against security.user_account. Wrong password, unknown user, disabled and locked
 * accounts all fail the same way (one message, comparable time) so the answer never reveals which accounts exist.
 * Every attempt writes a security.auth_event row; verified logins are cached briefly (see {@link AuthenticationCache}).
 */
@ApplicationScoped
public class DatabaseIdentityProvider implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    public static final String FAILURE_MESSAGE = "Wrong username or password";
    static final int BCRYPT_COST = 12;
    private static final Logger LOG = Logger.getLogger(DatabaseIdentityProvider.class);
    /** Verified against when the user does not exist, so unknown and known users take as long. */
    private static final String DUMMY_HASH = BcryptUtil.bcryptHash("castor-no-such-user", BCRYPT_COST);

    private final EntityManager em;
    private final JdbcAuthEvents events;
    private final AuthenticationCache cache;
    private final LockoutPolicy lockout;

    public DatabaseIdentityProvider(
            EntityManager em, JdbcAuthEvents events, AuthenticationCache cache, CastorConfig castor) {
        this.em = em;
        this.events = events;
        this.cache = cache;
        this.lockout = new LockoutPolicy(
                castor.security().lockout().maxFailures(),
                castor.security().lockout().window());
    }

    @Override
    public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
        return UsernamePasswordAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            UsernamePasswordAuthenticationRequest request, AuthenticationRequestContext context) {
        String username = request.getUsername();
        String password = new String(request.getPassword().getPassword());
        RoutingContext routing = request.getAttribute(HttpSecurityUtils.ROUTING_CONTEXT_ATTRIBUTE);
        String ip = null;
        String agent = null;
        if (routing != null) {
            ip = routing.request().remoteAddress() == null
                    ? null
                    : routing.request().remoteAddress().hostAddress();
            agent = routing.request().getHeader("User-Agent");
        }
        String remoteIp = ip;
        String userAgent = agent;
        return context.runBlocking(() -> verify(request, username, password, remoteIp, userAgent));
    }

    private SecurityIdentity verify(
            UsernamePasswordAuthenticationRequest request,
            String username,
            String password,
            String remoteIp,
            String userAgent) {
        SecurityIdentity cached = cache.get(username, password);
        if (cached != null) {
            return cached;
        }
        Instant now = Instant.now();
        Snapshot account = QuarkusTransaction.requiringNew().call(() -> load(username));

        if (account == null) {
            BcryptUtil.matches(password, DUMMY_HASH);
            QuarkusTransaction.requiringNew()
                    .run(() -> events.record(username, AuthOutcome.FAILURE, remoteIp, userAgent));
            throw new AuthenticationFailedException(FAILURE_MESSAGE);
        }
        if (LockoutPolicy.isLocked(account.lockedUntil(), now)) {
            BcryptUtil.matches(password, DUMMY_HASH);
            QuarkusTransaction.requiringNew()
                    .run(() -> events.record(username, AuthOutcome.LOCKED, remoteIp, userAgent));
            throw new AuthenticationFailedException(FAILURE_MESSAGE);
        }
        boolean matches = BcryptUtil.matches(password, account.passwordHash());
        if (!matches || !account.enabled()) {
            QuarkusTransaction.requiringNew().run(() -> recordFailure(account, remoteIp, userAgent, matches));
            cache.evict(username);
            throw new AuthenticationFailedException(FAILURE_MESSAGE);
        }
        QuarkusTransaction.requiringNew().run(() -> {
            UserAccount user = em.find(UserAccount.class, account.id());
            user.failedLogins = 0;
            user.lockedUntil = null;
            user.lastLoginAt = now;
            events.record(account.username(), AuthOutcome.SUCCESS, remoteIp, userAgent);
        });
        SecurityIdentity identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(account.username()))
                .addCredential(request.getPassword())
                .addRoles(account.roles())
                .addAttribute("displayName", account.displayName())
                .build();
        cache.put(username, password, identity);
        return identity;
    }

    private void recordFailure(Snapshot account, String remoteIp, String userAgent, boolean passwordWasRight) {
        Instant now = Instant.now();
        // A disabled account with the right password is refused without counting towards a lock
        if (passwordWasRight) {
            events.record(account.username(), AuthOutcome.FAILURE, remoteIp, userAgent);
            return;
        }
        UserAccount user = em.find(UserAccount.class, account.id());
        int count = lockout.nextCount(user.failedLogins, events.lastFailure(account.username()), now);
        user.failedLogins = (short) Math.min(count, Short.MAX_VALUE);
        if (lockout.locks(count)) {
            user.lockedUntil = lockout.lockedUntil(now);
            LOG.warnf("Account %s locked until %s after %d failed logins", account.username(), user.lockedUntil, count);
        }
        events.record(account.username(), AuthOutcome.FAILURE, remoteIp, userAgent);
    }

    private Snapshot load(String username) {
        return em.createQuery("select u from UserAccount u where lower(u.username) = :name", UserAccount.class)
                .setParameter("name", username.toLowerCase())
                .getResultStream()
                .findFirst()
                .map(u -> new Snapshot(
                        u.id,
                        u.username,
                        u.displayName,
                        u.passwordHash,
                        u.enabled,
                        u.lockedUntil,
                        u.roles.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet())))
                .orElse(null);
    }

    private record Snapshot(
            Long id,
            String username,
            String displayName,
            String passwordHash,
            boolean enabled,
            Instant lockedUntil,
            Set<String> roles) {}
}
