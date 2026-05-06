package io.aeyer.voidcore.messages;

/**
 * A "conference" in BBS speak per SPEC §3 / §7.2. v1 has 4 seeded bases
 * (general / production / releases / meta).
 */
public record MessageBase(
        long id,
        String slug,
        String name,
        String description,
        int sortOrder,
        boolean locked
) {}
