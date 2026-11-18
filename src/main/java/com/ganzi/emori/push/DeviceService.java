package com.ganzi.emori.push;

import com.ganzi.emori.api.ApiException;
import com.ganzi.emori.api.DbSupport;
import com.ganzi.emori.api.IdempotencyResponseCipher;
import com.ganzi.emori.push.DeviceController.Registration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class DeviceService {
    private static final Pattern EXPO=Pattern.compile("(?:Expo|Exponent)PushToken\\[[A-Za-z0-9_-]{10,200}\\]");
    private final JdbcTemplate jdbc;
    private final IdempotencyResponseCipher cipher;
    public DeviceService(JdbcTemplate jdbc,IdempotencyResponseCipher cipher){this.jdbc=jdbc;this.cipher=cipher;}
    @Transactional
    public void register(UUID actor,String bearer,String idText,Registration input){
        UUID installation=DbSupport.pathId(idText);
        if(input==null || input.platform()==null || !List.of("IOS","ANDROID").contains(input.platform()) ||
                input.deliveryToken()==null || !EXPO.matcher(input.deliveryToken()).matches())
            throw ApiException.invalid("Valid platform and Expo push token are required");
        String hash=DbSupport.hash(input.deliveryToken());
        String accessHash=DbSupport.hash(bearer.substring(7));
        List<UUID> sessions=jdbc.query("""
                SELECT id FROM auth_session WHERE user_id=? AND access_token_hash=? AND revoked_at IS NULL
                """,(rs,n)->rs.getObject(1,UUID.class),actor,accessHash);
        if(sessions.isEmpty()) throw ApiException.unauthorized();
        jdbc.query("SELECT id FROM device_registration_guard WHERE id=1 FOR UPDATE",(rs,n)->rs.getInt(1));
        // A token may move to a different installation/account; old queued jobs remain tied to their old owner.
        jdbc.update("DELETE FROM device_registration WHERE token_hash=? AND installation_id<>?",hash,installation);
        if(jdbc.query("SELECT installation_id FROM device_registration WHERE installation_id=?",
                (rs,n)->rs.getObject(1),installation).isEmpty()) {
            var now=DbSupport.now();
            jdbc.update("""
                    INSERT INTO device_registration(installation_id,user_id,session_id,platform,token_hash,
                        token_ciphertext,key_version,registered_at,updated_at) VALUES (?,?,?,?,?,?,1,?,?)
                    """,installation,actor,sessions.getFirst(),input.platform(),hash,
                    cipher.encryptSecret(input.deliveryToken()).getBytes(StandardCharsets.UTF_8),now,now);
        } else {
            jdbc.update("""
                    UPDATE device_registration SET user_id=?,session_id=?,platform=?,token_hash=?,
                    token_ciphertext=?,key_version=1,updated_at=? WHERE installation_id=?
                    """,actor,sessions.getFirst(),input.platform(),hash,
                    cipher.encryptSecret(input.deliveryToken()).getBytes(StandardCharsets.UTF_8),DbSupport.now(),installation);
        }
    }
    @Transactional
    public void delete(UUID actor,String idText){
        UUID id=DbSupport.pathId(idText);
        jdbc.query("SELECT id FROM device_registration_guard WHERE id=1 FOR UPDATE",(rs,n)->rs.getInt(1));
        List<UUID> owners=jdbc.query("SELECT user_id FROM device_registration WHERE installation_id=?",
                (rs,n)->rs.getObject(1,UUID.class),id);
        if(owners.isEmpty()) return;
        if(!owners.getFirst().equals(actor)) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Device not found");
        jdbc.update("DELETE FROM device_registration WHERE installation_id=? AND user_id=?",id,actor);
    }
}
