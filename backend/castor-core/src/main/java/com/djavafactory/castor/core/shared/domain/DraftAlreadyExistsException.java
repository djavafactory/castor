package com.djavafactory.castor.core.shared.domain;

import java.util.UUID;

/** A layout already has an open draft. */
public class DraftAlreadyExistsException extends ConflictException {

    private final transient UUID draftId;

    public DraftAlreadyExistsException() {
        this(null);
    }

    public DraftAlreadyExistsException(UUID draftId) {
        super("A draft already exists for this layout");
        this.draftId = draftId;
    }

    /** The id of the draft that is in the way, when known. */
    public UUID draftId() {
        return draftId;
    }
}
