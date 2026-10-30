package com.djavafactory.castor.gateway.mapping.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.JsonNode;
import org.jboss.logging.Logger;

import com.djavafactory.castor.gateway.mapping.application.LoadMappings;
import com.djavafactory.castor.gateway.mapping.domain.MappingSeed;
import com.djavafactory.castor.gateway.shared.application.StartupStep;
import com.djavafactory.castor.gateway.shared.domain.Json;
import com.djavafactory.castor.gateway.shared.infrastructure.CastorConfig;

/**
 * Local, dev and test: at every start and after every reset, loads the mapping seed files of
 * {@code seed/identity-mappings} (the classpath folder lists its files in {@code index.txt}, because a folder inside a jar
 * cannot be listed). When castor-core has not created the view or published a layout yet the step stays pending and
 * {@link MappingSeedJob} tries again until it succeeds; the dev stack starts the two services in any order.
 */
@ApplicationScoped
public class MappingSeedStep implements StartupStep {

    private static final Logger LOG = Logger.getLogger(MappingSeedStep.class);
    private static final String FOLDER = "seed/identity-mappings/";

    private final CastorConfig castor;
    private final LoadMappings loader;
    private final AtomicBoolean pending = new AtomicBoolean();

    public MappingSeedStep(CastorConfig castor, LoadMappings loader) {
        this.castor = castor;
        this.loader = loader;
    }

    @Override
    public void run() {
        if (!CastorConfig.KNOWN_ENVIRONMENTS.contains(castor.env())) {
            return;
        }
        try {
            pending.set(loader.load(castor.portCode(), seeds()).pending());
        } catch (RuntimeException e) {
            pending.set(true);
            LOG.warn("Identity mapping seed failed, trying again later", e);
        }
    }

    /** Runs the step again if the last run could not finish. */
    void retryIfPending() {
        if (pending.get()) {
            run();
        }
    }

    private static List<MappingSeed> seeds() {
        List<MappingSeed> seeds = new ArrayList<>();
        for (String file : read(FOLDER + "index.txt").split("\\R")) {
            if (file.isBlank()) {
                continue;
            }
            try {
                for (JsonNode node : Json.parse(read(FOLDER + file.strip()))) {
                    seeds.add(new MappingSeed(
                            text(node, "sourceSystem"),
                            text(node, "sourceEntityType"),
                            text(node, "sourceId"),
                            text(node, "assetCode"),
                            text(node, "canonicalId")));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Seed file " + file + " is not valid JSON", e);
            }
        }
        return seeds;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String read(String resource) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
