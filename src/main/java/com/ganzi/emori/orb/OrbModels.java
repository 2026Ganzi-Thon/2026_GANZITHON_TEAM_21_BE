package com.ganzi.emori.orb;

import java.util.List;

public final class OrbModels {
    private OrbModels() {}
    public record Emotion(String id, String color, String area, String word) {}
    public record Reaction(String code, String label, String emoji) {}
    public record EmotionCatalog(String version, List<Emotion> items) {}
    public record ReactionCatalog(List<Reaction> items) {}
    public record Orb(String id, String kind, List<Emotion> emotions, Object playlist, String createdAt) {}
    public record OrbCreateRequest(List<String> emotionIds) {}
    public record OrbCreateResponse(Orb orb, String recordId) {}
    public record OrbDetail(Orb orb, Object generation) {}
}
