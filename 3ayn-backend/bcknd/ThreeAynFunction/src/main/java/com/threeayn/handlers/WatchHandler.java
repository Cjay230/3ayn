package com.threeayn.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.threeayn.util.ApiResponse;
import com.threeayn.util.RequestParser;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Consent-gated "trusted viewer" — NOT live video. The wearer opts in from
 * Settings; a caregiver with the resulting link sees a still frame refreshed
 * every few seconds. This is the safety-net fallback behind the real live-video
 * feature (Kinesis Video Streams WebRTC) — always available, nothing to fail.
 *
 * POST /watch/start { userId? }        -> { watchId }         wearer opts in
 * POST /watch/frame { watchId, image } -> { ok:true }         wearer uploads a frame
 * GET  /watch/{watchId}                -> { active, image, updatedAt } caregiver polls
 * POST /watch/stop  { watchId }        -> { ok:true }         wearer revokes access
 */
public class WatchHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final DynamoDbClient DDB = DynamoDbClient.create();
    private static final String TABLE = System.getenv().getOrDefault("WATCH_TABLE", "ThreeAynWatch");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            String path = event.getResource();
            String method = event.getHttpMethod();

            if ("GET".equalsIgnoreCase(method)) return getFrame(event);
if (path.endsWith("/start")) return start(event);
if (path.endsWith("/request")) return request(event);
if (path.endsWith("/consent")) return consent(event);
if (path.endsWith("/frame")) return frame(event);
if (path.endsWith("/stop")) return stop(event);
            return ApiResponse.error(404, "Unknown watch route");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("WatchHandler error: " + e);
            return ApiResponse.error(500, "Watch operation failed");
        }
    }

    private APIGatewayProxyResponseEvent start(APIGatewayProxyRequestEvent event) {
        RequestParser req = new RequestParser(event);
        String userId = req.field("userId", "");
        String watchId = UUID.randomUUID().toString().substring(0, 8);
String consentToken = UUID.randomUUID().toString();

Map<String, AttributeValue> item = new HashMap<>();
item.put("watchId", AttributeValue.fromS(watchId));
item.put("active", AttributeValue.fromBool(true));
item.put("consentStatus", AttributeValue.fromS("idle"));
item.put("consentToken", AttributeValue.fromS(consentToken));
item.put("userId", AttributeValue.fromS(userId));
item.put("createdAt", AttributeValue.fromS(Instant.now().toString()));

DDB.putItem(b -> b.tableName(TABLE).item(item));

return ApiResponse.success(Map.of(
    "watchId", watchId,
    "consentToken", consentToken
));
    }
private APIGatewayProxyResponseEvent request(APIGatewayProxyRequestEvent event) {
    RequestParser req = new RequestParser(event);
    String watchId = req.requiredField("watchId");

    GetItemResponse existing = DDB.getItem(b -> b.tableName(TABLE)
        .key(Map.of("watchId", AttributeValue.fromS(watchId))));

    if (!existing.hasItem()) {
        return ApiResponse.error(404, "Watch session not found");
    }

    boolean active = existing.item()
        .getOrDefault("active", AttributeValue.fromBool(false))
        .bool();

    if (!active) {
        return ApiResponse.error(410, "Watch session not active");
    }

    Map<String, AttributeValue> item = new HashMap<>(existing.item());
    item.put("consentStatus", AttributeValue.fromS("pending"));
    item.put("requestedAt", AttributeValue.fromS(Instant.now().toString()));

    DDB.putItem(b -> b.tableName(TABLE).item(item));

    return ApiResponse.success(Map.of(
        "ok", true,
        "consentStatus", "pending"
    ));
}

