package com.ganzi.emori;

import com.ganzi.emori.sound.SoundService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties={"spring.datasource.url=${EMORI_STAGE8_TEST_DB_URL:jdbc:h2:mem:stage8_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
        "EMORI_ASMR_STORAGE_ROOT=./build/stage8-audio"})
@AutoConfigureMockMvc
class Stage8HttpTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired SoundService sounds;

    @Test void colorSoundAndDelivery() throws Exception {
        byte[] wav=wav(1000);
        assertThatThrownBy(()->sounds.register("too long",null,wav(16000))).isInstanceOf(RuntimeException.class);
        UUID sound=sounds.register("Test fixture",null,wav);
        User sender=signup(),friend=signup(),stranger=signup();
        assertThat(call("GET","/catalog/sounds",sender,null,null,200).at("/items/0/id").asText()).isEqualTo(sound.toString());
        assertThat(raw("GET","/catalog/sounds/"+sound+"/audio",null,null,null).status()).isEqualTo(401);
        assertThat(raw("GET","/catalog/sounds/"+sound+"/audio",friend.token(),null,null).bytes()).isEqualTo(wav);

        String key=UUID.randomUUID().toString();
        String body="{\"colors\":[\"BLUE\",\"PURPLE\"],\"soundId\":\""+sound+"\"}";
        JsonNode created=call("POST","/orbs",sender,body,key,201);
        String orb=created.at("/orb/id").asText();
        assertThat(call("POST","/orbs",sender,body,key,201).at("/orb/id").asText()).isEqualTo(orb);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orb WHERE id=?",Integer.class,UUID.fromString(orb))).isEqualTo(1);
        assertThat(created.at("/orb/emotions/0/color").asText()).isEqualTo("BLUE");
        assertThat(created.at("/orb/emotions/0/words").size()).isEqualTo(4);
        assertThat(created.at("/orb/emotions/1/color").asText()).isEqualTo("PURPLE");
        assertThat(created.at("/orb/playlist/tracks/0/id").asText()).isEqualTo(sound.toString());
        assertThat(call("POST","/orbs",sender,"{\"colors\":[\"BLUE\",\"BLUE\"]}",UUID.randomUUID().toString(),422).at("/error/code").asText()).isEqualTo("INVALID_INPUT");
        assertThat(call("POST","/orbs",sender,"{\"colors\":[\"INVALID\"]}",UUID.randomUUID().toString(),422).at("/error/code").asText()).isEqualTo("INVALID_INPUT");
        assertThat(call("POST","/orbs",sender,"{\"colors\":[\"BLUE\"],\"soundId\":\""+UUID.randomUUID()+"\"}",UUID.randomUUID().toString(),422).at("/error/code").asText()).isEqualTo("INVALID_INPUT");
        assertThat(call("POST","/orbs/"+orb+"/asmr-generations",sender,"{}",UUID.randomUUID().toString(),409).at("/error/code").asText()).isEqualTo("ACTION_NOT_ALLOWED");
        jdbc.update("UPDATE sound_catalog SET active=FALSE WHERE id=?",sound);
        assertThat(call("GET","/catalog/sounds",sender,null,null,200).path("items").size()).isEqualTo(0);
        assertThat(raw("GET","/catalog/sounds/"+sound+"/audio",sender.token(),null,null).status()).isEqualTo(404);
        assertThat(call("POST","/orbs",sender,body,UUID.randomUUID().toString(),422).at("/error/code").asText()).isEqualTo("INVALID_INPUT");
        assertThat(raw("GET","/orbs/"+orb+"/sound/audio",stranger.token(),null,null).status()).isEqualTo(404);
        assertThat(raw("GET","/orbs/"+orb+"/sound/audio",sender.token(),null,null).bytes()).isEqualTo(wav);

        befriend(sender,friend);
        String dispatchPath="/orbs/"+orb+"/dispatches",dispatchKey=UUID.randomUUID().toString();
        String dispatchBody="{\"route\":\"FRIEND\",\"friendId\":\""+friend.id()+"\"}";
        JsonNode dispatched=call("POST",dispatchPath,sender,dispatchBody,dispatchKey,201);
        String dispatch=dispatched.at("/dispatch/id").asText();
        assertThat(call("POST",dispatchPath,sender,dispatchBody,dispatchKey,201).at("/dispatch/id").asText()).isEqualTo(dispatch);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receipt WHERE dispatch_id=?",Integer.class,UUID.fromString(dispatch))).isEqualTo(1);
        JsonNode inbox=call("GET","/inbox",friend,null,null,200);
        String receipt=inbox.at("/items/0/id").asText();
        assertThat(inbox.at("/items/0/orb/playlist/tracks/0/playbackUrl").asText()).isEqualTo("/api/v1/orbs/"+orb+"/sound/audio");
        assertThat(inbox.at("/items/0/openedAt").isNull()).isTrue();
        assertThat(raw("GET","/orbs/"+orb+"/sound/audio",friend.token(),null,null).bytes()).isEqualTo(wav);
        assertThat(call("POST","/receipts/"+receipt+"/open",friend,"{}",UUID.randomUUID().toString(),200).path("mode").asText()).isEqualTo("FIRST");
        assertThat(call("GET","/receipts/"+receipt+"/replay",friend,null,null,200).path("mode").asText()).isEqualTo("REPLAY");
        assertThat(call("GET","/records?side=OWN",sender,null,null,200).at("/items/0/counterpart/id").asText()).isEqualTo(friend.id());
        assertThat(call("GET","/records?side=RECEIVED",friend,null,null,200).at("/items/0/counterpart/id").asText()).isEqualTo(sender.id());
        call("DELETE","/records/"+dispatched.path("recordId").asText(),sender,null,null,204);
        assertThat(raw("GET","/orbs/"+orb+"/sound/audio",friend.token(),null,null).status()).isEqualTo(200);

        JsonNode silent=call("POST","/orbs",sender,"{\"colors\":[\"YELLOW\"],\"soundId\":null}",UUID.randomUUID().toString(),201);
        assertThat(silent.at("/orb/playlist").isNull()).isTrue();
        call("POST","/orbs/"+silent.at("/orb/id").asText()+"/dispatches",sender,"{\"route\":\"SELF\"}",UUID.randomUUID().toString(),201);
        JsonNode careOrb=call("POST","/orbs",sender,"{\"colors\":[\"BLUE\",\"PURPLE\"]}",UUID.randomUUID().toString(),201);
        call("POST","/orbs/"+careOrb.at("/orb/id").asText()+"/dispatches",sender,"{\"route\":\"SELF\"}",UUID.randomUUID().toString(),201);
        assertThat(call("GET","/me/care",sender,null,null,200).path("daysIn7").asInt()).isEqualTo(1);
    }

    private User signup() throws Exception {
        String login="s8"+UUID.randomUUID().toString().substring(0,8);
        JsonNode result=call("POST","/auth/signup",null,"{\"loginId\":\""+login+"\",\"password\":\"secret123\"}",null,201);
        return new User(result.at("/user/id").asText(),login,result.at("/session/accessToken").asText());
    }
    private void befriend(User a,User b) throws Exception {
        String id=call("POST","/friend-requests",a,"{\"friendCode\":\""+b.login()+"\"}",null,201).path("id").asText();
        call("PUT","/friend-requests/"+id+"/decision",b,"{\"decision\":\"ACCEPT\"}",null,200);
    }
    private JsonNode call(String method,String path,User user,String body,String key,int expected) throws Exception {
        Response r=raw(method,path,user==null?null:user.token(),body,key);
        assertThat(r.status()).as(method+" "+path+" "+r.body()).isEqualTo(expected);
        return r.body().isBlank()?null:json.readTree(r.body());
    }
    private Response raw(String method,String path,String token,String body,String key) throws Exception {
        var request=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),"/api/v1"+path);
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(body);
        if(key!=null)request.header("Idempotency-Key",key);
        var response=mvc.perform(request).andReturn().getResponse();
        return new Response(response.getStatus(),response.getContentAsString(),response.getContentAsByteArray());
    }
    private static byte[] wav(int millis) {
        int samples=8000*millis/1000;
        ByteBuffer b=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R','I','F','F'}).putInt(36+samples*2).put(new byte[]{'W','A','V','E'});
        b.put(new byte[]{'f','m','t',' '}).putInt(16).putShort((short)1).putShort((short)1);
        b.putInt(8000).putInt(16000).putShort((short)2).putShort((short)16);
        b.put(new byte[]{'d','a','t','a'}).putInt(samples*2);
        return b.array();
    }
    private record User(String id,String login,String token) {}
    private record Response(int status,String body,byte[] bytes) {}
}
