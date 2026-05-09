package io.aeyer.voidcore.instance;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@ConfigurationProperties(prefix = "voidcore.instance")
public record InstanceFeatureProperties(List<String> disabledScreens, String root) {

    public Set<InstanceFeature> disabledFeatures() {
        LinkedHashSet<InstanceFeature> out = new LinkedHashSet<>();
        if (disabledScreens == null) return out;
        for (String token : disabledScreens) {
            InstanceFeature feature = InstanceFeature.fromConfigToken(token);
            if (feature != null) out.add(feature);
        }
        return Set.copyOf(out);
    }

    public Path rootPath() {
        String configured = root == null || root.isBlank() ? "/instance" : root.trim();
        return Path.of(configured);
    }

    public Path skinsRoot() {
        return rootPath().resolve("skins");
    }

    public Path themesRoot() {
        return rootPath().resolve("themes");
    }

    public Path extensionsRoot() {
        return rootPath().resolve("extensions");
    }
}
