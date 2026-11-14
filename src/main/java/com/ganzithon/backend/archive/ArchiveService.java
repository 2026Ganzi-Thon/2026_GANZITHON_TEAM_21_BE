package com.ganzithon.backend.archive;

import com.ganzithon.backend.api.ApiModels.Color;
import com.ganzithon.backend.common.Db;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Service
public class ArchiveService {

    private final Db db;

    public ArchiveService(Db db) {
        this.db = db;
    }

    public Map<String, Object> week(
            String userId,
            LocalDate date
    ) {
        LocalDate monday = date.with(
                TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
        );

        ZoneId seoul = ZoneId.of("Asia/Seoul");

        long from = monday.atStartOfDay(seoul)
                .toInstant().toEpochMilli();

        long to = monday.plusWeeks(1)
                .atStartOfDay(seoul)
                .toInstant().toEpochMilli();

        // 수신자 수와 무관하게 본인이 만든 구슬만 집계
        var rows = db.jdbc.queryForList("""
                SELECT color_first,color_second,created_at
                FROM bead
                WHERE sender_id=?
                  AND created_at>=?
                  AND created_at<?
                ORDER BY created_at DESC,id DESC
                """,
                userId, from, to
        );

        Map<String, Integer> counts = new LinkedHashMap<>();

        for (Color color : Color.values()) {
            counts.put(color.name(), 0);
        }

        for (var row : rows) {
            counts.compute(
                    (String) row.get("color_first"),
                    (key, value) -> value + 1
            );

            if (row.get("color_second") != null) {
                counts.compute(
                        (String) row.get("color_second"),
                        (key, value) -> value + 1
                );
            }
        }

        int max = counts.values().stream()
                .max(Integer::compare)
                .orElse(0);

        String representative = null;

        // 대표 구슬 집계 우선순위: 최다 색상 → 최근 기록 → 먼저 선택한 색상
        outer:
        for (var row : rows) {
            for (String field : List.of(
                    "color_first",
                    "color_second"
            )) {
                String color = (String) row.get(field);

                if (color != null && counts.get(color) == max) {
                    representative = color;
                    break outer;
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("weekStart", monday.toString());
        result.put("weekEnd", monday.plusDays(6).toString());
        result.put("timezone", "Asia/Seoul");
        result.put("beadCount", rows.size());
        result.put("colorCounts", counts);
        result.put("representativeColor", representative);

        return result;
    }
}