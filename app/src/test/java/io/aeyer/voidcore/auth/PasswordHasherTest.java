package io.aeyer.voidcore.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level tests against the Argon2id wrapper. Cost parameters are pushed
 * to the floor (1024 KB / 1 iter / 1 par) so the suite stays fast — the
 * deploy-box calibration to ~250ms/hash is an operational concern, not a
 * unit-test invariant. SPEC §13's 200–400ms acceptance is a per-deploy
 * tune, not a per-build assertion.
 */
class PasswordHasherTest {

    private static final Argon2Properties FAST = new Argon2Properties(1024, 1, 1);

    @Test
    void hashIsArgon2idEncoded() {
        String h = new PasswordHasher(FAST).hash("correct horse battery staple");
        assertThat(h).startsWith("$argon2id$");
    }

    @Test
    void verifyAcceptsCorrectPassword() {
        PasswordHasher hasher = new PasswordHasher(FAST);
        String h = hasher.hash("hunter2-but-longer-please");
        assertThat(hasher.verify(h, "hunter2-but-longer-please")).isTrue();
    }

    @Test
    void verifyRejectsWrongPassword() {
        PasswordHasher hasher = new PasswordHasher(FAST);
        String h = hasher.hash("the-real-password");
        assertThat(hasher.verify(h, "the-fake-password")).isFalse();
    }

    @Test
    void hashesAreSaltedAndUnique() {
        PasswordHasher hasher = new PasswordHasher(FAST);
        String a = hasher.hash("same-password");
        String b = hasher.hash("same-password");
        // Random per-hash salt → encoded strings differ even for the same input.
        assertThat(a).isNotEqualTo(b);
        // Both still verify against the same plaintext.
        assertThat(hasher.verify(a, "same-password")).isTrue();
        assertThat(hasher.verify(b, "same-password")).isTrue();
    }

    @Test
    void encodedHashCarriesConfiguredParameters() {
        // Use distinct values so the assertion is meaningful.
        PasswordHasher hasher = new PasswordHasher(new Argon2Properties(2048, 2, 1));
        String h = hasher.hash("any-password-will-do");
        // Argon2 encoded format: $argon2id$v=N$m=<KB>,t=<iters>,p=<par>$salt$hash
        assertThat(h).contains("m=2048").contains("t=2").contains("p=1");
    }

    @Test
    void verifyIsResilientToMalformedHashes() {
        PasswordHasher hasher = new PasswordHasher(FAST);
        assertThat(hasher.verify(null, "x")).isFalse();
        assertThat(hasher.verify("", "x")).isFalse();
        assertThat(hasher.verify("not-an-argon2-string", "x")).isFalse();
        assertThat(hasher.verify("$argon2id$totally-malformed", "x")).isFalse();
    }

    @Test
    void verifyIsResilientToNullPassword() {
        PasswordHasher hasher = new PasswordHasher(FAST);
        String h = hasher.hash("ok");
        assertThat(hasher.verify(h, null)).isFalse();
    }
}
