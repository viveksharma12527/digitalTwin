package com.digitaltwin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pushes period-T changes to the physical stack via Node-RED HTTP.
 * Node-RED forwards to Contiki over UDP; success is inferred from the HTTP response.
 */
public final class PhysicalNodeSync {

    private static final String SET_PARAMS_URL = "http://127.0.0.1:1880/set-params";
    private static final String REVIVE_URL = "http://127.0.0.1:1880/revive-node";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PhysicalNodeSync() {}

    public static final class SyncFailedException extends RuntimeException {
        public SyncFailedException(String message) {
            super(message);
        }
    }

    /**
     * POST /set-params and complete successfully only when Node-RED returns HTTP 200
     * with a body indicating success (e.g. {@code {"status":"ok"}}).
     */
    public static CompletableFuture<Void> syncPeriodT(int moteId, int newT) {
        String json = "{\"moteId\": " + moteId + ", \"periodT\": " + newT + "}";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(SET_PARAMS_URL))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        return HTTP_CLIENT
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .handle((response, error) -> {
                if (error != null) {
                    throw new SyncFailedException(
                        "Could not reach Node-RED at " + SET_PARAMS_URL + ": " + error.getMessage());
                }
                int code = response.statusCode();
                String body = response.body() != null ? response.body() : "";

                if (code != 200) {
                    throw new SyncFailedException(
                        "Node-RED /set-params returned HTTP " + code + ": " + body);
                }
                if (!isSuccessBody(body)) {
                    throw new SyncFailedException(
                        "Node-RED /set-params did not confirm success: " + body);
                }
                System.out.println("Physical sync OK for mote " + moteId + ", periodT=" + newT);
                return (Void) null;
            });
    }

    /**
     * POST /revive-node so the physical/simulated mote resumes operation, mirroring
     * the recovery already applied to its twin actor after a supervised restart.
     */
    public static CompletableFuture<Void> reviveNode(int moteId) {
        String json = "{\"moteId\": " + moteId + "}";

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(REVIVE_URL))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        return HTTP_CLIENT
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .handle((response, error) -> {
                if (error != null) {
                    throw new SyncFailedException(
                        "Could not reach Node-RED at " + REVIVE_URL + ": " + error.getMessage());
                }
                int code = response.statusCode();
                if (code != 200) {
                    throw new SyncFailedException(
                        "Node-RED /revive-node returned HTTP " + code + ": " + response.body());
                }
                System.out.println("Physical revive OK for mote " + moteId);
                return (Void) null;
            });
    }

    private static boolean isSuccessBody(String body) {
        if (body == null || body.trim().isEmpty()) {
            return false;
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node.has("status") && "ok".equalsIgnoreCase(node.get("status").asText())) {
                return true;
            }
            if (node.has("error")) {
                return false;
            }
        } catch (Exception ignored) {
            // fall through to string check
        }
        return body.contains("\"status\"") && body.contains("ok");
    }
}
