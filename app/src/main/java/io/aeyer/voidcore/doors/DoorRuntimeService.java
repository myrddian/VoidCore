package io.aeyer.voidcore.doors;

import io.aeyer.voidcore.llm.ChatCompletion;
import io.aeyer.voidcore.llm.ChatMessage;
import io.aeyer.voidcore.llm.LlmGatewayException;
import io.aeyer.voidcore.llm.LlmGatewayService;
import io.aeyer.voidcore.llm.TokenUsage;
import io.aeyer.voidcore.workers.WorkerPools;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.Envelope;
import io.aeyer.voidcore.ws.SessionRegistry;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.bus.MessageBus;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnBean(DoorStateRepository.class)
public class DoorRuntimeService {

    public static final String PROTOCOL_VERSION = "voidcore-door-v1";
    public static final String CATALOG_TOPIC = "doors";
    private static final Logger log = LoggerFactory.getLogger(DoorRuntimeService.class);

    private final ObjectMapper json;
    private final SessionRegistry sessions;
    private final MessageBus bus;
    private final DoorStateRepository state;
    private final LlmGatewayService llm;
    private final WorkerPools workers;
    private final io.aeyer.voidcore.social.AchievementRepository achievements;

    private final Map<String, ConnectedDoor> doorsById = new ConcurrentHashMap<>();
    private final Map<String, String> wsIdToDoorId = new ConcurrentHashMap<>();
    private final Map<String, Attachment> attachmentsByBbsSessionId = new ConcurrentHashMap<>();
    private final Map<String, String> bbsSessionIdByDoorSessionKey = new ConcurrentHashMap<>();

    public DoorRuntimeService(ObjectMapper json,
                              SessionRegistry sessions,
                              MessageBus bus,
                              DoorStateRepository state,
                              LlmGatewayService llm,
                              WorkerPools workers,
                              io.aeyer.voidcore.social.AchievementRepository achievements) {
        this.json = json;
        this.sessions = sessions;
        this.bus = bus;
        this.state = state;
        this.llm = llm;
        this.workers = workers;
        this.achievements = achievements;
        this.sessions.addTerminationListener(proxy -> leaveByBbsSessionId(proxy.id(), "session_terminated"));
    }

    public List<DoorSummary> listConnectedDoors() {
        return doorsById.values().stream()
                .sorted(Comparator.comparing(door -> door.manifest.name(), String.CASE_INSENSITIVE_ORDER))
                .map(door -> new DoorSummary(
                        door.manifest.doorId(),
                        door.manifest.name(),
                        door.manifest.description(),
                        door.attachedSessionIds.size()))
                .toList();
    }

    public DoorSessionState stateFor(VoidCoreSession session) {
        Attachment attachment = attachmentsByBbsSessionId.get(session.id());
        return attachment == null ? null : attachment.state;
    }

    public boolean ensureAttached(VoidCoreSession session, String doorId, String theme) {
        Attachment existing = attachmentsByBbsSessionId.get(session.id());
        if (existing != null && existing.doorId.equals(doorId)
                && existing.state.status() != DoorSessionState.Status.DETACHED
                && existing.state.status() != DoorSessionState.Status.ERROR) {
            return true;
        }
        ConnectedDoor door = doorsById.get(doorId);
        if (door == null) {
            DoorSessionState offline = new DoorSessionState(
                    doorId, doorId, null, DoorSessionState.Status.ERROR,
                    placeholderRows("Door offline", "No connected sidecar is advertising " + doorId + "."),
                    DoorPromptState.none(),
                    "door offline",
                    1);
            attachmentsByBbsSessionId.put(session.id(), new Attachment(session.id(), doorId, null, offline));
            bus.notify(topicFor(session.id()));
            return false;
        }
        if (!door.manifest.supportsMode("normal")) {
            DoorSessionState unsupported = new DoorSessionState(
                    door.manifest.doorId(), door.manifest.name(), null, DoorSessionState.Status.ERROR,
                    placeholderRows(door.manifest.name(), "Door does not support normal-mode attach."),
                    DoorPromptState.none(),
                    "mode not supported",
                    1);
            attachmentsByBbsSessionId.put(session.id(), new Attachment(session.id(), doorId, null, unsupported));
            bus.notify(topicFor(session.id()));
            return false;
        }
        if (door.attachedSessionIds.size() >= door.manifest.maxConcurrentSessions()) {
            DoorSessionState busy = new DoorSessionState(
                    door.manifest.doorId(), door.manifest.name(), null, DoorSessionState.Status.ERROR,
                    placeholderRows(door.manifest.name(), "Door is busy right now. Try again shortly."),
                    DoorPromptState.none(),
                    "door busy",
                    1);
            attachmentsByBbsSessionId.put(session.id(), new Attachment(session.id(), doorId, null, busy));
            bus.notify(topicFor(session.id()));
            return false;
        }

        String doorSessionId = UUID.randomUUID().toString();
        DoorSessionState connecting = DoorSessionState.connecting(
                door.manifest.doorId(), door.manifest.name(), doorSessionId,
                placeholderRows(door.manifest.name(), "Connecting to sidecar..."));
        Attachment attachment = new Attachment(session.id(), doorId, doorSessionId, connecting);
        attachmentsByBbsSessionId.put(session.id(), attachment);
        bbsSessionIdByDoorSessionKey.put(doorKey(doorId, doorSessionId), session.id());
        door.attachedSessionIds.add(doorSessionId);

        ObjectNode payload = json.createObjectNode();
        payload.put("session_id", doorSessionId);
        if (session.userId() != null && door.manifest.capabilities().userIdVisible()) {
            payload.put("user_id", session.userId());
        }
        if (door.manifest.capabilities().userHandleVisible()) {
            payload.put("handle", blankTo(session.handle(), "visitor"));
        }
        payload.put("role", session.isSysop() ? "SYSOP" : "USER");
        payload.put("mode", "normal");
        if (existing != null) payload.put("reason", "reconnect");
        ObjectNode viewport = payload.putObject("viewport");
        viewport.put("cols", 80);
        viewport.put("rows", 40);
        ObjectNode preferences = payload.putObject("preferences");
        preferences.put("theme", blankTo(theme, "phosphor"));
        send(door, "attach", payload);
        return true;
    }

