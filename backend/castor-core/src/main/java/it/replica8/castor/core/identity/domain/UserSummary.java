package it.replica8.castor.core.identity.domain;

import java.util.Set;

public record UserSummary(String username, String displayName, Set<Role> roles) {}
