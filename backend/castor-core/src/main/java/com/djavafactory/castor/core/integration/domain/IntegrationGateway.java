package com.djavafactory.castor.core.integration.domain;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The admin API of the ingest gateway as castor-core uses it (rule R7: browsers never reach the gateway, core does, with
 * the service token). Paths are relative to the gateway's {@code /internal/v1/ports/{portId}}. Answers are the gateway's
 * JSON as it is: core adds access control and the audit trail, not another data model.
 */
public interface IntegrationGateway {

    /** GET; {@code query} entries with a null or blank value are left out. */
    JsonNode get(String portId, String path, Map<String, String> query);

    /** POST with a JSON body, null for none. */
    JsonNode post(String portId, String path, JsonNode body);

    JsonNode delete(String portId, String path);
}
