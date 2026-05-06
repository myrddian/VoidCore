package io.aeyer.voidcore.documents;

public class UnknownTypeException extends IllegalStateException {

    public UnknownTypeException(String typeSlug) {
        super("Unknown document type '" + typeSlug + "'");
    }

    public UnknownTypeException(String typeSlug, int typeVersion) {
        super("Unknown document type '" + typeSlug + "' at version " + typeVersion);
    }
}
