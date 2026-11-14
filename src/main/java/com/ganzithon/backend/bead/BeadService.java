package com.ganzithon.backend.bead;

import com.ganzithon.backend.api.ApiException;
import com.ganzithon.backend.common.Db;
import com.ganzithon.backend.friend.FriendService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static com.ganzithon.backend.api.ApiModels.*;

@Service
public class BeadService {

    private final Db db;
    private final FriendService friends;

    public BeadService(Db db, FriendService friends) {
        this.db = db;
        this.friends = friends;
    }

    public List<Map<String, Object>> colors() {
        return Arrays.stream(Color.values())
                .map(color -> Map.<String, Object>of(
                        "code", color.name(),
                        "label", color.label,
                        "area", color.area
                ))
                .toList();
    }

    public Map<String, Object> emojis() {
        return Map.of(
                "provisional", true,
                "items", db.jdbc.queryForList(
                        "SELECT emoji FROM emoji_catalog ORDER BY emoji",
                        String.class
                )
        );
    }

    @Transactional
    public Map<String, Object> send(
            String actor,
            String key,
            SendBead input
    ) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,80}")) {
            throw ApiException.bad(
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "8~80자 Idempotency-Key 헤더가 필요해요."
            );
        }

        if (new HashSet<>(input.colors()).size()
                != input.colors().size()) {
            throw ApiException.bad(
                    "DUPLICATE_COLOR",
                    "같은 색상을 두 번 선택할 수 없어요."
            );
        }

        if (new HashSet<>(input.recipientIds()).size()
                != input.recipientIds().size()
                || input.recipientIds().contains(actor)) {
            throw ApiException.bad(
                    "INVALID_RECIPIENTS",
                    "중복 수신자를 빼고, 본인 저장은 saveToSelf로 지정해주세요."
            );
        }

        if (!input.saveToSelf() && input.recipientIds().isEmpty()) {
            throw ApiException.bad(
                    "NO_RECIPIENT",
                    "저장 또는 수신자를 선택해주세요."
            );
        }

        List<String> recipients = input.recipientIds()
                .stream()
                .sorted()
                .toList();

        String requestHash = Db.hash(
                input.colors() + "|"
                        + input.saveToSelf() + "|"
                        + recipients + "|"
                        + input.withMusic()
        );

        List<String> lockIds = new ArrayList<>(recipients);
        lockIds.add(actor);
        db.lockUsers(lockIds);

        var previous = db.jdbc.queryForList("""
                SELECT id,request_hash FROM bead
                WHERE sender_id=? AND request_key=?
                """,
                actor, key
        );

        if (!previous.isEmpty()) {
            if (!requestHash.equals(
                    previous.getFirst().get("request_hash")
            )) {
                throw new ApiException(
                        409,
                        "IDEMPOTENCY_CONFLICT",
                        "같은 키로 다른 내용을 전송할 수 없어요."
                );
            }

            return sendResult(
                    (String) previous.getFirst().get("id"),
                    true
            );
        }

        if (input.withMusic()
                && !Db.bool(db.user(actor), "premium")) {
            throw new ApiException(
                    403,
                    "MUSIC_PAYMENT_REQUIRED",
                    "아직 결제가 되지 않았어요!"
            );
        }

        List<String> accepted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (String recipient : recipients) {
            if (!friends.friends(actor, recipient)) {
                throw new ApiException(
                        403,
                        "NOT_FRIEND",
                        "수락된 친구에게만 보낼 수 있어요."
                );
            }

            if (Db.bool(db.user(recipient), "resting")) {
                skipped.add(recipient);
            } else {
                accepted.add(recipient);
            }
        }

        if (accepted.isEmpty() && !input.saveToSelf()) {
            return Map.of(
                    "sent", false,
                    "replayed", false,
                    "deliveredRecipientIds", List.of(),
                    "skippedRecipients", skipViews(skipped)
            );
        }

        String beadId = Db.id();
        long now = Db.now();

        db.jdbc.update("""
                INSERT INTO bead(
                    id,sender_id,color_first,color_second,
                    music_color,self_saved,created_at,
                    request_key,request_hash
                ) VALUES (?,?,?,?,?,?,?,?,?)
                """,
                beadId,
                actor,
                input.colors().getFirst().name(),
                input.colors().size() == 2
                        ? input.colors().get(1).name()
                        : null,
                input.withMusic()
                        ? input.colors().getFirst().name()
                        : null,
                input.saveToSelf(),
                now,
                key,
                requestHash
        );

        for (String recipient : accepted) {
            db.jdbc.update("""
                    INSERT INTO delivery(
                        id,bead_id,recipient_id,received_at
                    ) VALUES (?,?,?,?)
                    """,
                    Db.id(), beadId, recipient, now
            );

            enqueue(recipient, beadId, "BEAD_RECEIVED");
        }

        for (String recipient : skipped) {
            db.jdbc.update("""
                    INSERT INTO send_skip(
                        bead_id,user_id,reason
                    ) VALUES (?,?,?)
                    """,
                    beadId, recipient, "RESTING"
            );
        }

        return sendResult(beadId, false);
    }

    private List<Map<String, Object>> skipViews(
            List<String> ids
    ) {
        return ids.stream()
                .map(id -> Map.<String, Object>of(
                        "userId", id,
                        "loginId", db.user(id).get("login_id"),
                        "reason", "RESTING"
                ))
                .toList();
    }

    private Map<String, Object> sendResult(
            String beadId,
            boolean replayed
    ) {
        List<String> delivered = db.jdbc.queryForList("""
                SELECT recipient_id FROM delivery
                WHERE bead_id=? ORDER BY recipient_id
                """,
                String.class, beadId
        );

        List<String> skipped = db.jdbc.queryForList("""
                SELECT user_id FROM send_skip
                WHERE bead_id=? ORDER BY user_id
                """,
                String.class, beadId
        );

        return Map.of(
                "sent", true,
                "beadId", beadId,
                "replayed", replayed,
                "deliveredRecipientIds", delivered,
                "skippedRecipients", skipViews(skipped)
        );
    }

    private void enqueue(
            String userId,
            String beadId,
            String eventType
    ) {
        db.jdbc.update("""
                INSERT INTO push_outbox(
                    id,user_id,bead_id,event_type,
                    status,next_attempt,created_at
                ) VALUES (?,?,?,?,?,?,?)
                """,
                Db.id(),
                userId,
                beadId,
                eventType,
                "PENDING",
                Db.now(),
                Db.now()
        );
    }

    Map<String, Object> accessible(
            String actor,
            String beadId
    ) {
        return db.one("""
                SELECT b.*,u.login_id AS sender_login
                FROM bead b
                JOIN app_user u ON u.id=b.sender_id
                WHERE b.id=?
                  AND (
                      b.sender_id=?
                      OR EXISTS (
                          SELECT 1 FROM delivery d
                          WHERE d.bead_id=b.id
                            AND d.recipient_id=?
                      )
                  )
                """,
                beadId, actor, actor
        );
    }

    private Map<String, Object> summary(
            Map<String, Object> bead
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("id", bead.get("id"));
        result.put("senderId", bead.get("sender_id"));
        result.put("senderLoginId", bead.get("sender_login"));
        result.put("createdAt", bead.get("created_at"));

        result.put(
                "colors",
                bead.get("color_second") == null
                        ? List.of(bead.get("color_first"))
                        : List.of(
                        bead.get("color_first"),
                        bead.get("color_second")
                )
        );

        result.put(
                "hasMusic",
                bead.get("music_color") != null
        );

        return result;
    }

    public Map<String, Object> detail(
            String actor,
            String beadId
    ) {
        return detail(actor, beadId, true);
    }

    public Map<String, Object> detail(
            String actor,
            String beadId,
            boolean initialReceipt
    ) {
        var bead = accessible(actor, beadId);
        var result = summary(bead);

        result.put(
                "music",
                bead.get("music_color") == null
                        ? null
                        : Map.of(
                        "source", "PRESET",
                        "color", bead.get("music_color"),
                        "url", "/api/v1/beads/" + beadId + "/audio",
                        "durationSeconds", 10
                )
        );

        var deliveries = db.jdbc.queryForList("""
                SELECT opened_at,listening_mode FROM delivery
                WHERE bead_id=? AND recipient_id=?
                """,
                beadId, actor
        );

        result.put(
                "receipt",
                deliveries.isEmpty()
                        ? null
                        : deliveries.getFirst()
        );

        result.put(
                "initialChoiceAvailable",
                initialReceipt
                        && !deliveries.isEmpty()
                        && deliveries.getFirst().get("listening_mode") == null
        );

        result.put(
                "reactions",
                db.jdbc.queryForList("""
                        SELECT r.id,r.actor_id,u.login_id,
                               r.emoji,r.created_at
                        FROM reaction r
                        JOIN app_user u ON u.id=r.actor_id
                        WHERE bead_id=?
                        ORDER BY r.created_at,r.id
                        """,
                        beadId
                )
        );

        return result;
    }

    public List<Map<String, Object>> archive(
            String actor,
            int page,
            int size
    ) {
        checkPage(page, size);

        return db.jdbc.queryForList("""
                SELECT b.*,u.login_id AS sender_login
                FROM bead b
                JOIN app_user u ON u.id=b.sender_id
                WHERE b.sender_id=?
                   OR EXISTS (
                       SELECT 1 FROM delivery d
                       WHERE d.bead_id=b.id AND d.recipient_id=?
                   )
                ORDER BY b.created_at DESC,b.id
                LIMIT ? OFFSET ?
                """,
                actor, actor, size, (long) page * size
        ).stream().map(this::summary).toList();
    }

    public List<Map<String, Object>> inbox(
            String actor,
            int page,
            int size
    ) {
        checkPage(page, size);

        return db.jdbc.queryForList("""
                SELECT b.*,u.login_id AS sender_login,
                       d.opened_at,d.listening_mode
                FROM delivery d
                JOIN bead b ON b.id=d.bead_id
                JOIN app_user u ON u.id=b.sender_id
                WHERE d.recipient_id=?
                ORDER BY d.received_at DESC,b.id
                LIMIT ? OFFSET ?
                """,
                actor, size, (long) page * size
        ).stream().map(row -> {
            var result = summary(row);
            result.put("openedAt", row.get("opened_at"));
            result.put("listeningMode", row.get("listening_mode"));
            return result;
        }).toList();
    }

    private void checkPage(int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) {
            throw ApiException.bad(
                    "INVALID_PAGE",
                    "page는 0~10000, size는 1~100이어야 해요."
            );
        }
    }

    @Transactional
    public void open(String actor, String beadId) {
        int updated = db.jdbc.update("""
                UPDATE delivery
                SET opened_at=COALESCE(opened_at,?)
                WHERE bead_id=? AND recipient_id=?
                """,
                Db.now(), beadId, actor
        );

        if (updated == 0) {
            throw ApiException.missing();
        }
    }

    @Transactional
    public void quiet(String actor, String beadId) {
        db.lockUsers(List.of(actor));

        var delivery = db.one("""
                SELECT * FROM delivery
                WHERE bead_id=? AND recipient_id=?
                """,
                beadId, actor
        );

        Object mode = delivery.get("listening_mode");

        if (mode != null && !"QUIET".equals(mode)) {
            throw new ApiException(
                    409,
                    "CHOICE_ALREADY_MADE",
                    "이미 반응한 구슬이에요."
            );
        }

        db.jdbc.update("""
                UPDATE delivery
                SET listening_mode='QUIET',
                    opened_at=COALESCE(opened_at,?)
                WHERE bead_id=? AND recipient_id=?
                """,
                Db.now(), beadId, actor
        );

        // 조용히 듣기는 발신자에게 알림을 보내지 않음
    }

    @Transactional
    public Map<String, Object> react(
            String actor,
            String beadId,
            String emoji
    ) {
        var bead = accessible(actor, beadId);

        String sender = (String) bead.get("sender_id");

        db.lockUsers(List.of(actor, sender));

        db.one("""
                SELECT id FROM delivery
                WHERE bead_id=? AND recipient_id=?
                """,
                beadId, actor
        );

        if (!db.exists(
                "SELECT emoji FROM emoji_catalog WHERE emoji=?",
                emoji
        )) {
            throw ApiException.bad(
                    "UNSUPPORTED_EMOJI",
                    "지원하지 않는 이모지예요."
            );
        }

        var previous = db.jdbc.queryForList("""
                SELECT id FROM reaction
                WHERE bead_id=? AND actor_id=? AND emoji=?
                """,
                beadId, actor, emoji
        );

        if (!previous.isEmpty()) {
            return Map.of(
                    "id", previous.getFirst().get("id"),
                    "alreadyAdded", true
            );
        }

        String reactionId = Db.id();

        db.jdbc.update("""
                INSERT INTO reaction(
                    id,bead_id,actor_id,emoji,created_at
                ) VALUES (?,?,?,?,?)
                """,
                reactionId,
                beadId,
                actor,
                emoji,
                Db.now()
        );

        db.jdbc.update("""
                UPDATE delivery
                SET listening_mode=COALESCE(listening_mode,'REACT'),
                    opened_at=COALESCE(opened_at,?)
                WHERE bead_id=? AND recipient_id=?
                """,
                Db.now(), beadId, actor
        );

        if (!Db.bool(db.user(sender), "resting")) {
            enqueue(sender, beadId, "REACTION_ADDED");
        }

        return Map.of(
                "id", reactionId,
                "alreadyAdded", false
        );
    }
}