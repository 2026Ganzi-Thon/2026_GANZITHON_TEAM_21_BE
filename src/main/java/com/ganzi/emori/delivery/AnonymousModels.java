package com.ganzi.emori.delivery;

public final class AnonymousModels {
    private AnonymousModels() {}
    public record ReactionResponse(String code,String reactedAt) {}
    public record ReportRequest(String reasonCode,String detail) {}
    public record ReportResponse(String reportId,String status) {}
}
