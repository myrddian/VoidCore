package io.aeyer.voidcore.extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExtensionDataServiceTest {

    @Test
    void globalAccessNormalizesSlugAndKey() {
        ExtensionDataRepository repo = mock(ExtensionDataRepository.class);
        ObjectNode payload = new ObjectMapper().createObjectNode().put("ok", true);
        when(repo.get("aeyer", "global", "", "release:42")).thenReturn(Optional.of(payload));
        ExtensionDataService service = new ExtensionDataService(repo);

        Optional<com.fasterxml.jackson.databind.JsonNode> result =
                service.getGlobal(" AEYER ", " Release:42 ");

        assertThat(result).contains(payload);
        verify(repo).get("aeyer", "global", "", "release:42");
    }

    @Test
    void userWritesUseUserScopeKey() {
        ExtensionDataRepository repo = mock(ExtensionDataRepository.class);
        ObjectNode payload = new ObjectMapper().createObjectNode().put("lastViewed", 42);
        ExtensionDataService service = new ExtensionDataService(repo);

        service.putForUser("aeyer", 7L, "releases:last-viewed", payload);

        verify(repo).put("aeyer", "user", "7", "releases:last-viewed", payload);
    }

    @Test
    void blankKeysAreRejected() {
        ExtensionDataService service = new ExtensionDataService(mock(ExtensionDataRepository.class));

        assertThatThrownBy(() -> service.getGlobal("aeyer", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
    }

    @Test
    void disabledServiceBecomesSafeNoOp() {
        ExtensionDataService service = ExtensionDataService.disabled();
        ObjectNode payload = new ObjectMapper().createObjectNode().put("theme", "ws360");

        assertThat(service.getGlobal("aeyer", "theme")).isEmpty();
        assertThat(service.userKeys("aeyer", 7L, "", 10)).isEmpty();

        service.putGlobal("aeyer", "theme", payload);
        service.deleteForUser("aeyer", 7L, "theme");
    }
}
