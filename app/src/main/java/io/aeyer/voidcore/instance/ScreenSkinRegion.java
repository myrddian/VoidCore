package io.aeyer.voidcore.instance;

import java.util.List;

/**
 * One skinned region (banner or main) backed by an ANSI/text asset.
 */
public record ScreenSkinRegion(String asset,
                               Integer minWidth,
                               Integer minHeight,
                               List<ScreenSkinSlot> slots) {
}
