package com.ganzi.emori;

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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=${EMORI_STAGE3_TEST_DB_URL:jdbc:h2:mem:stage3_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}")
@AutoConfigureMockMvc
class Stage3HttpTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test void inboxOpenReplayOwnershipAndDelete() throws Exception {
        User a = signup(), b = signup(), stranger = signup();
        befriend(a,b);
        Send first = send(a,b);
        assertThat(call("GET", "/inbox?unreadOnly=true", b, null, null, 200).at("/items/0/id").asText()).isEqualTo(first.receipt());
        assertThat(call("GET", "/records/"+first.ownRecord(), a, null, null, 200).at("/record/side").asText()).isEqualTo("OWN");
        assertThat(call("GET", "/records/"+first.receivedRecord(), b, null, null, 200).at("/dispatch").isNull()).isTrue();
        assertThat(call("GET", "/records/"+first.ownRecord(), stranger, null, null, 404).at("/error/code").asText()).isEqualTo("NOT_FOUND");
        assertThat(call("GET", "/receipts/"+first.receipt()+"/replay", b, null, null, 409).at("/error/code").asText()).isEqualTo("NOT_OPENED");
        assertThat(jdbc.queryForObject("SELECT opened_at IS NULL FROM receipt WHERE id=?", Boolean.class, UUID.fromString(first.receipt()))).isTrue();
        String key=UUID.randomUUID().toString();
        JsonNode opened=call("POST", "/receipts/"+first.receipt()+"/open", b, key, "{}", 200);
        assertThat(opened.path("mode").asText()).isEqualTo("FIRST");
        assertThat(opened.path("actions").size()).isZero();
        assertThat(opened.path("actionToken").isNull()).isTrue();
        String firstOpenedAt=opened.at("/receipt/openedAt").asText();
        assertThat(call("POST", "/receipts/"+first.receipt()+"/open", b, key, "{}", 200)).isEqualTo(opened);
        JsonNode repeat=call("POST", "/receipts/"+first.receipt()+"/open", b, UUID.randomUUID().toString(), "{}", 200);
        assertThat(repeat.path("mode").asText()).isEqualTo("REPLAY");
        assertThat(repeat.at("/receipt/openedAt").asText()).isEqualTo(firstOpenedAt);
        assertThat(call("GET", "/receipts/"+first.receipt()+"/replay", b, null, null, 200).path("mode").asText()).isEqualTo("REPLAY");
        assertThat(call("GET", "/inbox?unreadOnly=true", b, null, null, 200).path("items").size()).isZero();
        assertThat(call("POST", "/receipts/"+first.receipt()+"/reaction", b, UUID.randomUUID().toString(), "{\"actionToken\":\"x\",\"code\":\"HEART\"}", 403).at("/error/code").asText()).isEqualTo("ACTION_NOT_ALLOWED");
        assertThat(call("POST", "/receipts/"+first.receipt()+"/finish", b, null, "{\"actionToken\":\"x\"}", 403).at("/error/code").asText()).isEqualTo("ACTION_NOT_ALLOWED");
        assertThat(call("POST", "/dispatches/"+first.dispatch()+"/targets/"+first.target()+"/cancel", a, UUID.randomUUID().toString(), "{}", 409).at("/error/code").asText()).isEqualTo("CANCEL_NOT_ALLOWED");
        call("DELETE", "/records/"+first.ownRecord(), a, null, null, 204);
        call("DELETE", "/records/"+first.ownRecord(), a, null, null, 204);
        assertThat(call("GET", "/records/"+first.ownRecord(), a, null, null, 404).at("/error/code").asText()).isEqualTo("NOT_FOUND");
        assertThat(call("GET", "/records/"+first.receivedRecord(), b, null, null, 200).at("/record/receiptId").asText()).isEqualTo(first.receipt());
        assertThat(call("DELETE", "/records/"+first.receivedRecord(), b, null, null, 403).at("/error/code").asText()).isEqualTo("ACTION_NOT_ALLOWED");
    }

    @Test void cancelWindowAndRace() throws Exception {
        User a=signup(), b=signup(); befriend(a,b);
        Send cancelled=send(a,b);
        String path="/dispatches/"+cancelled.dispatch()+"/targets/"+cancelled.target()+"/cancel";
        String key=UUID.randomUUID().toString();
        JsonNode response=call("POST",path,a,key,"{}",200);
        assertThat(response.at("/dispatch/status").asText()).isEqualTo("CANCELLED");
        assertThat(call("POST",path,a,key,"{}",200)).isEqualTo(response);
        assertThat(call("POST",path,a,UUID.randomUUID().toString(),"{}",409).at("/error/code").asText()).isEqualTo("CANCEL_NOT_ALLOWED");
        assertThat(call("GET","/receipts/"+cancelled.receipt()+"/replay",b,null,null,404).at("/error/code").asText()).isEqualTo("NOT_FOUND");
        assertThat(call("GET","/records/"+cancelled.receivedRecord(),b,null,null,404).at("/error/code").asText()).isEqualTo("NOT_FOUND");
        assertThat(call("GET","/records/"+cancelled.ownRecord(),a,null,null,200).at("/dispatch/status").asText()).isEqualTo("CANCELLED");
        Send expired=send(a,b);
        jdbc.update("UPDATE dispatch SET cancel_until=? WHERE id=?", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1), UUID.fromString(expired.dispatch()));
        assertThat(call("POST","/dispatches/"+expired.dispatch()+"/targets/"+expired.target()+"/cancel",a,UUID.randomUUID().toString(),"{}",409).at("/error/code").asText()).isEqualTo("CANCEL_NOT_ALLOWED");
        Send racing=send(a,b);
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var opening=pool.submit(() -> {start.await(); return status("POST","/receipts/"+racing.receipt()+"/open",b,UUID.randomUUID().toString(),"{}");});
            var cancelling=pool.submit(() -> {start.await(); return status("POST","/dispatches/"+racing.dispatch()+"/targets/"+racing.target()+"/cancel",a,UUID.randomUUID().toString(),"{}");});
            start.countDown();
            List<Integer> outcomes=List.of(opening.get(),cancelling.get());
            assertThat(outcomes).contains(200);
            assertThat(outcomes).containsAnyOf(404,409);
        }
        var state=jdbc.queryForMap("SELECT opened_at,cancelled_at FROM receipt WHERE id=?",UUID.fromString(racing.receipt()));
        assertThat((state.get("opened_at")!=null)^(state.get("cancelled_at")!=null)).isTrue();
    }

    @Test void paginationDatesAndEqualTimestamps() throws Exception {
        User a=signup();
        OffsetDateTime same=OffsetDateTime.parse("2026-11-15T15:30:00Z"); // Nov 16 in KST.
        for(int i=0;i<105;i++) {
            String orb=call("POST","/orbs",a,UUID.randomUUID().toString(),"{\"emotionIds\":[\"blue_01\"]}",201).at("/orb/id").asText();
            JsonNode saved=call("POST","/orbs/"+orb+"/dispatches",a,UUID.randomUUID().toString(),"{\"route\":\"SELF\"}",201);
            jdbc.update("UPDATE emotion_record SET recorded_at=? WHERE id=?",same,UUID.fromString(saved.path("recordId").asText()));
        }
        String cursor=null; HashSet<String> ids=new HashSet<>();
        do {
            JsonNode page=call("GET","/records?side=OWN&date=2026-11-16&limit=37"+(cursor==null?"":"&cursor="+cursor),a,null,null,200);
            for(JsonNode item:page.path("items")) assertThat(ids.add(item.path("id").asText())).isTrue();
            cursor=page.path("nextCursor").isNull()?null:page.path("nextCursor").asText();
        } while(cursor!=null);
        assertThat(ids).hasSize(105);
        assertThat(call("GET","/records?side=OWN&date=2026-11-15",a,null,null,200).path("items").size()).isZero();
        assertThat(call("GET","/records?side=OWN&from=2026-11-16&to=2026-11-16&limit=100",a,null,null,200).path("items").size()).isEqualTo(100);
        assertThat(call("GET","/records?date=2026-11-16&from=2026-11-16",a,null,null,422).at("/error/code").asText()).isEqualTo("INVALID_INPUT");
    }

    private User signup() throws Exception {
        String login="s3_"+UUID.randomUUID().toString().substring(0,8);
        JsonNode result=call("POST","/auth/signup",null,null,"{\"loginId\":\""+login+"\",\"password\":\"secret123\"}",201);
        return new User(result.at("/user/id").asText(),login,result.at("/session/accessToken").asText());
    }
    private void befriend(User a,User b) throws Exception {
        String id=call("POST","/friend-requests",a,null,"{\"friendCode\":\""+b.login()+"\"}",201).path("id").asText();
        call("PUT","/friend-requests/"+id+"/decision",b,null,"{\"decision\":\"ACCEPT\"}",200);
    }
    private Send send(User a,User b) throws Exception {
        String orb=call("POST","/orbs",a,UUID.randomUUID().toString(),"{\"emotionIds\":[\"yellow_01\"]}",201).at("/orb/id").asText();
        JsonNode result=call("POST","/orbs/"+orb+"/dispatches",a,UUID.randomUUID().toString(),"{\"route\":\"FRIEND\",\"friendId\":\""+b.id()+"\"}",201);
        String dispatch=result.at("/dispatch/id").asText();
        String receipt=jdbc.queryForObject("SELECT id FROM receipt WHERE dispatch_id=?",UUID.class,UUID.fromString(dispatch)).toString();
        String receivedRecord=jdbc.queryForObject("SELECT id FROM emotion_record WHERE receipt_id=?",UUID.class,UUID.fromString(receipt)).toString();
        return new Send(dispatch,result.at("/dispatch/targets/0/id").asText(),receipt,result.path("recordId").asText(),receivedRecord);
    }
    private int status(String method,String path,User user,String key,String body) throws Exception {return perform(method,path,user,key,body).getStatus();}
    private JsonNode call(String method,String path,User user,String key,String body,int expected) throws Exception {
        var response=perform(method,path,user,key,body);
        assertThat(response.getStatus()).as(method+" "+path+" "+response.getContentAsString()).isEqualTo(expected);
        return response.getContentAsString().isBlank()?null:json.readTree(response.getContentAsString());
    }
    private org.springframework.mock.web.MockHttpServletResponse perform(String method,String path,User user,String key,String body) throws Exception {
        var request=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),"/api/v1"+path);
        if(user!=null)request.header("Authorization","Bearer "+user.token());
        if(key!=null)request.header("Idempotency-Key",key);
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn().getResponse();
    }
    private record User(String id,String login,String token) {}
    private record Send(String dispatch,String target,String receipt,String ownRecord,String receivedRecord) {}
}
