package com.djavafactory.castor.core.support;

import java.util.List;

import jakarta.persistence.OptimisticLockException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import com.djavafactory.castor.core.shared.domain.DraftAlreadyExistsException;
import com.djavafactory.castor.core.shared.domain.MissingIfMatchException;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;
import com.djavafactory.castor.core.shared.domain.RuleViolation;
import com.djavafactory.castor.core.shared.domain.RuleViolationException;

/** Endpoints that raise each kind of error, so the mappers can be checked before real endpoints exist. */
@Path("/test-problems")
public class ProblemTestResource {

    public record Sample(@NotBlank String name) {}

    @POST
    @Path("/validation")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String validation(@Valid Sample sample) {
        return sample.name();
    }

    @GET
    @Path("/not-found")
    public String notFound() {
        throw new ResourceNotFoundException("Layout 9c1 does not exist");
    }

    @GET
    @Path("/draft-exists")
    public String draftExists() {
        throw new DraftAlreadyExistsException();
    }

    @GET
    @Path("/optimistic-lock")
    public String optimisticLock() {
        throw new OptimisticLockException("org.hibernate.StaleObjectStateException: row layout.asset id=1");
    }

    @GET
    @Path("/missing-if-match")
    public String missingIfMatch() {
        throw new MissingIfMatchException();
    }

    @GET
    @Path("/rule")
    public String rule() {
        throw new RuleViolationException(
                "Invalid asset",
                List.of(new RuleViolation(
                        "OVERLAP", "5f0c1b1e-0000-4000-8000-000000000001", "/geometry", "Overlaps Warehouse A")));
    }

    @GET
    @Path("/boom")
    public String boom() {
        throw new IllegalStateException("select * from layout.asset failed at com.djavafactory.Secret");
    }

    @POST
    @Path("/echo")
    @Consumes({MediaType.APPLICATION_JSON, "application/geo+json"})
    @Produces(MediaType.TEXT_PLAIN)
    public String echo(String body) {
        return String.valueOf(body.length());
    }
}
