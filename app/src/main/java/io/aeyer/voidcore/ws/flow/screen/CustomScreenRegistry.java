package io.aeyer.voidcore.ws.flow.screen;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.voidcore.extensions.ExtensionManifestDiscovery;
import io.aeyer.voidcore.extensions.ExtensionScreenRegistration;
import io.aeyer.voidcore.extensions.ExtensionScreenRuntime;
import io.aeyer.voidcore.extensions.PlaceholderExtensionScreenRuntime;
import io.aeyer.voidcore.instance.InstanceFeatureProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Startup registry of named custom screens.
 *
 * <p>Invalid registrations are skipped with diagnostics rather than failing
 * the app. This keeps one bad extension from blocking core startup.
 */
@Component
public class CustomScreenRegistry {

    private static final Logger log = LoggerFactory.getLogger(CustomScreenRegistry.class);
    private static final Pattern VALID_NAME =
            Pattern.compile("[a-z0-9][a-z0-9/._-]*");

    private final Map<String, CustomScreenProvider> providersByName;

    @Autowired
    public CustomScreenRegistry(List<CustomScreenProvider> providers,
                                ExtensionManifestDiscovery discovery,
                                ExtensionScreenRuntime runtime) {
        this.providersByName = buildIndex(
                providers,
                discoverManifestProviders(discovery, runtime));
    }

    public CustomScreenRegistry(List<CustomScreenProvider> providers,
                                ObjectMapper json,
                                InstanceFeatureProperties instance) {
        this(providers, json, instance, new PlaceholderExtensionScreenRuntime());
    }

    CustomScreenRegistry(List<CustomScreenProvider> providers,
                         ObjectMapper json,
                         InstanceFeatureProperties instance,
                         ExtensionScreenRuntime runtime) {
        this.providersByName = buildIndex(
                providers,
                discoverManifestProviders(new ExtensionManifestDiscovery(json, instance), runtime));
    }

    CustomScreenRegistry(List<CustomScreenProvider> providers) {
        this.providersByName = buildIndex(providers, List.of());
    }

    private CustomScreenRegistry(Map<String, CustomScreenProvider> providersByName) {
        this.providersByName = Map.copyOf(providersByName);
    }

    public static CustomScreenRegistry empty() {
        return new CustomScreenRegistry(Map.of());
    }

    public Optional<Screen> create(String screenName) {
        String key = normalize(screenName);
        CustomScreenProvider provider = providersByName.get(key);
        return provider == null ? Optional.empty() : Optional.ofNullable(provider.createScreen());
    }

    public boolean contains(String screenName) {
        return providersByName.containsKey(normalize(screenName));
    }

    public Set<String> names() {
        return providersByName.keySet();
    }

    private static Map<String, CustomScreenProvider> buildIndex(Collection<CustomScreenProvider> codeProviders,
                                                                Collection<CustomScreenProvider> manifestProviders) {
        Map<String, CustomScreenProvider> indexed = new LinkedHashMap<>();
        for (CustomScreenProvider provider : codeProviders) {
            register(indexed, provider);
        }
        for (CustomScreenProvider provider : manifestProviders) {
            register(indexed, provider);
        }
        return Map.copyOf(indexed);
    }

    private static void register(Map<String, CustomScreenProvider> indexed,
                                 CustomScreenProvider provider) {
        if (provider == null) return;
        String raw = provider.screenName();
        String key;
        try {
            key = normalize(raw);
        } catch (IllegalArgumentException e) {
            log.warn("skipping custom screen registration with invalid name '{}': {}",
                    raw, e.getMessage());
            return;
        }
        if (!VALID_NAME.matcher(key).matches()) {
            log.warn("skipping custom screen '{}': name must match {}", key, VALID_NAME.pattern());
            return;
        }
        if (indexed.containsKey(key)) {
            log.warn("skipping duplicate custom screen registration '{}'", key);
            return;
        }
        indexed.put(key, provider);
    }

    private static List<CustomScreenProvider> discoverManifestProviders(ExtensionManifestDiscovery discovery,
                                                                        ExtensionScreenRuntime runtime) {
        return discovery.discover().stream()
                .map(registration -> new ManifestCustomScreenProvider(registration, runtime))
                .map(provider -> (CustomScreenProvider) provider)
                .toList();
    }

    private static String normalize(String screenName) {
        if (screenName == null) throw new IllegalArgumentException("screenName must not be null");
        String normalized = screenName.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) throw new IllegalArgumentException("screenName must not be blank");
        return normalized;
    }

    private record ManifestCustomScreenProvider(ExtensionScreenRegistration registration,
                                                ExtensionScreenRuntime runtime) implements CustomScreenProvider {
        @Override
        public String screenName() {
            return registration.screenName();
        }

        @Override
        public Screen createScreen() {
            return runtime.createScreen(registration);
        }
    }
}
