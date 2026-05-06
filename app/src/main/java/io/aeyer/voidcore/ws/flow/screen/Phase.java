package io.aeyer.voidcore.ws.flow.screen;

/**
 * Identifier for the user's current screen / phase. Persisted per-session
 * (see {@code sessions.current_screen} in SPEC §3) and used by
 * {@link io.aeyer.voidcore.ws.flow.ScreenRouter} to dispatch input events to
 * the right {@link Screen} implementation.
 *
 * <p>Extracted from {@code ScreenRouter} as part of the v1.4 refactor
 * (ADR-025, SPEC-screens.md). Closed enum — third-party screen plugins
 * are tracked as a future v1.6+ concern; v1.4 keeps the phase set
 * compile-time exhaustive so the dispatcher can sanity-check that every
 * phase has a handler at startup.
 */
public enum Phase {
    LOGIN_HANDLE, LOGIN_PASSWORD,
    REGISTER_HANDLE, REGISTER_PASSWORD,
    REGISTER_LOCATION, REGISTER_SETUP,
    REGISTER_FOUND_VIA, REGISTER_FAV_GENRES,
    MENU,
    BULLETINS_LIST, BULLETINS_VIEW,
    RELEASES_LIST, RELEASES_VIEW,
    /** v1.5 PR-5: documents faceted-nav default landing — total count,
     *  recent N docs, facet pickers, [N]ew (coming soon), [/]search
     *  (coming soon), [Q]back. SPEC-documents §4.2. */
    DOCS_HUB,
    /** v1.5 PR-5: kind facet picker. Numbered selection extends the
     *  filter and re-enters {@link #DOCS_RESULTS}. */
    DOCS_FACET_KIND,
    /** v1.5 PR-5: tag facet picker (top-N by count within current filter). */
    DOCS_FACET_TAG,
    /** v1.5 PR-5: author facet picker (joined to users.handle). */
    DOCS_FACET_BY,
    /** v1.5 PR-5: year facet picker. Month picker is deferred — v1
     *  shows year-only buckets. */
    DOCS_FACET_WHEN,
    /** v1.5 PR-5: filtered list view — breadcrumb, paginated rows,
     *  narrow-further pickers (only multi-value facets), [..] back. */
    DOCS_RESULTS,
    /** v1.5 PR-6: free-form search / filter expression line prompt.
     *  Submit calls {@code FilterExpressionParser} and replaceTopAndEnter
     *  to {@code DOCS_RESULTS}. SPEC-documents §4.5. */
    DOCS_SEARCH_PROMPT,
    /** v1.5 PR-7: backlinks list — docs that link TO the current
     *  document via {@code ~slug} references. Pushed by [B] in
     *  {@code DOCUMENT_VIEW}. SPEC-documents §6. */
    DOCS_BACKLINKS,
    /** v1.6 unified document screen — replaces DOCUMENT_VIEW + DOCUMENT_EDIT_*
     *  + DOCS_NEW_*. Hosts the modal editor + inline metadata fields. */
    DOCUMENT_SCREEN,
    /** Ticket #85: post-auth "what's new since you were last here"
     *  summary. Pushed by AuthFinaliser on fresh login when the
     *  computed deltas are non-empty; dismiss with [Enter]/[Q]
     *  pops back to the menu underneath. Letter shortcuts jump to
     *  the relevant content surface. */
    LOGIN_SUMMARY,
    /** Ticket #91: list of users this user is watching. */
    WATCH_LIST,
    /** Ticket #87: recent activity feed (rolling event log). */
    ACTIVITY_FEED,
    /** Ticket #89: list of achievements unlocked by current user. */
    ACHIEVEMENTS,
    /** BBS-native achievements catalogue (push from {@link #ACHIEVEMENTS}). */
    ACHIEVEMENTS_BBS,
    /** Door picker — one numbered entry per door with at least one
     *  achievement on the user's profile or in the catalogue. */
    ACHIEVEMENTS_DOORS,
    /** Catalogue for a specific door, keyed by
     *  {@code VoidCoreSession.selectedAchievementDoorId}. */
    ACHIEVEMENTS_DOOR,
    /** Ticket #92: sysop audit-log browse screen. */
    SYSOP_AUDIT,
    /** Ticket #93: list of recent polls (open + closed). */
    POLLS_LIST,
    /** Ticket #93: single poll detail with vote keystroke. */
    POLL_VIEW,
    /** TUI-framework: unified new-poll wizard (WizardFormApp). */
    POLL_NEW,
    ONELINERS,
    DOORS_MENU, DOOR_SESSION,
    CHAT, CHAT_DIRECTS, CHAT_DIRECT_NEW, CHAT_ROOM,
    NETMAIL_INBOX, NETMAIL_OUTBOX, NETMAIL_READ,
    /** N1: unified 3-step wizard replacing NETMAIL_COMPOSE_TO + _SUBJECT + _BODY. */
    NETMAIL_COMPOSE,
    INFO_VIEW,
    // Sysop tools (#40) — entered with [S] from menu when is_sysop=true
    SYSOP_MENU,
    SYSOP_SCREEN_TOGGLES,
    SYSOP_USERS, SYSOP_USER,
    SYSOP_USER_BAN_REASON, SYSOP_USER_RESET_PW,
    SYSOP_BULLETINS, SYSOP_BULLETIN_NEW, SYSOP_BULLETIN_EDIT,
    SYSOP_RELEASES,
    SYSOP_RELEASE_EDIT,
    SYSOP_RELEASE_DELETE_CONFIRM,
    SYSOP_RELEASE_NEW,
    SYSOP_CHAT_ROOMS,
    SYSOP_CHAT_ROOM_NEW,
    SYSOP_CHAT_ROOM_USERS,
    SYSOP_CHAT_ROOM_USER,
    SYSOP_ROLES,
    SYSOP_ROLE_NEW,
    SYSOP_ROLE,
    SYSOP_ROLE_SUMMARY,
    SYSOP_ROLE_ONELINERS,
    SYSOP_ROLE_VOIDMAIL,
    SYSOP_ROLE_POLLS,
    SYSOP_ROLE_POLL,
    SYSOP_ROLE_CHAT_ROOMS,
    SYSOP_ROLE_CHAT_ROOM,
    SYSOP_ROLE_MESSAGE_BASES,
    SYSOP_ROLE_MESSAGE_BASE,
    SYSOP_ROLE_DOCUMENTS,
    SYSOP_ROLE_RELEASES,
    SYSOP_ROLE_ANNOUNCEMENTS,
    SYSOP_ROLE_DOCUMENT,
    SYSOP_USER_ROLES,
    SYSOP_USER_CHAT_ROOMS,
    SYSOP_USER_CHAT_ROOM,
    SYSOP_USER_PERMISSIONS,
    SYSOP_BROADCAST,
    // Forum (#36-#39): bases / threads / posts
    BASES_LIST,
    THREADS_LIST,
    THREAD_VIEW,
    /** C1: unified 2-step wizard replacing COMPOSE_THREAD_SUBJECT + COMPOSE_THREAD_BODY. */
    COMPOSE_THREAD,
    /** C2: single-Editor ScreenApp replacing COMPOSE_POST_BODY. */
    COMPOSE_POST,
    GOODBYE
}