    public void sendInputKey(VoidCoreSession session, String key) {
        Attachment attachment = attachmentsByBbsSessionId.get(session.id());
        if (attachment == null || attachment.doorSessionId == null) return;
        ConnectedDoor door = doorsById.get(attachment.doorId);
        if (door == null) return;
        log.info("door-input-key bbsSessionId={} doorId={} doorSessionId={} key={}",
                session.id(), attachment.doorId, attachment.doorSessionId, key);
        ObjectNode payload = json.createObjectNode();
        payload.put("session_id", attachment.doorSessionId);
        payload.put("key", key);
        payload.set("modifiers", json.createArrayNode());
        send(door, "input.key", payload);
    }

    public void sendInputLine(VoidCoreSession session, String text) {
        Attachment attachment = attachmentsByBbsSessionId.get(session.id());
        if (attachment == null || attachment.doorSessionId == null) return;
        ConnectedDoor door = doorsById.get(attachment.doorId);
        if (door == null) return;
        ObjectNode payload = json.createObjectNode();
        payload.put("session_id", attachment.doorSessionId);
        payload.put("text", text == null ? "" : text);
        send(door, "input.line", payload);
    }

    public void leave(VoidCoreSession session, String reason) {
        Attachment attachment = attachmentsByBbsSessionId.remove(session.id());
        if (attachment == null) return;
        sendDetach(attachment, reason);
        unlinkAttachment(attachment);
    }

    public void clearState(VoidCoreSession session) {
        Attachment attachment = attachmentsByBbsSessionId.remove(session.id());
        if (attachment != null) unlinkAttachment(attachment);
    }

    public String topicFor(String bbsSessionId) {
        return "door-session:" + bbsSessionId;
    }

    @Scheduled(fixedDelayString = "${voidcore.doors.time-tick-seconds:5}", timeUnit = TimeUnit.SECONDS)
    public void sendTimeTicks() {
        long unixTimeSec = Instant.now().getEpochSecond();
        for (Attachment attachment : attachmentsByBbsSessionId.values()) {
            if (attachment.doorSessionId == null || attachment.doorSessionId.isBlank()) continue;
            ConnectedDoor door = doorsById.get(attachment.doorId);
            if (door == null) continue;
            ObjectNode payload = json.createObjectNode();
            payload.put("session_id", attachment.doorSessionId);
            payload.put("unix_time_sec", unixTimeSec);
            send(door, "time.tick", payload);
        }
    }

    public boolean handleConnect(WebSocketSession session) {
        return true;
    }

    public void handleDisconnect(WebSocketSession session) {
        String doorId = wsIdToDoorId.remove(session.getId());
        if (doorId == null) return;
        ConnectedDoor door = doorsById.get(doorId);
        if (door == null) return;
        if (!door.session.getId().equals(session.getId())) return;
        doorsById.remove(doorId, door);
        for (String doorSessionId : door.attachedSessionIds) {
            String bbsSessionId = bbsSessionIdByDoorSessionKey.remove(doorKey(doorId, doorSessionId));
            if (bbsSessionId == null) continue;
            Attachment attachment = attachmentsByBbsSessionId.get(bbsSessionId);
            if (attachment == null) continue;
            DoorSessionState failed = new DoorSessionState(
                    attachment.state.doorId(),
                    attachment.state.doorName(),
                    attachment.state.doorSessionId(),
                    DoorSessionState.Status.ERROR,
                    placeholderRows(attachment.state.doorName(), "Door disconnected."),
                    DoorPromptState.none(),
                    "door disconnected",
                    attachment.state.version() + 1);
            attachmentsByBbsSessionId.put(bbsSessionId,
                    new Attachment(attachment.bbsSessionId, attachment.doorId, attachment.doorSessionId, failed));
            bus.notify(topicFor(bbsSessionId));
        }
        bus.notify(CATALOG_TOPIC);
    }

