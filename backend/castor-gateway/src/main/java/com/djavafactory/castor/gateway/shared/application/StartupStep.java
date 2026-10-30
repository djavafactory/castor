package com.djavafactory.castor.gateway.shared.application;

/**
 * A step run once the schema is migrated, at every start and after every reset (the demo keys are one). A step decides
 * for itself whether it applies in this environment and must be safe to run again.
 */
public interface StartupStep {

    void run();
}
