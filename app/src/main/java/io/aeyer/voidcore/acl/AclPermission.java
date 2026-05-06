package io.aeyer.voidcore.acl;

import java.util.Locale;

public enum AclPermission {
    VIEW,
    POST,
    EDIT,
    MANAGE;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
