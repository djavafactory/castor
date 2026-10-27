package com.djavafactory.castor.core.identity.infrastructure;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.djavafactory.castor.core.identity.domain.Role;

/** A row of security.user_account with its roles from security.user_role. */
@Entity
@Table(schema = "security", name = "user_account")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 64)
    public String username;

    @Column(name = "display_name", nullable = false, length = 120)
    public String displayName;

    @Column(name = "password_hash", nullable = false, length = 100)
    public String passwordHash;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "failed_logins", nullable = false)
    public short failedLogins;

    @Column(name = "locked_until")
    public Instant lockedUntil;

    @Column(name = "password_changed_at", nullable = false)
    public Instant passwordChangedAt = Instant.now();

    @Column(name = "last_login_at")
    public Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Version
    @Column(name = "row_version", nullable = false)
    public long rowVersion;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(schema = "security", name = "user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    public Set<Role> roles = new HashSet<>();
}
