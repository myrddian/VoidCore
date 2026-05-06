package io.aeyer.voidcore.ws.session;

import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;

/**
 * Public-facing {@link VoidCoreSession} implementation. Every method
 * routes through the per-session {@link SessionActor} so the wrapped
 * {@link VoidCoreSession} state is only ever read or written by the
 * actor's worker thread. Synchronous from the caller's POV (the
 * proxy blocks on {@code submit}); single-threaded from the core's POV.
 *
 * <h2>Hot-path caching</h2>
 *
 * <p>Two values are cached on the proxy and bypass the actor:
 * {@link #id()} and {@link #connectedAt()}. They're truly immutable for
 * the lifetime of the proxy (set at construction, never change) and
 * called frequently — every log line uses {@code id()}, for instance.
 * Routing them through the queue would cost a virtual-thread context
 * switch per call for no benefit.
 *
 * <p>{@link #underlying()} is <strong>not</strong> cached — it routes
 * through the actor. Per ADR-033 the underlying WS may be swapped at
 * runtime (reconnect attaches a new WS to an existing actor); a cached
 * reference would silently return the closed pre-swap socket.
 *
 * <p>All other methods go through the actor, including reads — the
 * cost is a few microseconds per call (parking/unparking a virtual
 * thread on a {@code BlockingQueue}) which is well below anything
 * the BBS's interaction speed can notice.
 *
 * <h2>Checked exceptions</h2>
 *
 * <p>{@link VoidCoreSession#send} and {@link VoidCoreSession#sendPing} throw
 * {@link IOException}. The actor's lambda type is {@code Function} which
 * doesn't allow checked exceptions, so the proxy wraps in
 * {@link UncheckedIOException} inside the lambda and unwraps on this
 * side before rethrowing.
 */
public final class SessionProxy implements VoidCoreSession {

    private final SessionActor actor;
    private final String id;
    private final Instant connectedAt;

    public SessionProxy(SessionActor actor, String id, Instant connectedAt) {
        this.actor = actor;
        this.id = id;
        this.connectedAt = connectedAt;
    }

    /** Visible for {@link SessionRegistry} / lifecycle management. */
    public SessionActor actor() {
        return actor;
    }

    // ─── Cached immutable accessors (no actor round-trip) ─────────────

    @Override public String id() { return id; }
    @Override public Instant connectedAt() { return connectedAt; }

    /**
     * Routes through the actor — the underlying WS may have been
     * swapped (ADR-033 reconnect path). Cost: one virtual-thread
     * context switch per call. {@code underlying()} is rare on the hot
     * path (handshake headers, remote address — typically a few times
     * per session), so the cost is fine.
     */
    @Override public WebSocketSession underlying() {
        return actor.submit(VoidCoreSession::underlying);
    }

    // ─── Identity / user state — actor-routed ──────────────────────────

    @Override public Long userId() { return actor.submit(VoidCoreSession::userId); }
    @Override public void setUserId(Long u) { actor.submit(c -> { c.setUserId(u); return null; }); }

    @Override public String handle() { return actor.submit(VoidCoreSession::handle); }
    @Override public void setHandle(String h) { actor.submit(c -> { c.setHandle(h); return null; }); }

    @Override public boolean isSysop() { return actor.submit(VoidCoreSession::isSysop); }
    @Override public void setSysop(boolean s) { actor.submit(c -> { c.setSysop(s); return null; }); }

    @Override public String sessionToken() { return actor.submit(VoidCoreSession::sessionToken); }
    @Override public void setSessionToken(String t) { actor.submit(c -> { c.setSessionToken(t); return null; }); }

    // ─── Pre-auth and intent state ─────────────────────────────────────

    @Override public String pendingIntent() { return actor.submit(VoidCoreSession::pendingIntent); }
    @Override public void setPendingIntent(String s) { actor.submit(c -> { c.setPendingIntent(s); return null; }); }

