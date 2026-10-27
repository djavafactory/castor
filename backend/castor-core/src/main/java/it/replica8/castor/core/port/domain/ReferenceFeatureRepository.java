package it.replica8.castor.core.port.domain;

import java.util.List;

public interface ReferenceFeatureRepository {

    /** Every feature of one kind in the port, ordered by OpenStreetMap reference so the answer is stable. */
    List<ReferenceFeature> findByKind(String portCode, String kind);
}
