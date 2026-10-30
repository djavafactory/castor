package com.djavafactory.castor.core.identity.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface UserRepository {

    Optional<UserSummary> findByUsername(String username);

    /** The roles of an enabled user; empty when the user is unknown or disabled. */
    Set<Role> activeRoles(String username);

    /** Enabled users with the PUBLISHER or ADMIN role, by username. */
    List<UserSummary> findReviewers();
}
