package com.djavafactory.castor.core.port.application;

import java.util.EnumSet;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.port.domain.BoundingBox;
import com.djavafactory.castor.core.port.domain.PortRepository;
import com.djavafactory.castor.core.port.domain.ReferenceKind;
import com.djavafactory.castor.core.shared.domain.InvalidRequestException;

/** Use case: background features (OpenStreetMap) of a port, filtered by kind and box. */
@ApplicationScoped
public class GetReferenceFeatures {

    private final PortRepository repository;

    public GetReferenceFeatures(PortRepository repository) {
        this.repository = repository;
    }

    /** @param kinds comma separated kinds, null or blank for all; @param bbox "minLon,minLat,maxLon,maxLat" or null */
    public String execute(String portCode, String kinds, String bbox) {
        long portId = PortLookup.require(repository, portCode).id();
        BoundingBox box = bbox == null || bbox.isBlank() ? null : BoundingBox.parse(bbox);
        return repository.referenceFeaturesGeoJson(portId, parseKinds(kinds), box);
    }

    static Set<ReferenceKind> parseKinds(String kinds) {
        if (kinds == null || kinds.isBlank()) {
            return EnumSet.allOf(ReferenceKind.class);
        }
        Set<ReferenceKind> result = EnumSet.noneOf(ReferenceKind.class);
        for (String part : kinds.split(",")) {
            try {
                result.add(ReferenceKind.valueOf(part.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestException("Unknown kind '" + part.trim() + "'");
            }
        }
        return result;
    }
}
