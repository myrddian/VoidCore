package io.aeyer.voidcore.extensions.host;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Read-only document projection for extensions.
 */
public record ExtensionDocumentView(long id,
                                    String slug,
                                    String title,
                                    String typeSlug,
                                    int typeVersion,
                                    int rev,
                                    String body,
                                    JsonNode frontmatter,
                                    List<String> tags,
                                    long authorId,
                                    String visibility,
                                    String status,
                                    OffsetDateTime createdAt,
                                    OffsetDateTime updatedAt) {
}
