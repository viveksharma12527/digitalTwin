package com.digitaltwin;
import java.util.concurrent.CountDownLatch;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;

public class App{
    public static void main(String[] args) throws InterruptedException {
        System.out.println("Hello World!");

        ActorSystem actorSystem = ActorSystem.create("DigitalTwinActorSystem");
        ActorRef moteManager = actorSystem.actorOf(MoteManager.props(), "moteManager");

        DigitalTwinServer server = new DigitalTwinServer(moteManager);
        server.startHttpServer(actorSystem);

        // Block the main thread until the JVM is asked to stop (SIGTERM/kill,
        // Ctrl-C, or run.sh's `kill -TERM`), instead of waiting on stdin --
        // run.sh backgrounds this process with no attached terminal, so a
        // stdin read would never return and the process could only be killed
        // by matching its command line rather than its real PID.
        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            actorSystem.terminate();
            shutdownLatch.countDown();
        }));

        shutdownLatch.await();
    }
}