package io.aeyer.voidcore.ws.flow;

/**
 * Per-session in-flight new-user form. Held on {@link io.aeyer.voidcore.ws.VoidCoreSession}
 * while the user walks the multi-step register prompts (handle → password
 * → location → setup → found_via → fav_genres). Cleared once the
 * AuthService.register call succeeds or the user cancels with Esc.
 *
 * <p>{@code with*} mutators return new instances — record semantics; the
 * value object stays immutable while the session's reference is swapped.
 */
public record RegisterDraft(
        String handle,
        String password,
        String location,
        String setup,
        String foundVia,
        String favGenres
) {
    public static RegisterDraft empty() {
        return new RegisterDraft(null, null, null, null, null, null);
    }

    public RegisterDraft withHandle(String h)   { return new RegisterDraft(h, password, location, setup, foundVia, favGenres); }
    public RegisterDraft withPassword(String p) { return new RegisterDraft(handle, p, location, setup, foundVia, favGenres); }
    public RegisterDraft withLocation(String l) { return new RegisterDraft(handle, password, l, setup, foundVia, favGenres); }
    public RegisterDraft withSetup(String s)    { return new RegisterDraft(handle, password, location, s, foundVia, favGenres); }
    public RegisterDraft withFoundVia(String v) { return new RegisterDraft(handle, password, location, setup, v, favGenres); }
    public RegisterDraft withFavGenres(String g) { return new RegisterDraft(handle, password, location, setup, foundVia, g); }
}
