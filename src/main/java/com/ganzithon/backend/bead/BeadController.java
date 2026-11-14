package com.ganzithon.backend.bead;

import com.ganzithon.backend.register.RegisterService;
import com.ganzithon.backend.api.ApiModels.ReactionRequest;
import com.ganzithon.backend.api.ApiModels.SendBead;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
// TODO: API 명세서 확정 후 수정
public class BeadController {

    private final BeadService beads;
    private final MusicService music;
    private final RegisterService register;

    public BeadController(
            BeadService beads,
            MusicService music,
            RegisterService register
    ) {
        this.beads = beads;
        this.music = music;
        this.register = register;
    }

    @GetMapping("/colors")
    public List<Map<String, Object>> colors() {
        return beads.colors();
    }

    @GetMapping("/emojis")
    public Map<String, Object> emojis() {
        return beads.emojis();
    }

    @PostMapping("/beads")
    public Map<String, Object> send(
            @RequestAttribute("userId") String userId,
            @RequestHeader(
                    value = "Idempotency-Key",
                    required = false
            ) String key,
            @Valid @RequestBody SendBead input
    ) {
        register.throttle("send:" + userId);

        return beads.send(userId, key, input);
    }

    @GetMapping("/beads")
    public List<Map<String, Object>> archive(
            @RequestAttribute("userId") String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return beads.archive(userId, page, size);
    }

    @GetMapping("/inbox")
    public List<Map<String, Object>> inbox(
            @RequestAttribute("userId") String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return beads.inbox(userId, page, size);
    }

    @GetMapping("/beads/{id}")
    public Map<String, Object> detail(
            @RequestAttribute("userId") String userId,
            @PathVariable String id,
            @RequestParam(defaultValue = "false")
            boolean initialReceipt
    ) {
        return beads.detail(userId, id, initialReceipt);
    }

    @PostMapping("/beads/{id}/open")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void open(
            @RequestAttribute("userId") String userId,
            @PathVariable String id
    ) {
        beads.open(userId, id);
    }

    @PostMapping("/beads/{id}/quiet")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void quiet(
            @RequestAttribute("userId") String userId,
            @PathVariable String id
    ) {
        beads.quiet(userId, id);
    }

    @PostMapping("/beads/{id}/reactions")
    public Map<String, Object> react(
            @RequestAttribute("userId") String userId,
            @PathVariable String id,
            @Valid @RequestBody ReactionRequest input
    ) {
        register.throttle("reaction:" + userId);

        return beads.react(userId, id, input.emoji());
    }

    @GetMapping(
            value = "/beads/{id}/audio",
            produces = "audio/wav"
    )
    public byte[] audio(
            @RequestAttribute("userId") String userId,
            @PathVariable String id
    ) {
        return music.audio(userId, id);
    }
}