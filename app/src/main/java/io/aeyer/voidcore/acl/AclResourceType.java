package io.aeyer.voidcore.acl;

import java.util.Locale;

public enum AclResourceType {
    CHAT_ROOM,
    ONELINER_WALL,
    POLL,
    VOIDMAIL_SYSTEM,
    MESSAGE_BASE,
    DOCUMENT;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
