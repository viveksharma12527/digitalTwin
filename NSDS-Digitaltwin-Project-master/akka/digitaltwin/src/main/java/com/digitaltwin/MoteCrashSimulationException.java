package com.digitaltwin;

/**
 * Thrown by {@link ModeActor} when it processes a {@link MoteMessages.MoteCrashed}
 * message, so that the actor's supervisor can distinguish an expected, simulated
 * mote crash (which should trigger a restart) from an unexpected bug.
 */
public class MoteCrashSimulationException extends RuntimeException {
    public final int moteId;

    public MoteCrashSimulationException(int moteId) {
        super("Simulated crash for mote " + moteId);
        this.moteId = moteId;
    }
}
