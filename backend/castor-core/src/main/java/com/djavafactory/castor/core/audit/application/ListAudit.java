package com.djavafactory.castor.core.audit.application;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import com.djavafactory.castor.core.audit.domain.AuditEntry;
import com.djavafactory.castor.core.audit.domain.AuditFilter;
import com.djavafactory.castor.core.audit.domain.AuditRepository;
import com.djavafactory.castor.core.port.domain.PortRepository;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/** Use case: the audit trail of a port, newest first. */
@ApplicationScoped
public class ListAudit {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;

    private final AuditRepository repository;
    private final PortRepository ports;

    public ListAudit(AuditRepository repository, PortRepository ports) {
        this.repository = repository;
        this.ports = ports;
    }

    @Transactional
    public List<AuditEntry> execute(String portCode, AuditFilter filter) {
        ports.findByCode(portCode.toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException("No port with code " + portCode));
        return repository.find(portCode.toLowerCase(), filter);
    }
}
