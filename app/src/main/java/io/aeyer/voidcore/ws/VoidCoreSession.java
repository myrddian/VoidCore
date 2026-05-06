package io.aeyer.voidcore.ws;

import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;

/**
 * Per-user / per-connection session abstraction. The public surface every
 * consumer (screens, services, dispatchers, the bus) calls — independent of
 * how the state is stored or how concurrency is handled.
 *
 * <p><strong>Architectural shift (post v1.5 ws-investigation cycle):</strong>
 * this used to be a {@code final class} that bundled connection state
 * (WebSocket, sendLock, ping counter) with user state (userId, drafts,
 * navigation breadcrumbs). The conflation caused the bus-loop recursion
 * and pong-vs-heartbeat race we hit. The interface now decouples the
 * <em>contract</em> from the implementation:
 *
 * <ul>
 *   <li>{@code VoidCoreSession} — owns all state. Plain fields (no
 *       {@code volatile}, no locks) because the actor's single thread is
 *       the only thread that reads or writes them.</li>
 *   <li>{@code SessionProxy} — implements this interface. Each method
 *       enqueues a {@code Msg} onto the actor's queue and (for read
 *       methods) blocks waiting for the result.</li>
 *   <li>{@code SessionActor} — wraps a {@code VoidCoreSession} with a
 *       virtual thread + {@code BlockingQueue}. Pulls messages, dispatches
 *       to the core, returns results to the proxy.</li>
 * </ul>
 *
 * <p>Self-calls inside {@code VoidCoreSession} go directly through the
 * core reference, never through the proxy — no risk of deadlocking on
 * its own queue. Bus listener closures enqueue {@code OnBusEvent} messages
 * onto the actor (async fire-and-forget) which structurally rules out the
 * synchronous publish → onEvent → onEnter recursion we just fixed.
 */
public interface VoidCoreSession {

    // ─── Identity ────────────────────────────────────────────────────────

    /**
     * Stable session id. Today this is the underlying WS session id;
     * post-actor refactor it remains stable across WS reconnects (the
     * actor outlives any individual WS).
     */
    String id();

    // ─── User identity (set on auth, cleared on logout) ─────────────────

    Long userId();
    void setUserId(Long userId);

    String handle();
    void setHandle(String handle);

    boolean isSysop();
    void setSysop(boolean sysop);

    String sessionToken();
    void setSessionToken(String sessionToken);

    // ─── Pre-auth and intent state ──────────────────────────────────────

    /** Deep-link intent the client sent at connect (SPEC §4.6); null after consumed. */
    String pendingIntent();
    void setPendingIntent(String pendingIntent);

    /** In-flight new-user form, set during multi-step register prompts; null otherwise. */
    io.aeyer.voidcore.ws.flow.RegisterDraft registerDraft();
    void setRegisterDraft(io.aeyer.voidcore.ws.flow.RegisterDraft d);

    /** Handle the user typed at the login-handle prompt, held while the password prompt is in front of them. */
    String pendingLoginHandle();
    void setPendingLoginHandle(String handle);

    // ─── In-flight composes ─────────────────────────────────────────────

    /** In-flight NetMail compose form. */
    io.aeyer.voidcore.netmail.NetmailDraft netmailDraft();
    void setNetmailDraft(io.aeyer.voidcore.netmail.NetmailDraft d);

    // ─── Navigation breadcrumbs (which thing am I currently viewing) ────

    Long currentNetmailId();
    void setCurrentNetmailId(Long id);

    Long currentBulletinId();
    void setCurrentBulletinId(Long id);

    Long currentReleaseId();
    void setCurrentReleaseId(Long id);

    Long currentDocumentId();
    void setCurrentDocumentId(Long id);

    /** Ticket #93 polls — the poll currently being viewed in {@code POLL_VIEW}. */
    Long currentPollId();
    void setCurrentPollId(Long id);

    /**
     * Which variant of {@code Phase.INFO_VIEW} the user is on:
     * {@code "users"}, {@code "last_callers"}, or {@code "whos_online"}.
     */
    String infoVariant();
    void setInfoVariant(String variant);

