package com.ganzi.emori.settings;

public final class SettingsModels {
    private SettingsModels() {}
    public record SettingsUpdate(Boolean anonymousEnabled, Boolean pushEnabled) {}
    public record Rest(String mode, String startsAt, String endsAt, boolean active) {}
    public record Settings(boolean anonymousEnabled, boolean pushEnabled, Rest rest) {}
}
