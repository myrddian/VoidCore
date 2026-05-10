package io.aeyer.voidcore.extensions;

import io.aeyer.voidcore.ws.flow.Banner;
import io.aeyer.voidcore.ws.flow.Frames;
import io.aeyer.voidcore.ws.flow.screen.BbsContext;
import io.aeyer.voidcore.ws.flow.screen.Phase;
import io.aeyer.voidcore.ws.flow.screen.Screen;
import io.aeyer.voidcore.ws.flow.screen.Transition;
import io.aeyer.voidcore.ws.protocol.ServerMessage;

import java.util.List;
import java.util.Locale;

/**
 * Conservative default runtime for manifest-backed screens.
 *
 * <p>Until a scripting runtime is installed, discovered screens still become
 * first-class routes and render a clear placeholder explaining what is
 * missing.
 */
public class PlaceholderExtensionScreenRuntime implements ExtensionScreenRuntime {

    @Override
    public Screen createScreen(ExtensionScreenRegistration registration) {
        return new UnavailableExtensionScreen(registration);
    }

    private static final class UnavailableExtensionScreen implements Screen {
        private final ExtensionScreenRegistration registration;

        private UnavailableExtensionScreen(ExtensionScreenRegistration registration) {
            this.registration = registration;
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
            ctx.send(Frames.update("banner", 1,
                    Banner.minimalRows(registration.displayLabel().toUpperCase(Locale.ROOT))));
            ctx.send(Frames.update("main", 1, Frames.textRows(List.of(
                    "",
                    "  Custom screen registered: " + registration.displayLabel(),
                    "",
                    "  Screen:      " + registration.screenName(),
                    "  Extension:   " + registration.extensionSlug(),
                    "  Version:     " + optional(registration.extensionVersion()),
                    "  Entrypoint:  " + optional(registration.entrypoint()),
                    "  Manifest:    " + registration.manifestPath(),
                    "  Root:        " + registration.extensionRoot(),
                    "",
                    "  The extension registry found this screen at startup,",
                    "  but no scripting runtime is installed for manifest-",
                    "  backed screens yet.",
                    "",
                    "  [Q] Back"
            ), "default")));
            ctx.send(new ServerMessage.InputPrompt("keystroke", "command:", null, "Q", null));
            return Transition.None.INSTANCE;
        }

        @Override
        public Transition onKey(BbsContext ctx, String key) {
            if ("Q".equalsIgnoreCase(key)) {
                ctx.pop();
            }
            return Transition.None.INSTANCE;
        }

        @Override
        public Transition onCancel(BbsContext ctx) {
            ctx.pop();
            return Transition.None.INSTANCE;
        }

        private static String optional(String value) {
            return value == null || value.isBlank() ? "<unspecified>" : value;
        }
    }
}
