package com.ganzi.emori;

import com.ganzi.emori.asmr.AsmrWorker;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("asmr-test")
@SpringBootTest(properties={"spring.datasource.url=${EMORI_STAGE7_TEST_DB_URL:jdbc:h2:mem:stage7_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
        "EMORI_ASMR_TEST_GENERATOR=true","EMORI_ASMR_STORAGE_ROOT=./build/stage7-audio"})
@AutoConfigureMockMvc
class Stage7IntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AsmrWorker worker;

    @Test void contractAndConnectedJourney() throws Exception {
        User sender=signup(),friend=signup(),otherFriend=signup(),stranger=signup();
        JsonNode login=call("POST","/auth/login",null,
                "{\"loginId\":\""+sender.login()+"\",\"password\":\"secret123\"}",200,null);
        String oldAccess=login.at("/session/accessToken").asText();
        JsonNode refreshed=call("POST","/auth/refresh",null,
                "{\"refreshToken\":\""+login.at("/session/refreshToken").asText()+"\"}",200,null);
        sender=new User(sender.id(),sender.login(),refreshed.path("accessToken").asText());
        assertThat(raw("GET","/me",oldAccess,null,null).status()).isEqualTo(401);
        befriend(sender,friend);befriend(sender,otherFriend);
        JsonNode page=call("GET","/friends?limit=1",sender,null,200,null);
        assertThat(page.path("items").size()).isEqualTo(1);
        assertThat(page.path("nextCursor").isNull()).isFalse();
        JsonNode next=call("GET","/friends?limit=1&cursor="+page.path("nextCursor").asText(),sender,null,200,null);
        assertThat(next.path("items").size()).isEqualTo(1);
        assertThat(next.at("/items/0/id").asText()).isNotEqualTo(page.at("/items/0/id").asText());
        assertThat(call("GET","/friends?limit=0",sender,null,422,null).at("/error/code").asText()).isEqualTo("INVALID_INPUT");
        assertThat(call("GET","/friend-requests?direction=BAD",sender,null,422,null).at("/error/code").asText()).isEqualTo("INVALID_INPUT");

        String orb=call("POST","/orbs",sender,"{\"emotionIds\":[\"yellow_03\",\"purple_01\"]}",201,
                UUID.randomUUID().toString()).at("/orb/id").asText();
        assertThat(call("POST","/orbs",sender,"{\"emotionIds\":[\"blue_01\"],\"soundId\":\"old\"}",422,
                UUID.randomUUID().toString()).at("/error/code").asText()).isEqualTo("INVALID_INPUT");
        String key=UUID.randomUUID().toString();String path="/orbs/"+orb+"/asmr-generations";
        String body="{\"description\":\"TEST_FAIL_ONE private description\"}";
        Response created=raw("POST",path,sender.token(),body,key);
        assertThat(created.status()).isEqualTo(202);
        JsonNode generation=json.readTree(created.body());String generationId=generation.path("id").asText();
        assertThat(created.location()).isEqualTo("/api/v1/asmr-generations/"+generationId);
        assertThat(call("POST",path,sender,body,202,key).path("id").asText()).isEqualTo(generationId);
        assertThat(call("POST",path,sender,"{}",409,key).at("/error/code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(raw("GET","/asmr-generations/"+generationId,sender.token(),null,null).retryAfter()).isEqualTo("3");
        assertThat(call("POST","/orbs/"+orb+"/dispatches",sender,
                "{\"route\":\"FRIEND\",\"friendId\":\""+friend.id()+"\"}",409,UUID.randomUUID().toString())
                .at("/error/code").asText()).isEqualTo("PLAYLIST_NOT_READY");
        for(int n=0;n<3;n++) assertThat(worker.runOnce()).isTrue();
        JsonNode partial=call("GET","/asmr-generations/"+generationId,sender,null,200,null);
        assertThat(partial.path("status").asText()).isEqualTo("PARTIAL_FAILED");
        String firstTrack=partial.at("/tracks/0/id").asText();
        call("POST","/asmr-generations/"+generationId+"/retry",sender,"{}",202,UUID.randomUUID().toString());
        assertThat(worker.runOnce()).isTrue();
        JsonNode ready=call("GET","/asmr-generations/"+generationId,sender,null,200,null);
        assertThat(ready.path("status").asText()).isEqualTo("READY");
        assertThat(ready.at("/tracks/0/id").asText()).isEqualTo(firstTrack);

        call("PUT","/me/rest",friend,"{\"mode\":\"MANUAL\"}",200,null);
        String installation=UUID.randomUUID().toString();
        call("PUT","/me/devices/"+installation,friend,
                "{\"platform\":\"ANDROID\",\"deliveryToken\":\"ExponentPushToken[stage7fixturetoken123]\"}",204,null);
        JsonNode sent=call("POST","/orbs/"+orb+"/dispatches",sender,
                "{\"route\":\"FRIEND\",\"friendId\":\""+friend.id()+"\"}",201,UUID.randomUUID().toString());
        String dispatch=sent.at("/dispatch/id").asText(),record=sent.path("recordId").asText();
        String receipt=jdbc.queryForObject("SELECT id FROM receipt WHERE dispatch_id=?",UUID.class,UUID.fromString(dispatch)).toString();
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE receipt_id=?",String.class,UUID.fromString(receipt)))
                .isEqualTo("SKIPPED");
        JsonNode inbox=call("GET","/inbox?unreadOnly=true",friend,null,200,null);
        assertThat(inbox.toString()).contains(receipt,firstTrack).doesNotContain("private description");
        assertThat(raw("GET","/asmr-tracks/"+firstTrack+"/audio",null,null,null).status()).isEqualTo(401);
        assertThat(raw("GET","/asmr-tracks/"+firstTrack+"/audio",stranger.token(),null,null).status()).isEqualTo(404);
        assertThat(raw("GET","/asmr-tracks/"+firstTrack+"/audio",friend.token(),null,null).bytes()).hasSizeGreaterThan(44);
        JsonNode opened=call("POST","/receipts/"+receipt+"/open",friend,"{}",200,UUID.randomUUID().toString());
        assertThat(opened.path("mode").asText()).isEqualTo("FIRST");
        assertThat(call("GET","/receipts/"+receipt+"/replay",friend,null,200,null).path("mode").asText()).isEqualTo("REPLAY");
        assertThat(call("POST","/dispatches/"+dispatch+"/targets/"+sent.at("/dispatch/targets/0/id").asText()+"/cancel",
                sender,"{}",409,UUID.randomUUID().toString()).at("/error/code").asText()).isEqualTo("CANCEL_NOT_ALLOWED");
        call("DELETE","/records/"+record,sender,null,204,null);
        assertThat(raw("GET","/asmr-tracks/"+firstTrack+"/audio",friend.token(),null,null).status()).isEqualTo(200);
        assertThat(call("GET","/records?side=RECEIVED",friend,null,200,null).toString()).contains(receipt);

        String cancelOrb=orb(sender,"yellow_01");
        JsonNode cancellable=call("POST","/orbs/"+cancelOrb+"/dispatches",sender,
                "{\"route\":\"FRIEND\",\"friendId\":\""+friend.id()+"\"}",201,UUID.randomUUID().toString());
        String cancelledReceipt=jdbc.queryForObject("SELECT id FROM receipt WHERE dispatch_id=?",UUID.class,
                UUID.fromString(cancellable.at("/dispatch/id").asText())).toString();
        call("POST","/dispatches/"+cancellable.at("/dispatch/id").asText()+"/targets/"+
                cancellable.at("/dispatch/targets/0/id").asText()+"/cancel",sender,"{}",200,UUID.randomUUID().toString());
        assertThat(call("POST","/receipts/"+cancelledReceipt+"/open",friend,"{}",404,
                UUID.randomUUID().toString()).at("/error/code").asText()).isEqualTo("NOT_FOUND");

        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        for(int daysAgo=0;daysAgo<=3;daysAgo++){
            String careOrb=orb(sender,"purple_01");
            String own=call("POST","/orbs/"+careOrb+"/dispatches",sender,"{\"route\":\"SELF\"}",201,
                    UUID.randomUUID().toString()).path("recordId").asText();
            jdbc.update("UPDATE emotion_record SET recorded_at=? WHERE id=?",
                    today.minusDays(daysAgo).atTime(12,0).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),UUID.fromString(own));
        }
        JsonNode care=call("GET","/me/care",sender,null,200,null);
        assertThat(care.path("daysIn7").asInt()).isEqualTo(4); // Deleted sender record is excluded.
        assertThat(care.path("level").asInt()).isEqualTo(1);
    }

    private User signup() throws Exception {String login="s7"+UUID.randomUUID().toString().substring(0,8);
        JsonNode result=call("POST","/auth/signup",null,
                "{\"loginId\":\""+login+"\",\"password\":\"secret123\"}",201,null);
        return new User(result.at("/user/id").asText(),login,result.at("/session/accessToken").asText());}
    private void befriend(User a,User b) throws Exception {String id=call("POST","/friend-requests",a,
            "{\"friendCode\":\""+b.login()+"\"}",201,null).path("id").asText();
        call("PUT","/friend-requests/"+id+"/decision",b,"{\"decision\":\"ACCEPT\"}",200,null);}
    private String orb(User a,String emotion) throws Exception {return call("POST","/orbs",a,
            "{\"emotionIds\":[\""+emotion+"\"]}",201,UUID.randomUUID().toString()).at("/orb/id").asText();}
    private JsonNode call(String method,String path,User user,String body,int status,String key) throws Exception {
        Response response=raw(method,path,user==null?null:user.token(),body,key);
        assertThat(response.status()).as(method+" "+path+" "+response.body()).isEqualTo(status);
        return response.body().isBlank()?null:json.readTree(response.body());
    }
    private Response raw(String method,String path,String token,String body,String key) throws Exception {
        var request=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),"/api/v1"+path);
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(body);
        if(key!=null)request.header("Idempotency-Key",key);
        var response=mvc.perform(request).andReturn().getResponse();
        return new Response(response.getStatus(),response.getContentAsString(),response.getContentAsByteArray(),
                response.getHeader("Location"),response.getHeader("Retry-After"));
    }
    private record User(String id,String login,String token){}
    private record Response(int status,String body,byte[] bytes,String location,String retryAfter){}
}
