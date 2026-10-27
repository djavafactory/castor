package it.replica8.castor.core.port.application;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import it.replica8.castor.core.port.domain.ReferenceKind;
import it.replica8.castor.core.shared.domain.InvalidRequestException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GetReferenceFeaturesTest {

    @Test
    void noKindMeansAllKinds() {
        assertEquals(EnumSet.allOf(ReferenceKind.class), GetReferenceFeatures.parseKinds(null));
        assertEquals(EnumSet.allOf(ReferenceKind.class), GetReferenceFeatures.parseKinds(" "));
    }

    @Test
    void parsesACommaSeparatedListIgnoringCaseAndSpaces() {
        assertEquals(
                Set.of(ReferenceKind.BUILDING, ReferenceKind.ROAD), GetReferenceFeatures.parseKinds("building, Road"));
    }

    @Test
    void unknownKindIsRefused() {
        var e = assertThrows(InvalidRequestException.class, () -> GetReferenceFeatures.parseKinds("BUILDING,CASTLE"));
        assertEquals("Unknown kind 'CASTLE'", e.getMessage());
    }
}
