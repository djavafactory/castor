package it.replica8.castor.core.identity.application;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import it.replica8.castor.core.identity.domain.AuthOutcome;
import it.replica8.castor.core.identity.domain.DemoUser;
import it.replica8.castor.core.identity.domain.UserRepository;
import it.replica8.castor.core.identity.domain.UserSummary;
import it.replica8.castor.core.shared.infrastructure.CastorConfig;

/** The identity use cases the API calls: who am I, which demo users exist, sign out. */
@ApplicationScoped
public class IdentityUseCases {

    private final UserRepository users;
    private final AuthEvents events;
    private final CastorConfig castor;

    public IdentityUseCases(UserRepository users, AuthEvents events, CastorConfig castor) {
        this.users = users;
        this.events = events;
        this.castor = castor;
    }

    /** The signed-in user as the client shows it, or null when the account no longer exists. */
    public UserSummary currentUser(String username) {
        return users.findByUsername(username).orElse(null);
    }

    /** Empty when demo login is off, so the endpoint answers 404. */
    public List<DemoUser> demoUsers() {
        return castor.demoLoginActive() ? DemoUser.ALL : List.of();
    }

    public void logout(String username, String remoteIp, String userAgent) {
        events.record(username, AuthOutcome.LOGOUT, remoteIp, userAgent);
    }
}
