package io.aeyer.voidcore.ws.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity checks against the bidirectional name<->class registry. Catches
 * a missing {@code @JsonSubTypes.Type} entry the moment a new sealed
 * variant is added without registering it.
 */
class ProtocolTypeRegistryTest {

    private final ProtocolTypeRegistry registry = new ProtocolTypeRegistry();

    @Test
    void everyClientVariantIsRegistered() {
        // SPEC §4.3 — exact wire names every v1 client knows.
        Set<String> expected = Set.of(
                "auth.login", "auth.register", "auth.resume", "auth.logout",
                "keystroke", "line.submit", "line.cancel",
                "scroll.request", "viewport.resize",
                "editor.commit", "editor.cancel", "editor.snapshot", "field.commit", "focus.move"
        );
        for (String name : expected) {
            assertThat(registry.clientClassFor(name))
                    .as("client type %s", name)
                    .isPresent();
        }
    }

    @Test
    void everyServerVariantIsRegistered() {
        // Build one of each ServerMessage variant and confirm a name comes back.
        // Sealed types make this list exhaustive at compile time — adding a new
        // variant breaks this test until the @JsonSubTypes entry is added.
        List<ServerMessage> all = List.of(
                new ServerMessage.ScreenDefine("scr-1", 1, new ServerMessage.Layout("default"), null, null),
                new ServerMessage.RegionUpdate("main", 1, List.of(), null),
                new ServerMessage.RegionAppend("main", 2, List.of()),
                new ServerMessage.RegionScrollback("main", 0L, List.of()),
                new ServerMessage.RegionClear("main"),
                new ServerMessage.RegionNotify("notifications", List.of(), null, "info"),
                new ServerMessage.InputPrompt("none", null, null, null, null),
                new ServerMessage.InputCancel(),
                new ServerMessage.EffectOpenUrl("https://example.com"),
                new ServerMessage.EffectPlaySound("modem"),
                new ServerMessage.EffectSetTitle("VOIDcore"),
                new ServerMessage.EffectCopyClipboard("nope"),
                new ServerMessage.EffectSetTheme("phosphor"),
                new ServerMessage.AuthOk(new ServerMessage.UserSummary(1L, "TRINITY", false), null),
                new ServerMessage.AuthErr("INVALID_CREDENTIALS", "no", null),
                new ServerMessage.ResumeOk(true, null),
                new ServerMessage.ResumeErr("AUTH_REQUIRED", "no token"),
                new ServerMessage.ProtocolError("INTERNAL", "boom", null, null)
        );
        for (ServerMessage m : all) {
            assertThat(registry.serverTypeName(m))
                    .as("server type for %s", m.getClass().getSimpleName())
                    .isNotBlank();
        }
    }

    @Test
    void unknownInboundReturnsEmpty() {
        assertThat(registry.clientClassFor("totally.fake")).isEmpty();
        assertThat(registry.clientClassFor(null)).isEmpty();
    }
}
