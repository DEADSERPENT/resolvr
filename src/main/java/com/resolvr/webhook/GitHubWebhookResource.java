package com.resolvr.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resolvr.github.WebhookSignatureVerifier;
import com.resolvr.orchestrator.PRReviewOrchestrator;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/webhook")
@ApplicationScoped
public class GitHubWebhookResource {

    @Inject PRReviewOrchestrator orchestrator;
    @Inject WebhookSignatureVerifier verifier;

    private final ObjectMapper mapper = new ObjectMapper();

    @POST
    @Path("/github")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleWebhook(
            @HeaderParam("X-GitHub-Event") String eventType,
            @HeaderParam("X-Hub-Signature-256") String signature,
            @HeaderParam("X-GitHub-Delivery") String deliveryId,
            String rawPayload
    ) {
        // ── Signature verification ────────────────────────────────────────────
        if (!verifier.verify(rawPayload, signature)) {
            Log.warn("Webhook signature verification failed — rejected");
            return Response.status(401).entity("{\"error\":\"invalid signature\"}").build();
        }

        Log.infof("[WEBHOOK] event=%s delivery=%s", eventType, deliveryId);

        try {
            JsonNode payload = mapper.readTree(rawPayload);
            dispatch(eventType, payload);
        } catch (Exception e) {
            Log.errorf(e, "Failed to parse webhook payload");
            return Response.status(400).entity("{\"error\":\"bad payload\"}").build();
        }

        return Response.ok("{\"status\":\"queued\"}").build();
    }

    private void dispatch(String eventType, JsonNode payload) {
        switch (eventType) {
            case "pull_request_review" -> handlePRReview(payload);
            case "pull_request_review_comment" -> handleReviewComment(payload);
            case "pull_request" -> {
                // Only care about opened/synchronized events
                String action = payload.path("action").asText("");
                if ("opened".equals(action) || "synchronize".equals(action)) {
                    handlePRReview(payload);
                }
            }
            default -> Log.debugf("Ignoring event type: %s", eventType);
        }
    }

    private void handlePRReview(JsonNode payload) {
        String action = payload.path("action").asText("submitted");
        String fullName = payload.path("repository").path("full_name").asText("");
        int prNumber = payload.path("pull_request").path("number").asInt(0);

        if (prNumber == 0) {
            // pull_request_review payloads always carry review.pull_request_url,
            // e.g. https://api.github.com/repos/OWNER/REPO/pulls/42 — fall back to
            // parsing the number from it if the top-level pull_request node is absent.
            String prUrl = payload.path("review").path("pull_request_url").asText("");
            int lastSlash = prUrl.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < prUrl.length() - 1) {
                try {
                    prNumber = Integer.parseInt(prUrl.substring(lastSlash + 1));
                } catch (NumberFormatException ignored) {
                    // leave prNumber at 0 — event is dropped below with a warning
                }
            }
        }

        if (fullName.contains("/") && prNumber > 0) {
            String[] parts = fullName.split("/", 2);
            orchestrator.enqueue(parts[0], parts[1], prNumber, action);
        } else {
            Log.warnf("handlePRReview: could not resolve repo/PR number (repo=%s, action=%s) — event dropped",
                    fullName, action);
        }
    }

    private void handleReviewComment(JsonNode payload) {
        String action = payload.path("action").asText("created");
        if (!"created".equals(action)) return;

        String fullName = payload.path("repository").path("full_name").asText("");
        int prNumber = payload.path("pull_request").path("number").asInt(0);

        if (fullName.contains("/") && prNumber > 0) {
            String[] parts = fullName.split("/", 2);
            orchestrator.enqueue(parts[0], parts[1], prNumber, "review_comment");
        }
    }

    // ── Manual trigger endpoint (for testing without a real webhook) ───────────
    @POST
    @Path("/trigger")
    @Produces(MediaType.APPLICATION_JSON)
    public Response manualTrigger(
            @QueryParam("owner") String owner,
            @QueryParam("repo") String repo,
            @QueryParam("pr") int prNumber
    ) {
        if (owner == null || repo == null || prNumber <= 0) {
            return Response.status(400).entity("{\"error\":\"owner, repo, pr required\"}").build();
        }
        orchestrator.enqueue(owner, repo, prNumber, "manual");
        return Response.ok("{\"status\":\"queued\",\"queue_size\":" + orchestrator.pendingCount() + "}").build();
    }

    // ── Health / queue status ─────────────────────────────────────────────────
    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public String status() {
        return "{\"pending_events\":" + orchestrator.pendingCount() + "}";
    }
}
