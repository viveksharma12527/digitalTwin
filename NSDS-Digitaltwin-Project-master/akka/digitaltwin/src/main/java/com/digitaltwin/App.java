package com.digitaltwin;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;

public class App{
    public static void main(String[] args) {
        System.out.println("Hello World!");


        ActorSystem actorSystem = ActorSystem.create("DigitalTwinActorSystem");
        ActorRef moteManager = actorSystem.actorOf(MoteManager.props(), "moteManager");

        DigitalTwinServer server = new DigitalTwinServer(moteManager);
        server.startHttpServer(actorSystem);
        // Terminate the actor system
        System.out.println("Press ENTER to terminate...");

        try {
            // This line stops the 'main' thread from finishing.
            // It will wait here until you press Enter in the console.
            System.in.read(); 
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // Cleanly shut down the system when you exit
            actorSystem.terminate();
        }
    }
}