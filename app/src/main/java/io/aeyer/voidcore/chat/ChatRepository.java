package io.aeyer.voidcore.chat;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.List;
import java.util.Optional;

import static io.aeyer.voidcore.jooq.Tables.USERS;

/**
 * jOOQ-backed CRUD for multi-room chat.
 */
public class ChatRepository {

    public static final String KIND_MSG = "msg";
    public static final String KIND_ACTION = "action";
    public static final String KIND_SYSTEM = "system";
    public static final String DEFAULT_ROOM_SLUG = "general";

    private static final Table<?> CHAT_ROOMS = DSL.table(DSL.name("chat_rooms"));
    private static final Field<Long> CHAT_ROOMS_ID = DSL.field(DSL.name("chat_rooms", "id"), Long.class);
    private static final Field<String> CHAT_ROOMS_SLUG = DSL.field(DSL.name("chat_rooms", "slug"), String.class);
    private static final Field<String> CHAT_ROOMS_LABEL = DSL.field(DSL.name("chat_rooms", "label"), String.class);
    private static final Field<Boolean> CHAT_ROOMS_IS_PRIVATE = DSL.field(DSL.name("chat_rooms", "is_private"), Boolean.class);
    private static final Field<Boolean> CHAT_ROOMS_IS_DM = DSL.field(DSL.name("chat_rooms", "is_dm"), Boolean.class);
    private static final Field<Boolean> CHAT_ROOMS_IS_ACTIVE = DSL.field(DSL.name("chat_rooms", "is_active"), Boolean.class);
    private static final Field<Integer> CHAT_ROOMS_SORT_ORDER = DSL.field(DSL.name("chat_rooms", "sort_order"), Integer.class);
    private static final Field<java.time.OffsetDateTime> CHAT_ROOMS_CREATED_AT =
            DSL.field(DSL.name("chat_rooms", "created_at"), java.time.OffsetDateTime.class);

    private static final Table<?> CHAT_ROOM_MEMBERS = DSL.table(DSL.name("chat_room_members"));
    private static final Field<Long> CHAT_ROOM_MEMBERS_ROOM_ID =
            DSL.field(DSL.name("chat_room_members", "room_id"), Long.class);
    private static final Field<Long> CHAT_ROOM_MEMBERS_USER_ID =
            DSL.field(DSL.name("chat_room_members", "user_id"), Long.class);

    private static final Table<?> CHAT_ROOM_MESSAGES = DSL.table(DSL.name("chat_room_messages"));
    private static final Field<Long> CHAT_ROOM_MESSAGES_ID = DSL.field(DSL.name("chat_room_messages", "id"), Long.class);
    private static final Field<Long> CHAT_ROOM_MESSAGES_ROOM_ID = DSL.field(DSL.name("chat_room_messages", "room_id"), Long.class);
    private static final Field<Long> CHAT_ROOM_MESSAGES_AUTHOR_ID = DSL.field(DSL.name("chat_room_messages", "author_id"), Long.class);
    private static final Field<String> CHAT_ROOM_MESSAGES_BODY = DSL.field(DSL.name("chat_room_messages", "body"), String.class);
    private static final Field<String> CHAT_ROOM_MESSAGES_KIND = DSL.field(DSL.name("chat_room_messages", "kind"), String.class);
    private static final Field<java.time.OffsetDateTime> CHAT_ROOM_MESSAGES_POSTED_AT =
            DSL.field(DSL.name("chat_room_messages", "posted_at"), java.time.OffsetDateTime.class);

    private final DSLContext dsl;

    public ChatRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long insert(long roomId, long authorId, String body, String kind) {
        Long id = dsl.insertInto(CHAT_ROOM_MESSAGES)
                .set(CHAT_ROOM_MESSAGES_ROOM_ID, roomId)
                .set(CHAT_ROOM_MESSAGES_AUTHOR_ID, authorId)
                .set(CHAT_ROOM_MESSAGES_BODY, body)
                .set(CHAT_ROOM_MESSAGES_KIND, kind)
                .returningResult(CHAT_ROOM_MESSAGES_ID)
                .fetchOne(CHAT_ROOM_MESSAGES_ID);
        if (id == null) throw new IllegalStateException("INSERT … RETURNING produced no id");
        return id;
    }

    public long createRoom(String slug, String label, boolean privateRoom) {
        return createRoom(slug, label, privateRoom, false);
    }

