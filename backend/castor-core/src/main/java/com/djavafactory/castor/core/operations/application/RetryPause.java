package com.djavafactory.castor.core.operations.application;

import java.time.Duration;

/** Waits between two attempts of the projector. Its own interface so no business code sleeps (R8). */
public interface RetryPause {

    void pause(Duration duration);
}
