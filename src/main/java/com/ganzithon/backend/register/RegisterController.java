package com.ganzithon.backend.register;

import com.ganzithon.backend.api.ApiModels.Credentials;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
// TODO: API 명세서 확정 후 수정
public class RegisterController {

    private final RegisterService register;

    public RegisterController(RegisterService register) {
        this.register = register;
    }

    @PostMapping("/register/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> signup(
            @Valid @RequestBody Credentials input
    ) {
        register.throttle(
                "account:" + input.loginId().toLowerCase(Locale.ROOT)
        );

        return register.signup(input);
    }

    @PostMapping("/register/login")
    public Map<String, Object> login(
            @Valid @RequestBody Credentials input
    ) {
        register.throttle(
                "account:" + input.loginId().toLowerCase(Locale.ROOT)
        );

        return register.login(input);
    }

    @PostMapping("/register/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @RequestHeader("Registration") String token
    ) {
        register.logout(token);
    }
}
