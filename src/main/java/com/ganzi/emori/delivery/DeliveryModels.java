package com.ganzi.emori.delivery;

import com.ganzi.emori.friend.FriendModels.UserSummary;
import com.ganzi.emori.orb.OrbModels.Orb;
import java.util.List;

public final class DeliveryModels {
    private DeliveryModels() {}
    public record Receipt(String id, String route, UserSummary sender, String receivedAt,
                          String openedAt, Orb orb) {}
    public record SingleView(Receipt receipt, Orb orb, String mode, List<String> actions,
                             String actionToken) {}
    public record EmptyRequest() {}
    public record ReactionRequest(String actionToken, String code) {}
    public record FinishRequest(String actionToken) {}
}