    String currentChatRoomSlug();
    void setCurrentChatRoomSlug(String slug);

    String currentDoorId();
    void setCurrentDoorId(String doorId);

    /** Door whose achievement catalogue is currently being viewed under
     *  {@link io.aeyer.voidcore.ws.flow.screen.Phase#ACHIEVEMENTS_DOOR}. */
    String selectedAchievementDoorId();
    void setSelectedAchievementDoorId(String doorId);

    Long selectedBaseId();
    void setSelectedBaseId(Long id);

    Long selectedThreadId();
    void setSelectedThreadId(Long id);

    String pendingThreadSubject();
    void setPendingThreadSubject(String s);

    // ─── Sysop tool state ───────────────────────────────────────────────

    /**
     * Generic "I'm holding an id while walking a sub-flow" slot —
     * sysop screens use this for selected user / file id, plus a few
     * sentinel values (-1L = expecting digit for pin-toggle,
     * -2L = expecting digit for delete).
     */
    Long selectedSysopId();
    void setSelectedSysopId(Long id);

    String selectedSysopSlug();
    void setSelectedSysopSlug(String slug);

    Long selectedSysopResourceId();
    void setSelectedSysopResourceId(Long id);

    String sysopBulletinTitle();
    void setSysopBulletinTitle(String t);

    boolean sysopBulletinPinned();
    void setSysopBulletinPinned(boolean p);

    // ─── Sysop file-new walk drafts ─────────────────────────────────────

    String pendingReleaseFilename();
    void setPendingReleaseFilename(String s);

    String pendingReleaseTitle();
    void setPendingReleaseTitle(String s);

    String pendingReleaseExternalUrl();
    void setPendingReleaseExternalUrl(String s);

    List<String> pendingReleaseNfoLines();
    void setPendingReleaseNfoLines(List<String> lines);

    Short pendingReleaseYear();
    void setPendingReleaseYear(Short y);

    String pendingReleaseArtist();
    void setPendingReleaseArtist(String s);

    String pendingReleaseLabel();
    void setPendingReleaseLabel(String s);

    String pendingReleaseCatalogNumber();
    void setPendingReleaseCatalogNumber(String s);

    String pendingReleaseGenre();
    void setPendingReleaseGenre(String s);

    // ─── Documents faceted-nav state (PR-5) ─────────────────────────────

    /**
     * Serialised {@code DocumentFilter} carrying the user's current
     * facet intersection on the documents-info surface (PR-5,
     * SPEC-documents §4). Empty / null = no facets selected.
     * Canonical {@code key=value&key=value} form so it doubles as a
     * cache key, persists across reconnects (ADR-033 — actor outlives
     * the WS), and round-trips through deep-link intents.
     */
    String docsFilter();
    void setDocsFilter(String s);

    /**
     * 0-indexed page number on {@code DOCS_RESULTS}. Null means page 0
     * (first entry); {@code [J]}/{@code [K]} advance / retreat. Reset
     * on every fresh entry to {@code DOCS_RESULTS} from a picker
     * screen so a new filter starts at page 0.
     */
    Integer docsResultsPage();
    void setDocsResultsPage(Integer page);

    /**
     * Active sort mode on {@code DOCS_RESULTS} (PR-6). Stored as
     * {@code DocumentSort.wireValue()} so it survives reconnect and
     * round-trips through {@code DocumentSort.parse()}. Null → default
     * ({@code RECENT}).
     */
    String docsResultsSort();
    void setDocsResultsSort(String s);

    // ─── New-doc walk drafts (PR-4b) ─────────────────────────────────────

    /**
     * In-flight {@code DocumentKind} for the new-doc walk, stored as
     * the wire-value string. Null between walks. Cleared on commit
     * or cancel.
     */
    String pendingDocKind();
    void setPendingDocKind(String kind);

    /** Title typed in step 2 of the new-doc walk. */
    String pendingDocTitle();
    void setPendingDocTitle(String title);

