package com.djavafactory.castor.core.audit.domain;

/** What changed in a layout; the list is the vocabulary of audit.audit_log.action. */
public enum AuditAction {
    ASSET_CREATED,
    ASSET_UPDATED,
    ASSET_DELETED,
    DRAFT_CREATED,
    DRAFT_DISCARDED,
    LAYOUT_PUBLISHED,
    LAYOUT_RESTORED,
    LAYOUT_IMPORTED,
    OVERRIDE_RECORDED
}
