package it.replica8.castor.core.port.domain;

/** Reads the lookup tables of schema ref. */
public interface CatalogueRepository {

    Catalogue load();
}
