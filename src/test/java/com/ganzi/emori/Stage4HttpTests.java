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

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties="spring.datasource.url=${EMORI_STAGE4_TEST_DB_URL:jdbc:h2:mem:stage4_tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}")
@AutoConfigureMockMvc
class Stage4HttpTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test void ageSettingsNoMatchAndDailyLimits() throws Exception {
        jdbc.update("UPDATE user_settings SET anonymous_enabled=FALSE");
        User sender=signup();
        assertThat(call("GET","/me/settings",sender,null,null,200).path("anonymousEnabled").asBoolean()).isFalse();
        assertThat(call("PATCH","/me/settings",sender,null,"{\"anonymousEnabled\":true}",403).at("/error/code").asText()).isEqualTo("AGE_REQUIRED");
        String draft=draft(sender);
        assertThat(call("POST","/orbs/"+draft+"/dispatches",sender,key(),"{\"route\":\"ANONYMOUS\"}",403).at("/error/code").asText()).isEqualTo("AGE_REQUIRED");
        adult(sender); on(sender);
        assertThat(call("POST","/orbs/"+draft+"/dispatches",sender,key(),"{\"route\":\"ANONYMOUS\"}",409).at("/error/code").asText()).isEqualTo("NO_MATCH");
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM anon_daily_usage WHERE user_id=? AND usage_date=?",Integer.class,UUID.fromString(sender.id()),today)).isZero();
        User b=signup(); adult(b); on(b);
        String sendKey=key();
        JsonNode firstSend=call("POST","/orbs/"+draft+"/dispatches",sender,sendKey,"{\"route\":\"ANONYMOUS\"}",201);
        assertThat(firstSend.at("/dispatch/recipientCount").asInt()).isEqualTo(1);
        assertThat(call("POST","/orbs/"+draft+"/dispatches",sender,sendKey,"{\"route\":\"ANONYMOUS\"}",201)).isEqualTo(firstSend);
        jdbc.update("INSERT INTO anon_daily_usage(user_id,usage_date,sent_count,received_count,updated_at) VALUES (?,?,5,0,CURRENT_TIMESTAMP)",
                UUID.fromString(sender.id()),today.minusDays(1));
        for(int i=0;i<2;i++) {
            JsonNode sent=send(sender);
            assertThat(sent.at("/dispatch/recipientCount").asInt()).isEqualTo(1);
            assertThat(sent.at("/dispatch/targets/0/counterpart").isNull()).isTrue();
        }
        String noMatchOrb=draft(sender);
        assertThat(call("POST","/orbs/"+noMatchOrb+"/dispatches",sender,key(),"{\"route\":\"ANONYMOUS\"}",409).at("/error/code").asText()).isEqualTo("NO_MATCH");
        assertThat(jdbc.queryForObject("SELECT sent_count FROM anon_daily_usage WHERE user_id=? AND usage_date=?",Integer.class,UUID.fromString(sender.id()),today)).isEqualTo(3);
        User c=signup(); adult(c); on(c);
        send(sender); send(sender);
        JsonNode limit=call("POST","/orbs/"+noMatchOrb+"/dispatches",sender,key(),"{\"route\":\"ANONYMOUS\"}",429);
        assertThat(limit.at("/error/code").asText()).isEqualTo("ANON_SEND_LIMIT");
        assertThat(limit.at("/error/retryAt").asText()).isEqualTo(today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant().toString());
    }

    @Test void threeTargetsIndividualCancelReactionReportBlockAndPrivacy() throws Exception {
        jdbc.update("UPDATE user_settings SET anonymous_enabled=FALSE");
        User a=signup(), b=signup(), c=signup(), d=signup();
        for(User u:List.of(a,b,c,d)){adult(u);on(u);}
        JsonNode sent=send(a);
        assertThat(sent.at("/dispatch/recipientCount").asInt()).isEqualTo(3);
        String dispatch=sent.at("/dispatch/id").asText();
        String response=sent.toString();
        for(User u:List.of(b,c,d)) {
            assertThat(response).doesNotContain(u.id(),u.login());
        }
        for(JsonNode target:sent.at("/dispatch/targets")) assertThat(target.path("counterpart").isNull()).isTrue();
        List<User> recipients=List.of(b,c,d);
        User openedUser=recipients.get(0);
        String receipt=receipt(dispatch,openedUser);
        JsonNode inbox=call("GET","/inbox",openedUser,null,null,200);
        assertThat(inbox.at("/items/0/sender").isNull()).isTrue();
        assertThat(inbox.toString()).doesNotContain(a.id(),a.login());
        String openKey=key();
        JsonNode first=call("POST","/receipts/"+receipt+"/open",openedUser,openKey,"{}",200);
        assertThat(first.path("mode").asText()).isEqualTo("FIRST");
        assertThat(first.path("actions").size()).isEqualTo(2);
        String actionToken=first.path("actionToken").asText();
        assertThat(actionToken).isNotBlank();
        assertThat(call("POST","/receipts/"+receipt+"/open",openedUser,openKey,"{}",200)).isEqualTo(first);
        String stored=jdbc.queryForObject("SELECT response_json FROM idempotency_request WHERE request_key=?",String.class,UUID.fromString(openKey));
        assertThat(stored).startsWith("enc:v1:").doesNotContain(actionToken);
        String reactionBody="{\"actionToken\":\""+actionToken+"\",\"code\":\"HEART\"}";
        String reactionKey=key();
        assertThat(call("POST","/receipts/"+receipt+"/reaction",openedUser,reactionKey,reactionBody,201).path("code").asText()).isEqualTo("HEART");
        assertThat(call("POST","/receipts/"+receipt+"/reaction",openedUser,reactionKey,reactionBody,201).path("code").asText()).isEqualTo("HEART");
        assertThat(call("POST","/receipts/"+receipt+"/reaction",openedUser,reactionKey,"{\"actionToken\":\""+actionToken+"\",\"code\":\"HUG\"}",409).at("/error/code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(call("POST","/receipts/"+receipt+"/reaction",openedUser,key(),reactionBody,409).at("/error/code").asText()).isEqualTo("ACTION_CLOSED");
        call("POST","/receipts/"+receipt+"/finish",openedUser,null,"{\"actionToken\":\""+actionToken+"\"}",204);
        assertThat(call("GET","/receipts/"+receipt+"/replay",openedUser,null,null,200).path("actions").size()).isZero();
        assertThat(call("GET","/records/"+sent.path("recordId").asText(),a,null,null,200).at("/reactions/0/count").asInt()).isEqualTo(1);
        String other=receipt(dispatch,c);
        String otherTarget=target(dispatch,c);
        JsonNode cancelled=call("POST","/dispatches/"+dispatch+"/targets/"+otherTarget+"/cancel",a,key(),"{}",200);
        assertThat(cancelled.at("/dispatch/status").asText()).isEqualTo("PARTIALLY_CANCELLED");
        assertThat(call("GET","/receipts/"+other+"/replay",c,null,null,404).at("/error/code").asText()).isEqualTo("NOT_FOUND");
        assertThat(call("POST","/dispatches/"+dispatch+"/targets/"+target(dispatch,openedUser)+"/cancel",a,key(),"{}",409).at("/error/code").asText()).isEqualTo("CANCEL_NOT_ALLOWED");
        String reportKey=key();
        assertThat(call("POST","/receipts/"+receipt+"/reports",openedUser,reportKey,"{\"reasonCode\":\"OTHER\",\"detail\":\" unwanted \"}",201).path("status").asText()).isEqualTo("RECEIVED");
        assertThat(call("POST","/receipts/"+receipt+"/reports",openedUser,reportKey,"{\"reasonCode\":\"OTHER\",\"detail\":\" unwanted \"}",201).path("status").asText()).isEqualTo("RECEIVED");
        assertThat(call("POST","/receipts/"+receipt+"/reports",openedUser,reportKey,"{\"reasonCode\":\"OTHER\",\"detail\":\"changed\"}",409).at("/error/code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(call("POST","/receipts/"+receipt+"/reports",openedUser,key(),"{\"reasonCode\":\"OTHER\"}",409).at("/error/code").asText()).isEqualTo("ALREADY_REPORTED");
        assertThat(call("POST","/receipts/"+receipt+"/reports",openedUser,key(),"{\"reasonCode\":\"INAPPROPRIATE\",\"detail\":\"x\"}",422).at("/error/code").asText()).isEqualTo("INVALID_INPUT");
        call("PUT","/receipts/"+receipt+"/anonymous-block",openedUser,null,"{}",204);
        call("PUT","/receipts/"+receipt+"/anonymous-block",openedUser,null,"{}",204);
        JsonNode next=send(a);
        assertThat(next.at("/dispatch/recipientCount").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receipt WHERE dispatch_id=? AND recipient_id=?",Integer.class,
                UUID.fromString(next.at("/dispatch/id").asText()),UUID.fromString(openedUser.id()))).isZero();
        JsonNode reverse=send(openedUser);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receipt WHERE dispatch_id=? AND recipient_id=?",Integer.class,
                UUID.fromString(reverse.at("/dispatch/id").asText()),UUID.fromString(a.id()))).isZero();
        call("PATCH","/me/settings",openedUser,null,"{\"anonymousEnabled\":false}",200);
        assertThat(call("GET","/inbox",openedUser,null,null,200).toString()).contains(receipt);
        assertThat(call("GET","/receipts/"+receipt+"/replay",openedUser,null,null,200).path("mode").asText()).isEqualTo("REPLAY");
    }

    @Test void finishReplayAndConcurrentRecipientQuota() throws Exception {
        jdbc.update("UPDATE user_settings SET anonymous_enabled=FALSE");
        User sender=signup(), receiver=signup(); adult(sender);adult(receiver);on(sender);on(receiver);
        JsonNode sent=send(sender);
        String receipt=receipt(sent.at("/dispatch/id").asText(),receiver);
        String token=call("POST","/receipts/"+receipt+"/open",receiver,key(),"{}",200).path("actionToken").asText();
        call("GET","/receipts/"+receipt+"/replay",receiver,null,null,200);
        assertThat(call("POST","/receipts/"+receipt+"/reaction",receiver,key(),"{\"actionToken\":\""+token+"\",\"code\":\"HUG\"}",409).at("/error/code").asText()).isEqualTo("ACTION_CLOSED");
        call("POST","/receipts/"+receipt+"/finish",receiver,null,"{\"actionToken\":\""+token+"\"}",204);
        User other=signup(); adult(other);on(other);
        // Fill this recipient to two, then two senders compete for its final daily slot.
        send(sender); // receiver + other
        call("PATCH","/me/settings",other,null,"{\"anonymousEnabled\":false}",200);
        User sender2=signup(); adult(sender2);on(sender2);
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        jdbc.update("UPDATE anon_daily_usage SET received_count=3 WHERE user_id=? AND usage_date=?",UUID.fromString(sender.id()),today);
        jdbc.update("INSERT INTO anon_daily_usage(user_id,usage_date,sent_count,received_count,updated_at) VALUES (?,?,0,3,CURRENT_TIMESTAMP)",UUID.fromString(sender2.id()),today);
        String orb1=draft(sender),orb2=draft(sender2);
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->{start.await();return status("POST","/orbs/"+orb1+"/dispatches",sender,key(),"{\"route\":\"ANONYMOUS\"}");});
            var two=pool.submit(()->{start.await();return status("POST","/orbs/"+orb2+"/dispatches",sender2,key(),"{\"route\":\"ANONYMOUS\"}");});
            start.countDown();
            assertThat(List.of(one.get(),two.get())).containsExactlyInAnyOrder(201,409);
        }
        assertThat(jdbc.queryForObject("SELECT received_count FROM anon_daily_usage WHERE user_id=? AND usage_date=?",Integer.class,
                UUID.fromString(receiver.id()),today)).isEqualTo(3);
    }

    @Test void concurrentSenderLimitAndFinishReactionRace() throws Exception {
        jdbc.update("UPDATE user_settings SET anonymous_enabled=FALSE");
        User a=signup(), b=signup(); adult(a);adult(b);on(a);on(b);
        JsonNode first=send(a);
        String receipt=receipt(first.at("/dispatch/id").asText(),b);
        String token=call("POST","/receipts/"+receipt+"/open",b,key(),"{}",200).path("actionToken").asText();
        String body="{\"actionToken\":\""+token+"\",\"code\":\"CHEER\"}";
        CountDownLatch start=new CountDownLatch(1);
        int reactionStatus,finishStatus;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var reaction=pool.submit(()->{start.await();return status("POST","/receipts/"+receipt+"/reaction",b,key(),body);});
            var finish=pool.submit(()->{start.await();return status("POST","/receipts/"+receipt+"/finish",b,null,"{\"actionToken\":\""+token+"\"}");});
            start.countDown(); reactionStatus=reaction.get(); finishStatus=finish.get();
        }
        assertThat(finishStatus).isEqualTo(204);
        assertThat(reactionStatus).isIn(201,409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receipt_reaction WHERE receipt_id=?",Integer.class,UUID.fromString(receipt)))
                .isEqualTo(reactionStatus==201?1:0);
        assertThat(call("POST","/receipts/"+receipt+"/open",b,key(),"{}",200).path("actionToken").isNull()).isTrue();
        JsonNode expiring=send(a);
        String expiringReceipt=receipt(expiring.at("/dispatch/id").asText(),b);
        String expiringToken=call("POST","/receipts/"+expiringReceipt+"/open",b,key(),"{}",200).path("actionToken").asText();
        jdbc.update("UPDATE receipt SET action_expires_at=? WHERE id=?",OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1),UUID.fromString(expiringReceipt));
        assertThat(call("POST","/receipts/"+expiringReceipt+"/reaction",b,key(),"{\"actionToken\":\""+expiringToken+"\",\"code\":\"HEART\"}",409).at("/error/code").asText()).isEqualTo("ACTION_CLOSED");
        assertThat(call("POST","/receipts/"+expiringReceipt+"/finish",b,null,"{\"actionToken\":\""+expiringToken+"\"}",409).at("/error/code").asText()).isEqualTo("ACTION_CLOSED");
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        jdbc.update("UPDATE anon_daily_usage SET sent_count=4 WHERE user_id=? AND usage_date=?",UUID.fromString(a.id()),today);
        jdbc.update("UPDATE anon_daily_usage SET received_count=0 WHERE user_id=? AND usage_date=?",UUID.fromString(b.id()),today);
        String orb1=draft(a),orb2=draft(a);
        CountDownLatch sendStart=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->{sendStart.await();return status("POST","/orbs/"+orb1+"/dispatches",a,key(),"{\"route\":\"ANONYMOUS\"}");});
            var two=pool.submit(()->{sendStart.await();return status("POST","/orbs/"+orb2+"/dispatches",a,key(),"{\"route\":\"ANONYMOUS\"}");});
            sendStart.countDown();
            assertThat(List.of(one.get(),two.get())).containsExactlyInAnyOrder(201,429);
        }
        assertThat(jdbc.queryForObject("SELECT sent_count FROM anon_daily_usage WHERE user_id=? AND usage_date=?",Integer.class,UUID.fromString(a.id()),today)).isEqualTo(5);
    }

    @Test void blockAndToggleOffSerializeWithMatching() throws Exception {
        jdbc.update("UPDATE user_settings SET anonymous_enabled=FALSE");
        User a=signup(),b=signup(); adult(a);adult(b);on(a);on(b);
        JsonNode original=send(a);
        String receipt=receipt(original.at("/dispatch/id").asText(),b);
        String orb=draft(a);
        CountDownLatch start=new CountDownLatch(1);
        int sendStatus,blockStatus;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var send=pool.submit(()->{start.await();return status("POST","/orbs/"+orb+"/dispatches",a,key(),"{\"route\":\"ANONYMOUS\"}");});
            var block=pool.submit(()->{start.await();return status("PUT","/receipts/"+receipt+"/anonymous-block",b,null,"{}");});
            start.countDown();sendStatus=send.get();blockStatus=block.get();
        }
        assertThat(blockStatus).isEqualTo(204);
        assertThat(sendStatus).isIn(201,409);
        assertThat(call("POST","/orbs/"+draft(a)+"/dispatches",a,key(),"{\"route\":\"ANONYMOUS\"}",409).at("/error/code").asText()).isEqualTo("NO_MATCH");
        jdbc.update("UPDATE user_settings SET anonymous_enabled=FALSE");
        User c=signup(),d=signup();adult(c);adult(d);on(c);on(d);
        String nextOrb=draft(c);
        CountDownLatch toggleStart=new CountDownLatch(1);
        int dispatchStatus,toggleStatus;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var send=pool.submit(()->{toggleStart.await();return status("POST","/orbs/"+nextOrb+"/dispatches",c,key(),"{\"route\":\"ANONYMOUS\"}");});
            var off=pool.submit(()->{toggleStart.await();return status("PATCH","/me/settings",d,null,"{\"anonymousEnabled\":false}");});
            toggleStart.countDown();dispatchStatus=send.get();toggleStatus=off.get();
        }
        assertThat(toggleStatus).isEqualTo(200);
        assertThat(dispatchStatus).isIn(201,409);
        assertThat(call("POST","/orbs/"+draft(c)+"/dispatches",c,key(),"{\"route\":\"ANONYMOUS\"}",409).at("/error/code").asText()).isEqualTo("NO_MATCH");
    }

    @Test void anonymousOpenAndCancelHaveOneWinner() throws Exception {
        jdbc.update("UPDATE user_settings SET anonymous_enabled=FALSE");
        User a=signup(),b=signup();adult(a);adult(b);on(a);on(b);
        JsonNode sent=send(a);
        String dispatch=sent.at("/dispatch/id").asText();
        String receipt=receipt(dispatch,b),target=target(dispatch,b);
        CountDownLatch start=new CountDownLatch(1);
        int opened,cancelled;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var open=pool.submit(()->{start.await();return status("POST","/receipts/"+receipt+"/open",b,key(),"{}");});
            var cancel=pool.submit(()->{start.await();return status("POST","/dispatches/"+dispatch+"/targets/"+target+"/cancel",a,key(),"{}");});
            start.countDown();opened=open.get();cancelled=cancel.get();
        }
        assertThat(List.of(opened,cancelled)).contains(200);
        assertThat(List.of(opened,cancelled)).containsAnyOf(404,409);
        var state=jdbc.queryForMap("SELECT opened_at,cancelled_at FROM receipt WHERE id=?",UUID.fromString(receipt));
        assertThat((state.get("opened_at")!=null)^(state.get("cancelled_at")!=null)).isTrue();
    }

    private User signup() throws Exception {
        String login="anon"+UUID.randomUUID().toString().substring(0,8);
        JsonNode result=call("POST","/auth/signup",null,null,"{\"loginId\":\""+login+"\",\"password\":\"secret123\"}",201);
        return new User(result.at("/user/id").asText(),login,result.at("/session/accessToken").asText());
    }
    private void adult(User u) { jdbc.update("UPDATE app_user SET adult_status='VERIFIED_19_PLUS',adult_checked_at=CURRENT_TIMESTAMP WHERE id=?",UUID.fromString(u.id())); }
    private void on(User u) throws Exception {call("PATCH","/me/settings",u,null,"{\"anonymousEnabled\":true}",200);}
    private String draft(User u) throws Exception {return call("POST","/orbs",u,key(),"{\"emotionIds\":[\"yellow_01\"]}",201).at("/orb/id").asText();}
    private JsonNode send(User u) throws Exception {return call("POST","/orbs/"+draft(u)+"/dispatches",u,key(),"{\"route\":\"ANONYMOUS\"}",201);}
    private String receipt(String dispatch,User user) {return jdbc.queryForObject("SELECT id FROM receipt WHERE dispatch_id=? AND recipient_id=?",UUID.class,UUID.fromString(dispatch),UUID.fromString(user.id())).toString();}
    private String target(String dispatch,User user) {return jdbc.queryForObject("SELECT target_id FROM receipt WHERE dispatch_id=? AND recipient_id=?",UUID.class,UUID.fromString(dispatch),UUID.fromString(user.id())).toString();}
    private String key(){return UUID.randomUUID().toString();}
    private int status(String method,String path,User user,String key,String body) throws Exception{return perform(method,path,user,key,body).getStatus();}
    private JsonNode call(String method,String path,User user,String key,String body,int expected) throws Exception{
        var response=perform(method,path,user,key,body);
        assertThat(response.getStatus()).as(method+" "+path+" "+response.getContentAsString()).isEqualTo(expected);
        return response.getContentAsString().isBlank()?null:json.readTree(response.getContentAsString());
    }
    private org.springframework.mock.web.MockHttpServletResponse perform(String method,String path,User user,String key,String body) throws Exception{
        var request=MockMvcRequestBuilders.request(HttpMethod.valueOf(method),"/api/v1"+path);
        if(user!=null) request.header("Authorization","Bearer "+user.token());
        if(key!=null) request.header("Idempotency-Key",key);
        if(body!=null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn().getResponse();
    }
    private record User(String id,String login,String token){}
}
