package com.ganzi.emori.orb;

import com.ganzi.emori.api.ApiException;
import com.ganzi.emori.api.DbSupport;
import com.ganzi.emori.api.Idempotency;
import com.ganzi.emori.asmr.AsmrService;
import com.ganzi.emori.orb.OrbModels.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
public class OrbService {
    private final JdbcTemplate jdbc;
    private final DbSupport db;
    private final Idempotency idempotency;
    private final ObjectMapper json;
    private final AsmrService asmr;

    public OrbService(JdbcTemplate jdbc, DbSupport db, Idempotency idempotency, ObjectMapper json, AsmrService asmr) {
        this.jdbc = jdbc; this.db = db; this.idempotency = idempotency; this.json = json; this.asmr=asmr;
    }

    public EmotionCatalog emotions() {
        List<Emotion> items = jdbc.query("""
                SELECT id,color,area,word FROM emotion_catalog WHERE version=1 AND active=TRUE
                ORDER BY CASE color WHEN 'YELLOW' THEN 1 WHEN 'ORANGE' THEN 2 WHEN 'PINK' THEN 3
                   WHEN 'RED' THEN 4 WHEN 'PURPLE' THEN 5 WHEN 'BLUE' THEN 6 ELSE 7 END,id
                """, (rs, n) -> new Emotion(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        return new EmotionCatalog("1", items);
    }

    public ReactionCatalog reactions() {
        return new ReactionCatalog(jdbc.query("""
                SELECT code,label,emoji FROM reaction_catalog WHERE active=TRUE
                ORDER BY CASE code WHEN 'HEART' THEN 1 WHEN 'HUG' THEN 2 WHEN 'CHEER' THEN 3 ELSE 4 END,code
                """, (rs, n) -> new Reaction(rs.getString(1), rs.getString(2), rs.getString(3))));
    }

    @Transactional
    public String create(UUID actor, UUID key, OrbCreateRequest input) {
        db.lockUsers(actor);
        return idempotency.execute(actor, "POST", "/api/v1/orbs", key, json.writeValueAsString(input), () -> createNew(actor, input));
    }

    private OrbCreateResponse createNew(UUID actor, OrbCreateRequest input) {
        if (input == null || input.emotionIds() == null || input.emotionIds().size() < 1 || input.emotionIds().size() > 2) {
            throw ApiException.invalid("emotionIds는 1개 또는 2개여야 합니다.");
        }
        List<EmotionSnapshot> selected = new ArrayList<>();
        HashSet<String> colors = new HashSet<>();
        for (String id : input.emotionIds()) {
            List<EmotionSnapshot> found = jdbc.query("""
                    SELECT version,id,color,area,word,is_care FROM emotion_catalog
                    WHERE version=1 AND id=? AND active=TRUE
                    """, (rs, n) -> new EmotionSnapshot(rs.getInt(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getBoolean(6)), id);
            if (found.isEmpty()) throw ApiException.invalid("감정 카탈로그 ID를 확인해주세요.");
            EmotionSnapshot choice = found.getFirst();
            if (!colors.add(choice.color())) throw ApiException.invalid("혼합 감정은 서로 다른 색이어야 합니다.");
            selected.add(choice);
        }
        UUID orbId = UUID.randomUUID();
        OffsetDateTime now = DbSupport.now();
        String kind = selected.size() == 1 ? "SINGLE" : "MIXED";
        jdbc.update("INSERT INTO orb(id,author_id,kind,created_at) VALUES (?,?,?,?)", orbId, actor, kind, now);
        for (int i = 0; i < selected.size(); i++) {
            EmotionSnapshot choice = selected.get(i);
            jdbc.update("""
                    INSERT INTO orb_emotion(orb_id,position,catalog_version,emotion_id,color,area,word,is_care)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, orbId, i + 1, choice.version(), choice.id(), choice.color(), choice.area(), choice.word(), choice.care());
        }
        List<Emotion> emotions = selected.stream().map(e -> new Emotion(e.id(), e.color(), e.area(), e.word())).toList();
        return new OrbCreateResponse(new Orb(orbId.toString(), kind, emotions, null, now.toInstant().toString()), null);
    }

    public OrbDetail get(UUID actor, String idText) {
        UUID id = DbSupport.pathId(idText);
        List<OrbRow> rows = jdbc.query("SELECT id,author_id,kind,created_at FROM orb WHERE id=?",
                (rs, n) -> new OrbRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getObject(4, OffsetDateTime.class)), id);
        if (rows.isEmpty() || !rows.getFirst().author().equals(actor)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "구슬을 찾을 수 없습니다.");
        }
        return new OrbDetail(snapshot(id), asmr.forOrb(actor,id));
    }

    // Internal snapshot loader. Callers must establish their own receipt or record ownership first.
    public Orb snapshot(UUID id) {
        List<OrbRow> rows = jdbc.query("SELECT id,author_id,kind,created_at FROM orb WHERE id=?",
                (rs, n) -> new OrbRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getObject(4, OffsetDateTime.class)), id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "구슬을 찾을 수 없습니다.");
        OrbRow row = rows.getFirst();
        List<Emotion> selected = jdbc.query("""
                SELECT emotion_id,color,area,word FROM orb_emotion WHERE orb_id=? ORDER BY position
                """, (rs, n) -> new Emotion(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), id);
        return new Orb(id.toString(), row.kind(), selected, asmr.playlist(id), row.createdAt().toInstant().toString());
    }

    private record EmotionSnapshot(int version, String id, String color, String area, String word, boolean care) {}
    private record OrbRow(UUID id, UUID author, String kind, OffsetDateTime createdAt) {}
}
