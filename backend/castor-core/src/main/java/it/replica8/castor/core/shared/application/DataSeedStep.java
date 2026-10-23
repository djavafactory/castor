package it.replica8.castor.core.shared.application;

/** A step of the reset hook. Later tickets (CORE-02, CORE-04, SETUP-12) add their seed as a bean implementing this. */
public interface DataSeedStep {

    /** Loads the seed into freshly migrated schemas and returns the number of assets it created. */
    int seed();
}
