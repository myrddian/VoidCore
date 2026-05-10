package io.aeyer.voidcore.extensions.host;

import io.aeyer.voidcore.extensions.ExtensionDataService;
import io.aeyer.voidcore.extensions.ExtensionScreenRegistration;
import io.aeyer.voidcore.extensions.ExtensionScreenRuntime;
import io.aeyer.voidcore.extensions.PlaceholderExtensionScreenRuntime;
import io.aeyer.voidcore.ws.flow.Banner;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.ScreenRoute;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.flow.view.DocumentView;
import io.aeyer.voidcore.ws.protocol.ServerMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Runtime entrypoint for manifest-backed custom screens.
 *
 * <p>Walks the installed script hosts and falls back to the placeholder
 * runtime when no host claims the registration yet.
 */
@Component
public class HostedExtensionScreenRuntime implements ExtensionScreenRuntime {

    private final List<ExtensionScriptHost> hosts;
    private final ExtensionDataService extensionData;
    private final PlaceholderExtensionScreenRuntime placeholder = new PlaceholderExtensionScreenRuntime();

    public HostedExtensionScreenRuntime(List<ExtensionScriptHost> hosts,
                                        ExtensionDataService extensionData) {
        this.hosts = hosts;
        this.extensionData = extensionData;
    }

    @Override
    public Screen createScreen(ExtensionScreenRegistration registration) {
        for (ExtensionScriptHost host : hosts) {
            Optional<ExtensionScript> script = host.createScript(registration);
            if (script.isPresent()) {
                return new HostedExtensionScreen(registration, script.get(), extensionData);
            }
        }
        return placeholder.createScreen(registration);
    }

    private static final class HostedExtensionScreen implements Screen {
        private final ExtensionScreenRegistration registration;
        private final ExtensionScript script;
        private final ExtensionDataService extensionData;

        private HostedExtensionScreen(ExtensionScreenRegistration registration,
                                      ExtensionScript script,
                                      ExtensionDataService extensionData) {
            this.registration = registration;
            this.script = script;
            this.extensionData = extensionData;
        }

        @Override
        public Phase phase() {
            return Phase.MENU;
        }

        @Override
        public String name() {
            return "custom-screen:" + registration.screenName();
        }

        @Override
        public Transition onEnter(BbsContext ctx) {
            ctx.persistCustomScreen(registration.screenName());
            script.onEnter(new DefaultExtensionHostContext(registration, ctx, extensionData));
            return Transition.None.INSTANCE;
        }

        @Override
        public Transition onKey(BbsContext ctx, String key) {
            script.onKey(new DefaultExtensionHostContext(registration, ctx, extensionData), key);
            return Transition.None.INSTANCE;
        }

        @Override
        public Transition onLine(BbsContext ctx, String text) {
            script.onLine(new DefaultExtensionHostContext(registration, ctx, extensionData), text);
            return Transition.None.INSTANCE;
        }

        @Override
        public Transition onCancel(BbsContext ctx) {
            script.onCancel(new DefaultExtensionHostContext(registration, ctx, extensionData));
            return Transition.None.INSTANCE;
        }
    }

    private static final class DefaultExtensionHostContext implements ExtensionHostContext {
        private final ExtensionScreenRegistration registration;
        private final BbsContext ctx;
        private final ExtensionDataService extensionData;
        private final ExtensionUi ui;
        private final ExtensionNavigation navigation;
        private final ExtensionSessionView session;
        private final ExtensionDocuments documents;
        private final ExtensionDataAccess data;
        private final ExtensionEffects effects;

        private DefaultExtensionHostContext(ExtensionScreenRegistration registration,
                                            BbsContext ctx,
                                            ExtensionDataService extensionData) {
            this.registration = registration;
            this.ctx = ctx;
            this.extensionData = extensionData;
            this.ui = new DefaultExtensionUi(ctx);
            this.navigation = new DefaultExtensionNavigation(ctx);
            this.session = buildSession(ctx);
            this.documents = new DefaultExtensionDocuments(ctx);
            this.data = new DefaultExtensionDataAccess(registration, ctx, extensionData);
            this.effects = new DefaultExtensionEffects(ctx);
        }

        @Override
        public ExtensionScreenRegistration registration() {
            return registration;
        }

        @Override
        public ExtensionUi ui() {
            return ui;
        }

        @Override
        public ExtensionNavigation navigation() {
            return navigation;
        }

        @Override
        public ExtensionSessionView session() {
            return session;
        }

        @Override
        public ExtensionDocuments documents() {
            return documents;
        }

        @Override
        public ExtensionDataAccess data() {
            return data;
        }

        @Override
        public ExtensionEffects effects() {
            return effects;
        }

        private static ExtensionSessionView buildSession(BbsContext ctx) {
            Long userId = ctx.user() == null ? null : ctx.user().id();
            String handle = ctx.user() == null ? null : ctx.user().handle();
            ScreenRoute route = ctx.currentRoute();
            return new ExtensionSessionView(
                    ctx.isAuthenticated(),
                    userId,
                    handle,
                    ctx.isSysop(),
                    route == null ? null : route.key());
        }
    }

    private static final class DefaultExtensionUi implements ExtensionUi {
        private final BbsContext ctx;

