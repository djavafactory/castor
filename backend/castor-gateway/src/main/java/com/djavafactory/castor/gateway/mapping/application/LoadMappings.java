package com.djavafactory.castor.gateway.mapping.application;

import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import com.djavafactory.castor.gateway.mapping.domain.MappingSeed;
import com.djavafactory.castor.gateway.mapping.domain.MappingStore;
import com.djavafactory.castor.gateway.mapping.domain.Mappings;

/**
 * The mapping loader. It writes the identity mappings of the seed files that the database does not have yet: a code is
 * resolved to the id of the asset in the current published layout of castor-core, a source id that has any row is left
 * alone (so a mapping an operator changed is never put back), and a code the layout does not have is logged and skipped.
 * Running it again inserts nothing.
 */
@ApplicationScoped
public class LoadMappings {

    private static final Logger LOG = Logger.getLogger(LoadMappings.class);

    /** Created by this loader: the author of the rows, and the start of their validity (before any event). */
    static final String CREATED_BY = "seed";

    static final Instant VALID_FROM = Instant.EPOCH;

    /**
     * @param inserted new rows
     * @param existing seeds whose source id had a row already
     * @param skipped seeds without an asset in the layout, or malformed
     * @param pending the layout is not readable yet (castor-core has not migrated or published): try again later
     */
    public record Result(int inserted, int existing, int skipped, boolean pending) {}

    private final MappingStore store;
    private final Mappings mappings;
    private final InstantSource clock;

    public LoadMappings(MappingStore store, Mappings mappings, InstantSource clock) {
        this.store = store;
        this.mappings = mappings;
        this.clock = clock;
    }

    public Result load(String portId, List<MappingSeed> seeds) {
        Map<String, String> assets;
        try {
            assets = store.publishedAssets(portId);
        } catch (MappingStore.AssetDirectoryUnavailableException e) {
            LOG.warnf("Identity mappings not loaded yet: layout.published_asset cannot be read (%s)", e.getMessage());
            return new Result(0, 0, 0, true);
        }
        if (assets.isEmpty() && seeds.stream().anyMatch(s -> s.assetCode() != null)) {
            LOG.warnf("Identity mappings not loaded yet: port %s has no published layout", portId);
            return new Result(0, 0, 0, true);
        }
        Instant now = clock.instant();
        int inserted = 0;
        int existing = 0;
        int skipped = 0;
        for (MappingSeed seed : seeds) {
            String canonicalId = canonicalId(portId, seed, assets);
            if (canonicalId == null) {
                skipped++;
            } else if (store.insertIfMissing(
                    portId,
                    seed.sourceSystem(),
                    seed.sourceEntityType(),
                    seed.sourceId(),
                    canonicalId,
                    VALID_FROM,
                    CREATED_BY,
                    now)) {
                inserted++;
            } else {
                existing++;
            }
        }
        if (inserted > 0) {
            mappings.invalidate();
        }
        LOG.infof(
                "Identity mappings of port %s: %d inserted, %d already there, %d skipped",
                portId, inserted, existing, skipped);
        return new Result(inserted, existing, skipped, false);
    }

    private static String canonicalId(String portId, MappingSeed seed, Map<String, String> assets) {
        if (!seed.wellFormed()) {
            LOG.warnf(
                    "Identity mapping skipped: %s needs a source system, entity type, source id and one of assetCode or canonicalId",
                    seed);
            return null;
        }
        if (seed.canonicalId() != null && !seed.canonicalId().isBlank()) {
            return seed.canonicalId();
        }
        String id = assets.get(seed.assetCode());
        if (id == null) {
            LOG.warnf(
                    "Identity mapping skipped: %s %s '%s' names asset code '%s', which is not in the published layout of port %s",
                    seed.sourceSystem(), seed.sourceEntityType(), seed.sourceId(), seed.assetCode(), portId);
        }
        return id;
    }
}
