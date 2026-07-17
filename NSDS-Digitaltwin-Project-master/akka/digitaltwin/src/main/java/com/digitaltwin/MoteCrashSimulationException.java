package com.digitaltwin;

public class MoteCrashSimulationException extends RuntimeException {
    public final int moteId;

    public MoteCrashSimulationException(int moteId) {
        super("Simulated crash for mote " + moteId);
        this.moteId = moteId;
    }
}