    @Override public io.aeyer.voidcore.ws.flow.RegisterDraft registerDraft() {
        return actor.submit(VoidCoreSession::registerDraft);
    }
    @Override public void setRegisterDraft(io.aeyer.voidcore.ws.flow.RegisterDraft d) {
        actor.submit(c -> { c.setRegisterDraft(d); return null; });
    }

    @Override public String pendingLoginHandle() { return actor.submit(VoidCoreSession::pendingLoginHandle); }
    @Override public void setPendingLoginHandle(String h) { actor.submit(c -> { c.setPendingLoginHandle(h); return null; }); }

    // ─── In-flight composes ────────────────────────────────────────────

    @Override public io.aeyer.voidcore.netmail.NetmailDraft netmailDraft() {
        return actor.submit(VoidCoreSession::netmailDraft);
    }
    @Override public void setNetmailDraft(io.aeyer.voidcore.netmail.NetmailDraft d) {
        actor.submit(c -> { c.setNetmailDraft(d); return null; });
    }

    // ─── Navigation breadcrumbs ────────────────────────────────────────

    @Override public Long currentNetmailId() { return actor.submit(VoidCoreSession::currentNetmailId); }
    @Override public void setCurrentNetmailId(Long id) { actor.submit(c -> { c.setCurrentNetmailId(id); return null; }); }

    @Override public Long currentBulletinId() { return actor.submit(VoidCoreSession::currentBulletinId); }
    @Override public void setCurrentBulletinId(Long id) { actor.submit(c -> { c.setCurrentBulletinId(id); return null; }); }

    @Override public Long currentReleaseId() { return actor.submit(VoidCoreSession::currentReleaseId); }
    @Override public void setCurrentReleaseId(Long id) { actor.submit(c -> { c.setCurrentReleaseId(id); return null; }); }

    @Override public Long currentDocumentId() { return actor.submit(VoidCoreSession::currentDocumentId); }
    @Override public void setCurrentDocumentId(Long id) { actor.submit(c -> { c.setCurrentDocumentId(id); return null; }); }

    @Override public Long currentPollId() { return actor.submit(VoidCoreSession::currentPollId); }
    @Override public void setCurrentPollId(Long id) { actor.submit(c -> { c.setCurrentPollId(id); return null; }); }

    @Override public String infoVariant() { return actor.submit(VoidCoreSession::infoVariant); }
    @Override public void setInfoVariant(String v) { actor.submit(c -> { c.setInfoVariant(v); return null; }); }

    @Override public String currentChatRoomSlug() { return actor.submit(VoidCoreSession::currentChatRoomSlug); }
    @Override public void setCurrentChatRoomSlug(String slug) { actor.submit(c -> { c.setCurrentChatRoomSlug(slug); return null; }); }
    @Override public String currentDoorId() { return actor.submit(VoidCoreSession::currentDoorId); }
    @Override public void setCurrentDoorId(String doorId) { actor.submit(c -> { c.setCurrentDoorId(doorId); return null; }); }
    @Override public String selectedAchievementDoorId() { return actor.submit(VoidCoreSession::selectedAchievementDoorId); }
    @Override public void setSelectedAchievementDoorId(String doorId) { actor.submit(c -> { c.setSelectedAchievementDoorId(doorId); return null; }); }

    @Override public Long selectedBaseId() { return actor.submit(VoidCoreSession::selectedBaseId); }
    @Override public void setSelectedBaseId(Long id) { actor.submit(c -> { c.setSelectedBaseId(id); return null; }); }

    @Override public Long selectedThreadId() { return actor.submit(VoidCoreSession::selectedThreadId); }
    @Override public void setSelectedThreadId(Long id) { actor.submit(c -> { c.setSelectedThreadId(id); return null; }); }

    @Override public String pendingThreadSubject() { return actor.submit(VoidCoreSession::pendingThreadSubject); }
    @Override public void setPendingThreadSubject(String s) { actor.submit(c -> { c.setPendingThreadSubject(s); return null; }); }

    // ─── Sysop tool state ──────────────────────────────────────────────

