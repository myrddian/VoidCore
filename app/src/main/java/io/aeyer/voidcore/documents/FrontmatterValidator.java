package io.aeyer.voidcore.documents;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application-layer JSON Schema validator for document frontmatter.
 * Schemas are immutable/versioned, so compiled instances are safe to cache.
 */
public class FrontmatterValidator {

    private static final JsonSchemaFactory FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private final ConcurrentHashMap<String, JsonSchema> compiled = new ConcurrentHashMap<>();

    public void validate(Schema schema, JsonNode frontmatter) {
        if (schema == null) {
            throw new IllegalArgumentException("schema must not be null");
        }
        JsonNode effectiveFrontmatter = frontmatter == null
                ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                : frontmatter;
        String key = schema.slug() + "@" + schema.version();
        JsonSchema compiledSchema = compiled.computeIfAbsent(key,
                ignored -> FACTORY.getSchema(schema.definition()));
        Set<ValidationMessage> errors = compiledSchema.validate(effectiveFrontmatter);
        if (!errors.isEmpty()) {
            throw new InvalidFrontmatterException(schema.slug(), schema.version(), errors);
        }
    }
}
