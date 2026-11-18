package com.ganzi.emori.push;

import com.ganzi.emori.api.DbSupport;
import com.ganzi.emori.api.IdempotencyResponseCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class PushWorker {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PushTransport transport;
    private final IdempotencyResponseCipher cipher;
    private final boolean enabled;
    public PushWorker(JdbcTemplate jdbc,TransactionTemplate tx,PushTransport transport,
                      IdempotencyResponseCipher cipher,@Value("${PUSH_WORKER_ENABLED:false}") boolean enabled){
        this.jdbc=jdbc;this.tx=tx;this.transport=transport;this.cipher=cipher;this.enabled=enabled;
    }
    @Scheduled(fixedDelayString="${PUSH_POLL_MS:5000}")
    public void scheduled(){if(enabled) runOnce();}

    public boolean runOnce(){
        Job job=tx.execute(status->{
            OffsetDateTime now=DbSupport.now();
            List<Job> rows=jdbc.query("""
                    SELECT id,receipt_id,installation_id,user_id,attempt_count,provider_ticket_id
                    FROM outbox_event WHERE
                    (status='PENDING' AND available_at<=?) OR
                    (status='PROCESSING' AND lease_until<?)
                    ORDER BY available_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """,(rs,n)->new Job(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                    rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getInt(5),rs.getString(6),null),now,now);
            if(rows.isEmpty()) return null;
            Job selected=rows.getFirst(); UUID lease=UUID.randomUUID();
            jdbc.update("""
                    UPDATE outbox_event SET status='PROCESSING',lease_token=?,lease_until=?,
                    attempt_count=attempt_count+?,updated_at=? WHERE id=?
                    """,lease,now.plusSeconds(60),selected.ticket()==null?1:0,now,selected.id());
            return new Job(selected.id(),selected.receipt(),selected.installation(),selected.user(),
                    selected.attempts()+(selected.ticket()==null?1:0),selected.ticket(),lease);
        });
        if(job==null) return false;
        try {
            Delivery current=tx.execute(status->delivery(job));
            if(current==null){complete(job,"SKIPPED",null,"NOT_DELIVERABLE",null);return true;}
            PushTransport.Result result=job.ticket()==null
                    ? transport.send(current.token(),job.receipt().toString())
                    : transport.check(job.ticket());
            switch(result.state()) {
                case "ACCEPTED","WAIT" -> complete(job,"PENDING",result.ticketId(),null,DbSupport.now().plusSeconds(30));
                case "DELIVERED" -> complete(job,"SENT",result.ticketId(),null,null);
                default -> {
                    String code=result.errorCode()==null?"EXPO_REJECTED":result.errorCode();
                    if("DeviceNotRegistered".equals(code)) removeInvalid(job,current.hash());
                    complete(job,"FAILED",job.ticket(),code,null);
                }
            }
        } catch(Exception e) {
            if(job.attempts()>=5) complete(job,"FAILED",job.ticket(),"TRANSPORT_FAILURE",null);
            else complete(job,"PENDING",job.ticket(),"TRANSPORT_RETRY",
                    DbSupport.now().plusSeconds(Math.min(300,1L<<Math.min(job.attempts(),8))));
        }
        return true;
    }

    private Delivery delivery(Job job){
        List<Delivery> rows=jdbc.query("""
                SELECT d.token_hash,d.token_ciphertext,s.push_enabled,s.rest_mode,s.rest_starts_at,s.rest_ends_at
                FROM outbox_event e JOIN receipt r ON r.id=e.receipt_id
                JOIN user_settings s ON s.user_id=e.user_id
                JOIN device_registration d ON d.installation_id=e.installation_id AND d.user_id=e.user_id
                JOIN auth_session a ON a.id=d.session_id AND a.revoked_at IS NULL
                WHERE e.id=? AND e.lease_token=? AND e.status='PROCESSING'
                  AND r.cancelled_at IS NULL AND r.recipient_id=e.user_id
                """,(rs,n)->{
            boolean allowed=rs.getBoolean("push_enabled") && !PushOutboxService.resting(
                    rs.getString("rest_mode"),rs.getObject("rest_starts_at",OffsetDateTime.class),
                    rs.getObject("rest_ends_at",OffsetDateTime.class),DbSupport.now());
            return allowed?new Delivery(cipher.decryptSecret(new String(rs.getBytes("token_ciphertext"),StandardCharsets.UTF_8)),
                    rs.getString("token_hash")):null;
        },job.id(),job.lease());
        return rows.isEmpty()?null:rows.getFirst();
    }
    private void removeInvalid(Job job,String hash){
        tx.executeWithoutResult(status->jdbc.update("""
                DELETE FROM device_registration WHERE installation_id=? AND user_id=? AND token_hash=?
                """,job.installation(),job.user(),hash));
    }
    private void complete(Job job,String state,String ticket,String error,OffsetDateTime next){
        tx.executeWithoutResult(status->jdbc.update("""
                UPDATE outbox_event SET status=?,provider_ticket_id=?,last_error_code=?,
                available_at=COALESCE(?,available_at),lease_token=NULL,lease_until=NULL,updated_at=?
                WHERE id=? AND lease_token=? AND status='PROCESSING'
                """,state,ticket,error,next,DbSupport.now(),job.id(),job.lease()));
    }
    private record Job(UUID id,UUID receipt,UUID installation,UUID user,int attempts,String ticket,UUID lease){}
    private record Delivery(String token,String hash){}
}
