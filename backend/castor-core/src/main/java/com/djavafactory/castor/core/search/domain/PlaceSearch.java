package com.djavafactory.castor.core.search.domain;

import java.util.List;
import java.util.UUID;

/** What the search use case needs from storage. Implemented in infrastructure. */
public interface PlaceSearch {

    /** Port entry label suffix; the port record has no district column, so the label is fixed here. */
    String PORT_LABEL_SUFFIX = ", Pulau Indah (layout origin)";

    /**
     * Places whose name or code contains the text, best match first. The text is already trimmed and lower-cased.
     *
     * @param layoutId the layout version whose assets are searched, null for none
     * @param limit    most results to return
     */
    List<PlaceHit> find(long portId, UUID layoutId, String text, int limit);
}
