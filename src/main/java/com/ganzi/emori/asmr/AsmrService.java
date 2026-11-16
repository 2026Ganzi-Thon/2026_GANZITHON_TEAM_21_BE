package com.ganzi.emori.asmr;

import com.ganzi.emori.api.ApiException;
import com.ganzi.emori.api.DbSupport;
import com.ganzi.emori.api.Idempotency;
import com.ganzi.emori.asmr.AsmrModels.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AsmrService {
    private final JdbcTemplate jdbc;
    private final DbSupport db;
    private final Idempotency idempotency;
    private final ObjectMapper json;
    private final AsmrProvider provider;
    private final AsmrStorage storage;
    public AsmrService(JdbcTemplate jdbc,DbSupport db,Idempotency idempotency,ObjectMapper json,
                       AsmrProvider provider,AsmrStorage storage){
        this.jdbc=jdbc;this.db=db;this.idempotency=idempotency;this.json=json;
        this.provider=provider;this.storage=storage;
    }

    @Transactional
    public String create(UUID actor,UUID orb,UUID key,GenerateRequest input){
        db.lockUsers(actor);
        String path="/api/v1/orbs/"+orb+"/asmr-generations";
        return idempotency.execute(actor,"POST",path,key,json.writeValueAsString(input),202,
                ()->createNew(actor,orb,input));
    }
    private Generation createNew(UUID actor,UUID orb,GenerateRequest input){
        if(!provider.available()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "GENERATION_UNAVAILABLE","ASMR 생성 공급자가 아직 연결되지 않았습니다.");
        String description=input==null?null:input.description();
        if(description!=null && description.codePointCount(0,description.length())>300)
            throw ApiException.invalid("description은 300자 이하여야 합니다.");
        lockOrb(actor,orb);
        if(dispatched(orb)) throw conflict("ALREADY_DISPATCHED");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM asmr_generation WHERE orb_id=?",Integer.class,orb)>0)
            throw conflict("GENERATION_EXISTS");
        UUID generation=UUID.randomUUID(); OffsetDateTime now=DbSupport.now();
        jdbc.update("""
                INSERT INTO asmr_generation(id,orb_id,status,track_count,description,created_at,updated_at)
                VALUES (?,?,'QUEUED',3,?,?,?)
                """,generation,orb,description,now,now);
        for(int position=1;position<=3;position++){
            UUID track=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO asmr_track(id,generation_id,position,status,attempt,provider_request_key,created_at,updated_at)
                    VALUES (?,?,?,'QUEUED',1,?,?,?)
                    """,track,generation,position,track+":1",now,now);
            enqueue(track,1,now);
        }
        return view(generation);
    }

    @Transactional
    public String retry(UUID actor,UUID generation,UUID key){
        db.lockUsers(actor);
        String path="/api/v1/asmr-generations/"+generation+"/retry";
        return idempotency.execute(actor,"POST",path,key,"{}",202,()->retryNew(actor,generation));
    }
    private Generation retryNew(UUID actor,UUID generation){
        Gen row=owned(actor,generation);
        lockOrb(actor,row.orb());
        if(dispatched(row.orb())) throw conflict("ALREADY_DISPATCHED");
        if(row.detached()!=null) throw conflict("PLAYLIST_DETACHED");
        if(!List.of("FAILED","PARTIAL_FAILED").contains(row.status()))
            throw conflict("GENERATION_IN_PROGRESS");
        OffsetDateTime now=DbSupport.now();
        List<Attempt> failed=jdbc.query("SELECT id,attempt FROM asmr_track WHERE generation_id=? AND status='FAILED' ORDER BY position FOR UPDATE",
                (rs,n)->new Attempt(rs.getObject(1,UUID.class),rs.getInt(2)),generation);
        if(failed.isEmpty()) throw conflict("GENERATION_IN_PROGRESS");
        for(Attempt track:failed){
            int next=track.attempt()+1;
            jdbc.update("""
                    UPDATE asmr_track SET status='QUEUED',attempt=?,provider=NULL,provider_job_id=NULL,
                    provider_request_key=?,error_code=NULL,attempt_deadline_at=NULL,updated_at=? WHERE id=?
                    """,next,track.id()+":"+next,now,track.id());
            enqueue(track.id(),next,now);
        }
        jdbc.update("UPDATE asmr_generation SET status='QUEUED',updated_at=? WHERE id=?",now,generation);
        return view(generation);
    }

    @Transactional
    public void detach(UUID actor,UUID orb){
        db.lockUsers(actor);
        lockOrb(actor,orb);
        if(dispatched(orb)) throw conflict("ALREADY_DISPATCHED");
        List<Gen> rows=byOrb(orb);
        if(rows.isEmpty() || rows.getFirst().detached()!=null) return;
        Gen row=rows.getFirst();
        if(List.of("QUEUED","PROCESSING").contains(row.status())) throw conflict("GENERATION_IN_PROGRESS");
        jdbc.update("UPDATE asmr_generation SET detached_at=?,updated_at=? WHERE id=?",
                DbSupport.now(),DbSupport.now(),row.id());
    }

    public Generation get(UUID actor,UUID generation){return view(owned(actor,generation).id());}
    public Generation forOrb(UUID actor,UUID orb){
        List<Gen> rows=byOrb(orb);
        return rows.isEmpty()?null:get(actor,rows.getFirst().id());
    }
    public Playlist playlist(UUID orb){
        List<Gen> rows=byOrb(orb);
        if(rows.isEmpty()) return null;
        Gen row=rows.getFirst();
        if(row.detached()!=null || !"READY".equals(row.status())) return null;
        List<Track> tracks=jdbc.query("""
                SELECT id,position,title,duration_ms FROM asmr_track
                WHERE generation_id=? AND status='READY' ORDER BY position
                """,(rs,n)->new Track(rs.getObject(1,UUID.class).toString(),rs.getInt(2),rs.getString(3),
                rs.getInt(4),audioPath(rs.getObject(1,UUID.class))),row.id());
        return tracks.size()==3?new Playlist(row.id().toString(),"READY",tracks):null;
    }
    public void requireReady(UUID orb){
        List<Gen> rows=byOrb(orb);
        if(rows.isEmpty() || rows.getFirst().detached()!=null) return;
        Gen generation=rows.getFirst();
        jdbc.query("SELECT id FROM asmr_generation WHERE id=? FOR UPDATE",(rs,n)->rs.getObject(1),generation.id());
        if(!"READY".equals(generation.status()) || jdbc.queryForObject("""
                SELECT COUNT(*) FROM asmr_track WHERE generation_id=? AND status='READY'
                AND duration_ms BETWEEN 1 AND 30000
                """,Integer.class,generation.id())!=3) throw conflict("PLAYLIST_NOT_READY");
    }
    public byte[] audio(UUID actor,UUID trackId){
        List<AudioRow> rows=jdbc.query("""
                SELECT g.orb_id,t.storage_key,o.author_id FROM asmr_track t
                JOIN asmr_generation g ON g.id=t.generation_id JOIN orb o ON o.id=g.orb_id
                WHERE t.id=? AND t.status='READY'
                """,(rs,n)->new AudioRow(rs.getObject(1,UUID.class),
                rs.getString(2),rs.getObject(3,UUID.class)),trackId);
        if(rows.isEmpty()) throw missing();
        AudioRow row=rows.getFirst();
        Boolean received=jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM receipt r JOIN dispatch d ON d.id=r.dispatch_id
                WHERE d.orb_id=? AND r.recipient_id=? AND r.cancelled_at IS NULL)
                """,Boolean.class,row.orb(),actor);
        if(!actor.equals(row.author()) && !Boolean.TRUE.equals(received)) throw missing();
        try{return storage.read(row.storageKey());}
        catch(IOException e){throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "AUDIO_UNAVAILABLE","음원을 읽을 수 없습니다.");}
    }

    private Generation view(UUID id){
        Gen row=jdbc.queryForObject("""
                SELECT id,orb_id,status,detached_at,created_at,updated_at FROM asmr_generation WHERE id=?
                """,(rs,n)->new Gen(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
                rs.getObject(4,OffsetDateTime.class),rs.getObject(5,OffsetDateTime.class),
                rs.getObject(6,OffsetDateTime.class)),id);
        List<GenerationTrack> tracks=jdbc.query("""
                SELECT id,position,status,title,duration_ms,error_code FROM asmr_track
                WHERE generation_id=? ORDER BY position
                """,(rs,n)->new GenerationTrack(rs.getObject(1,UUID.class).toString(),rs.getInt(2),
                rs.getString(3),rs.getString(4),(Integer)rs.getObject(5),
                "READY".equals(rs.getString(3))?audioPath(rs.getObject(1,UUID.class)):null,
                rs.getString(6)),id);
        return new Generation(id.toString(),row.orb().toString(),row.status(),3,tracks,
                row.created().toInstant().toString(),row.updated().toInstant().toString());
    }
    private Gen owned(UUID actor,UUID generation){
        List<Gen> rows=jdbc.query("""
                SELECT g.id,g.orb_id,g.status,g.detached_at,g.created_at,g.updated_at FROM asmr_generation g
                JOIN orb o ON o.id=g.orb_id WHERE g.id=? AND o.author_id=?
                """,(rs,n)->new Gen(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
                rs.getObject(4,OffsetDateTime.class),rs.getObject(5,OffsetDateTime.class),
                rs.getObject(6,OffsetDateTime.class)),generation,actor);
        if(rows.isEmpty()) throw missing();
        return rows.getFirst();
    }
    private List<Gen> byOrb(UUID orb){
        return jdbc.query("SELECT id,orb_id,status,detached_at,created_at,updated_at FROM asmr_generation WHERE orb_id=?",
                (rs,n)->new Gen(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
                rs.getObject(4,OffsetDateTime.class),rs.getObject(5,OffsetDateTime.class),
                rs.getObject(6,OffsetDateTime.class)),orb);
    }
    private void lockOrb(UUID actor,UUID orb){
        List<UUID> authors=jdbc.query("SELECT author_id FROM orb WHERE id=? FOR UPDATE",
                (rs,n)->rs.getObject(1,UUID.class),orb);
        if(authors.isEmpty() || !authors.getFirst().equals(actor)) throw missing();
    }
    private boolean dispatched(UUID orb){
        return jdbc.queryForObject("SELECT COUNT(*) FROM dispatch WHERE orb_id=?",Integer.class,orb)>0;
    }
    private void enqueue(UUID track,int attempt,OffsetDateTime now){
        jdbc.update("""
                INSERT INTO asmr_outbox_event(id,track_id,attempt,status,available_at,created_at,updated_at)
                VALUES (?,?,?,'PENDING',?,?,?)
                """,UUID.randomUUID(),track,attempt,now,now,now);
    }
    private static String audioPath(UUID track){return "/api/v1/asmr-tracks/"+track+"/audio";}
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","ASMR 작업을 찾을 수 없습니다.");}
    private static ApiException conflict(String code){return new ApiException(HttpStatus.CONFLICT,code,"ASMR 작업 상태를 확인해주세요.");}
    private record Gen(UUID id,UUID orb,String status,OffsetDateTime detached,OffsetDateTime created,OffsetDateTime updated){}
    private record Attempt(UUID id,int attempt){}
    private record AudioRow(UUID orb,String storageKey,UUID author){}
}
