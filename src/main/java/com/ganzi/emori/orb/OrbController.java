package com.ganzi.emori.orb;

import com.ganzi.emori.api.Idempotency;
import com.ganzi.emori.orb.OrbModels.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class OrbController {
    private final OrbService orbs;
    private final Idempotency idempotency;
    public OrbController(OrbService orbs, Idempotency idempotency) { this.orbs = orbs; this.idempotency = idempotency; }

    @GetMapping("/catalog/emotions")
    public EmotionCatalog emotions() { return orbs.emotions(); }

    @GetMapping("/catalog/reactions")
    public ReactionCatalog reactions() { return orbs.reactions(); }

    @PostMapping("/orbs")
    public ResponseEntity<String> create(HttpServletRequest request,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                         @RequestBody OrbCreateRequest input) {
        UUID actor = (UUID) request.getAttribute("userId");
        UUID parsed = idempotency.key(key);
        String body = idempotency.gate(actor, "POST", "/api/v1/orbs", parsed, () -> orbs.create(actor, parsed, input));
        return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    @GetMapping("/orbs/{orbId}")
    public OrbDetail get(HttpServletRequest request, @PathVariable String orbId) {
        return orbs.get((UUID) request.getAttribute("userId"), orbId);
    }
}