    public void handleEnvelope(WebSocketSession session, Envelope envelope) throws IOException {
        if (!PROTOCOL_VERSION.equals(envelope.protocol_version())) {
            if ("hello".equals(envelope.type())) {
                ObjectNode payload = json.createObjectNode();
                payload.put("code", "MANIFEST_INCOMPATIBLE");
                payload.put("reason", "protocol_version_mismatch");
                send(session, "manifest_rejected", payload, envelope.id());
                closeQuietly(session, CloseStatus.PROTOCOL_ERROR.withReason("protocol_version_mismatch"));
                return;
            }
            throw new IOException("protocol mismatch");
        }
        switch (envelope.type()) {
            case "hello" -> handleHello(session, envelope.id(), envelope.payload());
            case "paint" -> handlePaint(session, envelope.payload());
            case "prompt" -> handlePrompt(session, envelope.payload());
            case "notify" -> handleNotify(session, envelope.payload());
            case "effect" -> handleEffect(session, envelope.payload());
            case "llm.chat" -> handleLlmChat(session, envelope.id(), envelope.payload());
            case "llm.stream" -> handleLlmStream(session, envelope.id(), envelope.payload());
            case "storage.get" -> handleStorageGet(session, envelope.id(), envelope.payload());
            case "storage.put" -> handleStoragePut(session, envelope.id(), envelope.payload());
            case "storage.del" -> handleStorageDelete(session, envelope.id(), envelope.payload());
            case "storage.scan" -> handleStorageScan(session, envelope.id(), envelope.payload());
            case "achievement.unlock" -> handleAchievementUnlock(session, envelope.payload());
            case "detach", "reject_attach" -> handleDetach(session, envelope.type(), envelope.payload());
            case "goodbye" -> handleGoodbye(session);
            case "ready" -> handleReady(session, envelope.payload());
            default -> {
                // MVP: ignore unsupported protocol messages until KV and
                // viewport/control extensions land.
            }
        }
    }

    private void handleHello(WebSocketSession session, String requestId, JsonNode payload) {
        String doorId = payload.path("door_id").asText("");
        JsonNode manifestNode = payload.path("manifest");
        DoorManifest manifest = new DoorManifest(
                blankTo(manifestNode.path("door_id").asText(doorId), doorId),
                blankTo(manifestNode.path("name").asText("Unnamed door"), "Unnamed door"),
                blankTo(manifestNode.path("version").asText("0.0.0"), "0.0.0"),
                readAuthors(manifestNode.path("authors")),
                blankTo(manifestNode.path("description").asText("No description."), "No description."),
                readModes(manifestNode.path("modes_supported")),
                blankTo(manifestNode.path("default_mode").asText("normal"), "normal"),
                readCapabilities(manifestNode.path("capabilities")),
                manifestNode.path("max_concurrent_sessions").isIntegralNumber()
                        ? manifestNode.path("max_concurrent_sessions").asInt()
                        : 64);
        if (!manifest.supportsMode("normal")) {
            ObjectNode rejected = json.createObjectNode();
            rejected.put("code", "MANIFEST_INCOMPATIBLE");
            rejected.put("reason", "normal mode is required in v1");
            send(session, "manifest_rejected", rejected, requestId);
            closeQuietly(session, CloseStatus.POLICY_VIOLATION.withReason("normal mode required"));
            return;
        }
        ConnectedDoor door = new ConnectedDoor(session, manifest,
                llm.isEnabled() && manifest.capabilities().llm());
        ConnectedDoor previous = doorsById.put(manifest.doorId(), door);
        wsIdToDoorId.put(session.getId(), manifest.doorId());
        if (previous != null && !previous.session.getId().equals(session.getId())) {
            closeQuietly(previous.session, CloseStatus.SERVICE_RESTARTED.withReason("door re-registered"));
        }

        ObjectNode payloadOut = json.createObjectNode();
        payloadOut.put("protocol_version", PROTOCOL_VERSION);
        payloadOut.put("door_session_token", UUID.randomUUID().toString());
        ArrayNode caps = payloadOut.putArray("capabilities_granted");
        if (manifest.capabilities().storageKv()) caps.add("storage_kv");
        if (door.llmGranted) caps.add("llm");
        if (manifest.capabilities().notifications()) caps.add("notifications");
        if (manifest.capabilities().multiSession()) caps.add("multi_session");
        if (manifest.capabilities().interSessionMessages()) caps.add("inter_session_messages");
        if (manifest.capabilities().userHandleVisible()) caps.add("user_handle_visible");
        if (manifest.capabilities().userIdVisible()) caps.add("user_id_visible");
        payloadOut.put("kv_quota_bytes", 1048576);
        payloadOut.put("max_concurrent_sessions_granted", manifest.maxConcurrentSessions());
        send(door, "welcome", payloadOut, requestId);
        bus.notify(CATALOG_TOPIC);
    }

    private void handleReady(WebSocketSession session, JsonNode payload) {
        updateState(session, payload, attachment -> new DoorSessionState(
                attachment.state.doorId(),
                attachment.state.doorName(),
                attachment.state.doorSessionId(),
                DoorSessionState.Status.ACTIVE,
                attachment.state.rows(),
                attachment.state.prompt(),
                attachment.state.notice(),
                attachment.state.version() + 1), false);
    }

