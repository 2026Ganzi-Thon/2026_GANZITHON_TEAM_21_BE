package com.ganzi.emori.sound;

import com.ganzi.emori.api.DbSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class SoundController {
    private final SoundService sounds;
    public SoundController(SoundService sounds) { this.sounds=sounds; }
    @GetMapping("/catalog/sounds")
    public SoundService.Catalog catalog() { return sounds.catalog(); }
    @GetMapping(value="/catalog/sounds/{id}/audio",produces="audio/wav")
    public ResponseEntity<byte[]> preview(@PathVariable String id) { return audio(sounds.preview(DbSupport.pathId(id))); }
    @GetMapping(value="/orbs/{id}/sound/audio",produces="audio/wav")
    public ResponseEntity<byte[]> attached(HttpServletRequest request,@PathVariable String id) {
        return audio(sounds.attached((UUID)request.getAttribute("userId"),DbSupport.pathId(id)));
    }
    private static ResponseEntity<byte[]> audio(byte[] bytes) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .contentType(MediaType.parseMediaType("audio/wav")).body(bytes);
    }
}
