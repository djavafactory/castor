package com.djavafactory.castor.core.layout.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A row of layout.asset: identity only, never deleted. */
@Entity
@Table(schema = "layout", name = "asset")
public class AssetEntity {

    @Id
    public UUID id;

    @Column(name = "port_id", nullable = false)
    public Long portId;

    @Column(nullable = false)
    public String code;

    @Column(name = "type_code", nullable = false, length = 40)
    public String typeCode;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "created_by", nullable = false, length = 64)
    public String createdBy;
}