    private void handlePaint(WebSocketSession session, JsonNode payload) throws IOException {
        List<ServerMessage.Row> rows = readRows(payload.path("rows"));
        updateState(session, payload, attachment -> new DoorSessionState(
                attachment.state.doorId(),
                attachment.state.doorName(),
                attachment.state.doorSessionId(),
                DoorSessionState.Status.ACTIVE,
                rows,
                attachment.state.prompt(),
                attachment.state.notice(),
                attachment.state.version() + 1), true);
    }

    private void handlePrompt(WebSocketSession session, JsonNode payload) {
        DoorPromptState prompt = new DoorPromptState(
                blankTo(payload.path("mode").asText("none"), "none"),
                blankToNull(payload.path("label").asText(null)),
                payload.path("max_length").isIntegralNumber() ? payload.path("max_length").asInt() : null,
                blankToNull(payload.path("valid_keys").asText(null)));
        updateState(session, payload, attachment -> new DoorSessionState(
                attachment.state.doorId(),
                attachment.state.doorName(),
                attachment.state.doorSessionId(),
                attachment.state.status(),
                attachment.state.rows(),
                prompt,
                attachment.state.notice(),
                attachment.state.version() + 1), true);
    }

    /**
     * Door-sourced achievement unlock. The door declares its catalogue
     * inline on every send (id + title + flavor + points + category); the
     * BBS upserts the canonical row by namespaced slug (so door slugs
     * cannot collide with BBS-native ones) and awards to the connected
     * user. Idempotent on re-send via the {@code user_achievements}
     * composite PK; safe to call repeatedly.
     *
     * <p>Note: the BBS achievements schema stores {@code slug/name/description}.
     * The door's {@code points} and {@code category} fields are accepted and
     * logged but not yet persisted server-side — they live in the door's
     * local profile state for now. A future schema bump can lift them into
     * the {@code achievements} table without changing the wire protocol.
     */
    private void handleAchievementUnlock(WebSocketSession session, JsonNode payload) {
        String wsId = session.getId();
        String doorId = wsIdToDoorId.get(wsId);
        if (doorId == null) {
            log.debug("achievement.unlock from unrecognised door connection ws={}", wsId);
            return;
        }
        String envelopeDoorId = blankTo(payload.path("door_id").asText(""), doorId);
        if (!doorId.equals(envelopeDoorId)) {
            log.warn("achievement.unlock door_id mismatch: connection={} envelope={}", doorId, envelopeDoorId);
        }
        String achievementId = blankTo(payload.path("achievement_id").asText(""), "");
        String title = blankTo(payload.path("title").asText(""), achievementId);
        String description = blankTo(payload.path("flavor").asText(""), title);
        long points = payload.path("points").asLong(0L);
        String category = blankTo(payload.path("category").asText(""), "");
        if (achievementId.isBlank()) {
            log.warn("achievement.unlock missing achievement_id from door={}", doorId);
            return;
        }
        String bbsSessionId = resolveBbsSessionId(session, payload);
        if (bbsSessionId == null) {
            log.debug("achievement.unlock no attached bbs session: door={} achievement={}",
                    doorId, achievementId);
            return;
        }
        VoidCoreSession bbs = sessions.get(bbsSessionId);
        if (bbs == null || bbs.userId() == null) {
            log.debug("achievement.unlock bbs session has no userId: door={} achievement={}",
                    doorId, achievementId);
            return;
        }
        // Namespace door slugs so they cannot collide with BBS-native slugs.
        String slug = "door:" + doorId + ":" + achievementId;
        int safePoints = (int) Math.max(0L, Math.min(10_000L, points));
        try {
            io.aeyer.voidcore.social.AchievementRepository.Achievement def =
                    achievements.upsertBySlug(slug, title, description, safePoints, category, "door");
            boolean fresh = achievements.award(bbs.userId(), def.id());
            if (fresh) {
                log.info("achievement.unlock fresh user={} door={} slug={} title=\"{}\" points={} category={}",
                        bbs.userId(), doorId, slug, title, safePoints, blankTo(category, "(none)"));
            } else {
                log.debug("achievement.unlock already-held user={} slug={}", bbs.userId(), slug);
            }
        } catch (RuntimeException e) {
            log.warn("achievement.unlock failed user={} slug={}: {}",
                    bbs.userId(), slug, e.getMessage());
        }
    }

    private void handleNotify(WebSocketSession session, JsonNode payload) {
        String bbsSessionId = resolveBbsSessionId(session, payload);
        if (bbsSessionId == null) return;
        VoidCoreSession bbs = sessions.get(bbsSessionId);
        if (bbs == null) return;
        sendQuiet(bbs, Frames.notify("notifications",
                blankTo(payload.path("text").asText("door event"), "door event"),
                blankTo(payload.path("level").asText("info"), "info"),
                payload.path("duration_ms").asLong(2500L)));
    }

