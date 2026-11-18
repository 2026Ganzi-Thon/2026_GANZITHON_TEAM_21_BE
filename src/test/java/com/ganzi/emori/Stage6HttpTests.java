package com.ganzi.emori;

import com.ganzi.emori.asmr.AsmrWorker;
import com.ganzi.emori.asmr.AsmrStorage;
import com.ganzi.emori.asmr.TestAsmrProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("asmr-test")
@SpringBootTest(properties={"spring.datasource.url=${EMORI_STAGE6_TEST_DB_URL:jdbc:h2:mem:stage6_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
        "EMORI_ASMR_TEST_GENERATOR=true","EMORI_ASMR_STORAGE_ROOT=./build/stage6-audio"})
@AutoConfigureMockMvc
class Stage6HttpTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AsmrWorker worker;
    @Autowired AsmrStorage storage;
    @Autowired TestAsmrProvider provider;
    @Autowired TransactionTemplate tx;

    @Test void partialRetrySendReplayAndAccess() throws Exception {
        User sender=signup(),recipient=signup(),stranger=signup();friend(sender,recipient);
        String orb=orb(sender);
        String key=UUID.randomUUID().toString();
        JsonNode generation=call("POST","/orbs/"+orb+"/asmr-generations",sender,
                "{\"description\":\"TEST_FAIL_ONE private note\"}",202,key);
        assertThat(call("POST","/orbs/"+orb+"/asmr-generations",sender,
                "{\"description\":\"TEST_FAIL_ONE private note\"}",202,key).path("id").asText()).isEqualTo(generation.path("id").asText());
        assertThat(call("POST","/orbs/"+orb+"/asmr-generations",sender,"{}",409,UUID.randomUUID().toString())
                .at("/error/code").asText()).isEqualTo("GENERATION_EXISTS");
        String send="{\"route\":\"FRIEND\",\"friendId\":\""+recipient.id()+"\"}";
        assertThat(call("POST","/orbs/"+orb+"/dispatches",sender,send,409,UUID.randomUUID().toString())
                .at("/error/code").asText()).isEqualTo("PLAYLIST_NOT_READY");
        assertThat(call("DELETE","/orbs/"+orb+"/playlist",sender,null,409,null)
                .at("/error/code").asText()).isEqualTo("GENERATION_IN_PROGRESS");
        for(int n=0;n<3;n++) assertThat(worker.runOnce()).isTrue();
        JsonNode partial=call("GET","/asmr-generations/"+generation.path("id").asText(),sender,null,200,null);
        assertThat(partial.path("status").asText()).isEqualTo("PARTIAL_FAILED");
        String first=partial.at("/tracks/0/id").asText(),third=partial.at("/tracks/2/id").asText();
        assertThat(partial.toString()).doesNotContain("private note");
        assertThat(partial.at("/tracks/0/durationMs").asInt()).isEqualTo(30000);
        call("POST","/asmr-generations/"+generation.path("id").asText()+"/retry",sender,"{}",202,UUID.randomUUID().toString());
        assertThat(call("POST","/asmr-generations/"+generation.path("id").asText()+"/retry",sender,"{}",409,UUID.randomUUID().toString())
                .at("/error/code").asText()).isEqualTo("GENERATION_IN_PROGRESS");
        UUID failedTrack=UUID.fromString(partial.at("/tracks/1/id").asText());
        jdbc.update("""
                UPDATE asmr_outbox_event SET status='PROCESSING',lease_token=?,lease_until=?,available_at=?
                WHERE track_id=? AND attempt=1
                """,UUID.randomUUID(),OffsetDateTime.now().minusSeconds(1),OffsetDateTime.now().minusDays(1),failedTrack);
        assertThat(worker.runOnce()).isFalse(); // A recovered event from attempt 1 cannot modify attempt 2.
        assertThat(jdbc.queryForObject("SELECT status FROM asmr_outbox_event WHERE track_id=? AND attempt=1",
                String.class,failedTrack)).isEqualTo("FAILED");
        assertThat(worker.runOnce()).isTrue();
        JsonNode ready=call("GET","/asmr-generations/"+generation.path("id").asText(),sender,null,200,null);
        assertThat(ready.path("status").asText()).isEqualTo("READY");
        assertThat(ready.at("/tracks/0/id").asText()).isEqualTo(first);
        assertThat(ready.at("/tracks/2/id").asText()).isEqualTo(third);
        assertThat(ready.at("/tracks/1/order").asInt()).isEqualTo(2);
        JsonNode sent=call("POST","/orbs/"+orb+"/dispatches",sender,send,201,UUID.randomUUID().toString());
        String record=sent.path("recordId").asText();
        JsonNode inbox=call("GET","/inbox",recipient,null,200,null);
        String receipt=inbox.at("/items/0/id").asText();
        assertThat(inbox.toString()).doesNotContain("private note");
        assertThat(inbox.at("/items/0/orb/playlist/tracks/0/id").asText()).isEqualTo(first);
        String audio="/asmr-tracks/"+first+"/audio";
        byte[] original=audio(sender,audio,200);
        assertThat(original.length).isGreaterThan(44);
        assertThat(audio(stranger,audio,404)).isNotEqualTo(original);
        call("POST","/receipts/"+receipt+"/open",recipient,"{}",200,UUID.randomUUID().toString());
        assertThat(call("GET","/receipts/"+receipt+"/replay",recipient,null,200,null).toString())
                .doesNotContain("private note");
        call("DELETE","/records/"+record,sender,null,204,null);
        assertThat(audio(recipient,audio,200)).isEqualTo(original);
        assertThat(call("DELETE","/orbs/"+orb+"/playlist",sender,null,409,null)
                .at("/error/code").asText()).isEqualTo("ALREADY_DISPATCHED");
    }

    @Test void overLimitDetachAndExpiredLeaseRecovery() throws Exception {
        User owner=signup();String orb=orb(owner);
        JsonNode generation=call("POST","/orbs/"+orb+"/asmr-generations",owner,
                "{\"description\":\"TEST_OVER_LIMIT\"}",202,UUID.randomUUID().toString());
        for(int n=0;n<3;n++)worker.runOnce();
        assertThat(call("GET","/asmr-generations/"+generation.path("id").asText(),owner,null,200,null)
                .at("/tracks/1/errorCode").asText()).isEqualTo("INVALID_AUDIO");
        String track=generation.at("/tracks/1/id").asText();
        call("POST","/asmr-generations/"+generation.path("id").asText()+"/retry",owner,"{}",202,UUID.randomUUID().toString());
        UUID oldLease=UUID.randomUUID();
        jdbc.update("UPDATE asmr_outbox_event SET status='PROCESSING',lease_token=?,lease_until=? WHERE track_id=? AND attempt=2",
                oldLease,OffsetDateTime.now().minusSeconds(1),UUID.fromString(track));
        assertThat(worker.runOnce()).isTrue();
        String state=jdbc.queryForObject("SELECT status FROM asmr_generation WHERE id=?",String.class,
                UUID.fromString(generation.path("id").asText()));
        assertThat(state).isEqualTo("PARTIAL_FAILED");
        call("DELETE","/orbs/"+orb+"/playlist",owner,null,204,null);
        call("DELETE","/orbs/"+orb+"/playlist",owner,null,204,null);
        assertThat(call("GET","/orbs/"+orb,owner,null,200,null).at("/orb/playlist").isNull()).isTrue();
        assertThat(audio(owner,"/asmr-tracks/"+generation.at("/tracks/0/id").asText()+"/audio",200)).hasSizeGreaterThan(44);
        call("POST","/orbs/"+orb+"/dispatches",owner,"{\"route\":\"SELF\"}",201,UUID.randomUUID().toString());
    }
    @Test void restartedWorkerIgnoresLateLeaseResult() throws Exception {
        User owner=signup();String orb=orb(owner);
        JsonNode generation=call("POST","/orbs/"+orb+"/asmr-generations",owner,"{}",202,UUID.randomUUID().toString());
        UUID first=UUID.fromString(generation.at("/tracks/0/id").asText());
        jdbc.update("""
                UPDATE asmr_outbox_event SET available_at=? WHERE track_id<>? AND track_id IN
                (SELECT id FROM asmr_track WHERE generation_id=?)
                """,OffsetDateTime.now().plusDays(1),first,UUID.fromString(generation.path("id").asText()));
        provider.pauseNextStart();
        CompletableFuture<Boolean> old=CompletableFuture.supplyAsync(worker::runOnce);
        try {
            assertThat(provider.awaitPaused()).isTrue();
            UUID event=jdbc.queryForObject("SELECT id FROM asmr_outbox_event WHERE track_id=?",UUID.class,first);
            UUID oldLease=jdbc.queryForObject("SELECT lease_token FROM asmr_outbox_event WHERE id=?",UUID.class,event);
            jdbc.update("UPDATE asmr_outbox_event SET lease_until=? WHERE id=?",OffsetDateTime.now().minusSeconds(1),event);
            AsmrWorker restarted=new AsmrWorker(jdbc,tx,provider,storage,false);
            assertThat(restarted.runOnce()).isTrue();
            String accepted=jdbc.queryForObject("SELECT storage_key FROM asmr_track WHERE id=?",String.class,first);
            assertThat(accepted).isNotBlank().doesNotContain(oldLease.toString());
            provider.resume();
            assertThat(old.get()).isTrue();
            assertThat(jdbc.queryForObject("SELECT storage_key FROM asmr_track WHERE id=?",String.class,first)).isEqualTo(accepted);
            assertThat(jdbc.queryForObject("SELECT status FROM asmr_outbox_event WHERE id=?",String.class,event)).isEqualTo("SENT");
            jdbc.update("""
                    UPDATE asmr_outbox_event SET available_at=? WHERE status='PENDING' AND track_id IN
                    (SELECT id FROM asmr_track WHERE generation_id=?)
                    """,OffsetDateTime.now().minusSeconds(1),UUID.fromString(generation.path("id").asText()));
            assertThat(restarted.runOnce()).isTrue();assertThat(restarted.runOnce()).isTrue();
            assertThat(call("GET","/asmr-generations/"+generation.path("id").asText(),owner,null,200,null)
                    .path("status").asText()).isEqualTo("READY");
        } finally {provider.resume();}
    }
    @Test void storageFailureLeavesNoReadyMetadataAndCanRetry() throws Exception {
        User owner=signup();String orb=orb(owner);
        JsonNode generation=call("POST","/orbs/"+orb+"/asmr-generations",owner,"{}",202,UUID.randomUUID().toString());
        AsmrWorker brokenStorage=new AsmrWorker(jdbc,tx,provider,new AsmrStorage("build.gradle"),false);
        for(int n=0;n<3;n++) assertThat(brokenStorage.runOnce()).isTrue();
        UUID id=UUID.fromString(generation.path("id").asText());
        assertThat(jdbc.queryForObject("SELECT status FROM asmr_generation WHERE id=?",String.class,id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asmr_track WHERE generation_id=? AND storage_key IS NOT NULL",
                Integer.class,id)).isZero();
        call("POST","/asmr-generations/"+id+"/retry",owner,"{}",202,UUID.randomUUID().toString());
        for(int n=0;n<12;n++) {
            if("READY".equals(jdbc.queryForObject("SELECT status FROM asmr_generation WHERE id=?",String.class,id))) break;
            worker.runOnce();
        }
        assertThat(call("GET","/asmr-generations/"+id,owner,null,200,null).path("status").asText()).isEqualTo("READY");
    }
    private User signup() throws Exception {String login="s6"+UUID.randomUUID().toString().substring(0,8);
        JsonNode result=call("POST","/auth/signup",null,"{\"loginId\":\""+login+"\",\"password\":\"secret123\"}",201,null);
        return new User(result.at("/user/id").asText(),login,result.at("/session/accessToken").asText());}
    private void friend(User a,User b) throws Exception {String id=call("POST","/friend-requests",a,
            "{\"friendCode\":\""+b.login()+"\"}",201,null).path("id").asText();
        call("PUT","/friend-requests/"+id+"/decision",b,"{\"decision\":\"ACCEPT\"}",200,null);}
    private String orb(User a) throws Exception {return call("POST","/orbs",a,"{\"emotionIds\":[\"yellow_01\"]}",201,
            UUID.randomUUID().toString()).at("/orb/id").asText();}
    private JsonNode call(String method,String path,User user,String body,int expected,String key) throws Exception {
        var request=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),"/api/v1"+path);
        if(user!=null)request.header("Authorization","Bearer "+user.token());
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(body);
        if(key!=null)request.header("Idempotency-Key",key);
        var response=mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(method+" "+path+" "+response.getContentAsString()).isEqualTo(expected);
        return response.getContentAsString().isBlank()?null:json.readTree(response.getContentAsString());
    }
    private byte[] audio(User user,String path,int expected) throws Exception {
        var response=mvc.perform(MockMvcRequestBuilders.get("/api/v1"+path).header("Authorization","Bearer "+user.token()))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(expected);return response.getContentAsByteArray();
    }
    private record User(String id,String login,String token){}
}
