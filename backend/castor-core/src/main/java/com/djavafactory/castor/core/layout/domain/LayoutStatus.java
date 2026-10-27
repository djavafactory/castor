package com.djavafactory.castor.core.layout.domain;

/** Life cycle of a layout version; the list equals the CHECK of layout.layout_version.status. */
public enum LayoutStatus {
    DRAFT,
    PUBLISHED,
    SUPERSEDED,
    DISCARDED
}
