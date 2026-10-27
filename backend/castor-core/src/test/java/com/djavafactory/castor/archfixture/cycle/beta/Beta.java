package com.djavafactory.castor.archfixture.cycle.beta;

import com.djavafactory.castor.archfixture.cycle.alpha.Alpha;

/** Deliberate violation: alpha and beta depend on each other. */
public class Beta {

    public Alpha alpha() {
        return new Alpha();
    }
}
