package it.replica8.castor.archfixture.violation.api;

import it.replica8.castor.archfixture.violation.infrastructure.BadAdapter;

/** Deliberate violation: an api class that uses infrastructure directly. */
public class BadResource {

    public String name() {
        return new BadAdapter().name();
    }
}
