package com.ganzi.emori.push;

public interface PushTransport {
    record Result(String state,String ticketId,String errorCode){}
    Result send(String token,String receiptId) throws Exception;
    Result check(String ticketId) throws Exception;
}
