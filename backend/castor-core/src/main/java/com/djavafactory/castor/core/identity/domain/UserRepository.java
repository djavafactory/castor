package com.djavafactory.castor.core.identity.domain;

import java.util.List;
import java.util.Optional;

public interface UserRepository {

    Optional<UserSummary> findByUsername(String username);

    /** Enabled users with the PUBLISHER or ADMIN role, by username. */
    List<UserSummary> findReviewers();
}
