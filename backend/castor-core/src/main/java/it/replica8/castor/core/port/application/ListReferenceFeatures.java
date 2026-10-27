package it.replica8.castor.core.port.application;

import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import it.replica8.castor.core.port.domain.PortRepository;
import it.replica8.castor.core.port.domain.ReferenceFeature;
import it.replica8.castor.core.port.domain.ReferenceFeatureRepository;
import it.replica8.castor.core.shared.domain.ResourceNotFoundException;
import it.replica8.castor.core.shared.domain.RuleViolation;
import it.replica8.castor.core.shared.domain.RuleViolationException;

/** Use case: the reference features of one kind in a port. */
@ApplicationScoped
public class ListReferenceFeatures {

    static final Set<String> KINDS = Set.of("BUILDING", "ROAD");

    private final PortRepository ports;
    private final ReferenceFeatureRepository features;

    public ListReferenceFeatures(PortRepository ports, ReferenceFeatureRepository features) {
        this.ports = ports;
        this.features = features;
    }

    public List<ReferenceFeature> execute(String portCode, String kind) {
        if (!KINDS.contains(kind)) {
            throw new RuleViolationException(
                    "Unknown reference feature kind",
                    List.of(new RuleViolation("INVALID_KIND", null, "/kind", "Kind must be one of " + KINDS)));
        }
        if (ports.findByCode(portCode).isEmpty()) {
            throw new ResourceNotFoundException("Unknown port " + portCode);
        }
        return features.findByKind(portCode, kind);
    }
}
