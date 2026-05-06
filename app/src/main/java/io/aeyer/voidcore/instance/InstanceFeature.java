package io.aeyer.voidcore.instance;

import java.util.Arrays;

/**
 * System-wide screen families that can be enabled / disabled per
 * running BBS instance.
 */
public enum InstanceFeature {
    ANNOUNCEMENTS("announcements", "Announcements"),
    FILES("files", "Files"),
    INFO_DOCS("info_docs", "Info / docs"),
    MESSAGE_BOARD("message_board", "Message board"),
    ONELINERS("oneliners", "One-liners"),
    CHAT("chat", "Chat"),
    VOIDMAIL("voidmail", "VoidMail"),
    POLLS("polls", "Polls"),
    DOORS("doors", "Doors");

    private final String slug;
    private final String label;

    InstanceFeature(String slug, String label) {
        this.slug = slug;
        this.label = label;
    }

    public String slug() { return slug; }
    public String label() { return label; }

    public static InstanceFeature fromSlug(String slug) {
        return Arrays.stream(values())
                .filter(value -> value.slug.equals(slug))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown feature slug: " + slug));
    }

    public static InstanceFeature fromConfigToken(String token) {
        if (token == null) return null;
        return switch (token.trim().toLowerCase()) {
            case "", "announcement", "announcements", "bulletin", "bulletins" -> ANNOUNCEMENTS;
            case "release", "releases", "file", "files" -> FILES;
            case "info", "docs", "documents", "doc", "info_docs" -> INFO_DOCS;
            case "message", "messages", "message_board", "message-board", "board", "boards" -> MESSAGE_BOARD;
            case "oneliner", "oneliners", "one-liners", "one_liners" -> ONELINERS;
            case "chat", "chats", "channel", "channels" -> CHAT;
            case "voidmail", "netmail", "mail" -> VOIDMAIL;
            case "poll", "polls" -> POLLS;
            case "door", "doors" -> DOORS;
            default -> null;
        };
    }
}
