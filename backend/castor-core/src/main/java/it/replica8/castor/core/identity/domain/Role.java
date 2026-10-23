package it.replica8.castor.core.identity.domain;

/** What a user may do. Business code checks these names with @RolesAllowed only. */
public enum Role {
    VIEWER,
    EDITOR,
    PUBLISHER,
    ADMIN
}
