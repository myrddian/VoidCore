package io.aeyer.voidcore.instance;

import java.util.List;

/**
 * Shell-first tree skin contract for {@code ScreenApp}-style screens.
 * Tree skins decorate layout chrome without replacing the underlying UI tree.
 */
public record ScreenTreeSkin(String variant,
                             String headerTitle,
                             String headerRightAnnotation,
                             Integer paddingLeft,
                             String topAsset,
                             List<String> topAssets,
                             String leftAsset,
                             List<String> leftAssets,
                             String rightAsset,
                             List<String> rightAssets,
                             String bottomAsset,
                             List<String> bottomAssets,
                             String footerText,
                             String footerStyle) {
}
