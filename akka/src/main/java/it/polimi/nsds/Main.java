package it.polimi.nsds;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class Main {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static ActorRef supervisor;

    public static void main(String[] args) {
        try {
            // 1. Initialize Akka Actor System
            ActorSystem system = ActorSystem.create("DigitalTwinSystem");
            supervisor = system.actorOf(SupervisorActor.props(), "supervisor");
            System.out.println("Digital Twin Actor System running.");

            // 2. Start JDK HTTP Server on port 8080
            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            
            server.createContext("/parentChanged", new ParentChangedHandler());
            server.createContext("/appMessage", new AppMessageHandler());
            server.createContext("/setPeriod", new SetPeriodHandler());
            server.createContext("/crash", new CrashHandler());
            
            server.setExecutor(null); // default executor
            server.start();
            
            System.out.println("HTTP Server listening on http://localhost:8080");
            System.out.println("Waiting for Node-RED orchestration...");

            // Keep main thread alive
            Thread.currentThread().join();

        } catch (Exception e) {
            System.err.println("Failed to start Digital Twin System: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String responseText) throws IOException {
        byte[] responseBytes = responseText.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    // Handler for: POST /parentChanged
    private static class ParentChangedHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                JsonNode json = objectMapper.readTree(body);

                String nodeId = json.get("nodeId").asText();
                String newParent = json.get("newParent").asText();
                String oldParent = json.has("oldParent") ? json.get("oldParent").asText() : "";

                System.out.println("HTTP: Received parent changed event for node " + nodeId);
                supervisor.tell(new Messages.ParentChangedMsg(nodeId, newParent, oldParent), ActorRef.noSender());

                sendResponse(exchange, 200, "{\"status\":\"success\",\"message\":\"Parent change event routed\"}");
            } catch (Exception e) {
                System.err.println("Error processing parentChanged: " + e.getMessage());
                sendResponse(exchange, 400, "{\"error\":\"Invalid request payload: " + e.getMessage() + "\"}");
            }
        }
    }

    // Handler for: POST /appMessage
    private static class AppMessageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                JsonNode json = objectMapper.readTree(body);

                String nodeId = json.get("nodeId").asText();
                long seq = json.get("seq").asLong();

                System.out.println("HTTP: Received app message event for node " + nodeId + ", seq " + seq);
                supervisor.tell(new Messages.AppMessageMsg(nodeId, seq), ActorRef.noSender());

                sendResponse(exchange, 200, "{\"status\":\"success\",\"message\":\"App message event routed\"}");
            } catch (Exception e) {
                System.err.println("Error processing appMessage: " + e.getMessage());
                sendResponse(exchange, 400, "{\"error\":\"Invalid request payload: " + e.getMessage() + "\"}");
            }
        }
    }

    // Handler for: POST /setPeriod
    private static class SetPeriodHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                JsonNode json = objectMapper.readTree(body);

                String nodeId = json.get("nodeId").asText();
                int newPeriod = json.get("newPeriod").asInt();

                System.out.println("HTTP: Received set period event for node " + nodeId + " to " + newPeriod + "s");
                supervisor.tell(new Messages.SetPeriodMsg(nodeId, newPeriod), ActorRef.noSender());

                sendResponse(exchange, 200, "{\"status\":\"success\",\"message\":\"Period update command routed\"}");
            } catch (Exception e) {
                System.err.println("Error processing setPeriod: " + e.getMessage());
                sendResponse(exchange, 400, "{\"error\":\"Invalid request payload: " + e.getMessage() + "\"}");
            }
        }
    }

    // Handler for: POST /crash
    private static class CrashHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
                return;
            }
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                JsonNode json = objectMapper.readTree(body);

                String nodeId = json.get("nodeId").asText();

                System.out.println("HTTP: Received crash event for node " + nodeId);
                supervisor.tell(new Messages.CrashMsg(nodeId), ActorRef.noSender());

                sendResponse(exchange, 200, "{\"status\":\"success\",\"message\":\"Crash event routed to actor\"}");
            } catch (Exception e) {
                System.err.println("Error processing crash: " + e.getMessage());
                sendResponse(exchange, 400, "{\"error\":\"Invalid request payload: " + e.getMessage() + "\"}");
            }
        }
    }
}
