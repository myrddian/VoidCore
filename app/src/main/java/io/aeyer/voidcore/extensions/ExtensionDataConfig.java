package io.aeyer.voidcore.extensions;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps hosted custom-screen wiring bootable in DB-less contexts.
 *
 * <p>The real repository-backed service is still provided by {@code AuthConfig}
 * when the datasource/jOOQ slice is active. This fallback only exists so smoke
 * tests and non-DB bootstraps can still create the hosted runtime without
 * forcing JDBC infrastructure into those contexts.
 */
@Configuration
public class ExtensionDataConfig {

    @Bean
    @ConditionalOnMissingBean(ExtensionDataService.class)
    public ExtensionDataService extensionDataServiceFallback() {
        return ExtensionDataService.disabled();
    }
}
