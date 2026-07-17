package com.digitaltwin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import akka.http.javadsl.model.sse.ServerSentEvent;

/**
 * Maps internal {@link MoteMessages.Command} payloads to the JSON shape
 * expected by frontend/main.js (type + fields).
 */
public final class DashboardEvents {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DashboardEvents() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class Payload {
        public final String type;
        public final int moteId;
        public final Integer newParentId;
        public final Integer newT;

        public Payload(String type, int moteId, Integer newParentId, Integer newT) {
            this.type = type;
            this.moteId = moteId;
            this.newParentId = newParentId;
            this.newT = newT;
        }
    }

    public static ServerSentEvent toServerSentEvent(MoteMessages.Command msg) {
        try {
            return ServerSentEvent.create(MAPPER.writeValueAsString(toPayload(msg)));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize dashboard event", e);
        }
    }

    public static Payload toPayload(MoteMessages.Command msg) {
        if (msg instanceof MoteMessages.ParentChanged) {
            MoteMessages.ParentChanged m = (MoteMessages.ParentChanged) msg;
            return new Payload("PARENT_CHANGED", m.moteId, m.newParentId, null);
        }
        if (msg instanceof MoteMessages.AppTrafficReceived) {
            MoteMessages.AppTrafficReceived m = (MoteMessages.AppTrafficReceived) msg;
            return new Payload("TRAFFIC", m.moteId, null, null);
        }
        if (msg instanceof MoteMessages.UpdatePeriodT) {
            MoteMessages.UpdatePeriodT m = (MoteMessages.UpdatePeriodT) msg;
            return new Payload("PERIOD_UPDATED", m.moteId, null, m.newT);
        }
        if (msg instanceof MoteMessages.MoteCrashed) {
            MoteMessages.MoteCrashed m = (MoteMessages.MoteCrashed) msg;
            return new Payload("CRASH", m.moteId, null, null);
        }
        if (msg instanceof MoteMessages.MoteRevived) {
            MoteMessages.MoteRevived m = (MoteMessages.MoteRevived) msg;
            return new Payload("REVIVED", m.moteId, null, null);
        }
        throw new IllegalArgumentException("Unknown command: " + msg.getClass());
    }
}