    /** Tags typed in step 3 (already split + normalised); null = none. */
    java.util.List<String> pendingDocTags();
    void setPendingDocTags(java.util.List<String> tags);

    /** Body typed in step 4 (`\n` already unwrapped). */
    String pendingDocBody();
    void setPendingDocBody(String body);

    /**
     * PR-4c: which frontmatter key the user is currently editing.
     * Set by the frontmatter menu screen on letter-press; consumed
     * by the field-edit screen and cleared on commit / cancel.
     */
    String pendingFrontmatterKey();
    void setPendingFrontmatterKey(String key);

    // ─── New-poll walk drafts (#93) ─────────────────────────────────────

    /**
     * Ticket #93 polls — question typed in step 1 of the new-poll
     * walk. Cleared on commit or cancel.
     */
    String pendingPollQuestion();
    void setPendingPollQuestion(String q);

    /**
     * Ticket #93 polls — options accumulated across step 2 line
     * submits. Each non-blank submit appends; blank line ends the
     * walk and commits if {@code size() >= 2}. Cleared on commit
     * or cancel.
     */
    java.util.List<String> pendingPollOptions();
    void setPendingPollOptions(java.util.List<String> opts);

    /**
     * Ticket #85: the user's {@code last_call_at} value from BEFORE
     * the current login bumped it. Captured by AuthFinaliser on
     * fresh login (not on resume) and consumed by
     * {@code LoginSummaryScreen} to compute "what's new since". Null
     * outside the login-summary window or for users who never
     * called before (fresh registers).
     */
    Instant previousLastCall();
    void setPreviousLastCall(Instant when);

    // ─── Lifecycle metadata ─────────────────────────────────────────────

    Instant connectedAt();

    boolean isOpen();

    // ─── Outbound (WS writes) ───────────────────────────────────────────

    /**
     * Send a typed server message to this session, wrapped in the wire
     * envelope per SPEC §4.2. {@code seq} is 0 and {@code mac} is null on
     * v1 (reserved for v2 per ADR-018). All writes serialised through
     * the implementation's lock / actor queue.
     *
     * <p>Envelope {@code id} is null — appropriate for unsolicited server
     * pushes (region.update, input.prompt, effect.*). For response
     * messages where the spec calls for request/response correlation
     * (auth.ok, auth.err, resume.ok, resume.err), use
     * {@link #sendInReplyTo} so the inbound's id is echoed.
     */
    void send(ServerMessage message) throws IOException;

    /**
     * Send a typed server message as a response to a specific inbound
     * message — echoes {@code inReplyTo} as the envelope's {@code id} per
     * SPEC §4 ("client-generated, used to correlate request/response").
     * Pass {@code null} for unsolicited pushes (equivalent to
     * {@link #send}).
     */
    void sendInReplyTo(ServerMessage message, String inReplyTo) throws IOException;

    /**
     * Send a {@link ServerMessage.ProtocolError}. Convenience for
     * the transport layer + dispatcher; never throws.
     */
    void sendError(String code, String message);

    /**
     * Send a WebSocket protocol-level Ping frame. Goes through the
     * same serialising path as {@link #send} so we never have two
     * concurrent {@code underlying.sendMessage} calls — Jakarta
     * WebSocket {@code RemoteEndpoint.Basic} is not thread-safe across
     * send method calls.
     */
    void sendPing(ByteBuffer payload) throws IOException;

    /** Close the underlying connection with the given status. */
    void close(CloseStatus status);

    // ─── Heartbeat counter (called by HeartbeatScheduler/handlePongMessage) ──

    int incrementPingsSinceLastPong();
    void resetPingCounter();
    void resetPingsSinceLastPong();
    int pingsSinceLastPong();

    // ─── Escape hatch ───────────────────────────────────────────────────

    /**
     * Direct access to the underlying Spring WS session for things only
     * it can answer — handshake headers, remote address, etc. Avoid
     * using this for anything the interface exposes; prefer the named
     * accessors above so the actor refactor can intercept calls cleanly.
     */
    WebSocketSession underlying();
}
