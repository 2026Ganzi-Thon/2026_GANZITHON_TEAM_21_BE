package com.ganzi.emori.record;

import com.ganzi.emori.friend.FriendModels.UserSummary;
import com.ganzi.emori.orb.DispatchModels.Dispatch;
import com.ganzi.emori.orb.OrbModels.Orb;
import java.util.List;

public final class RecordModels {
    private RecordModels() {}
    public record Record(String id, String side, Orb orb, String route, String recordedAt,
                         String receiptId, String dispatchId, UserSummary counterpart) {}
    public record ReactionCount(String code, int count) {}
    public record RecordDetail(Record record, Dispatch dispatch, List<ReactionCount> reactions) {}
}
