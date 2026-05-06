package io.aeyer.voidcore.documents;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Typed helper for {@code type_slug='release'} frontmatter.
 *
 * <p>The documents substrate keeps release metadata in JSONB; this
 * record gives screens a small, explicit decoding surface while the
 * broader file/release extraction is still in flight.
 */
public record ReleaseFrontmatter(
        String filename,
        long sizeBytes,
        int downloadCount,
        String externalUrl,
        Short year,
        String artist,
        String label,
        String catalogNumber,
        String genre
) {

    public static ReleaseFrontmatter from(JsonNode frontmatter) {
        JsonNode fm = frontmatter == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : frontmatter;
        return new ReleaseFrontmatter(
                textOrNull(fm, "filename"),
                longOrDefault(fm, "size_bytes", 0L),
                intOrDefault(fm, "download_count", 0),
                textOrNull(fm, "external_url"),
                shortOrNull(fm, "year"),
                textOrNull(fm, "artist"),
                textOrNull(fm, "label"),
                textOrNull(fm, "catalog_number"),
                textOrNull(fm, "genre"));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static long longOrDefault(JsonNode node, String field, long fallback) {
        JsonNode value = node.path(field);
        if (value.canConvertToLong()) return value.longValue();
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText().trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int intOrDefault(JsonNode node, String field, int fallback) {
        JsonNode value = node.path(field);
        if (value.canConvertToInt()) return value.intValue();
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText().trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Short shortOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (value.canConvertToInt()) return (short) value.intValue();
        if (value.isTextual()) {
            try {
                return Short.parseShort(value.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