        private DefaultExtensionUi(BbsContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void banner(String label) {
            String value = label == null ? "" : label.trim();
            ctx.send(Frames.update("banner", 1, Banner.minimalRows(value.toUpperCase(Locale.ROOT))));
        }

        @Override
        public void mainText(List<String> lines) {
            ctx.send(Frames.update("main", 1, Frames.textRows(lines == null ? List.of() : lines, "default")));
        }

        @Override
        public void mainTree(io.aeyer.voidcore.ws.flow.layout.Element tree, String focusPath) {
            ctx.send(Frames.tree("main", 1, tree, focusPath));
        }

        @Override
        public void promptNone() {
            ctx.send(new ServerMessage.InputPrompt("none", null, null, null, null));
        }

        @Override
        public void promptKeystroke(String label, String validKeys) {
            ctx.send(new ServerMessage.InputPrompt("keystroke", label, null, validKeys, null));
        }

        @Override
        public void promptLine(String label, Integer maxLength, String initial) {
            ctx.send(new ServerMessage.InputPrompt("line", label, maxLength, null, initial));
        }

        @Override
        public void promptPassword(String label, Integer maxLength) {
            ctx.send(new ServerMessage.InputPrompt("password", label, maxLength, null, null));
        }

        @Override
        public void notifyMain(String text, String level, long durationMs) {
            ctx.send(Frames.notify("main", text, level, durationMs));
        }
    }

    private static final class DefaultExtensionNavigation implements ExtensionNavigation {
        private final BbsContext ctx;

        private DefaultExtensionNavigation(BbsContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void pop() {
            ctx.pop();
        }

        @Override
        public void pushCustom(String screenName) {
            ctx.push(screenName);
        }

        @Override
        public void pushCore(Phase phase) {
            ctx.push(phase);
        }
    }

    private static final class DefaultExtensionDocuments implements ExtensionDocuments {
        private final BbsContext ctx;

        private DefaultExtensionDocuments(BbsContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Optional<ExtensionDocumentView> byId(long id) {
            return ctx.services().documents().byId(id)
                    .filter(doc -> ctx.services().documents().canRead(ctx.session(), doc))
                    .map(DefaultExtensionDocuments::map);
        }

        @Override
        public Optional<ExtensionDocumentView> bySlug(String slug) {
            return ctx.services().documents().bySlug(slug)
                    .filter(doc -> ctx.services().documents().canRead(ctx.session(), doc))
                    .map(DefaultExtensionDocuments::map);
        }

        @Override
        public List<ExtensionDocumentView> listByType(String typeSlug, int limit) {
            String normalized = typeSlug == null ? "" : typeSlug.trim().toLowerCase(Locale.ROOT);
            int safeLimit = Math.max(1, Math.min(limit, 100));
            DocumentView docs = ctx.services().documents();
            return docs.list().stream()
                    .filter(doc -> normalized.equals(doc.typeSlug()))
                    .filter(doc -> docs.canRead(ctx.session(), doc))
                    .limit(safeLimit)
                    .map(DefaultExtensionDocuments::map)
                    .toList();
        }

        private static ExtensionDocumentView map(io.aeyer.voidcore.documents.DocumentRow doc) {
            return new ExtensionDocumentView(
                    doc.id(),
                    doc.slug(),
                    doc.title(),
                    doc.typeSlug(),
                    doc.typeVersion(),
                    doc.rev(),
                    doc.body(),
                    doc.frontmatter(),
                    doc.tags(),
                    doc.authorId(),
                    doc.visibility().name(),
                    doc.status().name(),
                    doc.createdAt(),
                    doc.updatedAt());
        }
    }

    private static final class DefaultExtensionDataAccess implements ExtensionDataAccess {
        private final ExtensionScreenRegistration registration;
        private final BbsContext ctx;
        private final ExtensionDataService extensionData;

        private DefaultExtensionDataAccess(ExtensionScreenRegistration registration,
                                           BbsContext ctx,
                                           ExtensionDataService extensionData) {
            this.registration = registration;
            this.ctx = ctx;
            this.extensionData = extensionData;
        }

        @Override
        public Optional<com.fasterxml.jackson.databind.JsonNode> getGlobal(String key) {
            return extensionData.getGlobal(registration.extensionSlug(), key);
        }

        @Override
        public Optional<com.fasterxml.jackson.databind.JsonNode> getForCurrentUser(String key) {
            Long userId = requireUserId();
            return extensionData.getForUser(registration.extensionSlug(), userId, key);
        }

        @Override
        public void putGlobal(String key, com.fasterxml.jackson.databind.JsonNode value) {
            extensionData.putGlobal(registration.extensionSlug(), key, value);
        }

        @Override
        public void putForCurrentUser(String key, com.fasterxml.jackson.databind.JsonNode value) {
            Long userId = requireUserId();
            extensionData.putForUser(registration.extensionSlug(), userId, key, value);
        }

        @Override
        public void deleteGlobal(String key) {
            extensionData.deleteGlobal(registration.extensionSlug(), key);
        }

        @Override
        public void deleteForCurrentUser(String key) {
            Long userId = requireUserId();
            extensionData.deleteForUser(registration.extensionSlug(), userId, key);
        }

        @Override
        public List<String> globalKeys(String prefix, int limit) {
            return extensionData.globalKeys(registration.extensionSlug(), prefix, limit);
        }

        @Override
        public List<String> currentUserKeys(String prefix, int limit) {
            Long userId = requireUserId();
            return extensionData.userKeys(registration.extensionSlug(), userId, prefix, limit);
        }

        private Long requireUserId() {
            Long userId = ctx.user() == null ? null : ctx.user().id();
            if (userId == null) {
                throw new IllegalStateException("extension screen requires an authenticated user");
            }
            return userId;
        }
    }

    private static final class DefaultExtensionEffects implements ExtensionEffects {
        private final BbsContext ctx;

        private DefaultExtensionEffects(BbsContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void openUrl(String url) {
            ctx.send(new ServerMessage.EffectOpenUrl(url));
        }

        @Override
        public void setTheme(String name) {
            ctx.send(new ServerMessage.EffectSetTheme(name));
        }

        @Override
        public void copyClipboard(String text) {
            ctx.send(new ServerMessage.EffectCopyClipboard(text));
        }
    }
}
