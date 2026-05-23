package it.polimi.nsds;

import akka.actor.AbstractActor;
import akka.actor.Props;
import java.util.ArrayList;
import java.util.List;

public class IoTNodeActor extends AbstractActor {

    private final String nodeId;
    private String currentParent = "";
    private final List<String> lastKParents = new ArrayList<>();
    private int messagePeriod;
    private static final int K = 3;

    public IoTNodeActor(String nodeId, int initialPeriod) {
        this.nodeId = nodeId;
        this.messagePeriod = initialPeriod;
    }

    public static Props props(String nodeId, int period) {
        return Props.create(IoTNodeActor.class, nodeId, period);
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
            .match(Messages.ParentChangedMsg.class, this::onParentChanged)
            .match(Messages.AppMessageMsg.class,    this::onAppMessage)
            .match(Messages.SetPeriodMsg.class,     this::onSetPeriod)
            .match(Messages.CrashMsg.class,         this::onCrash)
            .build();
    }

    private void onParentChanged(Messages.ParentChangedMsg msg) {
        System.out.println("[" + nodeId + "] Parent: " + currentParent + " → " + msg.newParent);
        if (!currentParent.isEmpty()) {
            lastKParents.add(0, currentParent);
            if (lastKParents.size() > K) lastKParents.remove(lastKParents.size() - 1);
        }
        currentParent = msg.newParent;
    }

    private void onAppMessage(Messages.AppMessageMsg msg) {
        System.out.println("[" + nodeId + "] App message mimicked. seq=" + msg.seq);
    }

    private void onSetPeriod(Messages.SetPeriodMsg msg) {
        System.out.println("[" + nodeId + "] Period: " + messagePeriod + " → " + msg.newPeriod);
        messagePeriod = msg.newPeriod;
    }

    private void onCrash(Messages.CrashMsg msg) {
        System.out.println("[" + nodeId + "] CRASH!");
        throw new RuntimeException("Node " + nodeId + " crashed!");
    }
}
