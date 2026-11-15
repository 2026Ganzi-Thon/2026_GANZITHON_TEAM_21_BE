package com.ganzi.emori.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public class IdempotencyResponseCipher {
    private final SecretKeySpec key;
    private final SecureRandom random=new SecureRandom();
    public IdempotencyResponseCipher(@Value("${EMORI_IDEMPOTENCY_SECRET:}") String encoded) {
        byte[] bytes;
        if(encoded==null || encoded.isBlank()) {
            bytes=new byte[32]; random.nextBytes(bytes);
        } else {
            try { bytes=Base64.getDecoder().decode(encoded); }
            catch(IllegalArgumentException e) { throw new IllegalStateException("EMORI_IDEMPOTENCY_SECRET must be Base64",e); }
            if(bytes.length!=32) throw new IllegalStateException("EMORI_IDEMPOTENCY_SECRET must decode to 32 bytes");
        }
        key=new SecretKeySpec(bytes,"AES"); Arrays.fill(bytes,(byte)0);
    }
    public String protect(String json) {
        if(!json.contains("\"actionToken\":\"")) return json;
        try {
            byte[] nonce=new byte[12]; random.nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            byte[] encrypted=cipher.doFinal(json.getBytes(StandardCharsets.UTF_8));
            return "enc:v1:"+Base64.getEncoder().encodeToString(nonce)+":"+Base64.getEncoder().encodeToString(encrypted);
        } catch(Exception e) { throw new IllegalStateException("Could not encrypt idempotent response",e); }
    }
    public String reveal(String stored) {
        if(!stored.startsWith("enc:v1:")) return stored;
        try {
            String[] parts=stored.split(":",4);
            byte[] nonce=Base64.getDecoder().decode(parts[2]);
            byte[] ciphertext=Base64.getDecoder().decode(parts[3]);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            return new String(cipher.doFinal(ciphertext),StandardCharsets.UTF_8);
        } catch(Exception e) { throw new IllegalStateException("Could not decrypt idempotent response",e); }
    }
}
