package io.aeyer.voidcore.ws.flow;

import io.aeyer.voidcore.branding.BrandingProperties;
import org.springframework.stereotype.Component;

@Component
public class BannerBrandingInitializer {

    public BannerBrandingInitializer(BrandingProperties branding) {
        Banner.configure(branding);
    }
}
