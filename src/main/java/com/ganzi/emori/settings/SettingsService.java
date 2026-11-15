package com.ganzi.emori.settings;

import com.ganzi.emori.api.ApiException;
import com.ganzi.emori.api.DbSupport;
import com.ganzi.emori.settings.SettingsModels.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class SettingsService {
    private final JdbcTemplate jdbc;
    private final DbSupport db;
    public SettingsService(JdbcTemplate jdbc, DbSupport db) { this.jdbc = jdbc; this.db = db; }

    public Settings get(UUID actor) {
        return jdbc.queryForObject("""
                SELECT anonymous_enabled,push_enabled,rest_mode,rest_starts_at,rest_ends_at
                FROM user_settings WHERE user_id=?
                """, (rs,n) -> {
            OffsetDateTime starts=rs.getObject("rest_starts_at",OffsetDateTime.class);
            OffsetDateTime ends=rs.getObject("rest_ends_at",OffsetDateTime.class);
            String mode=rs.getString("rest_mode");
            OffsetDateTime now=DbSupport.now();
            boolean active=mode.equals("MANUAL") || (mode.equals("SCHEDULED") &&
                    starts!=null && !now.isBefore(starts) && ends!=null && now.isBefore(ends));
            return new Settings(rs.getBoolean("anonymous_enabled"),rs.getBoolean("push_enabled"),
                    new Rest(mode,starts==null?null:starts.toInstant().toString(),
                            ends==null?null:ends.toInstant().toString(),active));
        },actor);
    }

    @Transactional
    public Settings update(UUID actor, SettingsUpdate input) {
        if (input==null || (input.anonymousEnabled()==null && input.pushEnabled()==null))
            throw ApiException.invalid("At least one setting is required");
        db.lockUsers(actor);
        if (Boolean.TRUE.equals(input.anonymousEnabled())) {
            List<String> states=jdbc.query("SELECT adult_status FROM app_user WHERE id=?",
                    (rs,n)->rs.getString(1),actor);
            if (states.isEmpty() || !states.getFirst().equals("VERIFIED_19_PLUS"))
                throw new ApiException(HttpStatus.FORBIDDEN,"AGE_REQUIRED","Age verification is required");
        }
        jdbc.update("""
                UPDATE user_settings SET anonymous_enabled=COALESCE(?,anonymous_enabled),
                push_enabled=COALESCE(?,push_enabled),updated_at=? WHERE user_id=?
                """,input.anonymousEnabled(),input.pushEnabled(),DbSupport.now(),actor);
        return get(actor);
    }
}
