package it.replica8.castor.core.port.infrastructure;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;

import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

import it.replica8.castor.core.port.domain.DataSourceCode;
import it.replica8.castor.core.shared.application.DataSeedStep;
import it.replica8.castor.core.shared.infrastructure.CastorConfig;

/**
 * Loads the committed OpenStreetMap file of the configured port at start and after a dev reset. The import is
 * idempotent and skipped when the file is unchanged, so every start is safe. A missing file or a failed import is
 * logged, not fatal: the map shows no background until the data is fixed.
 */
@ApplicationScoped
public class ReferenceSeed implements DataSeedStep {

    private static final Logger LOG = Logger.getLogger(ReferenceSeed.class);

    private final OsmImportJob job;
    private final CastorConfig castor;

    public ReferenceSeed(OsmImportJob job, CastorConfig castor) {
        this.job = job;
        this.castor = castor;
    }

    void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_AFTER + 50) StartupEvent event) {
        importFile();
    }

    /** Called by the reset hook after the schemas are recreated. Creates no layout assets. */
    @Override
    public int seed() {
        importFile();
        return 0;
    }

    private void importFile() {
        if (!castor.reference().importOnStart()) {
            return;
        }
        String resource = "seed/" + castor.portCode() + "/osm-reference.geojson";
        if (!OsmImportJob.exists(resource)) {
            LOG.warnf("Reference file %s not found, no OpenStreetMap background is loaded", resource);
            return;
        }
        try {
            job.run(castor.portCode(), DataSourceCode.OSM, resource, true);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Reference import of %s failed, the OpenStreetMap background is not loaded", resource);
        }
    }
}
