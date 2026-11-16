package com.ganzi.emori.push;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class ExpoPushTransport implements PushTransport {
    private static final URI SEND=URI.create("https://exp.host/--/api/v2/push/send");
    private static final URI RECEIPTS=URI.create("https://exp.host/--/api/v2/push/getReceipts");
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final String accessToken;
    public ExpoPushTransport(ObjectMapper json,@Value("${EXPO_ACCESS_TOKEN:}") String accessToken){
        this.json=json;this.accessToken=accessToken;
    }
    @Override public Result send(String token,String receiptId) throws Exception {
        String body=json.writeValueAsString(Map.of("to",token,"title","EmOri",
                "body","새로운 감정구슬이 도착했어요.","data",Map.of("receiptId",receiptId)));
        JsonNode response=post(SEND,body);
        JsonNode data=response.path("data");
        JsonNode ticket=data.isArray()?data.path(0):data;
        if("ok".equals(ticket.path("status").asText()) && !ticket.path("id").asText().isBlank())
            return new Result("ACCEPTED",ticket.path("id").asText(),null);
        return new Result("ERROR",null,error(ticket));
    }
    @Override public Result check(String ticketId) throws Exception {
        JsonNode response=post(RECEIPTS,json.writeValueAsString(Map.of("ids",List.of(ticketId))));
        JsonNode receipt=response.path("data").path(ticketId);
        if(receipt.isMissingNode() || receipt.isNull()) return new Result("WAIT",ticketId,null);
        if("ok".equals(receipt.path("status").asText())) return new Result("DELIVERED",ticketId,null);
        return new Result("ERROR",ticketId,error(receipt));
    }
    private JsonNode post(URI uri,String body) throws Exception {
        HttpRequest.Builder request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Accept","application/json").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if(!accessToken.isBlank()) request.header("Authorization","Bearer "+accessToken);
        HttpResponse<String> response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()<200 || response.statusCode()>=300)
            throw new IllegalStateException("Expo push HTTP status "+response.statusCode());
        return json.readTree(response.body());
    }
    private static String error(JsonNode node){
        String code=node.path("details").path("error").asText();
        return code.isBlank()?"EXPO_REJECTED":code;
    }
}
