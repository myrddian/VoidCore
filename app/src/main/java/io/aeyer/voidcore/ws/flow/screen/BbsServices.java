package io.aeyer.voidcore.ws.flow.screen;

import io.aeyer.voidcore.auth.SessionService;
import io.aeyer.voidcore.auth.UserRepository;
import io.aeyer.voidcore.instance.InstanceFeatureService;
import io.aeyer.voidcore.sysop.SysopActionRepository;
import io.aeyer.voidcore.ws.VoidCoreSession;
import io.aeyer.voidcore.ws.flow.bus.MessageBus;
import io.aeyer.voidcore.ws.flow.view.ChatView;
import io.aeyer.voidcore.ws.flow.view.DocumentView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * Cross-cutting services every {@link Screen} needs that <strong>are
 * not</strong> ScreenRouter-specific. Things like persisting
 * {@code current_screen}, reading the user's theme, writing audit
 * rows: pure delegations to the relevant repositories / services
 * with no router state involved.
 *
 * <p>Spring-wired; injected into {@link BbsContext} so screens can
 * call {@code ctx.persistCurrentScreen(json)} etc. directly without
 * going through the router back-reference.
 *
 * <p>This is the destination for any "helper on ScreenRouter that
 * doesn't actually use ScreenRouter state." As more such helpers
 * migrate here, ScreenRouter shrinks and its responsibility narrows
 * to (a) routing input dispatch and (b) navigation stack management
 * — the things only it can do.
 */
@Component
@ConditionalOnBean(SessionService.class)
public class BbsServices {

    private static final Logger log = LoggerFactory.getLogger(BbsServices.class);
    private static final String[] THEME_CYCLE = {"phosphor", "amber", "cga", "modern"};

    private final SessionService sessions;
    private final ObjectMapper json;
    private final UserRepository users;
    private final SysopActionRepository sysopActions;
    private final MessageBus bus;
    private final MentionService mentions;
    private final ChatView chat;
    private final DocumentView documents;
    private final AuthFinaliser authFinaliser;
    private final InstanceFeatureService instanceFeatures;
    private final io.aeyer.voidcore.social.SocialEventService socialEvents;
    private final io.aeyer.voidcore.social.WatchListRepository watchList;

    public BbsServices(SessionService sessions,
                       ObjectMapper json,
                       UserRepository users,
                       SysopActionRepository sysopActions,
                       MessageBus bus,
                       MentionService mentions,
                       ChatView chat,
                       DocumentView documents,
                       AuthFinaliser authFinaliser,
                       InstanceFeatureService instanceFeatures,
                       org.springframework.beans.factory.ObjectProvider<io.aeyer.voidcore.social.SocialEventService> socialEvents,
                       org.springframework.beans.factory.ObjectProvider<io.aeyer.voidcore.social.WatchListRepository> watchList) {
        this.sessions = sessions;
        this.json = json;
        this.users = users;
        this.sysopActions = sysopActions;
        this.bus = bus;
        this.mentions = mentions;
        this.chat = chat;
        this.documents = documents;
        this.authFinaliser = authFinaliser;
        this.instanceFeatures = instanceFeatures;
        this.socialEvents = socialEvents.getIfAvailable();
        this.watchList = watchList.getIfAvailable();
    }

    /**
     * Stamp the user's current screen onto {@code sessions.current_screen}
     * per SPEC §3 / §13 (resume after disconnect lands the user back on
     * the same screen). Quiet on failure — debug log only.
     */
    public void persistCurrentScreen(VoidCoreSession session, String screenJson) {
        String token = session.sessionToken();
        if (token == null) return;
        try {
            sessions.updateScreen(token, json.readTree(screenJson));
        } catch (Exception e) {
            log.debug("persistCurrentScreen({}) failed: {}", screenJson, e.toString());
        }
    }

    /**
     * Read the user's saved theme from preferences JSONB; default
     * {@code phosphor}. Falls back to {@code phosphor} on any parse
     * error or unknown theme name.
     */
    public String currentTheme(long userId) {
        try {
            JsonNode node = json.readTree(users.preferences(userId));
            String t = node.path("theme").asText("phosphor");
            for (String known : THEME_CYCLE) if (known.equals(t)) return t;
            return "phosphor";
        } catch (Exception e) {
            return "phosphor";
        }
    }

    /**
     * Append a row to {@code sysop_actions} (audit log per SPEC §3).
     * No-op if the actor isn't authenticated — prevents writing audit
     * rows for actions taken in pre-auth flows.
     */
    public void audit(VoidCoreSession session, String action, JsonNode payload) {
        Long actor = session.userId();
        if (actor == null) return;
        sysopActions.record(actor, action, payload);
    }

    /** Access to the JSON mapper for screens that need to build JSONB payloads. */
    public ObjectMapper json() {
        return json;
    }

    /**
     * The cross-session message bus per ADR-027. Screens publish topic
     * invalidations after mutations ({@code bus().notify("oneliners")});
     * the {@link io.aeyer.voidcore.ws.flow.screen.Navigator} subscribes /
     * unsubscribes sessions on push / pop based on each screen's
     * {@link io.aeyer.voidcore.ws.flow.screen.Screen#topics(BbsContext)}.
     */
    public MessageBus bus() {
        return bus;
    }

    /**
     * Per-user "you were @-mentioned" delivery. Targeted notification,
     * distinct from the topic-invalidation bus per ADR-027 (the bus
     * deliberately doesn't cover targeted notifications in v1.4).
     */
    public MentionService mentions() {
        return mentions;
    }

    /**
     * Cached read view of recent chat messages per ADR-029. Writers
     * publish a room-scoped topic ({@link ChatView#topicFor(String)})
     * on insert, invalidating that room's cache so viewers re-read on
     * the next paint.
     */
    public ChatView chat() {
        return chat;
    }

    /**
     * Cached read-side view of the document pool per ADR-023 / ADR-029.
     * Same shape as the sibling views; screens calling
     * {@code services.documents().list()} / {@code byId()} /
     * {@code bySlug()} get the cached snapshot, with the
     * {@link DocumentView#canRead canRead} funnel for visibility
     * filtering.
     */
    public DocumentView documents() {
        return documents;
    }

    public InstanceFeatureService instanceFeatures() {
        return instanceFeatures;
    }

    /**
     * Auth-completion service. Login / register screens call this to
     * dispatch credentials, run the post-auth pipeline (presence,
     * banners, {@code auth.ok}, post-auth landing), and pivot into
     * the new-user registration walk. Brings the legacy {@code
     * router.legacy*} bridges to zero — see {@link AuthFinaliser}'s
     * javadoc for the Spring-cycle rationale.
     */
    /**
     * #87 / #89 social-event facade. Recording user-attributed
     * content events ({@code recordEvent}) appends to
     * {@code activity_events} for the activity feed and triggers
     * {@link io.aeyer.voidcore.social.AchievementAwardingService}
     * milestone evaluation. Nullable in DB-less test profiles.
     */
    public io.aeyer.voidcore.social.SocialEventService socialEvents() {
        return socialEvents;
    }

    /**
     * #91 watch-list repo. Used by the {@code [+]} keystroke on
     * the doc viewer to follow / unfollow the doc's author. Nullable
     * in DB-less test profiles.
     */
    public io.aeyer.voidcore.social.WatchListRepository watchList() {
        return watchList;
    }

    public AuthFinaliser authFinaliser() {
        return authFinaliser;
    }
}
