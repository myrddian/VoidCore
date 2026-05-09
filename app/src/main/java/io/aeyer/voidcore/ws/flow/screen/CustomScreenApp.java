package io.aeyer.voidcore.ws.flow.screen;

/**
 * Base class for first-class custom screens that live on the normal
 * navigator stack.
 *
 * <p>Subclasses still implement the normal {@link ScreenApp} hooks. This
 * class just centralises the stable custom screen name and the persistence
 * helper used by reconnect / restart restore.
 */
public abstract class CustomScreenApp extends ScreenApp {

    private final String screenName;

    protected CustomScreenApp(String screenName) {
        this.screenName = ScreenRoute.custom(screenName).key();
    }

    protected final String screenName() {
        return screenName;
    }

    /** Persist this custom screen as the reconnect / restart restore target. */
    protected final void persistCurrentCustomScreen(BbsContext ctx) {
        ctx.persistCustomScreen(screenName);
    }
}
