package com.ganzi.emori.asmr;

import java.util.List;

public final class AsmrModels {
    private AsmrModels() {}
    public record GenerateRequest(String description) {}
    public record EmptyRequest() {}
    public record Track(String id,int order,String title,int durationMs,String playbackUrl) {}
    public record Playlist(String id,String status,List<Track> tracks) {}
    public record GenerationTrack(String id,int order,String status,String title,Integer durationMs,
                                  String playbackUrl,String errorCode) {}
    public record Generation(String id,String orbId,String status,int trackCount,List<GenerationTrack> tracks,
                             String createdAt,String updatedAt) {}
}
