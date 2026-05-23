package it.polimi.nsds;

import akka.actor.*;
import akka.japi.pf.DeciderBuilder;
import java.util.HashMap;
import java.util.Map;

public class SupervisorActor extends AbstractActor {

    private final Map<String, ActorRef> nodeActors = new HashMap<>();

    public static Props props() { return Props.create(SupervisorActor.class); }

    @Override
    public SupervisorStrategy supervisorStrategy() {
        return new OneForOneStrategy(
            DeciderBuilder
                .match(RuntimeException.class, e -> {
                    System.out.println("Supervisor: restarting crashed actor due to: " + e.getMessage());
                    return SupervisorStrategy.restart();
                })
                .matchAny(e -> SupervisorStrategy.escalate())
                .build()
        );
    }

    @Override
    public void preStart() {
        for (int i = 1; i <= 3; i++) {
            String id = "node" + i;
            nodeActors.put(id, getContext().actorOf(IoTNodeActor.props(id, 5), id));
            System.out.println("Digital twin created: " + id);
        }
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
            .match(Messages.ParentChangedMsg.class, msg -> forward(msg.nodeId, msg))
            .match(Messages.AppMessageMsg.class,    msg -> forward(msg.nodeId, msg))
            .match(Messages.SetPeriodMsg.class,     msg -> forward(msg.nodeId, msg))
            .match(Messages.CrashMsg.class,         msg -> forward(msg.nodeId, msg))
            .build();
    }

    private void forward(String nodeId, Object msg) {
        // Automatically map node numbers (e.g. "1") or full names (e.g. "node1") correctly
        String targetId = nodeId.startsWith("node") ? nodeId : "node" + nodeId;
        ActorRef actor = nodeActors.get(targetId);
        if (actor != null) {
            actor.tell(msg, getSelf());
        } else {
            System.err.println("Supervisor: node actor not found for ID: " + targetId + " (original: " + nodeId + ")");
        }
    }
}
