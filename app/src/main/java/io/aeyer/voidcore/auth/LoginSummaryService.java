package io.aeyer.voidcore.auth;

import io.aeyer.voidcore.documents.DocumentKind;
import io.aeyer.voidcore.documents.DocumentRepository;
import io.aeyer.voidcore.messages.ThreadRepository;
import io.aeyer.voidcore.netmail.NetmailRepository;
import io.aeyer.voidcore.oneliners.OnelinerRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * Computes the {@link LoginSummary} per ticket #85. Pulls per-content-
 * type counts from the existing repos; no new tables. Pure read,
 * cheap to call inline during the post-auth pipeline.
 *
 * <p>Conditional on {@link DocumentRepository} so the bean drops out
 * cleanly in DB-less test contexts (mirroring sibling services).
 *
 * <p>The {@code since} parameter is the user's previous
 * {@code last_call_at} — captured BEFORE {@link
 * UserRepository#recordCall} bumps it on the current login. For
 * fresh registers (no prior call), pass {@code null} and the service
 * returns {@link LoginSummary#empty} so the screen doesn't appear
 * (showing "everything is new" to a user who just registered would
 * be noise, not signal).
 */
@Service
@ConditionalOnBean(DocumentRepository.class)
public class LoginSummaryService {

    private static final String TYPE_RELEASE = "release";

    private final DocumentRepository documents;
    private final ThreadRepository threads;
    private final OnelinerRepository oneliners;
    private final NetmailRepository netmail;

    public LoginSummaryService(DocumentRepository documents,
                               ThreadRepository threads,
                               OnelinerRepository oneliners,
                               NetmailRepository netmail) {
        this.documents = documents;
        this.threads = threads;
        this.oneliners = oneliners;
        this.netmail = netmail;
    }

    /**
     * Compute the deltas. Returns {@link LoginSummary#empty} for
     * null cutoffs (fresh registers — every doc is "new" so the
     * counts would be misleading).
     */
    public LoginSummary compute(long userId, OffsetDateTime since) {
        if (since == null) return LoginSummary.empty();
        long newArticles = documents.countByKindSince(DocumentKind.ARTICLE, since);
        long newReleases = documents.countByTypeSlugSince(TYPE_RELEASE, since);
        long newThreads = threads.countSince(since);
        long newOneliners = oneliners.countSince(since);
        // Unread netmail isn't time-bounded — the user's inbox carries
        // unread state across logins. Counted as a point-in-time signal.
        long unreadNetmail = netmail.countUnread(userId);
        return new LoginSummary(newArticles, newReleases, newThreads,
                newOneliners, unreadNetmail);
    }
}
