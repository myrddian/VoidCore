package io.aeyer.voidcore.instance;

/**
 * Optional CRT/visual toggles for overlay themes.
 */
public record ThemeVisualEffects(Boolean scanlines,
                                 Boolean noise,
                                 Boolean flicker) {
}
