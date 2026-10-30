package com.djavafactory.castor.gateway.shared.infrastructure;

import java.util.concurrent.atomic.AtomicReference;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import com.djavafactory.castor.gateway.shared.domain.FailPoint;
import com.djavafactory.castor.gateway.shared.domain.FailPoints;

/**
 * The fail points of this process. One may be armed at start by {@code castor.fail-point} (CASTOR_FAIL_POINT, a way to
 * rehearse a crash on the dev stack) or at any time by a test. {@code ConfigGuard} refuses the setting in a protected
 * environment, and {@link #arm} refuses there too.
 */
@ApplicationScoped
public class ConfiguredFailPoints implements FailPoints {

    private static final Logger LOG = Logger.getLogger(ConfiguredFailPoints.class);

    private final CastorConfig castor;
    private final AtomicReference<FailPoint> armed = new AtomicReference<>();

    public ConfiguredFailPoints(CastorConfig castor) {
        this.castor = castor;
        castor.failPoint().flatMap(FailPoint::fromCode).ifPresent(armed::set);
    }

    @Override
    public void check(FailPoint point) {
        if (armed.get() != point) {
            return;
        }
        if ("halt".equals(castor.failPointMode())) {
            LOG.errorf("Fail point %s: halting the process", point.code());
            Runtime.getRuntime().halt(137);
        }
        throw new FailPointException(point);
    }

    public void arm(FailPoint point) {
        if (!CastorConfig.KNOWN_ENVIRONMENTS.contains(castor.env())) {
            throw new IllegalStateException("Fail points are only allowed in local, dev or test");
        }
        armed.set(point);
    }

    public void disarm() {
        armed.set(null);
    }
}
