package io.aeyer.voidcore.instance;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InstanceFeaturePropertiesTest {

    @Test
    void disabledFeaturesAcceptsLegacyAndCurrentAliases() {
        InstanceFeatureProperties props = new InstanceFeatureProperties(
                List.of("files", "doors", "netmail", "bulletins", "docs", ""),
                null);

        assertThat(props.disabledFeatures()).containsExactlyInAnyOrder(
                InstanceFeature.FILES,
                InstanceFeature.DOORS,
                InstanceFeature.VOIDMAIL,
                InstanceFeature.ANNOUNCEMENTS,
                InstanceFeature.INFO_DOCS);
    }

    @Test
    void instanceRootsDefaultUnderConfiguredRoot() {
        InstanceFeatureProperties props = new InstanceFeatureProperties(List.of(), "/srv/voidcore-instance");

        assertThat(props.rootPath()).hasToString("/srv/voidcore-instance");
        assertThat(props.skinsRoot()).hasToString("/srv/voidcore-instance/skins");
        assertThat(props.themesRoot()).hasToString("/srv/voidcore-instance/themes");
        assertThat(props.extensionsRoot()).hasToString("/srv/voidcore-instance/extensions");
    }
}
