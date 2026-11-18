package com.ganzi.emori.push;

import com.ganzi.emori.api.DbSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class PushOutboxService {
    private final JdbcTemplate jdbc;
    public PushOutboxService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    // Called inside the same transaction that creates the receipt.
    public void enqueue(UUID receipt,UUID recipient){
        List<UUID> devices=jdbc.query("SELECT installation_id FROM device_registration WHERE user_id=?",
                (rs,n)->rs.getObject(1,UUID.class),recipient);
        if(devices.isEmpty()) return;
        OffsetDateTime now=DbSupport.now();
        var settings=jdbc.queryForObject("""
                SELECT push_enabled,rest_mode,rest_starts_at,rest_ends_at FROM user_settings WHERE user_id=?
                """,(rs,n)->new Settings(rs.getBoolean(1),rs.getString(2),
                rs.getObject(3,OffsetDateTime.class),rs.getObject(4,OffsetDateTime.class)),recipient);
        boolean skip=!settings.pushEnabled() || resting(settings.restMode(),
                settings.restStartsAt(),settings.restEndsAt(),now);
        for(UUID installation:devices) jdbc.update("""
                INSERT INTO outbox_event(id,type,receipt_id,installation_id,user_id,status,available_at,created_at,updated_at)
                VALUES (?,'PUSH_RECEIPT',?,?,?,?,?,?,?)
                """,UUID.randomUUID(),receipt,installation,recipient,skip?"SKIPPED":"PENDING",now,now,now);
    }
    public void cancel(UUID receipt){
        jdbc.update("""
                UPDATE outbox_event SET status='SKIPPED',last_error_code='CANCELLED',updated_at=?
                WHERE receipt_id=? AND status='PENDING'
                """,DbSupport.now(),receipt);
    }
    public static boolean resting(String mode,OffsetDateTime starts,OffsetDateTime ends,OffsetDateTime now){
        return "MANUAL".equals(mode) || ("SCHEDULED".equals(mode) && starts!=null && ends!=null &&
                !now.isBefore(starts) && now.isBefore(ends));
    }
    private record Settings(boolean pushEnabled,String restMode,OffsetDateTime restStartsAt,OffsetDateTime restEndsAt){}
}
