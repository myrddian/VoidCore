package io.aeyer.voidcore.extensions.host;

import java.util.List;
import java.util.Optional;

/**
 * Read-only document access for extension-backed screens.
 */
public interface ExtensionDocuments {

    Optional<ExtensionDocumentView> byId(long id);

    Optional<ExtensionDocumentView> bySlug(String slug);

    List<ExtensionDocumentView> listByType(String typeSlug, int limit);
}
