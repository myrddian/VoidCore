package io.aeyer.voidcore.documents;

import com.networknt.schema.ValidationMessage;

import java.util.List;
import java.util.Set;

public class InvalidFrontmatterException extends IllegalArgumentException {

    private final String typeSlug;
    private final int typeVersion;
    private final List<String> errors;

    public InvalidFrontmatterException(String typeSlug,
                                       int typeVersion,
                                       Set<ValidationMessage> errors) {
        super("Frontmatter failed validation for " + typeSlug + " v" + typeVersion
                + ": " + errors.stream().map(ValidationMessage::getMessage)
                .sorted()
                .findFirst()
                .orElse("unknown validation error"));
        this.typeSlug = typeSlug;
        this.typeVersion = typeVersion;
        this.errors = errors.stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .toList();
    }

    public String typeSlug() {
        return typeSlug;
    }

    public int typeVersion() {
        return typeVersion;
    }

    public List<String> errors() {
        return errors;
    }
}
