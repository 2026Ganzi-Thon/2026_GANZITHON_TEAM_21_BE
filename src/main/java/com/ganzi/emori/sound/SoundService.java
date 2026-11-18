package com.ganzi.emori.sound;

import com.ganzi.emori.api.ApiException;
import com.ganzi.emori.api.DbSupport;
import com.ganzi.emori.asmr.AsmrModels.Playlist;
import com.ganzi.emori.asmr.AsmrModels.Track;
import com.ganzi.emori.asmr.AsmrStorage;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
public class SoundService {
    public record Sound(String id, String title, String color, int durationMs, String playbackUrl) {}
    public record Catalog(List<Sound> items) {}
    private record Row(UUID id, String title, String color, int duration, String key, boolean active) {}

    private final JdbcTemplate jdbc;
    private final AsmrStorage storage;
    public SoundService(JdbcTemplate jdbc, AsmrStorage storage) { this.jdbc=jdbc; this.storage=storage; }

    public Catalog catalog() {
        List<Sound> items = jdbc.query("SELECT id,title,color,duration_ms,storage_key,active FROM sound_catalog WHERE active=TRUE ORDER BY created_at,id",
                (rs,n) -> row(rs)).stream().filter(r -> storage.exists(r.key()))
                .map(r -> new Sound(r.id().toString(),r.title(),r.color(),r.duration(),path(r.id()))).toList();
        return new Catalog(items);
    }

    public UUID activeId(String id) {
        UUID parsed;
        try { parsed=UUID.fromString(id); } catch (RuntimeException e) { throw ApiException.invalid("Invalid soundId"); }
        List<Row> rows=jdbc.query("SELECT id,title,color,duration_ms,storage_key,active FROM sound_catalog WHERE id=?",
                (rs,n)->row(rs), parsed);
        if(rows.isEmpty() || !rows.getFirst().active() || !storage.exists(rows.getFirst().key()))
            throw ApiException.invalid("Inactive or unavailable soundId");
        return parsed;
    }

    public Playlist playlist(UUID orb) {
        List<Row> rows=jdbc.query("""
                SELECT s.id,s.title,s.color,s.duration_ms,s.storage_key,s.active
                FROM orb o JOIN sound_catalog s ON s.id=o.sound_id WHERE o.id=?
                """,(rs,n)->row(rs),orb);
        if(rows.isEmpty()) return null;
        Row r=rows.getFirst();
        return new Playlist(r.id().toString(),"READY",List.of(new Track(r.id().toString(),1,r.title(),r.duration(),attachedPath(orb))));
    }

    public byte[] preview(UUID id) {
        List<Row> rows=jdbc.query("SELECT id,title,color,duration_ms,storage_key,active FROM sound_catalog WHERE id=?",
                (rs,n)->row(rs),id);
        if(rows.isEmpty() || !rows.getFirst().active()) throw missing();
        return read(rows.getFirst().key());
    }

    public byte[] attached(UUID actor, UUID orb) {
        List<Row> rows=jdbc.query("""
                SELECT s.id,s.title,s.color,s.duration_ms,s.storage_key,s.active
                FROM orb o JOIN sound_catalog s ON s.id=o.sound_id WHERE o.id=?
                AND (o.author_id=? OR EXISTS (
                  SELECT 1 FROM dispatch d JOIN receipt r ON r.dispatch_id=d.id
                  WHERE d.orb_id=o.id AND r.recipient_id=? AND r.cancelled_at IS NULL))
                """,(rs,n)->row(rs),orb,actor,actor);
        if(rows.isEmpty()) throw missing();
        return read(rows.getFirst().key());
    }

    // Invoked only by the operator import command, never exposed to app users.
    @Transactional
    public UUID register(String title, String color, byte[] wav) throws IOException {
        if(title==null || title.isBlank() || title.length()>200 ||
                (color!=null && !List.of("YELLOW","ORANGE","PINK","RED","PURPLE","BLUE","GREEN").contains(color)))
            throw ApiException.invalid("Invalid sound metadata");
        int duration=wavDuration(wav);
        if(duration<1 || duration>15000) throw ApiException.invalid("Sound duration must be 1..15000ms");
        UUID id=UUID.randomUUID();
        String key="samples/"+id+".wav";
        storage.write(key,wav);
        jdbc.update("INSERT INTO sound_catalog(id,title,color,duration_ms,storage_key,active,created_at) VALUES (?,?,?,?,?,TRUE,?)",
                id,title,color,duration,key,DbSupport.now());
        return id;
    }

    public static int wavDuration(byte[] bytes) {
        if(bytes==null || bytes.length<44 || bytes[0]!='R' || bytes[1]!='I' || bytes[2]!='F' || bytes[3]!='F' ||
                bytes[8]!='W' || bytes[9]!='A' || bytes[10]!='V' || bytes[11]!='E')
            throw ApiException.invalid("PCM WAV required");
        try(var stream=AudioSystem.getAudioInputStream(new ByteArrayInputStream(bytes))) {
            if(stream.getFormat().getFrameRate()<=0 || stream.getFrameLength()<=0 ||
                    !stream.getFormat().getEncoding().toString().startsWith("PCM")) throw ApiException.invalid("PCM WAV required");
            long frames=stream.readAllBytes().length/stream.getFormat().getFrameSize();
            if(frames!=stream.getFrameLength()) throw ApiException.invalid("Incomplete WAV data");
            long ms=Math.round(frames*1000.0/stream.getFormat().getFrameRate());
            if(ms<1 || ms>15000) throw ApiException.invalid("Sound duration must be 1..15000ms");
            return (int)ms;
        } catch (Exception e) {
            if(e instanceof ApiException api) throw api;
            throw ApiException.invalid("Readable PCM WAV required");
        }
    }

    private byte[] read(String key) {
        try { return storage.read(key); }
        catch(IOException e) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AUDIO_UNAVAILABLE","Sound file unavailable"); }
    }
    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getString(5),rs.getBoolean(6));
    }
    private static String path(UUID id) { return "/api/v1/catalog/sounds/"+id+"/audio"; }
    private static String attachedPath(UUID id) { return "/api/v1/orbs/"+id+"/sound/audio"; }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Sound not found"); }
}
