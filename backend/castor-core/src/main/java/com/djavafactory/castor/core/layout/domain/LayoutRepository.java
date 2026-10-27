package com.djavafactory.castor.core.layout.domain;

import java.util.List;

/** What the layout use cases need from storage. Implemented in infrastructure. */
public interface LayoutRepository {

    List<LayoutVersion> findLatestVersions();
}