    @Override public Long selectedSysopId() { return actor.submit(VoidCoreSession::selectedSysopId); }
    @Override public void setSelectedSysopId(Long id) { actor.submit(c -> { c.setSelectedSysopId(id); return null; }); }

    @Override public String selectedSysopSlug() { return actor.submit(VoidCoreSession::selectedSysopSlug); }
    @Override public void setSelectedSysopSlug(String slug) { actor.submit(c -> { c.setSelectedSysopSlug(slug); return null; }); }

    @Override public Long selectedSysopResourceId() { return actor.submit(VoidCoreSession::selectedSysopResourceId); }
    @Override public void setSelectedSysopResourceId(Long id) { actor.submit(c -> { c.setSelectedSysopResourceId(id); return null; }); }

    @Override public String sysopBulletinTitle() { return actor.submit(VoidCoreSession::sysopBulletinTitle); }
    @Override public void setSysopBulletinTitle(String t) { actor.submit(c -> { c.setSysopBulletinTitle(t); return null; }); }

    @Override public boolean sysopBulletinPinned() { return actor.submit(VoidCoreSession::sysopBulletinPinned); }
    @Override public void setSysopBulletinPinned(boolean p) { actor.submit(c -> { c.setSysopBulletinPinned(p); return null; }); }

    // ─── Sysop file-new walk drafts ────────────────────────────────────

    @Override public String pendingReleaseFilename() { return actor.submit(VoidCoreSession::pendingReleaseFilename); }
    @Override public void setPendingReleaseFilename(String s) { actor.submit(c -> { c.setPendingReleaseFilename(s); return null; }); }

    @Override public String pendingReleaseTitle() { return actor.submit(VoidCoreSession::pendingReleaseTitle); }
    @Override public void setPendingReleaseTitle(String s) { actor.submit(c -> { c.setPendingReleaseTitle(s); return null; }); }

    @Override public String pendingReleaseExternalUrl() { return actor.submit(VoidCoreSession::pendingReleaseExternalUrl); }
    @Override public void setPendingReleaseExternalUrl(String s) { actor.submit(c -> { c.setPendingReleaseExternalUrl(s); return null; }); }

    @Override public List<String> pendingReleaseNfoLines() { return actor.submit(VoidCoreSession::pendingReleaseNfoLines); }
    @Override public void setPendingReleaseNfoLines(List<String> lines) { actor.submit(c -> { c.setPendingReleaseNfoLines(lines); return null; }); }

    @Override public Short pendingReleaseYear() { return actor.submit(VoidCoreSession::pendingReleaseYear); }
    @Override public void setPendingReleaseYear(Short y) { actor.submit(c -> { c.setPendingReleaseYear(y); return null; }); }

    @Override public String pendingReleaseArtist() { return actor.submit(VoidCoreSession::pendingReleaseArtist); }
    @Override public void setPendingReleaseArtist(String s) { actor.submit(c -> { c.setPendingReleaseArtist(s); return null; }); }

    @Override public String pendingReleaseLabel() { return actor.submit(VoidCoreSession::pendingReleaseLabel); }
    @Override public void setPendingReleaseLabel(String s) { actor.submit(c -> { c.setPendingReleaseLabel(s); return null; }); }

    @Override public String pendingReleaseCatalogNumber() { return actor.submit(VoidCoreSession::pendingReleaseCatalogNumber); }
    @Override public void setPendingReleaseCatalogNumber(String s) { actor.submit(c -> { c.setPendingReleaseCatalogNumber(s); return null; }); }

    @Override public String pendingReleaseGenre() { return actor.submit(VoidCoreSession::pendingReleaseGenre); }
    @Override public void setPendingReleaseGenre(String s) { actor.submit(c -> { c.setPendingReleaseGenre(s); return null; }); }

    @Override public String docsFilter() { return actor.submit(VoidCoreSession::docsFilter); }
    @Override public void setDocsFilter(String s) { actor.submit(c -> { c.setDocsFilter(s); return null; }); }

