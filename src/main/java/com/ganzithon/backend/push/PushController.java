package com.ganzithon.backend.push;

import com.ganzithon.backend.api.ApiModels.SubscriptionRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
// TODO: API 명세서 확정 후 수정
public class PushController {

    private final PushService push;

    public PushController(PushService push) {
        this.push = push;
    }

    @GetMapping("/push/config")
    public Map<String, Object> config() {
        return push.config();
    }

    @PostMapping("/push/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> subscribe(
            @RequestAttribute("userId") String userId,
            @Valid @RequestBody SubscriptionRequest input
    ) {
        return push.subscribe(userId, input);
    }

    @DeleteMapping("/push/subscriptions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(
            @RequestAttribute("userId") String userId,
            @PathVariable String id
    ) {
        push.unsubscribe(userId, id);
    }
}