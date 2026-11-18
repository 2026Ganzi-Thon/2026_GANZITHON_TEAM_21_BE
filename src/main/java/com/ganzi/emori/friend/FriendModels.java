package com.ganzi.emori.friend;

public final class FriendModels {
    private FriendModels() {}
    public record UserSummary(String id, String nickname) {}
    public record FriendCodeRequest(String friendCode) {}
    public record DecisionRequest(String decision) {}
    public record FriendRequestView(String id, UserSummary from, UserSummary to, String status, String createdAt) {}
}