    private void handleEffect(WebSocketSession session, JsonNode payload) {
        String bbsSessionId = resolveBbsSessionId(session, payload);
        if (bbsSessionId == null) return;
        VoidCoreSession bbs = sessions.get(bbsSessionId);
        if (bbs == null) return;
        String kind = blankTo(payload.path("kind").asText(""), "");
        JsonNode params = payload.path("params");
        ServerMessage effect = switch (kind) {
            case "open_url", "effect.open_url" ->
                    new ServerMessage.EffectOpenUrl(blankTo(params.path("url").asText(""), ""));
            case "play_sound", "effect.play_sound" ->
                    new ServerMessage.EffectPlaySound(blankTo(params.path("name").asText(""), ""));
            case "set_title", "effect.set_title" ->
                    new ServerMessage.EffectSetTitle(blankTo(params.path("title").asText(""), ""));
            case "copy_clipboard", "effect.copy_clipboard" ->
                    new ServerMessage.EffectCopyClipboard(blankTo(params.path("text").asText(""), ""));
            case "set_theme", "effect.set_theme" ->
                    new ServerMessage.EffectSetTheme(blankTo(params.path("name").asText(""), ""));
            default -> null;
        };
        if (effect != null) sendQuiet(bbs, effect);
    }

    private void handleLlmChat(WebSocketSession session, String requestId, JsonNode payload) {
        DoorRequestContext req = resolveRequest(session, payload);
        if (req == null) return;
        if (!req.door().llmGranted) {
            log.info("door-llm-chat-denied doorId={} doorSessionId={} requestId={} reason=not-granted",
                    req.door().manifest.doorId(), req.doorSessionId(), requestId);
            sendLlmError(req.door(), requestId, req.doorSessionId(), "LLM_NOT_GRANTED",
                    "door is not allowed to use the BBS LLM gateway");
            return;
        }
        List<ChatMessage> messages = readChatMessages(payload);
        double temperature = payload.path("temperature").isNumber() ? payload.path("temperature").asDouble() : 0.7d;
        log.info("door-llm-chat-start doorId={} doorSessionId={} requestId={} messages={} temperature={}",
                req.door().manifest.doorId(), req.doorSessionId(), requestId, messages.size(), temperature);
        workers.llmPool().submit(() -> {
            try {
                ChatCompletion completion = llm.complete(messages, temperature);
                log.info("door-llm-chat-success doorId={} doorSessionId={} requestId={} finishReason={} usage={}",
                        req.door().manifest.doorId(), req.doorSessionId(), requestId,
                        completion.finishReason(), completion.usage());
                sendLlmResult(req.door(), requestId, req.doorSessionId(), completion);
            } catch (LlmGatewayException e) {
                log.warn("door-llm-chat-failure doorId={} doorSessionId={} requestId={} reason={}",
                        req.door().manifest.doorId(), req.doorSessionId(), requestId, e.getMessage());
                sendLlmError(req.door(), requestId, req.doorSessionId(), "LLM_GATEWAY_ERROR", e.getMessage());
            }
        });
    }

    private void handleLlmStream(WebSocketSession session, String requestId, JsonNode payload) {
        DoorRequestContext req = resolveRequest(session, payload);
        if (req == null) return;
        if (!req.door().llmGranted) {
            log.info("door-llm-stream-denied doorId={} doorSessionId={} requestId={} reason=not-granted",
                    req.door().manifest.doorId(), req.doorSessionId(), requestId);
            sendLlmError(req.door(), requestId, req.doorSessionId(), "LLM_NOT_GRANTED",
                    "door is not allowed to use the BBS LLM gateway");
            return;
        }
        List<ChatMessage> messages = readChatMessages(payload);
        double temperature = payload.path("temperature").isNumber() ? payload.path("temperature").asDouble() : 0.7d;
        log.info("door-llm-stream-start doorId={} doorSessionId={} requestId={} messages={} temperature={}",
                req.door().manifest.doorId(), req.doorSessionId(), requestId, messages.size(), temperature);
        workers.llmPool().submit(() -> {
            try {
                llm.completeStreaming(messages, temperature, delta ->
                        sendLlmDelta(req.door(), requestId, req.doorSessionId(), delta))
                        .whenComplete((completion, error) -> {
                            if (error != null) {
                                Throwable cause = error instanceof java.util.concurrent.CompletionException
                                        && error.getCause() != null ? error.getCause() : error;
                                log.warn("door-llm-stream-failure doorId={} doorSessionId={} requestId={} reason={}",
                                        req.door().manifest.doorId(), req.doorSessionId(), requestId,
                                        cause == null ? "unknown" : cause.getMessage());
                                sendLlmError(req.door(), requestId, req.doorSessionId(),
                                        "LLM_GATEWAY_ERROR", cause.getMessage());
                                return;
                            }
                            log.info("door-llm-stream-success doorId={} doorSessionId={} requestId={} finishReason={} usage={}",
                                    req.door().manifest.doorId(), req.doorSessionId(), requestId,
                                    completion.finishReason(), completion.usage());
                            sendLlmResult(req.door(), requestId, req.doorSessionId(), completion);
                        });
            } catch (LlmGatewayException e) {
                log.warn("door-llm-stream-failure doorId={} doorSessionId={} requestId={} reason={}",
                        req.door().manifest.doorId(), req.doorSessionId(), requestId, e.getMessage());
                sendLlmError(req.door(), requestId, req.doorSessionId(), "LLM_GATEWAY_ERROR", e.getMessage());
            }
        });
    }

