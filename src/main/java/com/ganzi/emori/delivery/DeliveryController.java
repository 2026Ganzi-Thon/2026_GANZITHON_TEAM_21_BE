package com.ganzi.emori.delivery;

import com.ganzi.emori.api.DbSupport;
import com.ganzi.emori.api.Idempotency;
import com.ganzi.emori.api.DbSupport.Page;
import com.ganzi.emori.delivery.DeliveryModels.*;
import com.ganzi.emori.delivery.AnonymousModels.ReportRequest;
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
    private final AnonymousActionService anonymous;
    public DeliveryController(DeliveryService deliveries, Idempotency idempotency, AnonymousActionService anonymous) {
        this.deliveries = deliveries; this.idempotency = idempotency; this.anonymous=anonymous;
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
    public ResponseEntity<String> reaction(HttpServletRequest request, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody ReactionRequest body) {
        UUID receipt=DbSupport.pathId(id),parsedKey=idempotency.key(key),actor=actor(request);
        String path="/api/v1/receipts/"+receipt+"/reaction";
        String response=idempotency.gate(actor,"POST",path,parsedKey,
                ()->anonymous.reaction(actor,receipt,parsedKey,body));
        return ResponseEntity.status(201).contentType(MediaType.APPLICATION_JSON).body(response);
    }
    @PostMapping("/receipts/{id}/finish")
    public ResponseEntity<Void> finish(HttpServletRequest request, @PathVariable String id, @RequestBody FinishRequest body) {
        anonymous.finish(actor(request),DbSupport.pathId(id),body);
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/receipts/{id}/reports")
    public ResponseEntity<String> report(HttpServletRequest request,@PathVariable String id,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody ReportRequest body) {
        UUID receipt=DbSupport.pathId(id),parsedKey=idempotency.key(key),actor=actor(request);
        String path="/api/v1/receipts/"+receipt+"/reports";
        String response=idempotency.gate(actor,"POST",path,parsedKey,
                ()->anonymous.report(actor,receipt,parsedKey,body));
        return ResponseEntity.status(201).contentType(MediaType.APPLICATION_JSON).body(response);
    }
    @PutMapping("/receipts/{id}/anonymous-block")
    public ResponseEntity<Void> block(HttpServletRequest request,@PathVariable String id,@RequestBody EmptyRequest body) {
        anonymous.block(actor(request),DbSupport.pathId(id));
        return ResponseEntity.noContent().build();
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
