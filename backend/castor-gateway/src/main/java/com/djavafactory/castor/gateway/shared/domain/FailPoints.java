package com.djavafactory.castor.gateway.shared.domain;

/** Asked by the pipeline at every {@link FailPoint}; does nothing unless that point is armed. */
public interface FailPoints {

    /** Throws {@link FailPointException} (or ends the process) when {@code point} is armed. */
    void check(FailPoint point);

    /** The simulated stop: the current run of a worker ends here, as if the process had died at this point. */
    class FailPointException extends RuntimeException {

        private final FailPoint point;

        public FailPointException(FailPoint point) {
            super("Fail point " + point.code() + " triggered");
            this.point = point;
        }

        public FailPoint point() {
            return point;
        }
    }
}
