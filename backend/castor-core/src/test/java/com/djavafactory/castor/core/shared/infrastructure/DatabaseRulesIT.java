package com.djavafactory.castor.core.shared.infrastructure;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Database rule suite: one failing case per rule, each expecting the database itself (constraint or trigger) to
 * reject the statement. The tables are created by the schema tickets that follow SETUP-07; until then V1 holds only
 * extensions and schemas, so every case is disabled with the migration that enables it. Enable a case when its table
 * exists: insert a row that breaks the rule through the superuser connection and assert the SQLException.
 */
class DatabaseRulesIT {

    private static final String PENDING = "Needs the layout schema migration; enable together with it";

    @Test
    @Disabled(PENDING)
    void berthGivenAPointIsRejected() {}

    @Test
    @Disabled(PENDING)
    void assetOutsideTheExtentIsRejected() {}

    @Test
    @Disabled(PENDING)
    void selfIntersectingPolygonIsRejected() {}

    @Test
    @Disabled(PENDING)
    void geometryWithSrid3857IsRejected() {}

    @Test
    @Disabled(PENDING)
    void duplicateAssetCodeIsRejected() {}

    @Test
    @Disabled(PENDING)
    void headingOf360IsRejected() {}

    @Test
    @Disabled(PENDING)
    void updateOfAPublishedRevisionIsRejected() {}

    @Test
    @Disabled(PENDING)
    void secondDraftForAPortIsRejected() {}

    @Test
    @Disabled("Needs the security schema migration; enable together with it")
    void plaintextPasswordIsRejected() {}

    @Test
    @Disabled("Needs the security schema migration; enable together with it")
    void bcryptCost10IsRejected() {}

    @Test
    @Disabled("Needs the security schema migration; enable together with it")
    void duplicateUpperCaseUsernameIsRejected() {}
}
