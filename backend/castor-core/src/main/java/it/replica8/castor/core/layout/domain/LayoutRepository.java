package it.replica8.castor.core.layout.domain;

import java.util.List;

/** What the layout use cases need from storage. Implemented in infrastructure. */
public interface LayoutRepository {

    List<LayoutVersion> findLatestVersions();
}
