package com.ganzi.emori.delivery;

import com.ganzi.emori.api.DbSupport;
import com.ganzi.emori.api.Idempotency;
import com.ganzi.emori.api.DbSupport.Page;
import com.ganzi.emori.delivery.DeliveryModels.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DeliveryController {
    private final DeliveryService deliveries;
    private final Idempotency idempotency;
    public DeliveryController(DeliveryService deliveries, Idempotency idempotency) {
        this.deliveries = deliveries; this.idempotency = idempotency;
    }
    @GetMapping("/inbox")
    public Page<Receipt> inbox(HttpServletRequest request,
            @RequestParam(required = false) Boolean unreadOnly, @RequestParam(required = false) String date,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return deliveries.inbox(actor(request), Boolean.TRUE.equals(unreadOnly), date, cursor, limit);
    }
    @PostMapping("/receipts/{id}/open")
    public ResponseEntity<String> open(HttpServletRequest request, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody EmptyRequest body) {
        UUID receipt = DbSupport.pathId(id), parsedKey = idempotency.key(key), actor = actor(request);
        String path = "/api/v1/receipts/" + receipt + "/open";
        String response = idempotency.gate(actor, "POST", path, parsedKey, () -> deliveries.open(actor, receipt, parsedKey));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
    }
    @GetMapping("/receipts/{id}/replay")
    public SingleView replay(HttpServletRequest request, @PathVariable String id) {
        return deliveries.replay(actor(request), id);
    }
    @PostMapping("/receipts/{id}/reaction")
    public void reaction(HttpServletRequest request, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody ReactionRequest body) {
        idempotency.key(key); deliveries.rejectFriendAction(actor(request), id);
    }
    @PostMapping("/receipts/{id}/finish")
    public void finish(HttpServletRequest request, @PathVariable String id, @RequestBody FinishRequest body) {
        deliveries.rejectFriendAction(actor(request), id);
    }
    @PostMapping("/dispatches/{dispatchId}/targets/{targetId}/cancel")
    public ResponseEntity<String> cancel(HttpServletRequest request, @PathVariable String dispatchId,
            @PathVariable String targetId, @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody EmptyRequest body) {
        UUID dispatch = DbSupport.pathId(dispatchId), target = DbSupport.pathId(targetId);
        UUID parsedKey = idempotency.key(key), actor = actor(request);
        String path = "/api/v1/dispatches/" + dispatch + "/targets/" + target + "/cancel";
        String response = idempotency.gate(actor, "POST", path, parsedKey,
                () -> deliveries.cancel(actor, dispatch, target, parsedKey));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
    }
    private static UUID actor(HttpServletRequest request) { return (UUID) request.getAttribute("userId"); }
}
