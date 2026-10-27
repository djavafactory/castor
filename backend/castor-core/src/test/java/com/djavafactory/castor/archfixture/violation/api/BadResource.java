package com.djavafactory.castor.archfixture.violation.api;

import com.djavafactory.castor.archfixture.violation.infrastructure.BadAdapter;

/** Deliberate violation: an api class that uses infrastructure directly. */
public class BadResource {

    public String name() {
        return new BadAdapter().name();
    }
}
