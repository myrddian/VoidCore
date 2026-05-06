package io.aeyer.voidcore.ws.flow.screen;

import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.auth.UserRepository.UserRow;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.SessionRegistry;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Per-user "you were @-mentioned" delivery. Per ADR-027 this is a
 * <em>targeted notification</em> — distinct from the topic-invalidation
 * bus, because the receiver set is "every open session of user X" and
 * the payload is meaningful (a {@code notify} frame plus a title
 * effect). The bus model deliberately doesn't cover this in v1.4;
 * MentionService is the v1 form.
 *
 * <p>Same-room suppression: if the @-mentioned user is currently
 * viewing the same screen the mention was posted on, they'll see
 * the line inline — no popup needed. The per-session phase comes
 * from {@link NavigationState} (the leaf bean owning the per-
 * session navigation stack per ADR-030). Earlier this was a
 * {@code @Lazy Navigator} dependency to break a Spring cycle —
 * NavigationState is a leaf with no cycle, so the {@code @Lazy}
 * is gone.
 *
 * <p>v1.4 PR-B step 13b: extracted from {@code ScreenRouter} so
 * {@link io.aeyer.voidcore.ws.flow.screen.impl.OnelinersScreen} can own
 * its full submit path without the legacy bridge. Chat (still in
 * router) routes its mention call through this same service.
 */
@Component
@ConditionalOnBean(UserRepository.class)
public class MentionService {

    private static final Logger log = LoggerFactory.getLogger(MentionService.class);

    /** Matches {@code @handle} per SPEC §4.5 (3–16 chars, alnum + _-.). */
    private static final Pattern MENTION_PATTERN =
            Pattern.compile("@([A-Za-z0-9_\\-.]{3,16})");

    private final UserRepository users;
    private final SessionRegistry wsSessions;
    private final NavigationState navState;

    public MentionService(UserRepository users,
                          SessionRegistry wsSessions,
                          NavigationState navState) {
        this.users = users;
        this.wsSessions = wsSessions;
        this.navState = navState;
    }

    /**
     * Scan {@code body} for {@code @handle} mentions, deliver a notify
     * + title-effect to every open session of the matched user that
     * is <em>not</em> currently on {@code suppressPhase} (where the
     * mention is visible inline anyway).
     *
     * @param sender the session that posted the body
     * @param body the text to scan; null / no-{@code @} returns immediately
     * @param contextLabel human-readable phrase for the notify body
     *        (e.g. {@code "the one-liner wall"}, {@code "chat"})
     * @param suppressPhase peer sessions on this phase are skipped;
     *        pass {@code null} to deliver to every matched session
     */
    /**
     * Send a targeted notify + title-effect to every open session of
     * {@code targetUserId} that is <em>not</em> currently on
     * {@code suppressPhase}. Sister to {@link #notify} but addressed
     * by user-id (no body scanning, no @-handle resolution).
     *
     * <p>v1 callers: NetMail compose finalisation (popup the
     * recipient if they're not already on the inbox screen — the
     * bus topic handles the inbox-repaint case). Future per-user
     * targeted-notification callers can collapse here too; per
     * ADR-027 this is the v1 form of targeted notifications until
     * the bus design covers them properly.
     */
    public void notifyUser(long targetUserId,
                           Phase suppressPhase,
                           String message,
                           int durationMs) {
        for (VoidCoreSession peer : wsSessions.all()) {
            if (!peer.isOpen()) continue;
            Long peerUid = peer.userId();
            if (peerUid == null || peerUid != targetUserId) continue;
            if (suppressPhase != null
                    && navState.currentPhase(peer) == suppressPhase) continue;
            try {
                peer.send(Frames.notify("notifications", message, "info", durationMs));
                peer.send(new ServerMessage.EffectSetTitle("VOIDcore (!)"));
            } catch (IOException e) {
                log.debug("targeted notify to session={} failed: {}",
                        peer.id(), e.toString());
            }
        }
    }

    /**
     * Broadcast {@code message} as an alert-style notify to every
     * authenticated open session (sessions still at the login or
     * goodbye phase don't see it — they aren't on the BBS yet /
     * any more). Used by the sysop {@code [X]} broadcast tool.
     *
     * @return the count of sessions delivered to (for the
     *         "broadcast sent to N sessions" confirmation)
     */
    public int broadcastAll(String message, int durationMs) {
        int sent = 0;
        for (VoidCoreSession peer : wsSessions.all()) {
            if (!peer.isOpen()) continue;
            if (peer.userId() == null) continue; // pre-auth session
            try {
                peer.send(Frames.notify("notifications", message, "alert", durationMs));
                sent++;
            } catch (IOException e) {
                log.debug("broadcast to session={} failed: {}",
                        peer.id(), e.toString());
            }
        }
        return sent;
    }

    public void notify(VoidCoreSession sender,
                       String body,
                       String contextLabel,
                       Phase suppressPhase) {
        if (body == null || body.indexOf('@') < 0) return;
        Long senderUid = sender.userId();
        String senderHandle = senderUid == null
                ? "?"
                : users.findById(senderUid).map(UserRow::handle).orElse("?");

        var matcher = MENTION_PATTERN.matcher(body);
        Set<String> seen = new HashSet<>();
        while (matcher.find()) {
            String handle = matcher.group(1);
            if (!seen.add(handle.toUpperCase())) continue;
            UserRow target = users.findByHandle(handle).orElse(null);
            if (target == null) continue;
            if (senderUid != null && senderUid == target.id()) continue;
            for (VoidCoreSession peer : wsSessions.all()) {
                if (!peer.isOpen()) continue;
                Long peerUid = peer.userId();
                if (peerUid == null || peerUid != target.id()) continue;
                if (suppressPhase != null
                        && navState.currentPhase(peer) == suppressPhase) continue;
                try {
                    peer.send(Frames.notify("notifications",
                            senderHandle + " mentioned you in " + contextLabel,
                            "warn", 5000));
                    peer.send(new ServerMessage.EffectSetTitle("VOIDcore (!)"));
                } catch (IOException e) {
                    log.debug("mention delivery to session={} failed: {}",
                            peer.id(), e.toString());
                }
            }
        }
    }
}
