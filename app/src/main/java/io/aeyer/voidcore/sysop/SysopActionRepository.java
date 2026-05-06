package io.aeyer.voidcore.sysop;

import io.aeyer.voidcore.monitoring.VoidCoreMeters;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.JSONB;

import static io.aeyer.voidcore.jooq.Tables.SYSOP_ACTIONS;

/**
 * Append-only audit log of every sysop action per SPEC §3 / §7.7. Every
 * mutation a sysop performs lands a row here with the actor's user id, an
 * action label (e.g. "ban_user"), and a JSONB payload of the relevant
 * details (target id, before/after values, etc).
 */
public class SysopActionRepository {

    private final DSLContext dsl;
    private final ObjectMapper json;
    private final VoidCoreMeters meters;

    public SysopActionRepository(DSLContext dsl, ObjectMapper json, VoidCoreMeters meters) {
        this.dsl = dsl;
        this.json = json;
        this.meters = meters;
    }

    public void record(long actorId, String action, JsonNode payload) {
        try {
            dsl.insertInto(SYSOP_ACTIONS)
                    .set(SYSOP_ACTIONS.ACTOR_ID, actorId)
                    .set(SYSOP_ACTIONS.ACTION, action)
                    .set(SYSOP_ACTIONS.PAYLOAD,
                            payload == null ? null : JSONB.valueOf(json.writeValueAsString(payload)))
                    .execute();
            meters.recordSysopAction();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("could not serialise sysop_actions payload", e);
        }
    }
}
