package com.djavafactory.castor.core.identity.domain;

import java.util.Optional;

public interface UserRepository {

    Optional<UserSummary> findByUsername(String username);
}
