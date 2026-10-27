package it.replica8.castor.core.port.domain;

/** A row of ref.data_source as the clients need it. */
public record DataSourceInfo(String code, String name, String licence, boolean mayTrace, boolean mayPersist) {}
