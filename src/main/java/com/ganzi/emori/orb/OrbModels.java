package com.ganzi.emori.orb;

import com.ganzi.emori.asmr.AsmrModels.Generation;
import com.ganzi.emori.asmr.AsmrModels.Playlist;
import java.util.List;

public final class OrbModels {
    private OrbModels() {}
    public record Emotion(String id, String color, String area, String word) {}
    public record Reaction(String code, String label, String emoji) {}
    public record EmotionCatalog(String version, List<Emotion> items) {}
    public record ReactionCatalog(List<Reaction> items) {}
    public record Orb(String id, String kind, List<Emotion> emotions, Playlist playlist, String createdAt) {}
    public record OrbCreateRequest(List<String> emotionIds) {}
    public record OrbCreateResponse(Orb orb, String recordId) {}
    public record OrbDetail(Orb orb, Generation generation) {}
}
