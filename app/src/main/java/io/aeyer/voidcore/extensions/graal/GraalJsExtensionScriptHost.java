package io.aeyer.voidcore.extensions.graal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.voidcore.extensions.ExtensionScreenRegistration;
import io.aeyer.voidcore.extensions.host.ExtensionHostContext;
import io.aeyer.voidcore.extensions.host.ExtensionScript;
import io.aeyer.voidcore.extensions.host.ExtensionScriptHost;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GraalJS-backed extension script host.
 *
 * <p>JS never receives direct access to Java internals. The only guest-visible
 * surface is a proxied API object plus proxied per-callback host context.
 */
@Component
public class GraalJsExtensionScriptHost implements ExtensionScriptHost {

    private static final Logger log = LoggerFactory.getLogger(GraalJsExtensionScriptHost.class);

    private final ObjectMapper json;

    public GraalJsExtensionScriptHost(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public Optional<ExtensionScript> createScript(ExtensionScreenRegistration registration) {
        Path entrypoint = registration.entrypointPath();
        String entrypointName = registration.entrypoint();
        if (entrypointName == null || !entrypointName.endsWith(".js")) {
            return Optional.empty();
        }
        if (entrypoint == null || !Files.isRegularFile(entrypoint)) {
            return Optional.of(errorScript("Missing script entrypoint: " + entrypointName));
        }
        try {
            return Optional.of(loadScript(registration, entrypoint));
        } catch (Exception e) {
            log.warn("failed loading GraalJS extension screen {} from {}: {}",
                    registration.screenName(), entrypoint, e.toString());
            return Optional.of(errorScript("Failed to load script: " + e.getMessage()));
        }
    }

    private ExtensionScript loadScript(ExtensionScreenRegistration registration, Path entrypoint) throws Exception {
        Context context = Context.newBuilder("js")
                .allowHostClassLookup(className -> false)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .allowIO(IOAccess.NONE)
                .option("js.ecmascript-version", "2024")
                .build();

        RegistrationCapture capture = new RegistrationCapture();
        context.getBindings("js").putMember("voidcore", ProxyObject.fromMap(Map.of(
                "apiVersion", "1",
                "registerScreen", (ProxyExecutable) args -> {
                    if (args.length != 1) {
                        throw new IllegalArgumentException("voidcore.registerScreen expects exactly one object");
                    }
                    capture.register(args[0]);
                    return null;
                }
        )));

        String sourceText = Files.readString(entrypoint);
        Source source = Source.newBuilder("js", sourceText, entrypoint.toString()).build();
        context.eval(source);
        Value screenObject = capture.screen();
        if (screenObject == null || !screenObject.hasMembers()) {
            throw new IllegalStateException("script did not call voidcore.registerScreen({...})");
        }
        return new GraalJsScreenScript(registration, context, screenObject, json);
    }

    private ExtensionScript errorScript(String message) {
        return new ExtensionScript() {
            @Override
            public void onEnter(io.aeyer.voidcore.extensions.host.ExtensionHostContext ctx) {
                ctx.ui().banner(ctx.registration().displayLabel());
                ctx.ui().mainText(List.of(
                        "",
                        "  Extension screen failed to load.",
                        "",
                        "  " + message,
                        "",
                        "  [Q] Back"
                ));
                ctx.ui().promptKeystroke("command:", "Q");
            }

            @Override
            public void onKey(io.aeyer.voidcore.extensions.host.ExtensionHostContext ctx, String key) {
                if ("Q".equalsIgnoreCase(key)) {
                    ctx.navigation().pop();
                }
            }
        };
    }

    private static final class RegistrationCapture {
        private Value screen;

        private void register(Value value) {
            if (screen != null) {
                throw new IllegalStateException("screen already registered");
            }
            this.screen = value;
        }

        private Value screen() {
            return screen;
        }
    }

    private static final class GraalJsScreenScript implements ExtensionScript {
        private final ExtensionScreenRegistration registration;
        private final Context context;
        private final Value screenObject;
        private final ObjectMapper json;

        private boolean disabled;
        private String disabledReason;

        private GraalJsScreenScript(ExtensionScreenRegistration registration,
                                    Context context,
                                    Value screenObject,
                                    ObjectMapper json) {
            this.registration = registration;
            this.context = context;
            this.screenObject = screenObject;
            this.json = json;
        }

        @Override
        public void onEnter(ExtensionHostContext ctx) {
            if (disabled) {
                renderFault(ctx);
                return;
            }
            invoke("onEnter", ctx);
        }

        @Override
        public void onKey(ExtensionHostContext ctx, String key) {
            if (disabled) {
                if ("Q".equalsIgnoreCase(key)) {
                    ctx.navigation().pop();
                }
                return;
            }
            invoke("onKey", ctx, key);
        }

        @Override
        public void onLine(ExtensionHostContext ctx, String text) {
            if (disabled) {
                return;
            }
            invoke("onLine", ctx, text);
        }

        @Override
        public void onCancel(ExtensionHostContext ctx) {
            if (disabled) {
                ctx.navigation().pop();
                return;
            }
            if (!invoke("onCancel", ctx)) {
                ctx.navigation().pop();
            }
        }

        private boolean invoke(String lifecycle, ExtensionHostContext hostCtx, Object... extraArgs) {
            Value callback = screenObject.getMember(lifecycle);
            if (callback == null || callback.isNull() || !callback.canExecute()) {
                return false;
            }
            try {
                Object[] args = new Object[extraArgs.length + 1];
                args[0] = new GraalJsHostBridge(hostCtx, json).asGuestObject();
                System.arraycopy(extraArgs, 0, args, 1, extraArgs.length);
                callback.execute(args);
                return true;
            } catch (PolyglotException | IllegalArgumentException e) {
                disabled = true;
                disabledReason = e.getMessage() == null ? e.toString() : e.getMessage();
                log.warn("disabling extension screen {} after {} failure: {}",
                        registration.screenName(), lifecycle, e.toString());
                renderFault(hostCtx);
                return true;
            }
        }

        private void renderFault(ExtensionHostContext ctx) {
            ctx.ui().banner(registration.displayLabel());
            ctx.ui().mainText(List.of(
                    "",
                    "  Extension screen disabled after a runtime error.",
                    "",
                    "  Screen:    " + registration.screenName(),
                    "  Extension: " + registration.extensionSlug(),
                    "  Error:     " + optional(disabledReason),
                    "",
                    "  [Q] Back"
            ));
            ctx.ui().promptKeystroke("command:", "Q");
        }

        private static String optional(String value) {
            return value == null || value.isBlank() ? "<unknown>" : value;
        }
    }
}
