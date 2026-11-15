package com.ganzi.emori.settings;

import com.ganzi.emori.settings.SettingsModels.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me/settings")
public class SettingsController {
    private final SettingsService settings;
    public SettingsController(SettingsService settings) { this.settings=settings; }
    @GetMapping public Settings get(HttpServletRequest request) {
        return settings.get((UUID)request.getAttribute("userId"));
    }
    @PatchMapping public Settings patch(HttpServletRequest request,@RequestBody SettingsUpdate input) {
        return settings.update((UUID)request.getAttribute("userId"),input);
    }
}
