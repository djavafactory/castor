package com.djavafactory.castor.core.identity.application;

import com.djavafactory.castor.core.identity.domain.AuthOutcome;

/** Port: records one row per login attempt and logout. */
public interface AuthEvents {

    void record(String username, AuthOutcome outcome, String remoteIp, String userAgent);
}
