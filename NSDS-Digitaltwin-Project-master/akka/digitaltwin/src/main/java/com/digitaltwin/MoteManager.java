package com.digitaltwin;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.OneForOneStrategy;
import akka.actor.Props;
import akka.actor.SupervisorStrategy;
import akka.japi.pf.DeciderBuilder;
import scala.concurrent.duration.Duration;

public class MoteManager extends AbstractActor {
    private final Map<Integer, ActorRef> twins = new HashMap<>();

    public static Props props() {
        return Props.create(MoteManager.class, MoteManager::new);
    }

    // One-For-One: a simulated mote crash only restarts that mote's own twin,
    // never its siblings. Restarting recreates the twin via ModeActor.props(),
    // resetting currentParent/parentHistory/periodT to a clean state.
    @Override
    public SupervisorStrategy supervisorStrategy() {
        return new OneForOneStrategy(
            -1, // unlimited retries
            Duration.create(1, TimeUnit.MINUTES),
            DeciderBuilder
                .match(MoteCrashSimulationException.class, e -> {
                    System.out.println("Manager: recovering mote " + e.moteId + " -> restarting twin actor");
                    // Mirror the twin's own recovery back onto the physical/simulated mote,
                    // via Node-RED, so the crash recovery is symmetric on both sides.
                    PhysicalNodeSync.reviveNode(e.moteId).exceptionally(err -> {
                        System.err.println("Manager: physical revive failed for mote " + e.moteId + ": " + err.getMessage());
                        return null;
                    });
                    return SupervisorStrategy.restart();
                })
                .matchAny(e -> SupervisorStrategy.escalate())
                .build()
        );
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
