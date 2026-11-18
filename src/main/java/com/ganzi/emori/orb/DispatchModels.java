package com.ganzi.emori.orb;

import com.ganzi.emori.friend.FriendModels.UserSummary;
import java.util.List;

public final class DispatchModels {
    private DispatchModels() {}
    public record DispatchRequest(String route, String friendId) {}
    public record Target(String id, UserSummary counterpart, String status,
                         String openedAt, String cancelledAt, boolean canCancel) {}
    public record Dispatch(String id, String orbId, String route, String status, int recipientCount,
                           String sentAt, String cancelUntil, List<Target> targets) {}
    public record DispatchResponse(Dispatch dispatch, String recordId) {}
}