    public long createRoom(String slug, String label, boolean privateRoom, boolean directMessageRoom) {
        Integer nextSortOrder = dsl.select(DSL.coalesce(DSL.max(CHAT_ROOMS_SORT_ORDER), 0).add(1))
                .from(CHAT_ROOMS)
                .fetchOne(0, Integer.class);
        Long id = dsl.insertInto(CHAT_ROOMS)
                .set(CHAT_ROOMS_SLUG, slug)
                .set(CHAT_ROOMS_LABEL, label)
                .set(CHAT_ROOMS_IS_PRIVATE, privateRoom)
                .set(CHAT_ROOMS_IS_DM, directMessageRoom)
                .set(CHAT_ROOMS_IS_ACTIVE, true)
                .set(CHAT_ROOMS_SORT_ORDER, nextSortOrder == null ? 1 : nextSortOrder)
                .returningResult(CHAT_ROOMS_ID)
                .fetchOne(CHAT_ROOMS_ID);
        if (id == null) throw new IllegalStateException("INSERT … RETURNING produced no room id");
        return id;
    }

    public void addRoomMember(long roomId, long userId) {
        dsl.insertInto(CHAT_ROOM_MEMBERS)
                .set(CHAT_ROOM_MEMBERS_ROOM_ID, roomId)
                .set(CHAT_ROOM_MEMBERS_USER_ID, userId)
                .onConflictDoNothing()
                .execute();
    }

    public void setRoomActive(long roomId, boolean active) {
        dsl.update(CHAT_ROOMS)
                .set(CHAT_ROOMS_IS_ACTIVE, active)
                .where(CHAT_ROOMS_ID.eq(roomId))
                .execute();
    }

    public List<ChatRoom> listAllRooms() {
        return dsl.select(
                    CHAT_ROOMS_ID,
                    CHAT_ROOMS_SLUG,
                    CHAT_ROOMS_LABEL,
                    CHAT_ROOMS_IS_PRIVATE,
                    CHAT_ROOMS_IS_DM,
                    CHAT_ROOMS_IS_ACTIVE,
                    CHAT_ROOMS_SORT_ORDER)
                .from(CHAT_ROOMS)
                .orderBy(CHAT_ROOMS_SORT_ORDER.asc(), CHAT_ROOMS_LABEL.asc(), CHAT_ROOMS_SLUG.asc())
                .fetch(this::mapRoom);
    }

    public Optional<ChatRoom> findRoomBySlug(String slug) {
        if (slug == null || slug.isBlank()) return Optional.empty();
        return dsl.select(
                    CHAT_ROOMS_ID,
                    CHAT_ROOMS_SLUG,
                    CHAT_ROOMS_LABEL,
                    CHAT_ROOMS_IS_PRIVATE,
                    CHAT_ROOMS_IS_DM,
                    CHAT_ROOMS_IS_ACTIVE,
                    CHAT_ROOMS_SORT_ORDER)
                .from(CHAT_ROOMS)
                .where(CHAT_ROOMS_SLUG.eq(slug))
                .fetchOptional(this::mapRoom);
    }

    public Optional<ChatRoom> findRoomById(long id) {
        return dsl.select(
                    CHAT_ROOMS_ID,
                    CHAT_ROOMS_SLUG,
                    CHAT_ROOMS_LABEL,
                    CHAT_ROOMS_IS_PRIVATE,
                    CHAT_ROOMS_IS_DM,
                    CHAT_ROOMS_IS_ACTIVE,
                    CHAT_ROOMS_SORT_ORDER)
                .from(CHAT_ROOMS)
                .where(CHAT_ROOMS_ID.eq(id))
                .fetchOptional(this::mapRoom);
    }

    public List<ChatRoom> listActiveRooms() {
        return dsl.select(
                    CHAT_ROOMS_ID,
                    CHAT_ROOMS_SLUG,
                    CHAT_ROOMS_LABEL,
                    CHAT_ROOMS_IS_PRIVATE,
                    CHAT_ROOMS_IS_DM,
                    CHAT_ROOMS_IS_ACTIVE,
                    CHAT_ROOMS_SORT_ORDER)
                .from(CHAT_ROOMS)
                .where(CHAT_ROOMS_IS_ACTIVE.eq(true)
                        .and(CHAT_ROOMS_IS_DM.eq(false)))
                .orderBy(CHAT_ROOMS_SORT_ORDER.asc(), CHAT_ROOMS_LABEL.asc(), CHAT_ROOMS_SLUG.asc())
                .fetch(this::mapRoom);
    }

