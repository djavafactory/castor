package com.djavafactory.castor.core.identity.domain;

/** Announced when a user signs out, so whatever was kept open for the user (a live stream) can be closed. */
public record UserSignedOut(String username) {}
