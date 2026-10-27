package com.djavafactory.castor.core.search.application;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.layout.domain.LayoutRepository;
import com.djavafactory.castor.core.layout.domain.LayoutStatus;
import com.djavafactory.castor.core.layout.domain.LayoutVersion;
import com.djavafactory.castor.core.port.domain.Port;
import com.djavafactory.castor.core.port.domain.PortRepository;
import com.djavafactory.castor.core.search.domain.PlaceHit;
import com.djavafactory.castor.core.search.domain.PlaceSearch;
import com.djavafactory.castor.core.shared.domain.InvalidRequestException;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/**
 * Use case: find the port, its control points, the assets of one layout and the OpenStreetMap shapes by name or code.
 * Everything is searched in the port's own data; the text is never stored and never leaves the service.
 */
@ApplicationScoped
public class SearchPlaces {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_RESULTS = 12;
    /** More than this is not a name or a code. */
    static final int MAX_LENGTH = 100;

    private final PortRepository ports;
    private final LayoutRepository layouts;
    private final PlaceSearch search;

    public SearchPlaces(PortRepository ports, LayoutRepository layouts, PlaceSearch search) {
        this.ports = ports;
        this.layouts = layouts;
        this.search = search;
    }

    /**
     * @param layoutId the version whose assets are searched; the published version when null
     * @param mayReadDrafts whether the caller may see versions that are not published
     */
    public List<PlaceHit> execute(String portCode, String q, UUID layoutId, boolean mayReadDrafts) {
        String text = q == null ? "" : q.strip();
        if (text.codePointCount(0, text.length()) < MIN_LENGTH) {
            throw new InvalidRequestException("q must be at least " + MIN_LENGTH + " characters");
        }
        if (text.length() > MAX_LENGTH) {
            throw new InvalidRequestException("q must be at most " + MAX_LENGTH + " characters");
        }
        Port port = ports.findByCode(portCode.toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException("No port with code '" + portCode + "'"));
        UUID searched =
                layoutId == null ? port.publishedLayoutVersionId() : requireVersion(port, layoutId, mayReadDrafts);
        return search.find(port.id(), searched, text.toLowerCase(Locale.ROOT), MAX_RESULTS);
    }

    /** A version of this port the caller may see; anything else is "not found" so drafts are not revealed. */
    private UUID requireVersion(Port port, UUID layoutId, boolean mayReadDrafts) {
        LayoutVersion version = layouts.findVersion(layoutId)
                .filter(v -> v.portCode().equals(port.code()))
                .orElseThrow(() -> new ResourceNotFoundException("No layout version " + layoutId));
        boolean open = version.status() == LayoutStatus.PUBLISHED || version.status() == LayoutStatus.SUPERSEDED;
        if (!open && !mayReadDrafts) {
            throw new ResourceNotFoundException("No layout version " + layoutId);
        }
        return version.id();
    }
}
