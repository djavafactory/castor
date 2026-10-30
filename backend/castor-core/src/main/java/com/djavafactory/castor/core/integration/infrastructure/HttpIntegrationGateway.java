package com.djavafactory.castor.core.integration.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import com.djavafactory.castor.core.integration.domain.IntegrationGateway;
import com.djavafactory.castor.core.shared.domain.GatewayRefusedException;
import com.djavafactory.castor.core.shared.domain.GatewayUnavailableException;
import com.djavafactory.castor.core.shared.infrastructure.CastorConfig;

/**
 * Calls the gateway admin API over HTTP with the service token as a bearer token. A 404, 409, 422 or 400 of the gateway
 * is a {@link GatewayRefusedException} with its code; anything else that is not a success (a refused token included, which
 * is a configuration error) and every network failure is a {@link GatewayUnavailableException}. Bodies, which can hold a
 * new API key, are never logged.
 */
@ApplicationScoped
public class HttpIntegrationGateway implements IntegrationGateway {

    private static final Logger LOG = Logger.getLogger(HttpIntegrationGateway.class);
    private static final String BASE_PATH = "/internal/v1/ports/";

    private final HttpClient client;
    private final ObjectMapper json;
    private final String baseUrl;
    private final String token;
    private final Duration timeout;

    public HttpIntegrationGateway(CastorConfig castor, ObjectMapper json) {
        CastorConfig.Gateway gateway = castor.gateway();
        this.json = json;
        this.baseUrl = gateway.url().replaceAll("/+$", "");
        this.token = gateway.serviceToken().orElse("");
        this.timeout = gateway.timeout();
        this.client = HttpClient.newBuilder()
                .connectTimeout(gateway.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public JsonNode get(String portId, String path, Map<String, String> query) {
        StringBuilder url = new StringBuilder(url(portId, path));
        char separator = '?';
        for (var entry : query.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                url.append(separator)
                        .append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
                separator = '&';
            }
        }
        return send(request(url.toString()).GET().build());
    }

    @Override
    public JsonNode post(String portId, String path, JsonNode body) {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString());
        return send(request(url(portId, path))
                .header("Content-Type", "application/json")
                .POST(publisher)
                .build());
    }

    @Override
    public JsonNode delete(String portId, String path) {
        return send(request(url(portId, path)).DELETE().build());
    }

    private String url(String portId, String path) {
        return baseUrl + BASE_PATH + URLEncoder.encode(portId, StandardCharsets.UTF_8) + path;
    }

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
    }

    private JsonNode send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GatewayUnavailableException("The ingest gateway cannot be reached", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayUnavailableException("The call to the ingest gateway was interrupted", e);
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return parse(response.body());
        }
        if (status == 400 || status == 404 || status == 409 || status == 422) {
            throw refusal(status, response.body());
        }
        if (status == 401) {
            LOG.errorf(
                    "The ingest gateway refused the service token (%s %s): check CASTOR_SERVICE_TOKEN on both services",
                    request.method(), request.uri().getRawPath());
        } else {
            LOG.errorf(
                    "The ingest gateway answered %d to %s %s",
                    status, request.method(), request.uri().getRawPath());
        }
        throw new GatewayUnavailableException("The ingest gateway did not accept the request");
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return json.nullNode();
        }
        try {
            return json.readTree(body);
        } catch (JsonProcessingException e) {
            throw new GatewayUnavailableException("The ingest gateway answered something that is not JSON", e);
        }
    }

    private GatewayRefusedException refusal(int status, String body) {
        String code = null;
        String message = "The ingest gateway refused the request";
        try {
            JsonNode problem = json.readTree(body);
            code = problem.path("errors").path(0).path("code").asText(null);
            message = problem.path("detail").asText(message);
        } catch (JsonProcessingException | RuntimeException ignored) {
            // the body is not Problem Details: the status alone says what happened
        }
        return new GatewayRefusedException(status, code, message);
    }
}
