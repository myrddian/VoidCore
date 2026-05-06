package io.aeyer.voidcore.documents;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-built-in-type editable frontmatter field definitions per
 * SPEC-documents §3, scoped to v1 PR-4c.
 *
 * <p>Deprecated in favour of schema-driven metadata from the
 * {@code schemas} table. This remains as a fallback for the six
 * built-ins while the screen layer migrates.
 */
@Deprecated
public final class FrontmatterSchema {

    private FrontmatterSchema() {}

    private static final Map<BuiltinType, List<FrontmatterField>> BY_KIND =
            new EnumMap<>(BuiltinType.class);

    static {
        BY_KIND.put(BuiltinType.RELEASE, List.of(
                new FrontmatterField('A', "artist",         "artist       ",
                        FrontmatterField.Type.STRING),
                new FrontmatterField('Y', "year",           "year         ",
                        FrontmatterField.Type.INTEGER),
                new FrontmatterField('L', "label",          "label        ",
                        FrontmatterField.Type.STRING),
                new FrontmatterField('C', "catalog_number", "catalog #    ",
                        FrontmatterField.Type.STRING),
                new FrontmatterField('G', "genre",          "genre        ",
                        FrontmatterField.Type.STRING),
                new FrontmatterField('U', "external_url",   "external url ",
                        FrontmatterField.Type.URL)
        ));
        BY_KIND.put(BuiltinType.LINK, List.of(
                new FrontmatterField('U', "url",         "url          ",
                        FrontmatterField.Type.URL),
                new FrontmatterField('S', "summary",     "summary      ",
                        FrontmatterField.Type.STRING),
                new FrontmatterField('K', "source_kind", "source kind  ",
                        FrontmatterField.Type.STRING)
        ));
        BY_KIND.put(BuiltinType.ARTICLE, List.of(
                new FrontmatterField('S', "summary", "summary",
                        FrontmatterField.Type.STRING),
                new FrontmatterField('P', "pinned",  "pinned ",
                        FrontmatterField.Type.BOOLEAN)
        ));
        BY_KIND.put(BuiltinType.GLOSSARY, List.of(
                new FrontmatterField('T', "term", "term",
                        FrontmatterField.Type.STRING)
        ));
        BY_KIND.put(BuiltinType.HOWTO, List.of(
                new FrontmatterField('S', "summary", "summary",
                        FrontmatterField.Type.STRING),
                new FrontmatterField('O', "outcome", "outcome",
                        FrontmatterField.Type.STRING)
        ));
        BY_KIND.put(BuiltinType.NOTE, List.of(
                new FrontmatterField('S', "summary", "summary",
                        FrontmatterField.Type.STRING)
        ));
    }

    public static List<FrontmatterField> fieldsFor(BuiltinType kind) {
        return BY_KIND.getOrDefault(kind, List.of());
    }

    public static Optional<FrontmatterField> byLetter(BuiltinType kind, char letter) {
        for (FrontmatterField f : fieldsFor(kind)) {
            if (f.letter() == letter) return Optional.of(f);
        }
        return Optional.empty();
    }

    public static Optional<FrontmatterField> byKey(BuiltinType kind, String key) {
        for (FrontmatterField f : fieldsFor(kind)) {
            if (f.key().equals(key)) return Optional.of(f);
        }
        return Optional.empty();
    }

    @Deprecated
    public static List<FrontmatterField> fieldsFor(DocumentKind kind) {
        return fieldsFor(BuiltinType.valueOf(kind.name()));
    }

    @Deprecated
    public static Optional<FrontmatterField> byLetter(DocumentKind kind, char letter) {
        return byLetter(BuiltinType.valueOf(kind.name()), letter);
    }

    @Deprecated
    public static Optional<FrontmatterField> byKey(DocumentKind kind, String key) {
        return byKey(BuiltinType.valueOf(kind.name()), key);
    }
}
