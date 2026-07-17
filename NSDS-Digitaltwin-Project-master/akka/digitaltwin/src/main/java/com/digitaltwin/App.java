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

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            actorSystem.terminate();
            shutdownLatch.countDown();
        }));

        shutdownLatch.await();
    }
}