package com.djavafactory.castor.core.layout.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.djavafactory.castor.core.layout.domain.LayoutVersion;

/** The JSON snapshot of a version row that goes into the audit trail. */
final class LayoutSnapshots {

    private static final ObjectMapper JSON = new ObjectMapper();

    static ObjectNode version(LayoutVersion v) {
        ObjectNode n = JSON.createObjectNode();
        n.put("id", v.id().toString());
        n.put("portCode", v.portCode());
        n.put("versionNo", v.versionNo());
        n.put("status", v.status().name());
        n.put(
                "basedOnVersionId",
                v.basedOnVersionId() == null ? null : v.basedOnVersionId().toString());
        n.put("basedOnVersionNo", v.basedOnVersionNo());
        n.put("note", v.note());
        n.put("createdBy", v.createdBy());
        n.put("createdAt", v.createdAt().toString());
        n.put("publishedBy", v.publishedBy());
        n.put("publishedAt", v.publishedAt() == null ? null : v.publishedAt().toString());
        return n;
    }

    private LayoutSnapshots() {}
}
