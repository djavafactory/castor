package com.djavafactory.castor.core.layout.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.security.identity.SecurityIdentity;

import com.djavafactory.castor.core.layout.application.DiscardDraft;
import com.djavafactory.castor.core.layout.application.GetLayout;
import com.djavafactory.castor.core.layout.application.LayoutHistory;
import com.djavafactory.castor.core.layout.application.LayoutValidation;
import com.djavafactory.castor.core.layout.application.ListLayoutVersions;
import com.djavafactory.castor.core.layout.application.PublishLayout;
import com.djavafactory.castor.core.layout.application.RestoreLayout;
import com.djavafactory.castor.core.layout.application.SaveAsset;
import com.djavafactory.castor.core.layout.domain.IssueLevel;
import com.djavafactory.castor.core.layout.domain.LayoutStatus;
import com.djavafactory.castor.core.layout.domain.LayoutVersion;
import com.djavafactory.castor.core.shared.api.CachedJson;
import com.djavafactory.castor.core.shared.domain.InvalidRequestException;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/** Layout versions by id: the newest version of each port, any version as GeoJSON, and a single asset. */
@Path("/api/v1/layouts")
@Produces(MediaType.APPLICATION_JSON)
public class LayoutResource {

    public record LayoutVersionResponse(UUID id, String portCode, int versionNo, LayoutStatus status) {}

    /** One failed rule: code and wording of the catalogue, the assets it is about (the first is the one to select). */
    public record IssueResponse(String code, IssueLevel level, String message, List<String> assetIds) {}

    public record ValidationResponse(int errors, int warnings, List<IssueResponse> issues) {}

    /** @param note what changed, 0 to 500 characters; @param reviewedBy a publisher or admin other than the caller */
    public record PublishRequest(String note, String reviewedBy) {}

    public record PublishResponse(
            int versionNo, Instant publishedAt, String publishedBy, String reviewedBy, int assetCount) {}

    /** @param discardDraft true to throw away an open draft instead of getting a 409 */
    public record RestoreRequest(Boolean discardDraft) {}

    /** Result of a single-asset save: the feature, new ETag and any warnings. */
    public record AssetSaveResponse(ObjectNode feature, long rowVersion, List<IssueResponse> warnings) {}

    private final ListLayoutVersions listLayoutVersions;
    private final GetLayout getLayout;
    private final DiscardDraft discardDraft;
    private final LayoutValidation validation;
    private final LayoutFeatureMapper mapper;
    private final SaveAsset saveAsset;
    private final PublishLayout publishLayout;
    private final RestoreLayout restoreLayout;
    private final LayoutHistory history;
    private final CachedJson cache;
    private final ObjectMapper json;
    private final SecurityIdentity identity;

    public LayoutResource(
            ListLayoutVersions listLayoutVersions,
            GetLayout getLayout,
            DiscardDraft discardDraft,
            LayoutValidation validation,
            LayoutFeatureMapper mapper,
            SaveAsset saveAsset,
            PublishLayout publishLayout,
            RestoreLayout restoreLayout,
            LayoutHistory history,
            CachedJson cache,
            ObjectMapper json,
            SecurityIdentity identity) {
        this.listLayoutVersions = listLayoutVersions;
        this.getLayout = getLayout;
        this.discardDraft = discardDraft;
        this.validation = validation;
        this.mapper = mapper;
        this.saveAsset = saveAsset;
        this.publishLayout = publishLayout;
        this.restoreLayout = restoreLayout;
        this.history = history;
        this.cache = cache;
        this.json = json;
        this.identity = identity;
    }

    @GET
    @RolesAllowed({"VIEWER", "EDITOR", "PUBLISHER", "ADMIN"})
    public List<LayoutVersionResponse> list() {
        return listLayoutVersions.execute().stream()
                .map(v -> new LayoutVersionResponse(v.id(), v.portCode(), v.versionNo(), v.status()))
                .toList();
    }

    /** Any version of the layout. Drafts and discarded versions are visible to editors and above only. */
    @GET
    @Path("{layoutId}")
    @RolesAllowed({"VIEWER", "EDITOR", "PUBLISHER", "ADMIN"})
    public Response get(
            @PathParam("layoutId") UUID layoutId,
            @HeaderParam("If-None-Match") String ifNoneMatch,
            @Context SecurityContext security) {
        var result = getLayout.byId(layoutId);
        requireVisible(result.view().version(), security);
        return cache.tagged(ifNoneMatch, result.etag(), () -> mapper.layout(result.view()));
    }

