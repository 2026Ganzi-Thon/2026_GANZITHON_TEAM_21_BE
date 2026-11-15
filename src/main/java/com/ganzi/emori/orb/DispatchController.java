package com.ganzi.emori.orb;

import com.ganzi.emori.api.DbSupport;
import com.ganzi.emori.api.Idempotency;
import com.ganzi.emori.orb.DispatchModels.Dispatch;
import com.ganzi.emori.orb.DispatchModels.DispatchRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DispatchController {
    private final DispatchService dispatches;
    private final Idempotency idempotency;
    public DispatchController(DispatchService dispatches, Idempotency idempotency) {
        this.dispatches = dispatches; this.idempotency = idempotency;
    }

    @PostMapping("/orbs/{orbId}/dispatches")
    public ResponseEntity<String> create(HttpServletRequest request, @PathVariable String orbId,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                         @RequestBody DispatchRequest input) {
        UUID actor = (UUID) request.getAttribute("userId");
        UUID parsedOrb = DbSupport.pathId(orbId);
        UUID parsedKey = idempotency.key(key);
        String path = "/api/v1/orbs/" + parsedOrb + "/dispatches";
        String body = idempotency.gate(actor, "POST", path, parsedKey,
                () -> dispatches.create(actor, parsedOrb, parsedKey, input));
        return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    @GetMapping("/dispatches/{dispatchId}")
    public Dispatch get(HttpServletRequest request, @PathVariable String dispatchId) {
        return dispatches.get((UUID) request.getAttribute("userId"), dispatchId);
    }
}