    private void handleDetach(WebSocketSession session, String type, JsonNode payload) {
        String bbsSessionId = resolveBbsSessionId(session, payload);
        Attachment existingAttachment = bbsSessionId == null ? null : attachmentsByBbsSessionId.get(bbsSessionId);
        String reason = switch (type) {
            case "reject_attach" -> blankTo(payload.path("reason").asText("door rejected attach"), "door rejected attach");
            default -> blankTo(payload.path("reason").asText("door closed"), "door closed");
        };
        updateState(session, payload, current -> new DoorSessionState(
                current.state.doorId(),
                current.state.doorName(),
                current.state.doorSessionId(),
                DoorSessionState.Status.DETACHED,
                placeholderRows(current.state.doorName(), reason),
                DoorPromptState.none(),
                reason,
                current.state.version() + 1), false);
        if (existingAttachment != null) unlinkAttachment(existingAttachment);
    }

    private void handleGoodbye(WebSocketSession session) {
        closeQuietly(session, CloseStatus.NORMAL.withReason("goodbye"));
    }

    private void handleStorageGet(WebSocketSession session, String requestId, JsonNode payload) {
        DoorRequestContext req = resolveRequest(session, payload);
        if (req == null) return;
        String key = blankTo(payload.path("key").asText(""), "");
        Optional<DoorStateRepository.Entry> existing =
                state.get(req.doorId(), req.scope(), req.scopeKey(), key);
        ObjectNode out = json.createObjectNode();
        out.put("session_id", req.doorSessionId());
        out.put("scope", req.scope());
        out.put("key", key);
        if (existing.isPresent()) {
            out.set("value", existing.get().value());
            out.put("version", existing.get().version());
            send(req.door(), "storage.value", out, requestId);
        } else {
            send(req.door(), "storage.miss", out, requestId);
        }
    }

    private void handleStoragePut(WebSocketSession session, String requestId, JsonNode payload) {
        DoorRequestContext req = resolveRequest(session, payload);
        if (req == null) return;
        String key = blankTo(payload.path("key").asText(""), "");
        JsonNode value = payload.path("value");
        String jsonValue = value == null ? "null" : value.toString();
        if (jsonValue.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_048_576) {
            sendError(req.door(), requestId, "STORAGE_QUOTA_EXCEEDED",
                    "door KV value exceeds the 1MB limit", null);
            return;
        }
        Long expectedVersion = payload.path("expected_version").isIntegralNumber()
                ? payload.path("expected_version").asLong()
                : null;
        DoorStateRepository.PutResult result = state.put(
                req.doorId(), req.scope(), req.scopeKey(), key, value, expectedVersion);
        ObjectNode out = json.createObjectNode();
        out.put("session_id", req.doorSessionId());
        out.put("scope", req.scope());
        out.put("key", key);
        if (result.ok()) {
            out.put("version", result.version());
            send(req.door(), "storage.put_ok", out, requestId);
        } else {
            if (result.currentVersion() != null) {
                out.put("current_version", result.currentVersion());
            }
            send(req.door(), "storage.put_conflict", out, requestId);
        }
    }

    private void handleStorageDelete(WebSocketSession session, String requestId, JsonNode payload) {
        DoorRequestContext req = resolveRequest(session, payload);
        if (req == null) return;
        String key = blankTo(payload.path("key").asText(""), "");
        state.delete(req.doorId(), req.scope(), req.scopeKey(), key);
        ObjectNode out = json.createObjectNode();
        out.put("session_id", req.doorSessionId());
        out.put("scope", req.scope());
        out.put("key", key);
        send(req.door(), "storage.del_ok", out, requestId);
    }

    private void handleStorageScan(WebSocketSession session, String requestId, JsonNode payload) {
        DoorRequestContext req = resolveRequest(session, payload);
        if (req == null) return;
        String prefix = blankTo(payload.path("prefix").asText(""), "");
        String cursor = blankToNull(payload.path("cursor").asText(null));
        int limit = payload.path("limit").isIntegralNumber() ? payload.path("limit").asInt() : 20;
        DoorStateRepository.ScanPage page = state.scan(
                req.doorId(), req.scope(), req.scopeKey(), prefix, cursor, limit);
        ObjectNode out = json.createObjectNode();
        out.put("session_id", req.doorSessionId());
        ArrayNode entries = out.putArray("entries");
        for (DoorStateRepository.Entry entry : page.entries()) {
            ObjectNode item = entries.addObject();
            item.put("key", entry.key());
            item.set("value", entry.value());
            item.put("version", entry.version());
        }
        if (page.cursor() != null) out.put("cursor", page.cursor());
        send(req.door(), "storage.scan_page", out, requestId);
    }

