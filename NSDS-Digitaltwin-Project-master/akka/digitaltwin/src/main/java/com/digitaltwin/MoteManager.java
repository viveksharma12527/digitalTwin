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
    //
    // Deciding whether/when to revive the physical or simulated mote is
    // coordination logic that belongs in Node-RED (per the project's design
    // constraint), not here: this decider only resets the twin's own state.
    // Node-RED independently notices the crash (it's the one that reported it
    // via POST /crash in the first place) and drives the physical revive,
    // later confirming it back to Akka via POST /revived.
    @Override
    public SupervisorStrategy supervisorStrategy() {
        return new OneForOneStrategy(
            -1, // unlimited retries
            Duration.create(1, TimeUnit.MINUTES),
            DeciderBuilder
                .match(MoteCrashSimulationException.class, e -> {
                    System.out.println("Manager: recovering mote " + e.moteId + " -> restarting twin actor");
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
            .match(MoteMessages.MoteRevived.class, this::routeMessage)
            .build();
    }

    private void routeMessage(MoteMessages.Command msg) {
        int moteId = msg.moteId();

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

}
