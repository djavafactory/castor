package com.djavafactory.castor.core.identity.domain;

import java.util.List;
import java.util.Set;

/** The demo accounts of local and dev. The password of each is its username. */
public record DemoUser(String username, String label, Set<Role> roles) {

    public static final List<DemoUser> ALL = List.of(
            new DemoUser("admin", "Admin: full access", Set.of(Role.ADMIN, Role.PUBLISHER, Role.EDITOR, Role.VIEWER)),
            new DemoUser(
                    "publisher",
                    "Publisher: publishes and restores layouts",
                    Set.of(Role.PUBLISHER, Role.EDITOR, Role.VIEWER)),
            new DemoUser("editor", "Editor: edits layout drafts", Set.of(Role.EDITOR, Role.VIEWER)),
            new DemoUser("viewer", "Viewer: monitor only", Set.of(Role.VIEWER)));
}
