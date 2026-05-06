package io.aeyer.voidcore.documents;

import java.util.Locale;

public enum SchemaStatus {
    DRAFT,
    ACTIVE,
    DEPRECATED;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SchemaStatus parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("SchemaStatus.parse: null is not valid");
        }
        return SchemaStatus.valueOf(raw.toUpperCase(Locale.ROOT));
    }
}