    @Override public Integer docsResultsPage() { return actor.submit(VoidCoreSession::docsResultsPage); }
    @Override public void setDocsResultsPage(Integer page) { actor.submit(c -> { c.setDocsResultsPage(page); return null; }); }

    @Override public String docsResultsSort() { return actor.submit(VoidCoreSession::docsResultsSort); }
    @Override public void setDocsResultsSort(String s) { actor.submit(c -> { c.setDocsResultsSort(s); return null; }); }

    @Override public String pendingDocKind() { return actor.submit(VoidCoreSession::pendingDocKind); }
    @Override public void setPendingDocKind(String kind) { actor.submit(c -> { c.setPendingDocKind(kind); return null; }); }

    @Override public String pendingDocTitle() { return actor.submit(VoidCoreSession::pendingDocTitle); }
    @Override public void setPendingDocTitle(String title) { actor.submit(c -> { c.setPendingDocTitle(title); return null; }); }

    @Override public java.util.List<String> pendingDocTags() { return actor.submit(VoidCoreSession::pendingDocTags); }
    @Override public void setPendingDocTags(java.util.List<String> tags) { actor.submit(c -> { c.setPendingDocTags(tags); return null; }); }

    @Override public String pendingDocBody() { return actor.submit(VoidCoreSession::pendingDocBody); }
    @Override public void setPendingDocBody(String body) { actor.submit(c -> { c.setPendingDocBody(body); return null; }); }

    @Override public String pendingFrontmatterKey() { return actor.submit(VoidCoreSession::pendingFrontmatterKey); }
    @Override public void setPendingFrontmatterKey(String key) { actor.submit(c -> { c.setPendingFrontmatterKey(key); return null; }); }

    @Override public String pendingPollQuestion() { return actor.submit(VoidCoreSession::pendingPollQuestion); }
    @Override public void setPendingPollQuestion(String q) { actor.submit(c -> { c.setPendingPollQuestion(q); return null; }); }

    @Override public java.util.List<String> pendingPollOptions() { return actor.submit(VoidCoreSession::pendingPollOptions); }
    @Override public void setPendingPollOptions(java.util.List<String> opts) { actor.submit(c -> { c.setPendingPollOptions(opts); return null; }); }

    @Override public Instant previousLastCall() { return actor.submit(VoidCoreSession::previousLastCall); }
    @Override public void setPreviousLastCall(Instant when) { actor.submit(c -> { c.setPreviousLastCall(when); return null; }); }

    // ─── Lifecycle ─────────────────────────────────────────────────────

    @Override public boolean isOpen() { return actor.submit(VoidCoreSession::isOpen); }

    // ─── Outbound (WS writes) — actor-routed; checked-exception unwrap ─

    @Override
    public void send(ServerMessage message) throws IOException {
        sendInReplyTo(message, null);
    }

    @Override
    public void sendInReplyTo(ServerMessage message, String inReplyTo) throws IOException {
        try {
            actor.submit(c -> {
                try {
                    c.sendInReplyTo(message, inReplyTo);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return null;
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    @Override
    public void sendError(String code, String message) {
        actor.submit(c -> { c.sendError(code, message); return null; });
    }

    @Override
    public void sendPing(ByteBuffer payload) throws IOException {
        try {
            actor.submit(c -> {
                try {
                    c.sendPing(payload);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return null;
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    @Override
    public void close(CloseStatus status) {
        actor.submit(c -> { c.close(status); return null; });
    }

    // ─── Heartbeat counter ─────────────────────────────────────────────

    @Override public int incrementPingsSinceLastPong() {
        return actor.submit(VoidCoreSession::incrementPingsSinceLastPong);
    }
    @Override public void resetPingCounter() {
        actor.submit(c -> { c.resetPingCounter(); return null; });
    }
    @Override public void resetPingsSinceLastPong() {
        actor.submit(c -> { c.resetPingsSinceLastPong(); return null; });
    }
    @Override public int pingsSinceLastPong() {
        return actor.submit(VoidCoreSession::pingsSinceLastPong);
    }
}
