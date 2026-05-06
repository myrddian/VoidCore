package io.aeyer.voidcore.ws.session;

import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.BbsWebSocketHandler;
import io.aeyer.voidcore.ws.protocol.ProtocolTypeRegistry;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Concrete {@link VoidCoreSession} implementation. Holds all per-user state
 * directly (no {@code volatile}, no locks) — the design contract is that
 * a single thread (the actor's worker thread, in the post-refactor world)
 * is the only thread that ever reads or writes these fields. Today the
 * state is still under the legacy "many threads can call this" regime,
 * which is why {@link #send} and {@link #sendPing} guard the underlying
 * write with {@link #sendLock}. Once {@code SessionActor} wraps this
 * class, the lock becomes structurally redundant (one queue, one thread,
 * one writer at a time) but stays for belt-and-suspenders correctness
 * during the migration.
 *
 * <p>Self-calls (one method on this class calling another) go directly
 * via {@code this.foo(...)}, never through an actor proxy — no risk of
 * the actor deadlocking on its own queue.
 *
 * <p>The {@link VoidCoreSession} interface is the public contract; consumers
 * (screens, services, the bus) should depend on the interface only.
 * {@code SessionCore} construction stays inside this package + the
 * registry for now; once {@code SessionProxy} lands, public callers
 * receive proxy instances that forward through the actor.
 */
public final class SessionCore implements VoidCoreSession {

    private static final Logger log = LoggerFactory.getLogger(SessionCore.class);

    /**
     * Stable session id, captured at construction from the initial
     * WebSocket's id. Survives WS reconnects (per ADR-033 the actor
     * outlives the WS). Used as the actor's identity in logs, in the
     * registry's primary index, and as the {@link VoidCoreSession#id()}
     * value every screen sees.
     */
    private final String id;
    private final ObjectMapper json;
    private final ProtocolTypeRegistry types;
    private final Instant connectedAt;
    private final AtomicInteger pingsSinceLastPong = new AtomicInteger(0);

    /**
     * Currently-attached WebSocket. Mutable to support the
     * AttachWs / DetachWs flow (ADR-033 phase 1) where a reconnecting
     * client swaps a fresh WS into an existing actor. Volatile because
     * the swap is initiated from the registry on a non-actor thread,
     * but reads only ever happen on the actor's worker — the volatile
     * is belt-and-suspenders for the publish from the registry side.
     */
    private volatile WebSocketSession underlying;

    /**
     * Retained from the pre-actor world as a defensive guard. With the
     * actor in place every send is already serialised through the
     * worker thread, so this is structurally redundant — but keeping
     * it costs nothing and protects against any code path that ends
     * up calling {@link #send} from a non-actor thread (test fakes,
     * future maintenance regressions).
     */
    private final Object sendLock = new Object();

    private volatile Long userId;
    private volatile String handle;
    private volatile boolean sysop;
    private volatile String sessionToken;
    private volatile String pendingIntent;
    private volatile io.aeyer.voidcore.ws.flow.RegisterDraft registerDraft;
    private volatile io.aeyer.voidcore.netmail.NetmailDraft netmailDraft;
    private volatile Long currentNetmailId;
    private volatile Long currentBulletinId;
    private volatile Long currentReleaseId;
    private volatile Long currentDocumentId;
    private volatile Long currentPollId;
    private volatile String pendingLoginHandle;
    private volatile String infoVariant;
    private volatile String currentChatRoomSlug;
    private volatile String currentDoorId;
    private volatile String selectedAchievementDoorId;
    private volatile Long selectedSysopId;
    private volatile String selectedSysopSlug;
    private volatile Long selectedSysopResourceId;
    private volatile String sysopBulletinTitle;
    private volatile boolean sysopBulletinPinned;
    private volatile Long selectedBaseId;
    private volatile Long selectedThreadId;
    private volatile String pendingThreadSubject;
    private volatile String pendingReleaseFilename;
    private volatile String pendingReleaseTitle;
    private volatile String pendingReleaseExternalUrl;
    private volatile List<String> pendingReleaseNfoLines;
    private volatile Short pendingReleaseYear;
    private volatile String pendingReleaseArtist;
    private volatile String pendingReleaseLabel;
    private volatile String pendingReleaseCatalogNumber;
    private volatile String pendingReleaseGenre;
    private volatile String docsFilter;
    private volatile Integer docsResultsPage;
    private volatile String docsResultsSort;
    private volatile String pendingDocKind;
    private volatile String pendingDocTitle;
    private volatile java.util.List<String> pendingDocTags;
    private volatile String pendingDocBody;
    private volatile String pendingFrontmatterKey;
    private volatile String pendingPollQuestion;
    private volatile java.util.List<String> pendingPollOptions;
    private volatile Instant previousLastCall;

    public SessionCore(WebSocketSession underlying, ObjectMapper json, ProtocolTypeRegistry types) {
        this.underlying = Objects.requireNonNull(underlying);
        this.json = Objects.requireNonNull(json);
        this.types = Objects.requireNonNull(types);
        this.connectedAt = Instant.now();
        // Capture the initial WS id as the actor's stable identity. The
        // underlying WS may later be swapped (ADR-033), but the id stays
        // anchored to the original — every log line, every registry
        // entry, every screen reference uses this same value for the
        // lifetime of the actor.
        this.id = underlying.getId();
    }

    @Override public String id() { return id; }

    /**
     * Returns the id of the currently-attached WebSocket — different
     * from {@link #id()} once a reconnect has swapped the underlying.
     * Useful for trace logs that want to correlate a frame back to the
     * Tomcat thread that received it.
     */
    public String currentWsId() { return underlying.getId(); }

    /**
     * Swap the underlying WebSocket — used by the registry on
     * AttachWs (ADR-033). The caller is responsible for ensuring the
     * old WS is closed before this is invoked; this method does not
     * close anything itself. The send-counter is reset so a fresh
     * heartbeat cycle starts on the new WS.
     */
    public void swapUnderlying(WebSocketSession next) {
        Objects.requireNonNull(next);
        WebSocketSession prev = this.underlying;
        this.underlying = next;
        this.pingsSinceLastPong.set(0);
        log.info("[ws-trace] core-swap-underlying id={} prev-ws={} next-ws={}",
                id, prev == null ? null : prev.getId(), next.getId());
    }

    @Override public Long userId() { return userId; }
    @Override public void setUserId(Long userId) { this.userId = userId; }

    @Override public String handle() { return handle; }
    @Override public void setHandle(String handle) { this.handle = handle; }

    @Override public boolean isSysop() { return sysop; }
    @Override public void setSysop(boolean sysop) { this.sysop = sysop; }

    @Override public String sessionToken() { return sessionToken; }
    @Override public void setSessionToken(String sessionToken) { this.sessionToken = sessionToken; }

    @Override public String pendingIntent() { return pendingIntent; }
    @Override public void setPendingIntent(String pendingIntent) { this.pendingIntent = pendingIntent; }

    @Override public io.aeyer.voidcore.ws.flow.RegisterDraft registerDraft() { return registerDraft; }
    @Override public void setRegisterDraft(io.aeyer.voidcore.ws.flow.RegisterDraft d) { this.registerDraft = d; }

    @Override public io.aeyer.voidcore.netmail.NetmailDraft netmailDraft() { return netmailDraft; }
    @Override public void setNetmailDraft(io.aeyer.voidcore.netmail.NetmailDraft d) { this.netmailDraft = d; }

    @Override public Long currentNetmailId() { return currentNetmailId; }
    @Override public void setCurrentNetmailId(Long id) { this.currentNetmailId = id; }

    @Override public Long currentBulletinId() { return currentBulletinId; }
    @Override public void setCurrentBulletinId(Long id) { this.currentBulletinId = id; }

    @Override public Long currentReleaseId() { return currentReleaseId; }
    @Override public void setCurrentReleaseId(Long id) { this.currentReleaseId = id; }

    @Override public Long currentDocumentId() { return currentDocumentId; }
    @Override public void setCurrentDocumentId(Long id) { this.currentDocumentId = id; }

    @Override public Long currentPollId() { return currentPollId; }
    @Override public void setCurrentPollId(Long id) { this.currentPollId = id; }

    @Override public String pendingLoginHandle() { return pendingLoginHandle; }
    @Override public void setPendingLoginHandle(String handle) { this.pendingLoginHandle = handle; }

    @Override public String infoVariant() { return infoVariant; }
    @Override public void setInfoVariant(String variant) { this.infoVariant = variant; }

    @Override public String currentChatRoomSlug() { return currentChatRoomSlug; }
    @Override public void setCurrentChatRoomSlug(String slug) { this.currentChatRoomSlug = slug; }
    @Override public String currentDoorId() { return currentDoorId; }
    @Override public void setCurrentDoorId(String doorId) { this.currentDoorId = doorId; }
    @Override public String selectedAchievementDoorId() { return selectedAchievementDoorId; }
    @Override public void setSelectedAchievementDoorId(String doorId) { this.selectedAchievementDoorId = doorId; }

    @Override public Long selectedSysopId() { return selectedSysopId; }
    @Override public void setSelectedSysopId(Long id) { this.selectedSysopId = id; }

    @Override public String selectedSysopSlug() { return selectedSysopSlug; }
    @Override public void setSelectedSysopSlug(String slug) { this.selectedSysopSlug = slug; }

    @Override public Long selectedSysopResourceId() { return selectedSysopResourceId; }
    @Override public void setSelectedSysopResourceId(Long id) { this.selectedSysopResourceId = id; }

    @Override public String sysopBulletinTitle() { return sysopBulletinTitle; }
    @Override public void setSysopBulletinTitle(String t) { this.sysopBulletinTitle = t; }

    @Override public boolean sysopBulletinPinned() { return sysopBulletinPinned; }
    @Override public void setSysopBulletinPinned(boolean p) { this.sysopBulletinPinned = p; }

    @Override public Long selectedBaseId() { return selectedBaseId; }
    @Override public void setSelectedBaseId(Long id) { this.selectedBaseId = id; }

    @Override public Long selectedThreadId() { return selectedThreadId; }
    @Override public void setSelectedThreadId(Long id) { this.selectedThreadId = id; }

    @Override public String pendingThreadSubject() { return pendingThreadSubject; }
    @Override public void setPendingThreadSubject(String s) { this.pendingThreadSubject = s; }

    @Override public String pendingReleaseFilename() { return pendingReleaseFilename; }
    @Override public void setPendingReleaseFilename(String s) { this.pendingReleaseFilename = s; }

    @Override public String pendingReleaseTitle() { return pendingReleaseTitle; }
    @Override public void setPendingReleaseTitle(String s) { this.pendingReleaseTitle = s; }

    @Override public String pendingReleaseExternalUrl() { return pendingReleaseExternalUrl; }
    @Override public void setPendingReleaseExternalUrl(String s) { this.pendingReleaseExternalUrl = s; }

    @Override public List<String> pendingReleaseNfoLines() { return pendingReleaseNfoLines; }
    @Override public void setPendingReleaseNfoLines(List<String> lines) { this.pendingReleaseNfoLines = lines; }

    @Override public Short pendingReleaseYear() { return pendingReleaseYear; }
    @Override public void setPendingReleaseYear(Short y) { this.pendingReleaseYear = y; }

    @Override public String pendingReleaseArtist() { return pendingReleaseArtist; }
    @Override public void setPendingReleaseArtist(String s) { this.pendingReleaseArtist = s; }

    @Override public String pendingReleaseLabel() { return pendingReleaseLabel; }
    @Override public void setPendingReleaseLabel(String s) { this.pendingReleaseLabel = s; }

    @Override public String pendingReleaseCatalogNumber() { return pendingReleaseCatalogNumber; }
    @Override public void setPendingReleaseCatalogNumber(String s) { this.pendingReleaseCatalogNumber = s; }

    @Override public String pendingReleaseGenre() { return pendingReleaseGenre; }
    @Override public void setPendingReleaseGenre(String s) { this.pendingReleaseGenre = s; }

    @Override public String docsFilter() { return docsFilter; }
    @Override public void setDocsFilter(String s) { this.docsFilter = s; }

    @Override public Integer docsResultsPage() { return docsResultsPage; }
    @Override public void setDocsResultsPage(Integer page) { this.docsResultsPage = page; }

    @Override public String docsResultsSort() { return docsResultsSort; }
    @Override public void setDocsResultsSort(String s) { this.docsResultsSort = s; }

    @Override public String pendingDocKind() { return pendingDocKind; }
    @Override public void setPendingDocKind(String kind) { this.pendingDocKind = kind; }

    @Override public String pendingDocTitle() { return pendingDocTitle; }
    @Override public void setPendingDocTitle(String title) { this.pendingDocTitle = title; }

    @Override public java.util.List<String> pendingDocTags() { return pendingDocTags; }
    @Override public void setPendingDocTags(java.util.List<String> tags) { this.pendingDocTags = tags; }

    @Override public String pendingDocBody() { return pendingDocBody; }
    @Override public void setPendingDocBody(String body) { this.pendingDocBody = body; }

    @Override public String pendingFrontmatterKey() { return pendingFrontmatterKey; }
    @Override public void setPendingFrontmatterKey(String key) { this.pendingFrontmatterKey = key; }

    @Override public String pendingPollQuestion() { return pendingPollQuestion; }
    @Override public void setPendingPollQuestion(String q) { this.pendingPollQuestion = q; }

    @Override public java.util.List<String> pendingPollOptions() { return pendingPollOptions; }
    @Override public void setPendingPollOptions(java.util.List<String> opts) { this.pendingPollOptions = opts; }

    @Override public Instant previousLastCall() { return previousLastCall; }
    @Override public void setPreviousLastCall(Instant when) { this.previousLastCall = when; }

    @Override public Instant connectedAt() { return connectedAt; }

    @Override public boolean isOpen() { return underlying.isOpen(); }

    @Override
    public void send(ServerMessage message) throws IOException {
        sendInReplyTo(message, null);
    }

    @Override
    public void sendInReplyTo(ServerMessage message, String inReplyTo) throws IOException {
        String typeName = types.serverTypeName(message);
        JsonNode payloadNode = json.valueToTree(message);
        ObjectNode envelope = json.createObjectNode();
        if (inReplyTo == null) {
            envelope.putNull("id");
        } else {
            envelope.put("id", inReplyTo);
        }
        envelope.put("type", typeName);
        envelope.put("protocol_version", BbsWebSocketHandler.PROTOCOL_VERSION);
        envelope.put("seq", 0L);
        envelope.putNull("mac");
        envelope.set("payload", payloadNode);
        String wire = json.writeValueAsString(envelope);
        synchronized (sendLock) {
            boolean open = underlying.isOpen();
            log.debug("[ws-trace] outbound id={} type={} bytes={} in-reply-to={} ws-open={}",
                    id(), typeName, wire.length(), inReplyTo, open);
            if (!open) {
                log.warn("[ws-trace] outbound-skip-closed id={} type={}", id(), typeName);
                return;
            }
            try {
                underlying.sendMessage(new TextMessage(wire));
            } catch (IOException e) {
                log.warn("[ws-trace] outbound-fail id={} type={} ex={}",
                        id(), typeName, e.toString());
                throw e;
            }
        }
    }

    @Override
    public void sendError(String code, String message) {
        try {
            send(new ServerMessage.ProtocolError(code, message, null, null));
        } catch (JsonProcessingException e) {
            log.error("session={} failed to serialise error", id(), e);
        } catch (IOException e) {
            log.warn("session={} failed to send error: {}", id(), e.toString());
        }
    }

    @Override
    public void close(CloseStatus status) {
        try {
            if (underlying.isOpen()) underlying.close(status);
        } catch (IOException e) {
            log.warn("session={} close raised: {}", id(), e.toString());
        }
    }

    @Override
    public void sendPing(ByteBuffer payload) throws IOException {
        synchronized (sendLock) {
            if (!underlying.isOpen()) return;
            underlying.sendMessage(new PingMessage(payload));
        }
    }

    @Override public int incrementPingsSinceLastPong() { return pingsSinceLastPong.incrementAndGet(); }
    @Override public void resetPingCounter() { pingsSinceLastPong.set(0); }
    @Override public void resetPingsSinceLastPong() { this.pingsSinceLastPong.set(0); }
    @Override public int pingsSinceLastPong() { return pingsSinceLastPong.get(); }

    @Override public WebSocketSession underlying() { return underlying; }
}
