package com.ganzithon.backend.user;

import com.ganzithon.backend.register.RegisterService;
import com.ganzithon.backend.api.ApiModels.RestSetting;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
// TODO: API 명세서 확정 후 수정
public class UserController {

    private final UserService users;
    private final RegisterService register;

    public UserController(
            UserService users,
            RegisterService register
    ) {
        this.users = users;
        this.register = register;
    }

    @GetMapping("/me")
    public Map<String, Object> me(
            @RequestAttribute("userId") String userId
    ) {
        return users.me(userId);
    }

    @PutMapping("/me/rest")
    public Map<String, Object> rest(
            @RequestAttribute("userId") String userId,
            @Valid @RequestBody RestSetting input
    ) {
        return users.setRest(userId, input.resting());
    }

    @GetMapping("/me/qr")
    public Map<String, Object> qr(
            @RequestAttribute("userId") String userId
    ) {
        return users.qr(userId);
    }

    @GetMapping("/users/search")
    public Map<String, Object> search(
            @RequestAttribute("userId") String userId,
            @RequestParam String loginId
    ) {
        register.throttle("search:" + userId);

        return users.search(loginId);
    }
}