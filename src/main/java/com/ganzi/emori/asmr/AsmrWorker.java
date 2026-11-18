package com.ganzi.emori.asmr;

import com.ganzi.emori.api.DbSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class AsmrWorker {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AsmrProvider provider;
    private final AsmrStorage storage;
    private final boolean enabled;
    public AsmrWorker(JdbcTemplate jdbc,TransactionTemplate tx,AsmrProvider provider,AsmrStorage storage,
            @Value("${EMORI_ASMR_WORKER_ENABLED:false}") boolean enabled){
        this.jdbc=jdbc;this.tx=tx;this.provider=provider;this.storage=storage;this.enabled=enabled;
    }
    @Scheduled(fixedDelayString="${EMORI_ASMR_POLL_MS:3000}")
    public void scheduled(){if(enabled && provider.available()) runOnce();}

    public boolean runOnce(){
        if(!provider.available()) return false;
        Job job=tx.execute(s->{
            OffsetDateTime now=DbSupport.now();
            List<Job> due=jdbc.query("""
                    SELECT id,track_id,attempt FROM asmr_outbox_event WHERE
                    (status='PENDING' AND available_at<=?) OR (status='PROCESSING' AND lease_until<?)
                    ORDER BY available_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """,(rs,n)->new Job(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                    rs.getInt(3),null,null,null,null,null),now,now);
            if(due.isEmpty()) return null;
            Job selected=due.getFirst();
            List<Job> tracks=jdbc.query("""
                    SELECT g.id,g.orb_id,t.provider_request_key,t.provider_job_id,t.attempt_deadline_at
                    FROM asmr_track t JOIN asmr_generation g ON g.id=t.generation_id WHERE t.id=? AND t.attempt=?
                    AND t.status IN ('QUEUED','PROCESSING') AND g.detached_at IS NULL
                    AND NOT EXISTS(SELECT 1 FROM dispatch d WHERE d.orb_id=g.orb_id)
                    """,(rs,n)->new Job(selected.event(),selected.track(),selected.attempt(),null,
                    rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
                    rs.getString(4),rs.getObject(5,OffsetDateTime.class)),selected.track(),selected.attempt());
            if(tracks.isEmpty()){
                jdbc.update("""
                        UPDATE asmr_outbox_event SET status='FAILED',lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=?
                        """,now,selected.event());
                return null;
            }
            UUID lease=UUID.randomUUID(); Job row=tracks.getFirst();
            jdbc.update("""
                    UPDATE asmr_outbox_event SET status='PROCESSING',lease_token=?,lease_until=?,updated_at=? WHERE id=?
                    """,lease,now.plusSeconds(60),now,selected.event());
            jdbc.update("""
                    UPDATE asmr_track SET status='PROCESSING',provider=?,attempt_deadline_at=COALESCE(attempt_deadline_at,?),updated_at=?
                    WHERE id=? AND attempt=?
                    """,provider.name(),now.plusMinutes(10),now,selected.track(),selected.attempt());
            jdbc.update("UPDATE asmr_generation SET status='PROCESSING',updated_at=? WHERE id=? AND status='QUEUED'",now,row.generation());
            return new Job(selected.event(),selected.track(),selected.attempt(),lease,row.generation(),row.orb(),
                    row.requestKey(),row.providerJob(),row.deadline()==null?now.plusMinutes(10):row.deadline());
        });
        if(job==null) return false;
        try {
            if(DbSupport.now().isAfter(job.deadline())) {finish(job,null,"GENERATION_TIMEOUT");return true;}
            AsmrProvider.Result result;
            if(job.providerJob()==null){
                List<String> emotions=jdbc.query("SELECT word FROM orb_emotion WHERE orb_id=? ORDER BY position",
                        (rs,n)->rs.getString(1),job.orb());
                String description=jdbc.queryForObject("SELECT description FROM asmr_generation WHERE id=?",String.class,job.generation());
                result=provider.start(new AsmrProvider.Request(job.requestKey(),emotions,description,
                        position(job.track()),30000,false,false));
            } else result=provider.poll(job.providerJob());
            if("WAIT".equals(result.state())){
                if(result.jobId()==null || result.jobId().isBlank() || result.jobId().length()>255)
                    finish(job,null,"GENERATION_FAILED");
                else waitForProvider(job,result.jobId());
            } else if("READY".equals(result.state())) finish(job,result,null);
            else finish(job,null,validCode(result.errorCode())?result.errorCode():"GENERATION_FAILED");
        } catch(Exception e){
            // A stable request key or provider job lookup is needed to avoid duplicate external work here.
            if(DbSupport.now().isAfter(job.deadline())) finish(job,null,"GENERATION_TIMEOUT");
            else waitForProvider(job,job.providerJob());
        }
        return true;
    }
    private int position(UUID track){return jdbc.queryForObject("SELECT position FROM asmr_track WHERE id=?",Integer.class,track);}
    private void waitForProvider(Job job,String providerJob){
        tx.executeWithoutResult(s->{
            List<UUID> active=jdbc.query("""
                    SELECT id FROM asmr_outbox_event WHERE id=? AND lease_token=? AND status='PROCESSING' FOR UPDATE
                    """,(rs,n)->rs.getObject(1,UUID.class),job.event(),job.lease());
            if(active.isEmpty()) return;
            if(providerJob!=null) jdbc.update("""
                UPDATE asmr_track SET provider_job_id=?,updated_at=? WHERE id=? AND attempt=?
                """,providerJob,DbSupport.now(),job.track(),job.attempt());
            jdbc.update("""
                UPDATE asmr_outbox_event SET status='PENDING',available_at=?,lease_token=NULL,lease_until=NULL,updated_at=?
                WHERE id=? AND lease_token=? AND status='PROCESSING'
                """,DbSupport.now().plusSeconds(3),DbSupport.now(),job.event(),job.lease());
        });
    }
    private void finish(Job job,AsmrProvider.Result result,String error){
        String storageKey=null;
        if(result!=null){
            if(result.audio()==null || result.title()==null || result.title().isBlank() ||
                    result.title().length()>100 || result.durationMs()==null || result.durationMs()<1 ||
                    result.durationMs()>30000 || wavDuration(result.audio())!=result.durationMs()) error="INVALID_AUDIO";
            else try {
                // Unique per lease: a stale worker cannot replace bytes accepted from a later lease.
                storageKey=storage.key(job.generation(),job.track(),job.attempt())
                        .replace(".wav","-"+job.lease()+".wav");
                storage.write(storageKey,result.audio());
            } catch(Exception e){error="STORAGE_FAILURE";storageKey=null;}
        }
        final String key=storageKey,code=error;
        tx.executeWithoutResult(s->{
            List<UUID> currentLease=jdbc.query("""
                    SELECT id FROM asmr_outbox_event WHERE id=? AND lease_token=? AND status='PROCESSING' FOR UPDATE
                    """,(rs,n)->rs.getObject(1,UUID.class),job.event(),job.lease());
            if(currentLease.isEmpty()) return;
            jdbc.query("SELECT id FROM orb WHERE id=? FOR UPDATE",(rs,n)->rs.getObject(1),job.orb());
            jdbc.query("SELECT id FROM asmr_generation WHERE id=? FOR UPDATE",(rs,n)->rs.getObject(1),job.generation());
            jdbc.query("SELECT id FROM asmr_track WHERE id=? FOR UPDATE",(rs,n)->rs.getObject(1),job.track());
            Integer active=jdbc.queryForObject("""
                    SELECT COUNT(*) FROM asmr_outbox_event e JOIN asmr_track t ON t.id=e.track_id
                    JOIN asmr_generation g ON g.id=t.generation_id WHERE e.id=? AND e.lease_token=?
                    AND e.status='PROCESSING' AND t.attempt=? AND t.status='PROCESSING' AND g.detached_at IS NULL
                    AND NOT EXISTS(SELECT 1 FROM dispatch d WHERE d.orb_id=g.orb_id)
                    """,Integer.class,job.event(),job.lease(),job.attempt());
            if(active==0) return;
            OffsetDateTime now=DbSupport.now();
            if(key!=null){
                jdbc.update("""
                        UPDATE asmr_track SET status='READY',title=?,duration_ms=?,storage_key=?,error_code=NULL,updated_at=?
                        WHERE id=?
                        """,result.title(),result.durationMs(),key,now,job.track());
            } else jdbc.update("""
                    UPDATE asmr_track SET status='FAILED',error_code=?,updated_at=? WHERE id=?
                    """,code==null?"GENERATION_FAILED":code,now,job.track());
            jdbc.update("""
                    UPDATE asmr_outbox_event SET status=?,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=?
                    """,key!=null?"SENT":"FAILED",now,job.event());
            List<String> states=jdbc.query("SELECT status FROM asmr_track WHERE generation_id=?",(rs,n)->rs.getString(1),job.generation());
            long ready=states.stream().filter("READY"::equals).count(),failed=states.stream().filter("FAILED"::equals).count();
            String state=ready==3?"READY":failed==3?"FAILED":ready+failed==3?"PARTIAL_FAILED":"PROCESSING";
            jdbc.update("UPDATE asmr_generation SET status=?,updated_at=? WHERE id=?",state,now,job.generation());
        });
    }
    private static int wavDuration(byte[] audio){
        if(audio.length<44 || audio[0]!='R' || audio[1]!='I' || audio[8]!='W' || audio[9]!='A' ||
                audio[12]!='f' || audio[13]!='m' || audio[36]!='d' || audio[37]!='a') return -1;
        ByteBuffer b=ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN);
        int format=b.getShort(20)&0xffff,channels=b.getShort(22)&0xffff,rate=b.getInt(24);
        int byteRate=b.getInt(28),bits=b.getShort(34)&0xffff,data=b.getInt(40);
        return format==1 && channels>=1 && channels<=2 && bits==16 && rate>0 &&
                byteRate==rate*channels*2 && data>0 && data==audio.length-44 &&
                1000L*data<=30000L*byteRate
                ? (int)(1000L*data/byteRate) : -1;
    }
    private static boolean validCode(String code){return code!=null && code.matches("[A-Z0-9_]{1,40}");}
    private record Job(UUID event,UUID track,int attempt,UUID lease,UUID generation,UUID orb,
                       String requestKey,String providerJob,OffsetDateTime deadline) {
        Job(UUID event,UUID track,int attempt,UUID lease,UUID generation,UUID orb,String requestKey,
            String providerJob){this(event,track,attempt,lease,generation,orb,requestKey,providerJob,null);}
    }
}
