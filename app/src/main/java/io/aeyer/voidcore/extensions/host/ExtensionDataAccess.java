package io.aeyer.voidcore.extensions.host;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

/**
 * Extension-owned persistence surface.
 */
public interface ExtensionDataAccess {

    Optional<JsonNode> getGlobal(String key);

    Optional<JsonNode> getForCurrentUser(String key);

    void putGlobal(String key, JsonNode value);

    void putForCurrentUser(String key, JsonNode value);

    void deleteGlobal(String key);

    void deleteForCurrentUser(String key);

    List<String> globalKeys(String prefix, int limit);

    List<String> currentUserKeys(String prefix, int limit);
}
