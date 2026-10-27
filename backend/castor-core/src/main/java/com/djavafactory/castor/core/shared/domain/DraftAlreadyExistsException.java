package com.djavafactory.castor.core.shared.domain;

/** A layout already has an open draft. */
public class DraftAlreadyExistsException extends ConflictException {

    public DraftAlreadyExistsException() {
        super("A draft already exists for this layout");
    }
}
