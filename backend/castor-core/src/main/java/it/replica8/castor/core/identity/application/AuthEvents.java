package it.replica8.castor.core.identity.application;

import it.replica8.castor.core.identity.domain.AuthOutcome;

/** Port: records one row per login attempt and logout. */
public interface AuthEvents {

    void record(String username, AuthOutcome outcome, String remoteIp, String userAgent);
}
