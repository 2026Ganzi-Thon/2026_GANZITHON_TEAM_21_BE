package com.ganzi.emori.care;

import com.ganzi.emori.api.ApiException;
import com.ganzi.emori.api.DbSupport;
import com.ganzi.emori.care.CareModels.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CareService {
    private static final ZoneId KST=ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final DbSupport db;
    private final Clock clock;
    public CareService(JdbcTemplate jdbc,DbSupport db,Clock clock){this.jdbc=jdbc;this.db=db;this.clock=clock;}

    @Transactional
    public Care get(UUID actor) {
        db.lockUsers(actor);
        LocalDate today=LocalDate.now(clock.withZone(KST));
        OffsetDateTime from=today.minusDays(13).atStartOfDay(KST).toOffsetDateTime();
        OffsetDateTime until=today.plusDays(1).atStartOfDay(KST).toOffsetDateTime();
        List<OffsetDateTime> dates=jdbc.query("""
                SELECT er.recorded_at FROM emotion_record er
                JOIN dispatch d ON d.id=er.dispatch_id
                JOIN orb_emotion e ON e.orb_id=d.orb_id AND e.is_care=TRUE
                WHERE er.owner_id=? AND er.side='OWN' AND er.deleted_at IS NULL
                  AND er.recorded_at>=? AND er.recorded_at<?
                  AND (d.route='SELF' OR EXISTS
                    (SELECT 1 FROM receipt r WHERE r.dispatch_id=d.id AND r.cancelled_at IS NULL))
                """,(rs,n)->rs.getObject(1,OffsetDateTime.class),actor,from,until);
        Set<LocalDate> days=dates.stream().map(t->t.atZoneSameInstant(KST).toLocalDate()).collect(Collectors.toSet());
        int count14=days.size();
        int count7=(int)days.stream().filter(d->!d.isBefore(today.minusDays(6))).count();
        int level=count14>=10?2:count7>=4?1:0;
        CareNotice notice=level==0?null:notice(actor,level);
        return new Care(today.toString(),"Asia/Seoul",count7,count14,level,notice);
    }

    private CareNotice notice(UUID actor,int level) {
        if(level==1 && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM care_notice WHERE user_id=? AND level=2 AND acknowledged_at IS NOT NULL)
                """,Boolean.class,actor))) return null;
        List<NoticeRow> rows=jdbc.query("""
                SELECT id,title,body,acknowledged_at FROM care_notice WHERE user_id=? AND level=?
                """,(rs,n)->new NoticeRow(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),
                rs.getObject(4,OffsetDateTime.class)),actor,level);
        if(rows.isEmpty()) {
            List<Content> content=jdbc.query("SELECT version,title,body FROM care_content WHERE level=? AND active=TRUE",
                    (rs,n)->new Content(rs.getInt(1),rs.getString(2),rs.getString(3)),level);
            if(content.isEmpty()) return null;
            Content selected=content.getFirst();
            UUID id=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO care_notice(id,user_id,level,title,body,content_version,qualified_at)
                    VALUES (?,?,?,?,?,?,?)
                    """,id,actor,level,selected.title(),selected.body(),selected.version(),DbSupport.now());
            rows=List.of(new NoticeRow(id,selected.title(),selected.body(),null));
        }
        NoticeRow row=rows.getFirst();
        if(row.acknowledged()!=null) return null;
        List<ResourceSummary> resources=jdbc.query("""
                SELECT cr.id,cr.title FROM care_notice_resource cnr JOIN care_resource cr ON cr.id=cnr.resource_id
                WHERE cnr.notice_id=? ORDER BY cnr.position
                """,(rs,n)->new ResourceSummary(rs.getString(1),rs.getString(2)),row.id());
        return new CareNotice(row.id().toString(),level,row.title(),row.body(),resources);
    }

    public CareResource resource(String id) {
        List<CareResource> rows=jdbc.query("SELECT id,title,body,url FROM care_resource WHERE id=? AND active=TRUE",
                (rs,n)->new CareResource(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)),id);
        if(rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    @Transactional
    public void acknowledge(UUID actor,String idText,Acknowledgement input) {
        if(input==null || !List.of("VIEWED","DISMISSED").contains(input.action()))
            throw ApiException.invalid("action must be VIEWED or DISMISSED");
        UUID id=DbSupport.pathId(idText);
        db.lockUsers(actor);
        List<UUID> rows=jdbc.query("SELECT id FROM care_notice WHERE id=? AND user_id=? FOR UPDATE",
                (rs,n)->rs.getObject(1,UUID.class),id,actor);
        if(rows.isEmpty()) throw missing();
        jdbc.update("UPDATE care_notice SET acknowledged_at=?,ack_action=? WHERE id=? AND acknowledged_at IS NULL",
                DbSupport.now(),input.action(),id);
    }
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Care item not found");}
    private record NoticeRow(UUID id,String title,String body,OffsetDateTime acknowledged){}
    private record Content(int version,String title,String body){}
}
