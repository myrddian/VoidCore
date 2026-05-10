package io.aeyer.voidcore.instance;

/**
 * Startup-loaded skin manifest rooted under {@code /instance/skins/*}.
 */
public record ScreenSkinManifest(String screenName,
                                 java.util.List<String> screenNames,
                                 java.util.List<String> includeScreens,
                                 java.util.List<String> excludeScreens,
                                 ScreenSkinRegion banner,
                                 ScreenSkinRegion main,
                                 ScreenTreeSkin tree,
                                 String bannerPolicy) {
}
