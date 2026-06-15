package com.digitaltwin;

import java.util.HashMap;
import java.util.Map;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;

public class MoteManager extends AbstractActor {
    private final Map<Integer, ActorRef> twins = new HashMap<>();

    public static Props props() {
        return Props.create(MoteManager.class, MoteManager::new);
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
            .match(MoteMessages.ParentChanged.class, this::routeMessage)
            .match(MoteMessages.AppTrafficReceived.class, this::routeMessage)
            .match(MoteMessages.UpdatePeriodT.class, this::routeMessage)
            .match(MoteMessages.MoteCrashed.class, this::routeMessage)
            .build();
    }

    private void routeMessage(MoteMessages.Command msg) {
        int moteId = getMoteId(msg);

        ActorRef twin = twins.get(moteId);
        if (twin == null) {
            System.out.println("Manager: Creating new twin actor for Mote " + moteId);
            // Use getContext().actorOf to create a child actor
            // This is where we use your ModeActor.props()!
            twin = getContext().actorOf(ModeActor.props(), "twin-" + moteId);
            twins.put(moteId, twin);
        }
        twin.tell(msg, getSelf());
    }

    private int getMoteId(MoteMessages.Command msg) {
        if (msg instanceof MoteMessages.ParentChanged) {
            return ((MoteMessages.ParentChanged) msg).moteId;
        } else if (msg instanceof MoteMessages.AppTrafficReceived) {
            return ((MoteMessages.AppTrafficReceived) msg).moteId;
        } else if (msg instanceof MoteMessages.UpdatePeriodT) {
            return ((MoteMessages.UpdatePeriodT) msg).moteId;
        } else if (msg instanceof MoteMessages.MoteCrashed) {
            return ((MoteMessages.MoteCrashed) msg).moteId;
        }
        //throw new IllegalArgumentException("Unknown message type: " + msg.getClass());
        return -1; // Should never reach here
    }

}
