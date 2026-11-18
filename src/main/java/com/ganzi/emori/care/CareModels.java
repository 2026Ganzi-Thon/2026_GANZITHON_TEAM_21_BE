package com.ganzi.emori.care;

import java.util.List;

public final class CareModels {
    private CareModels() {}
    public record ResourceSummary(String id,String title) {}
    public record CareNotice(String id,int level,String title,String body,List<ResourceSummary> resources) {}
    public record Care(String asOfDate,String timezone,int daysIn7,int daysIn14,int level,CareNotice notice) {}
    public record CareResource(String id,String title,String body,String url) {}
    public record Acknowledgement(String action) {}
}
