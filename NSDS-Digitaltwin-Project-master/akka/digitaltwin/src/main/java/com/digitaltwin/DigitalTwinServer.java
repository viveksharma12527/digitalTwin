package com.digitaltwin;

import java.util.Arrays;

import akka.NotUsed;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.marshalling.sse.EventStreamMarshalling;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntities;
import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.javadsl.model.sse.ServerSentEvent;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.japi.Pair;
import akka.stream.Materializer;
import akka.stream.OverflowStrategy;
import akka.stream.QueueOfferResult;
import akka.stream.SystemMaterializer;
import akka.stream.javadsl.BroadcastHub;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.SourceQueueWithComplete;

public class DigitalTwinServer extends AllDirectives {

    private static final Iterable<HttpHeader> CORS_HEADERS = Arrays.asList(
        RawHeader.create("Access-Control-Allow-Origin", "*"),
        RawHeader.create("Access-Control-Allow-Methods", "GET, POST, OPTIONS"),
        RawHeader.create("Access-Control-Allow-Headers", "Content-Type")
    );

    private final ActorRef manager;
    private Source<ServerSentEvent, NotUsed> sseBroadcast;
    private SourceQueueWithComplete<ServerSentEvent> sseQueue;

    public DigitalTwinServer(ActorRef manager) {
        this.manager = manager;
    }

    public void startHttpServer(ActorSystem system) {
        final Http http = Http.get(system);
        final Materializer mat = SystemMaterializer.get(system).materializer();
        initSseBroadcast(mat);

        final Route route = concat(
            path("events", () -> withCors(
                get(() -> completeOK(sseBroadcast, EventStreamMarshalling.toEventStream()))
            )),

            path("parent", () -> withCors(
                post(() ->
                    entity(Jackson.unmarshaller(MoteMessages.ParentChanged.class), msg -> {
                        manager.tell(msg, ActorRef.noSender());
                        broadcast(msg);
                        return complete("Parent changed");
                    })
                )
            )),

            path("traffic", () -> withCors(
                post(() ->
                    entity(Jackson.unmarshaller(MoteMessages.AppTrafficReceived.class), msg -> {
                        manager.tell(msg, ActorRef.noSender());
                        broadcast(msg);
                        return complete("Traffic mimicked");
                    })
                )
            )),

            path("updateT", () -> withCors(
                post(() ->
                    entity(Jackson.unmarshaller(MoteMessages.UpdatePeriodT.class), msg ->
                        onComplete(
                            PhysicalNodeSync.syncPeriodT(msg.moteId, msg.newT),
                            result -> {
                                if (result.isSuccess()) {
                                    manager.tell(msg, ActorRef.noSender());
                                    broadcast(msg);
                                    return complete("Period T synced to physical node and twin");
                                }
                                Throwable err = result.failed().get();
                                String detail = err.getMessage() != null ? err.getMessage() : err.toString();
                                System.err.println("updateT rejected for mote " + msg.moteId + ": " + detail);
                                return complete(
                                    StatusCodes.BAD_GATEWAY,
                                    HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, "Physical sync failed: " + detail)
                                );
                            }
                        )
                    )
                )
            )),

            path("crash", () -> withCors(
                post(() ->
                    entity(Jackson.unmarshaller(MoteMessages.MoteCrashed.class), msg -> {
                        manager.tell(msg, ActorRef.noSender());
                        broadcast(msg);
                        return complete("Crash handled");
                    })
                )
            )),

            // Called by Node-RED once it has told the physical/simulated mote to
            // resume, after Node-RED itself decided to revive it (see MoteManager's
            // supervisorStrategy comment on why that decision doesn't live in Akka).
            path("revived", () -> withCors(
                post(() ->
                    entity(Jackson.unmarshaller(MoteMessages.MoteRevived.class), msg -> {
                        manager.tell(msg, ActorRef.noSender());
                        broadcast(msg);
                        return complete("Revive handled");
                    })
                )
            ))
        );

        http.newServerAt("localhost", 8080).bind(route);
        System.out.println("Server online at http://localhost:8080/");
        System.out.println("SSE dashboard stream: GET http://localhost:8080/events");
    }

    /**
     * OPTIONS must be handled on the same path segment (e.g. /updateT), otherwise
     * Akka HTTP rejects preflight when the path is not fully consumed.
     */
    private Route withCors(Route inner) {
        return respondWithDefaultHeaders(CORS_HEADERS, () ->
            concat(
                options(() -> complete(StatusCodes.OK)),
                inner
            )
        );
    }

    private void initSseBroadcast(Materializer mat) {
        Pair<SourceQueueWithComplete<ServerSentEvent>, Source<ServerSentEvent, NotUsed>> pair =
            Source.<ServerSentEvent>queue(256, OverflowStrategy.dropHead())
                .toMat(BroadcastHub.of(ServerSentEvent.class), Keep.both())
                .run(mat);
        sseQueue = pair.first();
        sseBroadcast = pair.second();
    }

    private void broadcast(MoteMessages.Command msg) {
        sseQueue.offer(DashboardEvents.toServerSentEvent(msg))
            .thenAccept(result -> {
                if (!result.equals(QueueOfferResult.enqueued())) {
                    System.err.println("SSE offer result: " + result + " for " + msg.getClass().getSimpleName());
                }
            });
    }
}
