package com.ganzithon.backend.archive;

import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
// TODO: API 명세서 확정 후 수정
public class ArchiveController {

    private final ArchiveService archive;

    public ArchiveController(ArchiveService archive) {
        this.archive = archive;
    }

    @GetMapping("/archive/week")
    public Map<String, Object> week(
            @RequestAttribute("userId") String userId,
            @RequestParam(required = false) LocalDate date
    ) {
        LocalDate targetDate = date == null
                ? LocalDate.now(ZoneId.of("Asia/Seoul"))
                : date;

        return archive.week(userId, targetDate);
    }
}