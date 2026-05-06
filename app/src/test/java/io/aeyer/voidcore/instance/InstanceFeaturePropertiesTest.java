package io.aeyer.voidcore.instance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InstanceFeaturePropertiesTest {

    @Test
    void disabledFeaturesAcceptsLegacyAndCurrentAliases() {
        InstanceFeatureProperties props = new InstanceFeatureProperties(
                List.of("files", "doors", "netmail", "bulletins", "docs", ""));

        assertThat(props.disabledFeatures()).containsExactlyInAnyOrder(
                InstanceFeature.RELEASES,
                InstanceFeature.DOORS,
                InstanceFeature.VOIDMAIL,
                InstanceFeature.ANNOUNCEMENTS,
                InstanceFeature.INFO_DOCS);
    }
}
