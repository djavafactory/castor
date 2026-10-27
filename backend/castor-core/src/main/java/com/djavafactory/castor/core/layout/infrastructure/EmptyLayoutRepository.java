package com.djavafactory.castor.core.layout.infrastructure;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.layout.domain.LayoutRepository;
import com.djavafactory.castor.core.layout.domain.LayoutVersion;

/** Placeholder adapter until the persistence ticket adds the real one. */
@ApplicationScoped
public class EmptyLayoutRepository implements LayoutRepository {

    @Override
    public List<LayoutVersion> findLatestVersions() {
        return List.of();
    }
}