    /**
     * Publishes a draft. Any error of the rule catalogue answers 422 "Publishing blocked: N error(s). Fix them first."
     * with the issues, and nothing changes.
     */
    @POST
    @Path("{layoutId}/publish")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"PUBLISHER", "ADMIN"})
    public PublishResponse publish(
            @PathParam("layoutId") UUID layoutId, PublishRequest request, @Context SecurityContext security) {
        var body = request == null ? new PublishRequest(null, null) : request;
        var result = publishLayout.execute(
                layoutId,
                body.note(),
                body.reviewedBy(),
                security.getUserPrincipal().getName());
        var v = result.version();
        return new PublishResponse(
                v.versionNo(), v.publishedAt(), v.publishedBy(), result.reviewedBy(), result.assetCount());
    }

    /** Loads an older version into a new draft; the asset ids stay the same. */
    @POST
    @Path("{layoutId}/restore")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"PUBLISHER", "ADMIN"})
    public Response restore(
            @PathParam("layoutId") UUID versionId,
            RestoreRequest request,
            @Context SecurityContext security,
            @Context UriInfo uri) {
        boolean discard = request != null && Boolean.TRUE.equals(request.discardDraft());
        var restored = restoreLayout.execute(
                versionId, discard, security.getUserPrincipal().getName());
        var draft = restored.draft();
        var body = mapper.layout(getLayout.byId(draft.id()).view());
        body.put(
                "message",
                "Revision " + restored.sourceVersionNo()
                        + " loaded. Asset IDs are unchanged. Save the draft or publish to keep it.");
        return Response.created(
                        uri.getBaseUriBuilder().path("api/v1/layouts/{id}").build(draft.id()))
                .entity(body)
                .build();
    }

    /**
     * What differs between this version and another: added, removed and changed assets. Without {@code against} the
     * published version of the port is the other side.
     */
    @GET
    @Path("{layoutId}/diff")
    @RolesAllowed({"VIEWER", "EDITOR", "PUBLISHER", "ADMIN"})
    public DiffResponse diff(
            @PathParam("layoutId") UUID layoutId,
            @QueryParam("against") String against,
            @Context SecurityContext security) {
        UUID other = null;
        if (against != null && !against.isBlank()) {
            try {
                other = UUID.fromString(against.strip());
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestException("against must be the id of a layout version");
            }
        }
        return DiffResponse.of(history.diff(layoutId, other, isEditor(security)));
    }

    /** Discards a draft. The rows stay for the audit trail; the next entry to Design starts a new draft. */
    @DELETE
    @Path("{layoutId}")
    @RolesAllowed({"PUBLISHER", "ADMIN"})
    public Response discard(@PathParam("layoutId") UUID layoutId) {
        discardDraft.execute(layoutId);
        return Response.noContent().build();
    }

    /**
     * Every error and warning of a version, for the Issues list. Read-only: nothing is stored and no audit row is
     * written, so it may be asked as often as needed.
     */
    @POST
    @Path("{layoutId}/validate")
    @RolesAllowed({"EDITOR", "PUBLISHER", "ADMIN"})
    public ValidationResponse validate(@PathParam("layoutId") UUID layoutId) {
        var report = validation.layout(layoutId);
        return new ValidationResponse(
                report.errors().size(),
                report.warnings().size(),
                report.issues().stream()
                        .map(i -> new IssueResponse(i.code(), i.level(), i.message(), i.assetIds()))
                        .toList());
    }

    /** One feature of one version; its ETag is the row version of the revision. */
    @GET
    @Path("{layoutId}/assets/{assetId}")
    @RolesAllowed({"VIEWER", "EDITOR", "PUBLISHER", "ADMIN"})
    public Response asset(
            @PathParam("layoutId") UUID layoutId,
            @PathParam("assetId") UUID assetId,
            @HeaderParam("If-None-Match") String ifNoneMatch,
            @Context SecurityContext security) {
        var result = getLayout.asset(layoutId, assetId);
        requireVisible(result.view().version(), security);
        var asset = result.view().assets().get(0);
        return cache.tagged(
                ifNoneMatch,
                result.etag(),
                () -> mapper.feature(asset, result.view().measures().get(asset.id())));
    }

    static boolean isEditor(SecurityContext security) {
        return security.isUserInRole("EDITOR") || security.isUserInRole("PUBLISHER") || security.isUserInRole("ADMIN");
    }

    static void requireVisible(LayoutVersion version, SecurityContext security) {
        boolean open = version.status() == LayoutStatus.PUBLISHED || version.status() == LayoutStatus.SUPERSEDED;
        boolean editor =
                security.isUserInRole("EDITOR") || security.isUserInRole("PUBLISHER") || security.isUserInRole("ADMIN");
        if (!open && !editor) {
            throw new ResourceNotFoundException("No layout version " + version.id());
        }
    }

    /**
     * Creates an asset in the draft. A body with an existing asset {@code id} re-creates an asset that has no revision
     * here (undo of a delete). Returns 201 + Location + ETag + feature + warnings.
     */
    @POST
    @Path("{layoutId}/assets")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed({"EDITOR", "PUBLISHER", "ADMIN"})
    public Response createAsset(@PathParam("layoutId") UUID layoutId, @Context UriInfo uri, JsonNode body) {
        return created(
                layoutId,
                uri,
                saveAsset.create(layoutId, identity.getPrincipal().getName(), body));
    }

    /**
     * Merge-patches an asset of the draft; If-Match carries the row version. A body with an {@code override} records
     * an exclusion-zone override instead. Returns 200 + new ETag + feature + warnings + adjustmentMm.
     */
    @PATCH
    @Path("{layoutId}/assets/{assetId}")
    @Consumes({MediaType.APPLICATION_JSON, "application/merge-patch+json"})
    @RolesAllowed({"EDITOR", "PUBLISHER", "ADMIN"})
    public Response updateAsset(
            @PathParam("layoutId") UUID layoutId,
            @PathParam("assetId") UUID assetId,
            @HeaderParam("If-Match") String ifMatch,
            JsonNode body) {
        SaveAsset.Saved saved;
        if (body != null && body.isObject() && body.has("override")) {
            JsonNode o = body.get("override");
            String reason = o != null && o.has("reason") && !o.get("reason").isNull()
                    ? o.get("reason").asText()
                    : null;
            saved = saveAsset.override(
                    layoutId, assetId, ifMatch, reason, identity.getPrincipal().getName());
        } else {
            saved = saveAsset.patch(
                    layoutId, assetId, ifMatch, identity.getPrincipal().getName(), body);
        }
        return Response.ok(answer(layoutId, saved))
                .header("ETag", etag(saved))
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    /**
     * Removes the revision of an asset from the draft (identity kept) and the links to it. Returns 204 and lists what
     * was affected in X-Castor-Dependents.
     */
    @DELETE
    @Path("{layoutId}/assets/{assetId}")
    @RolesAllowed({"EDITOR", "PUBLISHER", "ADMIN"})
    public Response deleteAsset(
            @PathParam("layoutId") UUID layoutId,
            @PathParam("assetId") UUID assetId,
            @HeaderParam("If-Match") String ifMatch) {
        var deleted = saveAsset.delete(
                layoutId, assetId, ifMatch, identity.getPrincipal().getName());
        Response.ResponseBuilder out = Response.noContent();
        if (!deleted.dependents().isEmpty()) {
            out.header(
                    "X-Castor-Dependents",
                    deleted.dependents().size() + " link(s) removed from " + String.join(", ", deleted.dependents()));
        }
        return out.build();
    }

    /** Copies an asset 20 m along local x and y with the name "<name> copy" and a new code. */
    @POST
    @Path("{layoutId}/assets/{assetId}/duplicate")
    @RolesAllowed({"EDITOR", "PUBLISHER", "ADMIN"})
    public Response duplicateAsset(
            @PathParam("layoutId") UUID layoutId, @PathParam("assetId") UUID assetId, @Context UriInfo uri) {
        return created(
                layoutId,
                uri,
                saveAsset.duplicate(layoutId, assetId, identity.getPrincipal().getName()));
    }

    private Response created(UUID layoutId, UriInfo uri, SaveAsset.Saved saved) {
        URI location = uri.getBaseUriBuilder()
                .path("api/v1/layouts/{id}/assets/{assetId}")
                .build(layoutId, saved.asset().id());
        return Response.created(location)
                .header("ETag", etag(saved))
                .type(MediaType.APPLICATION_JSON)
                .entity(answer(layoutId, saved))
                .build();
    }

    private static String etag(SaveAsset.Saved saved) {
        return "\"" + saved.asset().rowVersion() + "\"";
    }

    /** {feature, warnings, adjustmentMm}: the feature is read back so it carries the computed measures. */
    private ObjectNode answer(UUID layoutId, SaveAsset.Saved saved) {
        var result = getLayout.asset(layoutId, saved.asset().id());
        ObjectNode out = json.createObjectNode();
        out.set(
                "feature",
                mapper.feature(
                        saved.asset(),
                        result.view().measures().get(saved.asset().id())));
        ArrayNode warnings = out.putArray("warnings");
        for (var w : saved.warnings()) {
            warnings.addObject().put("code", w.code()).put("message", w.message());
        }
        out.put("adjustmentMm", saved.adjustmentMm());
        return out;
    }
}
