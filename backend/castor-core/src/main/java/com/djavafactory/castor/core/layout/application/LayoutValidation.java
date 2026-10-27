package com.djavafactory.castor.core.layout.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.geometry.application.GeoJsonMapper;
import com.djavafactory.castor.core.geometry.domain.QuayFrame;
import com.djavafactory.castor.core.layout.domain.AssetType;
import com.djavafactory.castor.core.layout.domain.AssetTypeRepository;
import com.djavafactory.castor.core.layout.domain.LayoutAsset;
import com.djavafactory.castor.core.layout.domain.LayoutRepository;
import com.djavafactory.castor.core.layout.domain.LayoutStatus;
import com.djavafactory.castor.core.layout.domain.LayoutValidator;
import com.djavafactory.castor.core.layout.domain.LayoutVersion;
import com.djavafactory.castor.core.layout.domain.PlacementFacts;
import com.djavafactory.castor.core.layout.domain.Site;
import com.djavafactory.castor.core.layout.domain.ValidationIssue;
import com.djavafactory.castor.core.layout.domain.ValidationReport;
import com.djavafactory.castor.core.port.domain.DataSourceInfo;
import com.djavafactory.castor.core.port.domain.Port;
import com.djavafactory.castor.core.port.domain.PortRepository;
import com.djavafactory.castor.core.shared.domain.ConflictException;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;
import com.djavafactory.castor.core.shared.domain.RuleViolation;
import com.djavafactory.castor.core.shared.domain.RuleViolationException;

/**
 * Use cases of the rule catalogue: the full list of issues of a version (read-only, no audit row) and the check every
 * single-asset save has to pass. Both run {@link LayoutValidator}, so a shape is judged the same way wherever it is
 * checked.
 */
@ApplicationScoped
public class LayoutValidation {

    private final LayoutRepository layouts;
    private final AssetTypeRepository types;
    private final PortRepository ports;
    private final PlacementFacts facts;
    private final GeoJsonMapper geo;

    public LayoutValidation(
            LayoutRepository layouts,
            AssetTypeRepository types,
            PortRepository ports,
            PlacementFacts facts,
            GeoJsonMapper geo) {
        this.layouts = layouts;
        this.types = types;
        this.ports = ports;
        this.facts = facts;
        this.geo = geo;
    }

    /** Every error and warning of the version, as the Issues list and the publish check show them. */
    public ValidationReport layout(UUID layoutId) {
        LayoutVersion version = layouts.findVersion(layoutId)
                .orElseThrow(() -> new ResourceNotFoundException("No layout version " + layoutId));
        return validator(version.portCode()).validateLayout(version);
    }

    /** Checks one shape against the draft without storing anything; warnings are in the report too. */
    public ValidationReport asset(UUID draftId, LayoutAsset candidate) {
        LayoutVersion draft = layouts.findVersion(draftId)
                .orElseThrow(() -> new ResourceNotFoundException("No layout version " + draftId));
        if (draft.status() != LayoutStatus.DRAFT) {
            throw new ConflictException("Only a draft can be changed");
        }
        return validator(draft.portCode()).validateAsset(draft, candidate);
    }

    /**
     * The gate of a single-asset save: any error refuses the save with a 422 that lists the issues, nothing is stored.
     *
     * @return the warnings, which are returned with the saved asset
     * @throws RuleViolationException when the candidate has an error
     */
    public List<ValidationIssue> requireSavable(UUID draftId, LayoutAsset candidate) {
        ValidationReport report = asset(draftId, candidate);
        if (report.hasErrors()) {
            throw new RuleViolationException(
                    "The shape breaks layout rules",
                    report.errors().stream().map(LayoutValidation::violation).toList());
        }
        return report.warnings();
    }

    /** An issue as a 422 error item: the first asset it is about and the field to look at. */
    static RuleViolation violation(ValidationIssue issue) {
        String asset = issue.assetIds().isEmpty() ? null : issue.assetIds().get(0);
        return new RuleViolation(issue.code(), asset, pointer(issue.code()), issue.message());
    }

    private static String pointer(String code) {
        return switch (code) {
            case "UNKNOWN_TYPE" -> "/properties/type";
            case "TRACE_SOURCE_NOT_ALLOWED" -> "/properties/provenance/source";
            case "DIM_OUT_OF_RANGE" -> "/properties";
            default -> "/geometry";
        };
    }

    private LayoutValidator validator(String portCode) {
        Port port = ports.findByCode(portCode).orElseThrow(() -> new ResourceNotFoundException("No port " + portCode));
        var frame = new QuayFrame(port.origin().lon(), port.origin().lat(), port.frameRotDeg());
        var site = new Site(
                frame,
                geo.read(port.extent()),
                port.landExtent() == null ? null : geo.read(port.landExtent()),
                port.quayLine() == null ? null : geo.read(port.quayLine()));
        var byCode = types.findAll().stream().collect(Collectors.toMap(AssetType::code, t -> t));
        return new LayoutValidator(layouts, byCode, site, untraceable(ports.findDataSources()), facts);
    }

    /** A source that may be neither traced nor stored is a lookup only (Google Maps): nothing may come from it. */
    private static Set<String> untraceable(List<DataSourceInfo> sources) {
        return sources.stream()
                .filter(s -> !s.mayTrace() && !s.mayPersist())
                .map(DataSourceInfo::code)
                .collect(Collectors.toSet());
    }
}
