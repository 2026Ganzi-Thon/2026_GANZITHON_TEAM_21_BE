package com.ganzi.emori.settings;

import com.ganzi.emori.api.ApiException;
import com.ganzi.emori.api.DbSupport;
import com.ganzi.emori.settings.SettingsModels.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class SettingsService {
    private final JdbcTemplate jdbc;
    private final DbSupport db;
    private final Clock clock;
    public SettingsService(JdbcTemplate jdbc, DbSupport db,Clock clock) { this.jdbc = jdbc; this.db = db; this.clock=clock; }

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
            if (states.isEmpty() || !(states.getFirst().equals("VERIFIED_19_PLUS") ||
                    states.getFirst().equals("SELF_REPORTED_19_PLUS")))
                throw new ApiException(HttpStatus.FORBIDDEN,"AGE_REQUIRED","Age verification is required");
        }
        jdbc.update("""
                UPDATE user_settings SET anonymous_enabled=COALESCE(?,anonymous_enabled),
                push_enabled=COALESCE(?,push_enabled),updated_at=? WHERE user_id=?
                """,input.anonymousEnabled(),input.pushEnabled(),DbSupport.now(),actor);
        return get(actor);
    }

    @Transactional
    public Rest rest(UUID actor,RestUpdate input) {
        if(input==null || input.mode()==null) throw ApiException.invalid("mode is required");
        db.lockUsers(actor);
        OffsetDateTime now=OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        switch(input.mode()) {
            case "OFF" -> {
                if(input.startsAt()!=null || input.endsAt()!=null) throw ApiException.invalid("OFF has no dates");
                jdbc.update("""
                    UPDATE user_settings SET rest_mode='OFF',rest_starts_at=NULL,rest_ends_at=NULL,updated_at=?
                    WHERE user_id=?
                    """,now,actor);
            }
            case "MANUAL" -> {
                if(input.startsAt()!=null || input.endsAt()!=null) throw ApiException.invalid("MANUAL has no dates");
                jdbc.update("""
                    UPDATE user_settings SET rest_mode='MANUAL',rest_starts_at=?,rest_ends_at=NULL,updated_at=?
                    WHERE user_id=?
                    """,now,now,actor);
            }
            case "SCHEDULED" -> {
                OffsetDateTime starts=parse(input.startsAt()),ends=parse(input.endsAt());
                ZoneId kst=ZoneId.of("Asia/Seoul");
                if(!starts.isBefore(ends) || starts.atZoneSameInstant(kst).toLocalTime().toSecondOfDay()!=0 ||
                    ends.atZoneSameInstant(kst).toLocalTime().toSecondOfDay()!=0)
                    throw ApiException.invalid("Schedule must use KST day boundaries");
                Settings current=get(actor);
                if(!starts.isAfter(now) && !(current.rest().mode().equals("SCHEDULED") &&
                    starts.toInstant().toString().equals(current.rest().startsAt()) &&
                    ends.toInstant().toString().equals(current.rest().endsAt())))
                    throw ApiException.invalid("Schedule start must be in the future");
                jdbc.update("""
                    UPDATE user_settings SET rest_mode='SCHEDULED',rest_starts_at=?,rest_ends_at=?,updated_at=?
                    WHERE user_id=?
                    """,starts,ends,now,actor);
            }
            default -> throw ApiException.invalid("Invalid rest mode");
        }
        return get(actor).rest();
    }
    private static OffsetDateTime parse(String value) {
        try { if(value==null) throw new IllegalArgumentException(); return OffsetDateTime.parse(value); }
        catch(RuntimeException e) { throw ApiException.invalid("Valid RFC 3339 times are required"); }
    }
}
