package com.djavafactory.castor.gateway.support;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import static io.restassured.RestAssured.given;

/** What the tests of the internal admin API share: the service token and the paths. */
public abstract class AdminSupport extends MappingSupport {

    protected static final String ADMIN = "/internal/v1/ports/westports";

    @ConfigProperty(name = "castor.admin.service-token")
    protected String serviceToken;

    /** A request as castor-core makes it. */
    protected RequestSpecification admin() {
        return given().header("Authorization", "Bearer " + serviceToken).contentType(ContentType.JSON);
    }

    /** A GPS reading of a sensor; its sourceEventId is {@link #gpsEventId}. */
    protected static String gps(String sensorId, String ts) {
        return """
                {"sensorId":"%s","kind":"GPS","values":{"lon":101.3,"lat":2.98,"accuracyM":2.5},"ts":"%s"}""".formatted(sensorId, ts);
    }

    protected static String gpsEventId(String sensorId, String ts) {
        return sensorId + ":GPS:" + ts;
    }

    /** Posts a GPS reading of a sensor nobody mapped and normalizes it: it lands in the quarantine. */
    protected String quarantinedGps(String sensorId, String ts) {
        String receptionId = accepted("sensors", gps(sensorId, ts));
        normalizer.runOnce();
        return receptionId;
    }

    protected Response replay(String receptionId) {
        return admin().post(ADMIN + "/quarantine/" + receptionId + "/replay");
    }

    protected Response mapSensor(String sensorId, String canonicalId) {
        return admin().body("""
                        {"sourceSystem":"sensors","sourceEntityType":"EQUIPMENT","sourceId":"%s","canonicalId":"%s","actor":"admin"}""".formatted(sensorId, canonicalId)).post(ADMIN + "/mappings");
    }
}
