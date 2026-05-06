package io.aeyer.voidcore.branding;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voidcore.branding")
public record BrandingProperties(
        String name,
        String tagline,
        String description,
        String subtagline,
        String sysopHandle,
        String fidoAddr,
        String dialNumber,
        String connectRate
) {

    public String displayName() {
        return nonBlank(name, "VOIDcore");
    }

    public String displayTagline() {
        return nonBlank(tagline, "typed-document terminal platform");
    }

    public String displayDescription() {
        return nonBlank(description, "a self-hosted typed-document community platform");
    }

    public String displaySubtagline() {
        return trimToEmpty(subtagline);
    }

    public String displaySysopHandle() {
        return nonBlank(sysopHandle, "sysop");
    }

    public String displayFidoAddr() {
        return nonBlank(fidoAddr, "23:495/0");
    }

    public String displayDialNumber() {
        return nonBlank(dialNumber, "0398675309");
    }

    public String displayConnectRate() {
        return nonBlank(connectRate, "14400/ARQ/V42BIS");
    }

    private static String nonBlank(String value, String fallback) {
        String trimmed = trimToEmpty(value);
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
