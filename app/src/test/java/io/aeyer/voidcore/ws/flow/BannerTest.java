package io.aeyer.voidcore.ws.flow;

import io.aeyer.voidcore.branding.BrandingProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BannerTest {

    @AfterEach
    void tearDown() {
        Banner.resetForTests();
    }

    @Test
    void minimalRowsUsesConfiguredBrandName() {
        Banner.configure(new BrandingProperties(
                "Nebula Station", "quiet signal", "deep-space relay", "night relay",
                "MORPH", "21:2/9", "555-1212", "9600"));

        String text = Banner.minimalRows("CHAT").getFirst().spans().getFirst().text();

        assertThat(text).isEqualTo("Nebula Station · CHAT");
    }

    @Test
    void fullRowsIncludeTaglineAndIdentityDetails() {
        Banner.configure(new BrandingProperties(
                "Nebula Station", "quiet signal", "deep-space relay", "night relay",
                "MORPH", "21:2/9", "555-1212", "9600"));

        var rows = Banner.rows();

        assertThat(rows.get(3).spans().getFirst().text()).contains("quiet signal");
        assertThat(rows.get(7).spans().getFirst().text()).contains("Nebula Station  -  deep-space relay");
        assertThat(rows.get(8).spans().getFirst().text()).contains("net 21:2/9");
        assertThat(rows.get(8).spans().getFirst().text()).contains("sysop MORPH");
        assertThat(rows.get(8).spans().getFirst().text()).contains("running VOIDcore/");
    }

    @Test
    void boxRowsShareTheSameWidth() {
        var rows = Banner.rows();

        assertThat(rows.get(6).spans().getFirst().text().length()).isEqualTo(66);
        assertThat(rows.get(7).spans().getFirst().text().length()).isEqualTo(66);
        assertThat(rows.get(8).spans().getFirst().text().length()).isEqualTo(66);
        assertThat(rows.get(9).spans().getFirst().text().length()).isEqualTo(66);
    }
}
