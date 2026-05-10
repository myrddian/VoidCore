package io.aeyer.voidcore.instance;

import java.util.Locale;

/**
 * Banner presentation policy for instance skins.
 */
public enum ScreenBannerPolicy {
    ALWAYS_FULL("always_full"),
    AUTO_COMPACT("auto_compact"),
    ALWAYS_COMPACT("always_compact");

    private final String wireValue;

    ScreenBannerPolicy(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static ScreenBannerPolicy parse(String value) {
        if (value == null || value.isBlank()) return AUTO_COMPACT;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "always_full" -> ALWAYS_FULL;
            case "auto_compact" -> AUTO_COMPACT;
            case "always_compact" -> ALWAYS_COMPACT;
            default -> throw new IllegalArgumentException("unsupported banner policy " + value);
        };
    }
}
