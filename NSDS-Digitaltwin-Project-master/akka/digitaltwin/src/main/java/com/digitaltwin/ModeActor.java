package com.digitaltwin;

import java.util.LinkedList;

import akka.actor.AbstractActor;
import akka.actor.Props;


public class ModeActor extends AbstractActor{

    private int currentParent = 0;
    private int periodT = 5;
    private final int K = 3;
    private final LinkedList<Integer> parentHistory = new LinkedList<>();

    public static Props props() {
        return Props.create(ModeActor.class, ModeActor::new);
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
            .match(MoteMessages.ParentChanged.class, msg -> {
                if (this.currentParent != 0 && msg.newParentId != this.currentParent) {
                    parentHistory.addFirst(this.currentParent);
                    if (parentHistory.size() > K) {
                        parentHistory.removeLast();
                    }
                }
                this.currentParent = msg.newParentId;
                System.out.println("Parent changed to: " + this.currentParent);
            })
            .match(MoteMessages.AppTrafficReceived.class, msg -> {

                System.out.println("App traffic received for mote: " + msg.moteId);
            })
            .match(MoteMessages.UpdatePeriodT.class, msg -> {
                this.periodT = msg.newT;
                System.out.println("Twin period T updated to: " + this.periodT + " for mote " + msg.moteId);
            })
            .match(MoteMessages.MoteCrashed.class, msg -> {
                System.err.println("CRASH! Node " + msg.moteId + " has crashed, mirroring in Digital Twin.");
                throw new MoteCrashSimulationException(msg.moteId);
            })
            .match(MoteMessages.MoteRevived.class, msg -> {
                System.out.println("Node " + msg.moteId + " revived.");
            })
            .build();
    }

    @Override
    public void preStart() {
        System.out.println("ModeActor started");
    }
    @Override
    public void postStop() {
        System.out.println("ModeActor stopped");
        }
}
