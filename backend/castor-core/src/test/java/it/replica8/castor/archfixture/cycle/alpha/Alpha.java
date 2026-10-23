package it.replica8.castor.archfixture.cycle.alpha;

import it.replica8.castor.archfixture.cycle.beta.Beta;

/** Deliberate violation: alpha and beta depend on each other. */
public class Alpha {

    public Beta beta() {
        return new Beta();
    }
}
