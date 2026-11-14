package com.ganzithon.backend.push;

import com.ganzithon.backend.api.ApiException;
import com.ganzithon.backend.api.ApiModels.SubscriptionRequest;
import com.ganzithon.backend.common.Db;
import nl.martijndwars.webpush.Notification;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.security.Security;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class PushService {

    private static final Logger log =
            LoggerFactory.getLogger(PushService.class);

    private final Db db;
    private final boolean enabled;
    private final String publicKey;

    private final nl.martijndwars.webpush.PushService transport;
    private final TransactionTemplate tx;

    public PushService(
            Db db,
            PlatformTransactionManager manager,
            @Value("${emori.push.enabled}") boolean enabled,
            @Value("${emori.push.public-key}") String publicKey,
            @Value("${emori.push.private-key}") String privateKey,
            @Value("${emori.push.subject}") String subject
    ) throws Exception {
        this.db = db;
        this.enabled = enabled;
        this.publicKey = publicKey;
        this.tx = new TransactionTemplate(manager);

        if (enabled) {
            if (publicKey.isBlank()
                    || privateKey.isBlank()
                    || !(subject.startsWith("mailto:")
                    || subject.startsWith("https://"))) {
                throw new IllegalStateException(
                        "푸시 활성화 시 VAPID 키와 subject가 필요합니다."
                );
            }

            if (Security.getProvider("BC") == null) {
                Security.addProvider(new BouncyCastleProvider());
            }

            transport = new nl.martijndwars.webpush.PushService(
                    publicKey,
                    privateKey,
                    subject
            );
        } else {
            transport = null;
        }
    }

    public Map<String, Object> config() {
        return Map.of(
                "enabled", enabled,
                "vapidPublicKey", enabled ? publicKey : ""
        );
    }

    public static void validateEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();

            boolean allowed = host != null && (
                    host.equals("web.push.apple.com")
                            || host.equals("fcm.googleapis.com")
                            || host.equals("updates.push.services.mozilla.com")
                            || host.endsWith(".notify.windows.com")
            );

            if (!allowed
                    || !"https".equals(uri.getScheme())
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            throw ApiException.bad(
                    "INVALID_PUSH_ENDPOINT",
                    "지원하는 HTTPS 푸시 서비스 주소가 필요해요."
            );
        }
    }

    public static void validateKey(String value, int length) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);

            if (decoded.length != length
                    || (length == 65 && decoded[0] != 4)) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            throw ApiException.bad(
                    "INVALID_PUSH_KEY",
                    "푸시 구독 키 형식을 확인해주세요."
            );
        }
    }

    @Transactional
    public Map<String, Object> subscribe(
            String userId,
            SubscriptionRequest input
    ) {
        if (!enabled) {
            throw new ApiException(
                    503,
                    "PUSH_NOT_CONFIGURED",
                    "푸시 서비스 설정이 필요해요."
            );
        }

        validateEndpoint(input.endpoint());
        validateKey(input.p256dh(), 65);
        validateKey(input.auth(), 16);

        db.lockUsers(List.of(userId));

        var existing = db.jdbc.queryForList("""
                SELECT id,user_id FROM push_subscription
                WHERE endpoint=?
                """,
                input.endpoint()
        );

        if (!existing.isEmpty()) {
            if (!userId.equals(
                    existing.getFirst().get("user_id")
            )) {
                throw new ApiException(
                        409,
                        "SUBSCRIPTION_OWNED",
                        "기존 계정에서 이 기기의 알림 구독을 해제해주세요."
                );
            }

            String id = (String) existing.getFirst().get("id");

            db.jdbc.update("""
                    UPDATE push_subscription
                    SET p256dh=?,auth=?
                    WHERE id=?
                    """,
                    input.p256dh(), input.auth(), id
            );

            return Map.of("id", id);
        }

        Integer count = db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM push_subscription WHERE user_id=?",
                Integer.class,
                userId
        );

        if (count != null && count >= 10) {
            throw ApiException.bad(
                    "SUBSCRIPTION_LIMIT",
                    "계정당 알림 기기는 최대 10개예요."
            );
        }

        String id = Db.id();

        db.jdbc.update("""
                INSERT INTO push_subscription(
                    id,user_id,endpoint,p256dh,auth
                ) VALUES (?,?,?,?,?)
                """,
                id,
                userId,
                input.endpoint(),
                input.p256dh(),
                input.auth()
        );

        return Map.of("id", id);
    }

    @Transactional
    public void unsubscribe(String userId, String id) {
        db.lockUsers(List.of(userId));

        db.jdbc.update(
                "DELETE FROM push_subscription WHERE id=? AND user_id=?",
                id, userId
        );
    }

    @Scheduled(fixedDelayString = "${emori.push.poll-ms:5000}")
    public void dispatch() {
        // 단일 서버 MVP용. 다중 서버에서는 claim/lease 추가 필요.
        var pending = db.jdbc.queryForList("""
                SELECT id FROM push_outbox
                WHERE status='PENDING' AND next_attempt<=?
                ORDER BY created_at
                LIMIT 20
                """,
                Db.now()
        );

        for (var row : pending) {
            try {
                tx.executeWithoutResult(status ->
                        dispatchOne((String) row.get("id"))
                );
            } catch (Exception e) {
                log.warn("푸시 큐 처리 실패. 다음 주기에 재시도합니다.");
            }
        }

        db.jdbc.update(
                "DELETE FROM auth_session WHERE expires_at<?",
                Db.now()
        );
    }

    private void dispatchOne(String id) {
        var event = db.one(
                "SELECT * FROM push_outbox WHERE id=?",
                id
        );

        if (!"PENDING".equals(event.get("status"))) {
            return;
        }

        String userId = (String) event.get("user_id");

        db.lockUsers(List.of(userId));

        event = db.one(
                "SELECT * FROM push_outbox WHERE id=?",
                id
        );

        if (!"PENDING".equals(event.get("status"))) {
            return;
        }

        if (Db.bool(db.user(userId), "resting")) {
            finish(id, "SUPPRESSED");
            return;
        }

        if (!enabled) {
            finish(id, "DISABLED");
            return;
        }

        var subscriptions = db.jdbc.queryForList(
                "SELECT * FROM push_subscription WHERE user_id=?",
                userId
        );

        if (subscriptions.isEmpty()) {
            finish(id, "NO_SUBSCRIPTION");
            return;
        }

        String beadId = (String) event.get("bead_id");
        String eventType = (String) event.get("event_type");

        String title = "BEAD_RECEIVED".equals(eventType)
                ? "감정을 받았어요"
                : "친구가 반응했어요";

        String payload = """
                {
                  "title":"%s",
                  "body":"앱을 열어 확인해보세요.",
                  "beadId":"%s",
                  "eventId":"%s",
                  "eventType":"%s"
                }
                """.formatted(title, beadId, id, eventType);

        boolean retry = false;

        for (var subscription : subscriptions) {
            try {
                Notification notification = new Notification(
                        (String) subscription.get("endpoint"),
                        (String) subscription.get("p256dh"),
                        (String) subscription.get("auth"),
                        payload
                );

                var response = transport.sendAsync(notification)
                        .get(10, TimeUnit.SECONDS);

                int code = response.getStatusLine().getStatusCode();

                if (code == 404 || code == 410) {
                    db.jdbc.update(
                            "DELETE FROM push_subscription WHERE id=?",
                            subscription.get("id")
                    );
                } else if (code < 200 || code >= 300) {
                    retry = true;
                }
            } catch (Exception e) {
                retry = true;
            }
        }

        int attempt =
                ((Number) event.get("attempts")).intValue() + 1;

        String nextStatus = retry
                ? (attempt >= 3 ? "FAILED" : "PENDING")
                : "SENT";

        db.jdbc.update("""
                UPDATE push_outbox
                SET status=?,attempts=?,next_attempt=?
                WHERE id=?
                """,
                nextStatus,
                attempt,
                Db.now() + 60000L * attempt,
                id
        );
    }

    private void finish(String id, String status) {
        db.jdbc.update(
                "UPDATE push_outbox SET status=? WHERE id=?",
                status, id
        );
    }
}