package com.ganzi.emori.asmr;

import com.ganzi.emori.api.DbSupport;
import com.ganzi.emori.api.Idempotency;
import com.ganzi.emori.asmr.AsmrModels.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AsmrController {
    private final AsmrService asmr;
    private final Idempotency idempotency;
    private final ObjectMapper json;
    public AsmrController(AsmrService asmr,Idempotency idempotency,ObjectMapper json){this.asmr=asmr;this.idempotency=idempotency;this.json=json;}
    @PostMapping("/orbs/{orbId}/asmr-generations")
    public ResponseEntity<String> create(HttpServletRequest request,@PathVariable String orbId,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestBody(required=false) GenerateRequest body){
        UUID actor=actor(request),orb=DbSupport.pathId(orbId),parsed=idempotency.key(key);
        String path="/api/v1/orbs/"+orb+"/asmr-generations";
        String result=idempotency.gate(actor,"POST",path,parsed,()->asmr.create(actor,orb,parsed,body));
        // The generation ID is also the playlist ID.
        String id=json.readTree(result).path("id").asText();
        return ResponseEntity.accepted().header(HttpHeaders.LOCATION,"/api/v1/asmr-generations/"+id)
                .contentType(MediaType.APPLICATION_JSON).body(result);
    }
    @GetMapping("/asmr-generations/{id}")
    public ResponseEntity<Generation> get(HttpServletRequest request,@PathVariable String id){
        Generation result=asmr.get(actor(request),DbSupport.pathId(id));
        ResponseEntity.BodyBuilder response=ResponseEntity.ok();
        if("QUEUED".equals(result.status()) || "PROCESSING".equals(result.status())) response.header(HttpHeaders.RETRY_AFTER,"3");
        return response.body(result);
    }
    @PostMapping("/asmr-generations/{id}/retry")
    public ResponseEntity<String> retry(HttpServletRequest request,@PathVariable String id,
            @RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody EmptyRequest body){
        UUID actor=actor(request),generation=DbSupport.pathId(id),parsed=idempotency.key(key);
        String path="/api/v1/asmr-generations/"+generation+"/retry";
        String result=idempotency.gate(actor,"POST",path,parsed,()->asmr.retry(actor,generation,parsed));
        return ResponseEntity.accepted().contentType(MediaType.APPLICATION_JSON).body(result);
    }
    @DeleteMapping("/orbs/{orbId}/playlist")
    public ResponseEntity<Void> detach(HttpServletRequest request,@PathVariable String orbId){
        asmr.detach(actor(request),DbSupport.pathId(orbId));return ResponseEntity.noContent().build();
    }
    @GetMapping(value="/asmr-tracks/{id}/audio",produces="audio/wav")
    public ResponseEntity<byte[]> audio(HttpServletRequest request,@PathVariable String id){
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .contentType(MediaType.parseMediaType("audio/wav"))
                .body(asmr.audio(actor(request),DbSupport.pathId(id)));
    }
    private static UUID actor(HttpServletRequest request){return (UUID)request.getAttribute("userId");}
}
