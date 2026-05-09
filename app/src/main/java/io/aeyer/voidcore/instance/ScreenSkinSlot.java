package io.aeyer.voidcore.instance;

/**
 * Anchored rectangle within a skinned region. Coordinates are
 * 1-based so instance-authored manifests can think in terminal rows
 * and columns directly.
 */
public record ScreenSkinSlot(String name,
                             Integer row,
                             Integer col,
                             Integer width,
                             Integer height) {
}