    private void updateState(WebSocketSession doorSession,
                             JsonNode payload,
                             java.util.function.Function<Attachment, DoorSessionState> updater,
                             boolean pushToUser) {
        String bbsSessionId = resolveBbsSessionId(doorSession, payload);
        if (bbsSessionId == null) return;
        Attachment attachment = attachmentsByBbsSessionId.get(bbsSessionId);
        if (attachment == null) return;
        DoorSessionState next = updater.apply(attachment);
        Attachment updated = new Attachment(attachment.bbsSessionId, attachment.doorId, attachment.doorSessionId, next);
        attachmentsByBbsSessionId.put(bbsSessionId, updated);
        if (pushToUser) {
            VoidCoreSession bbs = sessions.get(bbsSessionId);
            if (bbs != null) {
                sendQuiet(bbs, Frames.update("main", next.version(), next.rows()));
                sendQuiet(bbs, new ServerMessage.InputPrompt(
                        next.prompt().mode(),
                        next.prompt().label(),
                        next.prompt().maxLength(),
                        next.prompt().validKeys(),
                        null));
            }
        } else {
            bus.notify(topicFor(bbsSessionId));
        }
    }

    private String resolveBbsSessionId(WebSocketSession doorSession, JsonNode payload) {
        String doorId = wsIdToDoorId.get(doorSession.getId());
        if (doorId == null) return null;
        String doorSessionId = payload.path("session_id").asText(null);
        if (doorSessionId == null || doorSessionId.isBlank()) return null;
        return bbsSessionIdByDoorSessionKey.get(doorKey(doorId, doorSessionId));
    }

    private void send(ConnectedDoor door, String type, ObjectNode payload) {
        send(door, type, payload, null);
    }

    private void send(ConnectedDoor door, String type, ObjectNode payload, String requestId) {
        send(door.session, type, payload, requestId);
    }

