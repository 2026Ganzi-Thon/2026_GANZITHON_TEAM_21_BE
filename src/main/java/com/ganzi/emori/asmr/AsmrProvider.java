package com.ganzi.emori.asmr;

import java.util.List;

public interface AsmrProvider {
    boolean available();
    String name();
    Result start(Request request) throws Exception;
    Result poll(String providerJobId) throws Exception;
    record Request(String requestKey,List<String> emotions,String description,int position,
                   int maxDurationMs,boolean vocalsAllowed,boolean lyricsAllowed) {}
    record Result(String state,String jobId,String title,Integer durationMs,byte[] audio,String errorCode) {}
}
