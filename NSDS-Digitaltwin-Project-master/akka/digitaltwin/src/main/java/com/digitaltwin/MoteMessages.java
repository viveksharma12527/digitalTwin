package com.digitaltwin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class MoteMessages {
    public interface Command {}
    // 1. Physical -> Digital: Mirroring Parent Changes
    public static final class ParentChanged implements Command {
        public final int moteId;
        public final int newParentId;
        
        @JsonCreator
        public ParentChanged(@JsonProperty("moteId") int moteId, @JsonProperty("newParentId") int newParentId) {
            this.moteId = moteId;
            this.newParentId = newParentId;
        }
    } 
    // 2. Physical -> Digital: Mimicking Message Flow (Traffic)

    public static final class AppTrafficReceived implements Command {
        public final int moteId;
        @JsonCreator
        public AppTrafficReceived(@JsonProperty("moteId") int moteId){
            this.moteId = moteId;
        }

    }
    // 3. Digital -> Physical: Synchronizing Period T
    public static final class UpdatePeriodT implements Command {
        public final int moteId;
        public final int newT;

        @JsonCreator
        public UpdatePeriodT(@JsonProperty("moteId") int moteId, @JsonProperty("newT") int newT) {
            this.moteId = moteId;
            this.newT = newT;
        }
    }

    // 4. Physical -> Digital: Mirroring Crash
    public static final class MoteCrashed implements Command {
        public final int moteId;

        @JsonCreator
        public MoteCrashed(@JsonProperty("moteId") int moteId) { this.moteId = moteId; }
    }

    
}
