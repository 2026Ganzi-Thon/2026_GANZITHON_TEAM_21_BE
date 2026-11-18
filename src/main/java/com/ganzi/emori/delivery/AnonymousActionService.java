package com.ganzi.emori.delivery;

import com.ganzi.emori.api.ApiException;
import com.ganzi.emori.api.DbSupport;
import com.ganzi.emori.api.Idempotency;
import com.ganzi.emori.delivery.AnonymousModels.*;
import com.ganzi.emori.delivery.DeliveryModels.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AnonymousActionService {
    private final JdbcTemplate jdbc;
    private final DbSupport db;
    private final Idempotency idempotency;
    private final ObjectMapper json;
    public AnonymousActionService(JdbcTemplate jdbc,DbSupport db,Idempotency idempotency,ObjectMapper json) {
        this.jdbc=jdbc; this.db=db; this.idempotency=idempotency; this.json=json;
    }

    @Transactional
    public String reaction(UUID actor,UUID receipt,UUID key,ReactionRequest input) {
        db.lockUsers(actor);
        String path="/api/v1/receipts/"+receipt+"/reaction";
        return idempotency.execute(actor,"POST",path,key,json.writeValueAsString(input),() -> {
            if(input==null || input.actionToken()==null || input.actionToken().isBlank() || input.code()==null)
                throw ApiException.invalid("actionToken and code are required");
            ActionRow row=owned(actor,receipt,true);
            if(!row.route().equals("ANONYMOUS")) throw notAllowed();
            validateToken(row,input.actionToken());
            Boolean valid=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM reaction_catalog WHERE code=? AND active=TRUE)",
                    Boolean.class,input.code());
            if(!Boolean.TRUE.equals(valid)) throw ApiException.invalid("Invalid reaction code");
            OffsetDateTime now=DbSupport.now();
            jdbc.update("INSERT INTO receipt_reaction(receipt_id,code,created_at) VALUES (?,?,?)",receipt,input.code(),now);
            jdbc.update("UPDATE receipt SET action_closed_at=? WHERE id=?",now,receipt);
            return new ReactionResponse(input.code(),now.toInstant().toString());
        });
    }

    @Transactional
    public void finish(UUID actor,UUID receipt,FinishRequest input) {
        if(input==null || input.actionToken()==null || input.actionToken().isBlank())
            throw ApiException.invalid("actionToken is required");
        ActionRow row=owned(actor,receipt,true);
        if(!row.route().equals("ANONYMOUS")) throw notAllowed();
        if(row.hash()==null || !row.hash().equals(DbSupport.hash(input.actionToken()))) throw closed();
        if(row.closedAt()==null && (row.expiresAt()==null || !DbSupport.now().isBefore(row.expiresAt()))) throw closed();
        if(row.closedAt()==null) jdbc.update("UPDATE receipt SET action_closed_at=? WHERE id=?",DbSupport.now(),receipt);
    }

    @Transactional
    public String report(UUID actor,UUID receipt,UUID key,ReportRequest input) {
        db.lockUsers(actor);
        String path="/api/v1/receipts/"+receipt+"/reports";
        String body=json.writeValueAsString(input);
        return idempotency.execute(actor,"POST",path,key,body,() -> {
            if(input==null || input.reasonCode()==null) throw ApiException.invalid("reasonCode is required");
            String reason=input.reasonCode();
            if(!List.of("INAPPROPRIATE","UNWANTED_REPEATED","HARASSMENT_THREAT","OTHER").contains(reason))
                throw ApiException.invalid("Invalid reasonCode");
            String detail=input.detail();
            if(detail!=null) {
                detail=detail.trim();
                if(!reason.equals("OTHER") || detail.isEmpty() || detail.length()>500)
                    throw ApiException.invalid("detail is valid only for OTHER and must be 1-500 characters");
            }
            ActionRow row=owned(actor,receipt,true);
            if(!row.route().equals("ANONYMOUS")) throw notAllowed();
            if(!jdbc.query("SELECT id FROM receipt_report WHERE receipt_id=?",(rs,n)->rs.getObject(1),receipt).isEmpty())
                throw new ApiException(HttpStatus.CONFLICT,"ALREADY_REPORTED","Already reported");
            UUID reportId=UUID.randomUUID();
            jdbc.update("INSERT INTO receipt_report(id,receipt_id,reason_code,detail,created_at) VALUES (?,?,?,?,?)",
                    reportId,receipt,reason,detail,DbSupport.now());
            return new ReportResponse(reportId.toString(),"RECEIVED");
        });
    }

    @Transactional
    public void block(UUID actor,UUID receipt) {
        ActionRow initial=owned(actor,receipt,false);
        if(!initial.route().equals("ANONYMOUS")) throw notAllowed();
        db.lockUsers(actor,initial.sender());
        ActionRow row=owned(actor,receipt,true);
        if(!row.route().equals("ANONYMOUS")) throw notAllowed();
        if(jdbc.query("SELECT blocker_id FROM anonymous_block WHERE blocker_id=? AND blocked_id=?",
                (rs,n)->rs.getObject(1),actor,row.sender()).isEmpty())
            jdbc.update("INSERT INTO anonymous_block(blocker_id,blocked_id,created_at) VALUES (?,?,?)",
                    actor,row.sender(),DbSupport.now());
    }

    private ActionRow owned(UUID actor,UUID receipt,boolean lock) {
        if(lock) jdbc.query("SELECT id FROM receipt WHERE id=? FOR UPDATE",(rs,n)->rs.getObject(1),receipt);
        String sql="""
                SELECT d.route,o.author_id,r.action_token_hash,r.action_expires_at,r.action_closed_at,
                    r.opened_at FROM receipt r JOIN dispatch d ON d.id=r.dispatch_id
                JOIN orb o ON o.id=d.orb_id WHERE r.id=? AND r.recipient_id=? AND r.cancelled_at IS NULL
                """;
        List<ActionRow> rows=jdbc.query(sql,(rs,n)->new ActionRow(rs.getString(1),
                rs.getObject(2,UUID.class),rs.getString(3),rs.getObject(4,OffsetDateTime.class),
                rs.getObject(5,OffsetDateTime.class),rs.getObject(6,OffsetDateTime.class)),receipt,actor);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","Receipt not found");
        return rows.getFirst();
    }

    private static void validateToken(ActionRow row,String supplied) {
        if(row.openedAt()==null || row.hash()==null || !row.hash().equals(DbSupport.hash(supplied)) ||
                row.closedAt()!=null || row.expiresAt()==null || !DbSupport.now().isBefore(row.expiresAt()))
            throw closed();
    }
    private static ApiException closed() { return new ApiException(HttpStatus.CONFLICT,"ACTION_CLOSED","Action is closed"); }
    private static ApiException notAllowed() { return new ApiException(HttpStatus.FORBIDDEN,"ACTION_NOT_ALLOWED","Action is not allowed"); }
    private record ActionRow(String route,UUID sender,String hash,OffsetDateTime expiresAt,
                             OffsetDateTime closedAt,OffsetDateTime openedAt) {}
}
