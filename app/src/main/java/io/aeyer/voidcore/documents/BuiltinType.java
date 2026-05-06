package io.aeyer.voidcore.documents;

import java.util.Locale;
import java.util.Optional;

/**
 * Built-in document types known to the engine. Custom/operator-defined
 * types use raw slugs from {@link DocumentRow#typeSlug()} instead.
 */
public enum BuiltinType {
    NOTE("note"),
    ARTICLE("article"),
    HOWTO("howto"),
    LINK("link"),
    GLOSSARY("glossary"),
    RELEASE("release");

    private final String slug;

    BuiltinType(String slug) {
        this.slug = slug;
    }

    public String slug() {
        return slug;
    }

    public static Optional<BuiltinType> bySlug(String slug) {
        if (slug == null) return Optional.empty();
        for (BuiltinType type : values()) {
            if (type.slug.equals(slug.toLowerCase(Locale.ROOT))) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
