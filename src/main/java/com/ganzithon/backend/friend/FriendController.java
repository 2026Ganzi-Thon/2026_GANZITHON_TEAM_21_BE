package com.ganzithon.backend.friend;

import com.ganzithon.backend.register.RegisterService;
import com.ganzithon.backend.api.ApiModels.FriendRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
// TODO: API 명세서 확정 후 수정
public class FriendController {

    private final FriendService friends;
    private final RegisterService register;

    public FriendController(
            FriendService friends,
            RegisterService register
    ) {
        this.friends = friends;
        this.register = register;
    }

    @GetMapping("/friends")
    public List<Map<String, Object>> friends(
            @RequestAttribute("userId") String userId
    ) {
        return friends.list(userId);
    }

    @GetMapping("/friend-requests")
    public List<Map<String, Object>> pending(
            @RequestAttribute("userId") String userId
    ) {
        return friends.pending(userId);
    }

    @PostMapping("/friend-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> request(
            @RequestAttribute("userId") String userId,
            @Valid @RequestBody FriendRequest input
    ) {
        register.throttle("friend:" + userId);

        return friends.request(userId, input);
    }

    @PostMapping("/friend-requests/{id}/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(
            @RequestAttribute("userId") String userId,
            @PathVariable String id
    ) {
        friends.decide(userId, id, true);
    }

    @PostMapping("/friend-requests/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(
            @RequestAttribute("userId") String userId,
            @PathVariable String id
    ) {
        friends.decide(userId, id, false);
    }

    @DeleteMapping("/friends/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @RequestAttribute("userId") String userId,
            @PathVariable String id
    ) {
        friends.delete(userId, id);
    }
}