private APIGatewayProxyResponseEvent consent(APIGatewayProxyRequestEvent event) {
    RequestParser req = new RequestParser(event);

    String watchId = req.requiredField("watchId");
    String consentToken = req.requiredField("consentToken");
    String decision = req.requiredField("decision").trim().toLowerCase();

    GetItemResponse existing = DDB.getItem(b -> b.tableName(TABLE)
        .key(Map.of("watchId", AttributeValue.fromS(watchId))));

    if (!existing.hasItem()) {
        return ApiResponse.error(404, "Watch session not found");
    }

    boolean active = existing.item()
        .getOrDefault("active", AttributeValue.fromBool(false))
        .bool();

    if (!active) {
        return ApiResponse.error(410, "Watch session not active");
    }

    String expectedToken = existing.item().containsKey("consentToken")
        ? existing.item().get("consentToken").s()
        : "";

    if (!expectedToken.equals(consentToken)) {
        return ApiResponse.error(403, "Invalid consent token");
    }

    String status;

    if ("allow".equals(decision) || "approved".equals(decision)) {
        status = "approved";
    } else if ("deny".equals(decision) || "denied".equals(decision)) {
        status = "denied";
    } else {
        return ApiResponse.error(400, "decision must be allow or deny");
    }

    Map<String, AttributeValue> item = new HashMap<>(existing.item());
    item.put("consentStatus", AttributeValue.fromS(status));
    item.put("consentUpdatedAt", AttributeValue.fromS(Instant.now().toString()));

    if ("denied".equals(status)) {
        item.remove("image");
        item.remove("updatedAt");
        item.remove("lat");
        item.remove("lon");
        item.remove("acc");
        item.remove("locAt");
    }

    DDB.putItem(b -> b.tableName(TABLE).item(item));

    return ApiResponse.success(Map.of(
        "ok", true,
        "consentStatus", status
    ));
}

    private APIGatewayProxyResponseEvent frame(APIGatewayProxyRequestEvent event) {
        RequestParser req = new RequestParser(event);
        String watchId = req.requiredField("watchId");
        byte[] image = req.imageBytes();

        // only update if the session is still active — revoked sessions ignore uploads
        GetItemResponse existing = DDB.getItem(b -> b.tableName(TABLE)
            .key(Map.of("watchId", AttributeValue.fromS(watchId))));
        if (!existing.hasItem() || !existing.item().getOrDefault("active", AttributeValue.fromBool(false)).bool()) {
            return ApiResponse.error(410, "Watch session not active");
        }
String consentStatus = existing.item().containsKey("consentStatus")
    ? existing.item().get("consentStatus").s()
    : "idle";

if (!"approved".equals(consentStatus)) {
    return ApiResponse.error(403, "Guardian viewing has not been approved");
}


        Map<String, AttributeValue> item = new HashMap<>(existing.item());
        item.put("image", AttributeValue.fromS(java.util.Base64.getEncoder().encodeToString(image)));
        item.put("updatedAt", AttributeValue.fromS(Instant.now().toString()));
        DDB.putItem(b -> b.tableName(TABLE).item(item));

        return ApiResponse.success(Map.of("ok", true));
    }

    private APIGatewayProxyResponseEvent getFrame(APIGatewayProxyRequestEvent event) {
        Map<String, String> path = event.getPathParameters();
        if (path == null || path.get("watchId") == null) return ApiResponse.error(400, "watchId is required");
        String watchId = path.get("watchId");

        GetItemResponse item = DDB.getItem(b -> b.tableName(TABLE)
            .key(Map.of("watchId", AttributeValue.fromS(watchId))));
        if (!item.hasItem()) return ApiResponse.error(404, "Watch session not found");

       boolean active = item.item()
    .getOrDefault("active", AttributeValue.fromBool(false))
    .bool();

String consentStatus = item.item().containsKey("consentStatus")
    ? item.item().get("consentStatus").s()
    : "idle";

Map<String, Object> out = new HashMap<>();
out.put("active", active);
out.put("consentStatus", consentStatus);

if (item.item().containsKey("requestedAt")) {
    out.put("requestedAt", item.item().get("requestedAt").s());
}

if ("approved".equals(consentStatus)) {
    out.put("image",
        item.item().containsKey("image") ? item.item().get("image").s() : null);
    out.put("updatedAt",
        item.item().containsKey("updatedAt") ? item.item().get("updatedAt").s() : null);
    out.put("lat",
        item.item().containsKey("lat") ? item.item().get("lat").s() : null);
    out.put("lon",
        item.item().containsKey("lon") ? item.item().get("lon").s() : null);
    out.put("acc",
        item.item().containsKey("acc") ? item.item().get("acc").s() : null);
    out.put("locAt",
        item.item().containsKey("locAt") ? item.item().get("locAt").s() : null);
} else {
    out.put("image", null);
    out.put("updatedAt", null);
    out.put("lat", null);
    out.put("lon", null);
    out.put("acc", null);
    out.put("locAt", null);
}

return ApiResponse.success(out);
    }

    private APIGatewayProxyResponseEvent stop(APIGatewayProxyRequestEvent event) {
        RequestParser req = new RequestParser(event);
        String watchId = req.requiredField("watchId");

        GetItemResponse existing = DDB.getItem(b -> b.tableName(TABLE)
            .key(Map.of("watchId", AttributeValue.fromS(watchId))));
        if (!existing.hasItem()) return ApiResponse.error(404, "Watch session not found");

        Map<String, AttributeValue> item = new HashMap<>(existing.item());
        item.put("active", AttributeValue.fromBool(false));
item.put("consentStatus", AttributeValue.fromS("stopped"));
        item.remove("image");
        DDB.putItem(b -> b.tableName(TABLE).item(item));

        return ApiResponse.success(Map.of("ok", true));
    }
}
