package it.replica8.castor.core.shared.application;

import jakarta.enterprise.context.ApplicationScoped;

import it.replica8.castor.core.shared.infrastructure.CastorConfig;

/** Which build is running: the version and commit stamped at build time, and the environment it runs in. */
@ApplicationScoped
public class ReadVersion {

    public record BuildInfo(String version, String commit, String buildTime, String environment) {}

    private final CastorConfig castor;

    public ReadVersion(CastorConfig castor) {
        this.castor = castor;
    }

    public BuildInfo execute() {
        var build = castor.build();
        return new BuildInfo(build.version(), build.commit(), build.time(), castor.env());
    }
}
