package io.aeyer.voidcore.documents;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

/** A row in the schemas table. Immutable; new versions are new rows. */
public record Schema(
        long id,
        String slug,
        int version,
        String label,
        String description,
        JsonNode definition,
        JsonNode presentation,
        SchemaStatus status,
        Long createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