    public List<ChatRoom> listActiveDirectRoomsForUser(long userId) {
        Field<java.time.OffsetDateTime> lastPostedAt =
                DSL.max(CHAT_ROOM_MESSAGES_POSTED_AT).as("last_posted_at");
        return dsl.select(
                    CHAT_ROOMS_ID,
                    CHAT_ROOMS_SLUG,
                    CHAT_ROOMS_LABEL,
                    CHAT_ROOMS_IS_PRIVATE,
                    CHAT_ROOMS_IS_DM,
                    CHAT_ROOMS_IS_ACTIVE,
                    CHAT_ROOMS_SORT_ORDER,
                    lastPostedAt)
                .from(CHAT_ROOMS)
                .join(CHAT_ROOM_MEMBERS).on(CHAT_ROOM_MEMBERS_ROOM_ID.eq(CHAT_ROOMS_ID))
                .leftJoin(CHAT_ROOM_MESSAGES).on(CHAT_ROOM_MESSAGES_ROOM_ID.eq(CHAT_ROOMS_ID))
                .where(CHAT_ROOMS_IS_ACTIVE.eq(true)
                        .and(CHAT_ROOMS_IS_DM.eq(true))
                        .and(CHAT_ROOM_MEMBERS_USER_ID.eq(userId)))
                .groupBy(CHAT_ROOMS_ID,
                        CHAT_ROOMS_SLUG,
                        CHAT_ROOMS_LABEL,
                        CHAT_ROOMS_IS_PRIVATE,
                        CHAT_ROOMS_IS_DM,
                        CHAT_ROOMS_IS_ACTIVE,
                        CHAT_ROOMS_SORT_ORDER,
                        CHAT_ROOMS_CREATED_AT)
                .orderBy(lastPostedAt.desc().nullsLast(),
                        CHAT_ROOMS_CREATED_AT.desc(),
                        CHAT_ROOMS_LABEL.asc(),
                        CHAT_ROOMS_SLUG.asc())
                .fetch(this::mapRoom);
    }

    public Optional<ChatRoom> findLatestDirectRoomForUser(long userId) {
        return listActiveDirectRoomsForUser(userId).stream().findFirst();
    }

    public List<Long> listRoomParticipantIds(long roomId) {
        return dsl.select(CHAT_ROOM_MEMBERS_USER_ID)
                .from(CHAT_ROOM_MEMBERS)
                .where(CHAT_ROOM_MEMBERS_ROOM_ID.eq(roomId))
                .orderBy(CHAT_ROOM_MEMBERS_USER_ID.asc())
                .fetch(CHAT_ROOM_MEMBERS_USER_ID);
    }

    public Optional<ChatRoom> findActiveRoomBySlug(String slug) {
        if (slug == null || slug.isBlank()) return Optional.empty();
        return dsl.select(
                    CHAT_ROOMS_ID,
                    CHAT_ROOMS_SLUG,
                    CHAT_ROOMS_LABEL,
                    CHAT_ROOMS_IS_PRIVATE,
                    CHAT_ROOMS_IS_DM,
                    CHAT_ROOMS_IS_ACTIVE,
                    CHAT_ROOMS_SORT_ORDER)
                .from(CHAT_ROOMS)
                .where(CHAT_ROOMS_IS_ACTIVE.eq(true).and(CHAT_ROOMS_SLUG.eq(slug)))
                .fetchOptional(this::mapRoom);
    }

    /**
     * Most-recent {@code limit} chat messages with handle, oldest first
     * (chat reads top-to-bottom in time order — the inverse of the wall).
     */
    public List<ChatMessage> recent(String roomSlug, int limit) {
        // Fetch newest-N then reverse in Java so the list is oldest-first.
        return dsl.select(
                        CHAT_ROOM_MESSAGES_ID,
                        USERS.HANDLE,
                        CHAT_ROOM_MESSAGES_BODY,
                        CHAT_ROOM_MESSAGES_KIND,
                        CHAT_ROOM_MESSAGES_POSTED_AT)
                .from(CHAT_ROOM_MESSAGES)
                .join(CHAT_ROOMS).on(CHAT_ROOMS_ID.eq(CHAT_ROOM_MESSAGES_ROOM_ID))
                .join(USERS).on(USERS.ID.eq(CHAT_ROOM_MESSAGES_AUTHOR_ID))
                .where(CHAT_ROOMS_SLUG.eq(roomSlug).and(CHAT_ROOMS_IS_ACTIVE.eq(true)))
                .orderBy(CHAT_ROOM_MESSAGES_POSTED_AT.desc())
                .limit(limit)
                .fetch(r -> new ChatMessage(
                        r.get(CHAT_ROOM_MESSAGES_ID),
                        r.get(USERS.HANDLE),
                        r.get(CHAT_ROOM_MESSAGES_BODY),
                        r.get(CHAT_ROOM_MESSAGES_KIND),
                        r.get(CHAT_ROOM_MESSAGES_POSTED_AT)))
                .reversed();
    }

    private ChatRoom mapRoom(org.jooq.Record r) {
        return new ChatRoom(
                r.get(CHAT_ROOMS_ID),
                r.get(CHAT_ROOMS_SLUG),
                r.get(CHAT_ROOMS_LABEL),
                Boolean.TRUE.equals(r.get(CHAT_ROOMS_IS_PRIVATE)),
                Boolean.TRUE.equals(r.get(CHAT_ROOMS_IS_DM)),
                Boolean.TRUE.equals(r.get(CHAT_ROOMS_IS_ACTIVE)),
                r.get(CHAT_ROOMS_SORT_ORDER) == null ? 0 : r.get(CHAT_ROOMS_SORT_ORDER));
    }
}
