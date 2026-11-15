package com.ganzi.emori.friend;

import com.ganzi.emori.api.DbSupport.Page;
import com.ganzi.emori.friend.FriendModels.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class FriendController {
    private final FriendService service;
    public FriendController(FriendService service) { this.service = service; }

    @GetMapping("/friends")
    public Page<UserSummary> friends(HttpServletRequest request, @RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) Integer limit) {
        return service.friends(user(request), cursor, limit);
    }

    @PostMapping("/friend-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public FriendRequestView send(HttpServletRequest request, @RequestBody FriendCodeRequest input) {
        return service.request(user(request), input);
    }

    @GetMapping("/friend-requests")
    public Page<FriendRequestView> requests(HttpServletRequest request,
                                            @RequestParam(defaultValue = "INCOMING") String direction,
                                            @RequestParam(required = false) String cursor,
                                            @RequestParam(required = false) Integer limit) {
        return service.requests(user(request), direction, cursor, limit);
    }

    @PutMapping("/friend-requests/{id}/decision")
    public FriendRequestView decide(HttpServletRequest request, @PathVariable String id,
                                    @RequestBody DecisionRequest input) {
        return service.decide(user(request), id, input);
    }

    @DeleteMapping("/friends/{friendId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(HttpServletRequest request, @PathVariable String friendId) {
        service.delete(user(request), friendId);
    }

    private UUID user(HttpServletRequest request) { return (UUID) request.getAttribute("userId"); }
}
