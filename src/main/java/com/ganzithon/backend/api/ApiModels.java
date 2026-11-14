package com.ganzithon.backend.api;

import jakarta.validation.constraints.*;

import java.util.List;

public final class ApiModels {

    private ApiModels() {
    }

    // TODO: API 명세서 확정 후 수정
    public record Credentials(
            @NotBlank
            @Pattern(regexp = "[a-zA-Z0-9_]{4,30}")
            String loginId,

            @NotBlank
            @Size(min = 8, max = 72)
            String password
    ) {
    }

    public record FriendRequest(
            @Size(max = 30)
            String loginId,

            @Size(max = 64)
            String qrToken
    ) {
    }

    public record RestSetting(
            @NotNull
            Boolean resting
    ) {
    }

    public record SendBead(
            @NotEmpty
            @Size(max = 2)
            List<@NotNull Color> colors,

            @NotNull
            Boolean saveToSelf,

            @NotNull
            @Size(max = 50)
            List<@NotBlank @Size(max = 36) String> recipientIds,

            @NotNull
            Boolean withMusic
    ) {
    }

    public record ReactionRequest(
            @NotBlank
            @Size(max = 32)
            String emoji
    ) {
    }

    public record SubscriptionRequest(
            @NotBlank
            @Size(max = 2048)
            String endpoint,

            @NotBlank
            @Size(max = 200)
            String p256dh,

            @NotBlank
            @Size(max = 100)
            String auth
    ) {
    }

    public enum Color {
        YELLOW("노랑", "기쁨·기대"),
        ORANGE("주황", "활력·설렘"),
        PINK("분홍", "애정·다정함"),
        RED("빨강", "강한 감정"),
        PURPLE("보라", "복잡함·불안"),
        BLUE("파랑", "가라앉음"),
        GREEN("초록", "안정·회복");

        public final String label;
        public final String area;

        Color(String label, String area) {
            this.label = label;
            this.area = area;
        }
    }
}