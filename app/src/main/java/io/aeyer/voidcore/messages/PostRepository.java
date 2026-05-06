package io.aeyer.voidcore.messages;

import org.jooq.DSLContext;

import java.util.List;

import static io.aeyer.voidcore.jooq.Tables.POSTS;
import static io.aeyer.voidcore.jooq.Tables.USERS;

public class PostRepository {

    private final DSLContext dsl;
    private final ThreadRepository threads;

    public PostRepository(DSLContext dsl, ThreadRepository threads) {
        this.dsl = dsl;
        this.threads = threads;
    }

    /** Insert a post and bump the thread's last_post_at + post_count atomically. */
    public long insert(long threadId, long authorId, String body) {
        Long id = dsl.insertInto(POSTS)
                .set(POSTS.THREAD_ID, threadId)
                .set(POSTS.AUTHOR_ID, authorId)
                .set(POSTS.BODY, body)
                .returningResult(POSTS.ID)
                .fetchOne(POSTS.ID);
        if (id == null) throw new IllegalStateException("INSERT … RETURNING produced no id");
        threads.bumpAfterPost(threadId);
        return id;
    }

    public List<Post> listInThread(long threadId) {
        return dsl.select(POSTS.ID, POSTS.THREAD_ID, USERS.HANDLE, POSTS.BODY,
                        POSTS.POSTED_AT, POSTS.EDITED_AT, POSTS.IS_DELETED)
                .from(POSTS)
                .join(USERS).on(USERS.ID.eq(POSTS.AUTHOR_ID))
                .where(POSTS.THREAD_ID.eq(threadId))
                .and(POSTS.IS_DELETED.eq(false))
                .orderBy(POSTS.POSTED_AT)
                .fetch(r -> new Post(
                        r.get(POSTS.ID),
                        r.get(POSTS.THREAD_ID),
                        r.get(USERS.HANDLE),
                        r.get(POSTS.BODY),
                        r.get(POSTS.POSTED_AT),
                        r.get(POSTS.EDITED_AT),
                        r.get(POSTS.IS_DELETED)));
    }
}
