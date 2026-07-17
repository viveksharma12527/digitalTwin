package com.digitaltwin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class MoteMessages {
    public interface Command {
        int moteId();
    }
    public static final class ParentChanged implements Command {
        public final int moteId;
        public final int newParentId;

        @JsonCreator
        public ParentChanged(@JsonProperty("moteId") int moteId, @JsonProperty("newParentId") int newParentId) {
            this.moteId = moteId;
            this.newParentId = newParentId;
        }

        @Override public int moteId() { return moteId; }
    }

    public static final class AppTrafficReceived implements Command {
        public final int moteId;
        @JsonCreator
        public AppTrafficReceived(@JsonProperty("moteId") int moteId){
            this.moteId = moteId;
        }

        @Override public int moteId() { return moteId; }
    }
    public static final class UpdatePeriodT implements Command {
        public final int moteId;
        public final int newT;

        @JsonCreator
        public UpdatePeriodT(@JsonProperty("moteId") int moteId, @JsonProperty("newT") int newT) {
            this.moteId = moteId;
            this.newT = newT;
        }

        @Override public int moteId() { return moteId; }
    }

    public static final class MoteCrashed implements Command {
        public final int moteId;

        @JsonCreator
        public MoteCrashed(@JsonProperty("moteId") int moteId) { this.moteId = moteId; }

        @Override public int moteId() { return moteId; }
    }

    public static final class MoteRevived implements Command {
        public final int moteId;

        @JsonCreator
        public MoteRevived(@JsonProperty("moteId") int moteId) { this.moteId = moteId; }

        @Override public int moteId() { return moteId; }
    }
}
