package com.djavafactory.castor.core.operations.application;

/** One Kafka record as the projector gets it: its place in the topic and its bytes. */
public record IncomingRecord(String topic, int partition, long offset, String key, byte[] value) {}
