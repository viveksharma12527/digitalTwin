package it.polimi.nsds;

public class Messages {
    public static class ParentChangedMsg {
        public final String nodeId, newParent, oldParent;
        public ParentChangedMsg(String nodeId, String newParent, String oldParent) {
            this.nodeId = nodeId;
            this.newParent = newParent;
            this.oldParent = oldParent;
        }
    }

    public static class AppMessageMsg {
        public final String nodeId;
        public final long seq;
        public AppMessageMsg(String nodeId, long seq) {
            this.nodeId = nodeId;
            this.seq = seq;
        }
    }

    public static class SetPeriodMsg {
        public final String nodeId;
        public final int newPeriod;
        public SetPeriodMsg(String nodeId, int newPeriod) {
            this.nodeId = nodeId;
            this.newPeriod = newPeriod;
        }
    }

    public static class CrashMsg {
        public final String nodeId;
        public CrashMsg(String nodeId) {
            this.nodeId = nodeId;
        }
    }
}
