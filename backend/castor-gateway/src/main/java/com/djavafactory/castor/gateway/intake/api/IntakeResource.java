package com.djavafactory.castor.gateway.intake.api;

import java.io.InputStream;
import java.util.List;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.smallrye.common.annotation.Blocking;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

import com.djavafactory.castor.gateway.intake.application.IntakeRequest;
import com.djavafactory.castor.gateway.intake.application.IntakeResult;
import com.djavafactory.castor.gateway.intake.application.ReceiveEvents;
import com.djavafactory.castor.gateway.intake.domain.IntakeException;

/**
 * The endpoint source systems post to. Authentication is the key in {@code X-Castor-Key}, not a user, so the resource
 * takes the raw headers and body and leaves every check, and their order, to {@link ReceiveEvents}.
 */
@Path("/api/v1/ports/{portId}/integrations/{source}/events")
@Produces(MediaType.APPLICATION_JSON)
public class IntakeResource {

    /** The answer for one record: 202 {receptionId} or 200 {receptionId, duplicate: true}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Received(String receptionId, Boolean duplicate) {}

    /** One element of the 207 answer to an array. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemAnswer(
            int index, int status, String receptionId, Boolean duplicate, List<IntakeException.Field> errors) {}

    /** The 207 answer to an array: one {@link ItemAnswer} per record, in request order. */
    public record BatchAnswer(List<ItemAnswer> items) {}

    private final ReceiveEvents receive;

    public IntakeResource(ReceiveEvents receive) {
        this.receive = receive;
    }

    @POST
    @Blocking
    @Consumes(MediaType.WILDCARD)
    @Operation(
            summary = "Send one event record, or an array of up to 500",
            description = "Authenticated with X-Castor-Key. 202 means the record is committed to the database.")
    @APIResponse(responseCode = "202", description = "Stored: {receptionId}")
    @APIResponse(responseCode = "200", description = "Already stored: {receptionId of the first, duplicate: true}")
    @APIResponse(responseCode = "207", description = "An array: one answer per record")
    public Response receive(
            @PathParam("portId") String portId,
            @PathParam("source") String source,
            @HeaderParam("X-Castor-Key") String key,
            @HeaderParam("X-Castor-Timestamp") String timestamp,
            @HeaderParam("X-Castor-Signature") String signature,
            @HeaderParam(HttpHeaders.CONTENT_LENGTH) @DefaultValue("-1") long contentLength,
            InputStream body) {
        IntakeResult result =
                receive.receive(new IntakeRequest(portId, source, key, timestamp, signature, contentLength, body));
        return switch (result) {
            case IntakeResult.Single single -> {
                var item = single.item();
                yield Response.status(item.status())
                        .entity(new Received(item.receptionId().toString(), item.duplicate() ? true : null))
                        .build();
            }
            case IntakeResult.Batch batch ->
                Response.status(207)
                        .entity(new BatchAnswer(batch.items().stream()
                                .map(IntakeResource::answer)
                                .toList()))
                        .build();
        };
    }

    private static ItemAnswer answer(IntakeResult.Item item) {
        return new ItemAnswer(
                item.index(),
                item.status(),
                item.receptionId() == null ? null : item.receptionId().toString(),
                item.duplicate() ? true : null,
                item.errors().isEmpty() ? null : item.errors());
    }
}