    private void send(WebSocketSession session, String type, ObjectNode payload, String requestId) {
        ObjectNode env = json.createObjectNode();
        if (requestId == null) env.putNull("id");
        else env.put("id", requestId);
        env.put("type", type);
        env.put("protocol_version", PROTOCOL_VERSION);
        env.put("seq", 0);
        env.putNull("mac");
        env.set("payload", payload);
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(env.toString()));
            }
        } catch (IOException ignored) {
        }
    }

    private List<ServerMessage.Row> readRows(JsonNode node) throws IOException {
        if (node == null || !node.isArray()) return List.of();
        List<ServerMessage.Row> rows = new ArrayList<>();
        for (JsonNode child : node) {
            rows.add(json.treeToValue(child, ServerMessage.Row.class));
        }
        return rows;
    }

    private static List<ServerMessage.Row> placeholderRows(String title, String detail) {
        return Frames.textRows(List.of(
                "",
                "  == " + title.toUpperCase() + " ==",
                "",
                "  " + detail,
                "",
                "  [Esc] leave"
        ), "default");
    }

    private static List<String> readAuthors(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode child : node) {
            String value = child.asText(null);
            if (value != null && !value.isBlank()) out.add(value);
        }
        return List.copyOf(out);
    }

    private static List<String> readModes(JsonNode node) {
        if (node == null || !node.isArray()) return List.of("normal");
        List<String> out = new ArrayList<>();
        for (JsonNode child : node) {
            String value = child.asText(null);
            if (value != null && !value.isBlank()) out.add(value);
        }
        return out.isEmpty() ? List.of("normal") : List.copyOf(out);
    }

    private static DoorManifest.Capabilities readCapabilities(JsonNode node) {
        if (node == null || !node.isObject()) return DoorManifest.Capabilities.defaults();
        return new DoorManifest.Capabilities(
                node.path("storage_kv").asBoolean(false),
                node.path("llm").asBoolean(false),
                node.path("notifications").asBoolean(false),
                node.path("multi_session").asBoolean(false),
                node.path("inter_session_messages").asBoolean(false),
                node.path("user_handle_visible").asBoolean(true),
                node.path("user_id_visible").asBoolean(true));
    }

    private List<ChatMessage> readChatMessages(JsonNode payload) {
        List<ChatMessage> messages = new ArrayList<>();
        JsonNode node = payload.path("messages");
        if (node.isArray()) {
            for (JsonNode item : node) {
                String role = blankTo(item.path("role").asText("user"), "user");
                String content = item.path("content").asText("");
                messages.add(new ChatMessage(role, content));
            }
        }
        if (messages.isEmpty()) {
            String system = blankToNull(payload.path("system").asText(null));
            String user = payload.path("prompt").asText("");
            if (system != null) messages.add(new ChatMessage("system", system));
            messages.add(new ChatMessage("user", user));
        }
        return messages;
    }

    private static String doorKey(String doorId, String doorSessionId) {
        return doorId + "::" + doorSessionId;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private DoorRequestContext resolveRequest(WebSocketSession doorSession, JsonNode payload) {
        String doorId = wsIdToDoorId.get(doorSession.getId());
        if (doorId == null) return null;
        String doorSessionId = payload.path("session_id").asText(null);
        if (doorSessionId == null || doorSessionId.isBlank()) return null;
        String bbsSessionId = bbsSessionIdByDoorSessionKey.get(doorKey(doorId, doorSessionId));
        if (bbsSessionId == null) return null;
        VoidCoreSession bbs = sessions.get(bbsSessionId);
        if (bbs == null) return null;
        String scope = normalizeScope(payload.path("scope").asText("user"));
        String scopeKey = switch (scope) {
            case "user" -> String.valueOf(bbs.userId());
            case "shared", "global" -> "";
            default -> "";
        };
        ConnectedDoor door = doorsById.get(doorId);
        if (door == null) return null;
        return new DoorRequestContext(door, doorId, doorSessionId, bbs, scope, scopeKey);
    }

    private static String normalizeScope(String scope) {
        return switch (blankTo(scope, "user").toLowerCase()) {
            case "shared" -> "shared";
            case "global" -> "global";
            default -> "user";
        };
    }

    private static void sendQuiet(VoidCoreSession session, ServerMessage message) {
        try {
            session.send(message);
        } catch (IOException ignored) {
        }
    }

    private void sendError(ConnectedDoor door,
                           String requestId,
                           String code,
                           String message,
                           ObjectNode details) {
        ObjectNode payload = json.createObjectNode();
        payload.put("code", code);
        payload.put("message", message);
        if (details != null && !details.isEmpty()) payload.set("details", details);
        send(door, "error", payload, requestId);
    }

    private void sendLlmDelta(ConnectedDoor door, String requestId, String doorSessionId, String content) {
        ObjectNode payload = json.createObjectNode();
        payload.put("session_id", doorSessionId);
        payload.put("content", content);
        send(door, "llm.delta", payload, requestId);
    }

    private void sendLlmResult(ConnectedDoor door,
                               String requestId,
                               String doorSessionId,
                               ChatCompletion completion) {
        ObjectNode payload = json.createObjectNode();
        payload.put("session_id", doorSessionId);
        payload.put("content", completion.content());
        if (completion.finishReason() != null) payload.put("finish_reason", completion.finishReason());
        TokenUsage usage = completion.usage();
        if (usage != null) {
            ObjectNode usageNode = payload.putObject("usage");
            if (usage.promptTokens() != null) usageNode.put("prompt_tokens", usage.promptTokens());
            if (usage.completionTokens() != null) usageNode.put("completion_tokens", usage.completionTokens());
            if (usage.totalTokens() != null) usageNode.put("total_tokens", usage.totalTokens());
        }
        send(door, "llm.result", payload, requestId);
    }

    private void sendLlmError(ConnectedDoor door,
                              String requestId,
                              String doorSessionId,
                              String code,
                              String message) {
        ObjectNode payload = json.createObjectNode();
        payload.put("session_id", doorSessionId);
        payload.put("code", code);
        payload.put("message", message == null ? "llm gateway error" : message);
        send(door, "llm.error", payload, requestId);
    }

    private void leaveByBbsSessionId(String bbsSessionId, String reason) {
        Attachment attachment = attachmentsByBbsSessionId.remove(bbsSessionId);
        if (attachment == null) return;
        sendDetach(attachment, reason);
        unlinkAttachment(attachment);
    }

    private void sendDetach(Attachment attachment, String reason) {
        if (attachment.doorSessionId == null || attachment.doorSessionId.isBlank()) return;
        ConnectedDoor door = doorsById.get(attachment.doorId);
        if (door == null) return;
        ObjectNode payload = json.createObjectNode();
        payload.put("session_id", attachment.doorSessionId);
        payload.put("reason", reason);
        send(door, "detach", payload);
    }

    private void unlinkAttachment(Attachment attachment) {
        if (attachment.doorSessionId != null && !attachment.doorSessionId.isBlank()) {
            bbsSessionIdByDoorSessionKey.remove(doorKey(attachment.doorId, attachment.doorSessionId));
        }
        ConnectedDoor door = doorsById.get(attachment.doorId);
        if (door != null && attachment.doorSessionId != null) {
            door.attachedSessionIds.remove(attachment.doorSessionId);
        }
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) session.close(status);
        } catch (IOException ignored) {
        }
    }

    @PreDestroy
    void shutdownDoors() {
        List<String> bbsSessionIds = new ArrayList<>(attachmentsByBbsSessionId.keySet());
        for (String bbsSessionId : bbsSessionIds) {
            leaveByBbsSessionId(bbsSessionId, "shutdown");
        }
        for (ConnectedDoor door : new ArrayList<>(doorsById.values())) {
            ObjectNode payload = json.createObjectNode();
            payload.put("reason", "bbs_shutdown");
            payload.put("grace_sec", 3);
            send(door, "shutdown", payload);
        }
    }

    private static final class ConnectedDoor {
        private final WebSocketSession session;
        private final DoorManifest manifest;
        private final boolean llmGranted;
        private final java.util.Set<String> attachedSessionIds = ConcurrentHashMap.newKeySet();

        private ConnectedDoor(WebSocketSession session, DoorManifest manifest, boolean llmGranted) {
            this.session = session;
            this.manifest = manifest;
            this.llmGranted = llmGranted;
        }
    }

    private record DoorRequestContext(ConnectedDoor door,
                                      String doorId,
                                      String doorSessionId,
                                      VoidCoreSession bbsSession,
                                      String scope,
                                      String scopeKey) {
    }

    private static final class Attachment {
        private final String bbsSessionId;
        private final String doorId;
        private final String doorSessionId;
        private final DoorSessionState state;

        private Attachment(String bbsSessionId,
                           String doorId,
                           String doorSessionId,
                           DoorSessionState state) {
            this.bbsSessionId = bbsSessionId;
            this.doorId = doorId;
            this.doorSessionId = doorSessionId;
            this.state = state;
        }
    }
}
