package com.djavafactory.castor.core.layout.domain;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.locationtech.jts.geom.Geometry;

/**
 * The spatial facts the placement rules need from the stored layout. Implemented in infrastructure with PostGIS, so
 * the GiST index on the geometries finds the neighbours and areas are measured in the metric CRS of the port.
 */
public interface PlacementFacts {

    /** The only type that forbids other types inside it; its allowed types are listed in its details. */
    String EXCLUSION_ZONE = "EXCLUSION_ZONE";

    /** Yard blocks: the shapes that warn when they sit on a road. */
    Set<String> BLOCK_TYPES = Set.of("STORAGE_PAD", "CONTAINER_YARD");

    Set<String> ROAD_TYPES = Set.of("ROAD_SEGMENT", "INTERNAL_ROAD", "JUNCTION");

    /** The shape about to be saved. {@code id} is null for a new asset. */
    record Candidate(UUID id, String typeCode, Geometry geom) {}

    /**
     * One shape touching another one that matters for a rule: solid with solid, anything with an exclusion zone, a
     * storage block with a road.
     *
     * @param overlapM2 area shared by two polygons, 0 when either is a line
     * @param crossing false when the two only touch along an edge or at a point
     * @param allowedTypes the types the other shape allows when it is an exclusion zone, otherwise empty
     */
    record Contact(
            UUID subjectId,
            UUID otherId,
            String otherName,
            String otherTypeCode,
            double overlapM2,
            boolean crossing,
            List<String> allowedTypes) {}

    /** A berth of the version, as a line along the quay. */
    record Berth(UUID id, String name, Geometry line) {}

    /**
     * The contacts of the candidate with the other shapes of the version, or of every shape of the version when the
     * candidate is null. Contacts are listed from the point of view of the subject, so a pair appears twice.
     */
    List<Contact> contacts(UUID layoutVersionId, Candidate candidate);

    /** Every berth of the version. */
    List<Berth> berths(UUID layoutVersionId);
}
