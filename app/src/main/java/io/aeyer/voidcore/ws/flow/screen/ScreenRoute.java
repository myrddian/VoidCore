package io.aeyer.voidcore.ws.flow.screen;

import java.util.Locale;
import java.util.Objects;

/**
 * Internal navigation identity for one stack layer.
 *
 * <p>Core screens still use {@link Phase} as their compile-time identity,
 * but custom/extension screens need a first-class route type too. This
 * abstraction lets the navigator stack carry either without giving up the
 * old phase-based compatibility shims.
 */
public sealed interface ScreenRoute permits ScreenRoute.Core, ScreenRoute.Custom {

    /** Stable route key used for logging, persistence, and comparisons. */
    String key();

    /** Core phase when this is a built-in route; otherwise {@code null}. */
    default Phase corePhaseOrNull() {
        return null;
    }

    static ScreenRoute core(Phase phase) {
        return new Core(Objects.requireNonNull(phase, "phase"));
    }

    static ScreenRoute custom(String screenName) {
        return new Custom(normalizeScreenName(screenName));
    }

    private static String normalizeScreenName(String raw) {
        if (raw == null) throw new IllegalArgumentException("screenName must not be null");
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("screenName must not be blank");
        }
        return normalized;
    }

    record Core(Phase phase) implements ScreenRoute {
        public Core {
            Objects.requireNonNull(phase, "phase");
        }

        @Override
        public String key() {
            return phase.name();
        }

        @Override
        public Phase corePhaseOrNull() {
            return phase;
        }
    }

    record Custom(String screenName) implements ScreenRoute {
        public Custom {
            screenName = normalizeScreenName(screenName);
        }

        @Override
        public String key() {
            return screenName;
        }
    }
}
