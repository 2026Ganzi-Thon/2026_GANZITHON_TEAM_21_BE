package com.ganzi.emori;

import com.ganzi.emori.push.PushTransport;
import com.ganzi.emori.push.PushWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties="spring.datasource.url=${EMORI_STAGE5_TEST_DB_URL:jdbc:h2:mem:stage5_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}")
@AutoConfigureMockMvc
class Stage5HttpTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PushWorker worker;
    @MockitoBean PushTransport transport;

    @Test void restDevicesAndOutbox() throws Exception {
        User a=signup(),b=signup(); befriend(a,b);
        String installation=UUID.randomUUID().toString();
        String token="ExponentPushToken[aaaaaaaaaaaaaaaaaaaa]";
        call("PUT","/me/devices/"+installation,b,"{\"platform\":\"ANDROID\",\"deliveryToken\":\""+token+"\"}",204);
        byte[] cipher=jdbc.queryForObject("SELECT token_ciphertext FROM device_registration WHERE installation_id=?",
                byte[].class,UUID.fromString(installation));
        assertThat(new String(cipher)).doesNotContain(token);
        assertThat(call("PUT","/me/rest",b,"{\"mode\":\"MANUAL\"}",200).path("active").asBoolean()).isTrue();
        JsonNode restingSend=send(a,b);
        String restingReceipt=receipt(restingSend);
        assertThat(call("GET","/inbox",b,null,200).toString()).contains(restingReceipt);
        assertThat(outboxStatus(restingReceipt)).isEqualTo("SKIPPED");
        call("PUT","/me/rest",b,"{\"mode\":\"OFF\"}",200);
        assertThat(outboxStatus(restingReceipt)).isEqualTo("SKIPPED");
        call("PATCH","/me/settings",b,"{\"pushEnabled\":false}",200);
        JsonNode muted=send(a,b);
        assertThat(outboxStatus(receipt(muted))).isEqualTo("SKIPPED");
        call("PATCH","/me/settings",b,"{\"pushEnabled\":true}",200);
        JsonNode pending=send(a,b);
        String pendingReceipt=receipt(pending);
        assertThat(outboxStatus(pendingReceipt)).isEqualTo("PENDING");
        String target=pending.at("/dispatch/targets/0/id").asText();
        call("POST","/dispatches/"+pending.at("/dispatch/id").asText()+"/targets/"+target+"/cancel",a,"{}",200,UUID.randomUUID().toString());
        assertThat(outboxStatus(pendingReceipt)).isEqualTo("SKIPPED");
        JsonNode deliver=send(a,b);
        String deliverReceipt=receipt(deliver);
        when(transport.send(anyString(),anyString())).thenReturn(new PushTransport.Result("ACCEPTED","ticket-1",null));
        when(transport.check("ticket-1")).thenReturn(new PushTransport.Result("DELIVERED","ticket-1",null));
        assertThat(worker.runOnce()).isTrue();
        assertThat(outboxStatus(deliverReceipt)).isEqualTo("PENDING");
        jdbc.update("UPDATE outbox_event SET available_at=? WHERE receipt_id=?",OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1),UUID.fromString(deliverReceipt));
        assertThat(worker.runOnce()).isTrue();
        assertThat(outboxStatus(deliverReceipt)).isEqualTo("SENT");
        JsonNode older=send(a,b);
        String olderReceipt=receipt(older);
        call("PUT","/me/devices/"+installation,a,"{\"platform\":\"ANDROID\",\"deliveryToken\":\"ExponentPushToken[bbbbbbbbbbbbbbbbbbbb]\"}",204);
        call("DELETE","/me/devices/"+installation,b,null,404);
        assertThat(worker.runOnce()).isTrue();
        assertThat(outboxStatus(olderReceipt)).isEqualTo("SKIPPED");
        call("POST","/auth/logout",a,"{}",204);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_registration WHERE installation_id=?",Integer.class,UUID.fromString(installation))).isZero();
    }

    @Test void scheduleAndCareHistory() throws Exception {
        User a=signup(),other=signup();
        assertThat(call("GET","/me/care",a,null,200).path("level").asInt()).isZero();
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        LocalDate future=now.atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate().plusDays(2);
        String starts=future.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().toString();
        String ends=future.plusDays(2).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().toString();
        String scheduled="{\"mode\":\"SCHEDULED\",\"startsAt\":\""+starts+"\",\"endsAt\":\""+ends+"\"}";
        assertThat(call("PUT","/me/rest",a,scheduled,200).path("active").asBoolean()).isFalse();
        assertThat(call("PUT","/me/rest",a,scheduled,200).path("startsAt").asText()).isEqualTo(starts);
        assertThat(call("PUT","/me/rest",a,"{\"mode\":\"SCHEDULED\",\"startsAt\":\""+now+"\",\"endsAt\":\""+ends+"\"}",422).at("/error/code").asText()).isEqualTo("INVALID_INPUT");
        jdbc.update("INSERT INTO care_content(level,version,title,body) VALUES (1,1,'Level one','Reviewed one'),(2,1,'Level two','Reviewed two')");
        jdbc.update("INSERT INTO care_resource(id,version,title,body,url,updated_at) VALUES ('r1',1,'Resource','Reviewed resource',NULL,CURRENT_TIMESTAMP)");
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        List<String> records=new ArrayList<>();
        for(int daysAgo=0;daysAgo<10;daysAgo++) {
            String record=saveCare(a);
            records.add(record);
            OffsetDateTime at=today.minusDays(daysAgo).atTime(12,0).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
            jdbc.update("UPDATE emotion_record SET recorded_at=? WHERE id=?",at,UUID.fromString(record));
        }
        JsonNode care=call("GET","/me/care",a,null,200);
        assertThat(care.path("daysIn14").asInt()).isEqualTo(10);
        assertThat(care.path("daysIn7").asInt()).isEqualTo(7);
        assertThat(care.path("level").asInt()).isEqualTo(2);
        String notice=care.at("/notice/id").asText();
        assertThat(call("GET","/me/care",a,null,200).at("/notice/id").asText()).isEqualTo(notice);
        assertThat(call("PUT","/me/care/notices/"+notice+"/acknowledgement",other,"{\"action\":\"VIEWED\"}",404).at("/error/code").asText()).isEqualTo("NOT_FOUND");
        call("PUT","/me/care/notices/"+notice+"/acknowledgement",a,"{\"action\":\"VIEWED\"}",204);
        call("PUT","/me/care/notices/"+notice+"/acknowledgement",a,"{\"action\":\"DISMISSED\"}",204);
        assertThat(call("GET","/me/care",a,null,200).path("notice").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT ack_action FROM care_notice WHERE id=?",String.class,UUID.fromString(notice))).isEqualTo("VIEWED");
        assertThat(call("GET","/me/care/resources/r1",a,null,200).path("title").asText()).isEqualTo("Resource");
        assertThat(call("GET","/me/care/resources/missing",a,null,404).at("/error/code").asText()).isEqualTo("NOT_FOUND");
        for(int i=4;i<records.size();i++) call("DELETE","/records/"+records.get(i),a,null,204);
        JsonNode lowered=call("GET","/me/care",a,null,200);
        assertThat(lowered.path("daysIn7").asInt()).isEqualTo(4);
        assertThat(lowered.path("level").asInt()).isEqualTo(1);
        assertThat(lowered.path("notice").isNull()).isTrue(); // Level 2 was acknowledged.
        User c=signup();
        List<String> four=new ArrayList<>();
        for(int daysAgo=0;daysAgo<4;daysAgo++) {
            String record=saveCare(c);four.add(record);
            jdbc.update("UPDATE emotion_record SET recorded_at=? WHERE id=?",
                    today.minusDays(daysAgo).atTime(12,0).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),UUID.fromString(record));
        }
        String mixed=saveMixedCare(c);
        jdbc.update("UPDATE emotion_record SET recorded_at=? WHERE id=?",
                today.atTime(13,0).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),UUID.fromString(mixed));
        JsonNode levelOne=call("GET","/me/care",c,null,200);
        assertThat(levelOne.path("daysIn7").asInt()).isEqualTo(4);
        assertThat(levelOne.path("level").asInt()).isEqualTo(1);
        String levelOneNotice=levelOne.at("/notice/id").asText();
        call("DELETE","/records/"+four.get(3),c,null,204);
        assertThat(call("GET","/me/care",c,null,200).path("level").asInt()).isZero();
        String replacement=saveCare(c);
        jdbc.update("UPDATE emotion_record SET recorded_at=? WHERE id=?",
                today.minusDays(3).atTime(12,0).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),UUID.fromString(replacement));
        assertThat(call("GET","/me/care",c,null,200).at("/notice/id").asText()).isEqualTo(levelOneNotice);
        call("PUT","/me/care/notices/"+levelOneNotice+"/acknowledgement",c,"{\"action\":\"DISMISSED\"}",204);
        assertThat(call("GET","/me/care",c,null,200).path("notice").isNull()).isTrue();
    }

    private User signup() throws Exception {String login="s5"+UUID.randomUUID().toString().substring(0,8);
        JsonNode body=call("POST","/auth/signup",null,"{\"loginId\":\""+login+"\",\"password\":\"secret123\"}",201);
        return new User(body.at("/user/id").asText(),login,body.at("/session/accessToken").asText());}
    private void befriend(User a,User b) throws Exception {String id=call("POST","/friend-requests",a,"{\"friendCode\":\""+b.login()+"\"}",201).path("id").asText();
        call("PUT","/friend-requests/"+id+"/decision",b,"{\"decision\":\"ACCEPT\"}",200);}
    private JsonNode send(User a,User b) throws Exception {String orb=call("POST","/orbs",a,"{\"emotionIds\":[\"yellow_01\"]}",201,UUID.randomUUID().toString()).at("/orb/id").asText();
        return call("POST","/orbs/"+orb+"/dispatches",a,"{\"route\":\"FRIEND\",\"friendId\":\""+b.id()+"\"}",201,UUID.randomUUID().toString());}
    private String saveCare(User a) throws Exception {String orb=call("POST","/orbs",a,"{\"emotionIds\":[\"purple_01\"]}",201,UUID.randomUUID().toString()).at("/orb/id").asText();
        return call("POST","/orbs/"+orb+"/dispatches",a,"{\"route\":\"SELF\"}",201,UUID.randomUUID().toString()).path("recordId").asText();}
    private String saveMixedCare(User a) throws Exception {String orb=call("POST","/orbs",a,"{\"emotionIds\":[\"purple_01\",\"blue_02\"]}",201,UUID.randomUUID().toString()).at("/orb/id").asText();
        return call("POST","/orbs/"+orb+"/dispatches",a,"{\"route\":\"SELF\"}",201,UUID.randomUUID().toString()).path("recordId").asText();}
    private String receipt(JsonNode sent){return jdbc.queryForObject("SELECT id FROM receipt WHERE dispatch_id=?",UUID.class,UUID.fromString(sent.at("/dispatch/id").asText())).toString();}
    private String outboxStatus(String receipt){return jdbc.queryForObject("SELECT status FROM outbox_event WHERE receipt_id=?",String.class,UUID.fromString(receipt));}
    private JsonNode call(String method,String path,User user,String body,int expected) throws Exception{return call(method,path,user,body,expected,null);}
    private JsonNode call(String method,String path,User user,String body,int expected,String key) throws Exception {
        var request=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),"/api/v1"+path);
        if(user!=null)request.header("Authorization","Bearer "+user.token());
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(body);
        if(key!=null)request.header("Idempotency-Key",key);
        var response=mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(method+" "+path+" "+response.getContentAsString()).isEqualTo(expected);
        return response.getContentAsString().isBlank()?null:json.readTree(response.getContentAsString());
    }
    private record User(String id,String login,String token){}
}
