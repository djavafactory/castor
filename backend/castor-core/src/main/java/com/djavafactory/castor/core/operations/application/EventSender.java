package com.djavafactory.castor.core.operations.application;

import java.util.Map;

/** Sends one record to Kafka and returns when the broker has it, or throws when it could not be sent. */
public interface EventSender {

    void send(String topic, String key, byte[] value, Map<String, String> headers);
}
