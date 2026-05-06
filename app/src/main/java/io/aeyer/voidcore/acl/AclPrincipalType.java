package io.aeyer.voidcore.acl;

import java.util.Locale;

public enum AclPrincipalType {
    EVERYONE,
    AUTHENTICATED,
    USER,
    ROLE,
    SYSOP;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
