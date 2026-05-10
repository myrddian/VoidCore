package io.aeyer.voidcore.ws.flow.screen;

/**
 * Factory for a named custom screen.
 *
 * <p>The provider returns a fresh {@link Screen} per navigator push so
 * custom screens can participate in the same per-session instance model as
 * built-in {@link ScreenAppComponent} screens.
 */
public interface CustomScreenProvider {

    /** Globally unique custom screen name, e.g. {@code aeyer/releases}. */
    String screenName();

    /** Mint a fresh screen instance for one session stack layer. */
    Screen createScreen();
}
