package com.djavafactory.castor.core.shared.application;

import java.time.Duration;

/** Business metrics the use cases report. The adapter decides where they go. */
public interface OperationMetrics {

    void validationFinished(Duration duration);

    void layoutPublished();

    void importFinished();

    void spatialQueryFinished(Duration duration);
}
