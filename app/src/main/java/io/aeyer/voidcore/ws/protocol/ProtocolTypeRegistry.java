package io.aeyer.voidcore.ws.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bidirectional type-name lookup for the protocol vocabulary, derived from
 * the {@link JsonSubTypes} annotations on {@link ClientMessage} and
 * {@link ServerMessage}. Single source of truth for wire type names — no
 * parallel string tables to drift.
 *
 * <p>Read at startup, immutable thereafter.
 */
@Component
public class ProtocolTypeRegistry {

    private final Map<String, Class<? extends ClientMessage>> clientByName;
    private final Map<Class<? extends ServerMessage>, String> serverByClass;

    public ProtocolTypeRegistry() {
        this.clientByName = readSubTypes(ClientMessage.class, ClientMessage.class);
        this.serverByClass = invert(readSubTypes(ServerMessage.class, ServerMessage.class));
    }

    /** Look up the concrete inbound class for a wire type name. */
    public Optional<Class<? extends ClientMessage>> clientClassFor(String typeName) {
        if (typeName == null) return Optional.empty();
        return Optional.ofNullable(clientByName.get(typeName));
    }

    /** Look up the wire type name for an outbound message. */
    public String serverTypeName(ServerMessage message) {
        Class<? extends ServerMessage> cls = message.getClass();
        String name = serverByClass.get(cls);
        if (name == null) {
            // Sealed types make this unreachable in practice, but defend
            // against future refactors that forget the @JsonSubTypes entry.
            throw new IllegalStateException(
                    "No @JsonSubTypes mapping for ServerMessage variant " + cls.getName());
        }
        return name;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T> Map<String, Class<? extends T>> readSubTypes(Class<?> annotated, Class<T> bound) {
        JsonSubTypes ann = annotated.getAnnotation(JsonSubTypes.class);
        if (ann == null) {
            throw new IllegalStateException("@JsonSubTypes missing on " + annotated.getName());
        }
        Map<String, Class<? extends T>> result = new HashMap<>();
        for (JsonSubTypes.Type t : ann.value()) {
            Class<?> raw = t.value();
            if (!bound.isAssignableFrom(raw)) {
                throw new IllegalStateException(
                        raw.getName() + " in @JsonSubTypes is not a subtype of " + bound.getName());
            }
            result.put(t.name(), (Class) raw);
        }
        return Collections.unmodifiableMap(result);
    }

    private static <K, V> Map<V, K> invert(Map<K, V> in) {
        Map<V, K> out = new HashMap<>(in.size());
        for (Map.Entry<K, V> e : in.entrySet()) {
            out.put(e.getValue(), e.getKey());
        }
        return Collections.unmodifiableMap(out);
    }
}
