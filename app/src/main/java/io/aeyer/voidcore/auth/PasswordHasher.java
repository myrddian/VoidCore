package io.aeyer.voidcore.auth;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Argon2id password hashing per SPEC §5 and DECISIONS.md ADR-006.
 *
 * <p>Stores the encoded hash string (which embeds salt + parameters) in
 * {@code users.pw_hash}. Verification re-parses the encoded string, so a
 * future bump of the cost parameters does not invalidate existing rows —
 * verify continues to work, and a re-hash on next successful login can
 * roll the row forward.
 *
 * <p>{@link Argon2} from de.mkammerer is thread-safe (the instance holds
 * no mutable state); a single bean is shared across all callers.
 *
 * <p>Inputs are taken as {@code String} for ergonomics but are converted to
 * {@code char[]} before hashing so {@link Argon2#wipeArray(char[])} can
 * zero them out post-hash. Spring's WS handler holds the password as
 * a String briefly during deserialisation; full memory hygiene against a
 * heap dump would need a custom Jackson deserialiser feeding char[] —
 * out of scope for v1.
 */
@Component
public class PasswordHasher {

    private static final Logger log = LoggerFactory.getLogger(PasswordHasher.class);

    private final Argon2 argon2;
    private final Argon2Properties props;

    public PasswordHasher(Argon2Properties props) {
        this.argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);
        this.props = props;
        log.info("Argon2id configured: memory={}KB iterations={} parallelism={}",
                props.memoryKb(), props.iterations(), props.parallelism());
    }

    /** Hash a plaintext password. Returns the encoded {@code $argon2id$...} string. */
    public String hash(String plaintext) {
        if (plaintext == null) throw new IllegalArgumentException("password is null");
        char[] buf = plaintext.toCharArray();
        try {
            return argon2.hash(props.iterations(), props.memoryKb(), props.parallelism(), buf);
        } finally {
            argon2.wipeArray(buf);
        }
    }

    /**
     * Verify a plaintext password against a previously stored hash.
     * Returns {@code false} on any verification failure (including malformed
     * hash strings) — never throws.
     */
    public boolean verify(String storedHash, String plaintext) {
        if (storedHash == null || plaintext == null) return false;
        char[] buf = plaintext.toCharArray();
        try {
            return argon2.verify(storedHash, buf);
        } catch (Exception e) {
            // de.mkammerer can throw on malformed/unrecognised hashes. Treat
            // as "not verified" rather than letting the exception bubble.
            log.debug("verify failed: {}", e.toString());
            return false;
        } finally {
            argon2.wipeArray(buf);
        }
    }
}
