package io.aeyer.voidcore.auth;

/**
 * Per-content-type "what's new since you last logged in" counts
 * (ticket #85). All counts are non-negative; zero counts are still
 * carried so the screen can decide how to render. Empty summary
 * (every count zero) → screen suppressed entirely.
 *
 * @param newArticles    pinned + unpinned articles since cutoff
 *                       (these are the v1.5 substrate equivalent
 *                       of "bulletins")
 * @param newReleases    new {@code documents.type_slug='release'} since cutoff
 * @param newThreads     forum threads created since cutoff
 * @param newOneliners   oneliners posted since cutoff
 * @param unreadNetmail  unread netmail addressed to this user
 *                       (point-in-time count, not strictly
 *                       "since last login" — netmail unread state
 *                       persists across logins)
 */
public record LoginSummary(
        long newArticles,
        long newReleases,
        long newThreads,
        long newOneliners,
        long unreadNetmail
) {
    public static LoginSummary empty() {
        return new LoginSummary(0, 0, 0, 0, 0);
    }

    /** True if every count is zero — caller should skip the screen. */
    public boolean isEmpty() {
        return newArticles == 0
                && newReleases == 0
                && newThreads == 0
                && newOneliners == 0
                && unreadNetmail == 0;
    }

    /** Sum of all counts — useful for header rendering. */
    public long total() {
        return newArticles + newReleases + newThreads
                + newOneliners + unreadNetmail;
    }
}
