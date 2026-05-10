package io.aeyer.voidcore.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.voidcore.ws.flow.layout.Element;
import io.aeyer.voidcore.ws.protocol.ServerMessage.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Startup-loaded ANSI skin registry. Invalid skins are skipped so one
 * bad asset doesn't break the whole instance.
 */
@Component
public class ScreenSkinRegistry {

    private static final Logger log = LoggerFactory.getLogger(ScreenSkinRegistry.class);

    private final LoadedSkins skins;

    @Autowired
    public ScreenSkinRegistry(ObjectMapper json, InstanceFeatureProperties instance) {
        this.skins = discover(json, instance.skinsRoot());
    }

    ScreenSkinRegistry(Map<String, LoadedSkin> exactSkins) {
        this.skins = new LoadedSkins(Map.copyOf(exactSkins), List.of());
    }

    public List<Row> renderBannerOrDefault(String screenName, List<Row> fallback) {
        return render(screenName, RegionKind.BANNER, fallback, Map.of());
    }

    public ScreenBannerPolicy bannerPolicyFor(String screenName) {
        LoadedSkin skin = resolve(screenName);
        return skin == null ? ScreenBannerPolicy.AUTO_COMPACT : skin.bannerPolicy();
    }

    public List<Row> renderMainOrDefault(String screenName,
                                         List<Row> fallback,
                                         Map<String, List<Row>> slots) {
        return render(screenName, RegionKind.MAIN, fallback, slots);
    }

    public Element renderTreeOrDefault(String screenName, Element fallback) {
        LoadedSkin skin = resolve(screenName);
        if (skin == null || skin.tree() == null || fallback == null) return fallback;
        return wrapTree(skin.tree(), fallback);
    }

    private List<Row> render(String screenName,
                             RegionKind kind,
                             List<Row> fallback,
                             Map<String, List<Row>> slots) {
        LoadedSkin skin = resolve(screenName);
        if (skin == null) return fallback;
        LoadedRegion region = kind == RegionKind.BANNER ? skin.banner() : skin.main();
        if (region == null) return fallback;
        try {
            AnsiArtSupport.Canvas canvas = region.canvas().copy();
            for (Map.Entry<String, List<Row>> entry : slots.entrySet()) {
                LoadedSlot slot = region.slots().get(entry.getKey());
                if (slot != null) overlay(canvas, slot, entry.getValue());
            }
            return AnsiArtSupport.toRows(canvas);
        } catch (Exception e) {
            log.warn("skin render failed for {} {}: {}", screenName, kind.name().toLowerCase(), e.toString());
            return fallback;
        }
    }

    private static void overlay(AnsiArtSupport.Canvas target, LoadedSlot slot, List<Row> rows) {
        AnsiArtSupport.Canvas source = AnsiArtSupport.fromRows(rows);
        int maxRow = Math.min(slot.height(), source.height());
        for (int row = 0; row < maxRow; row++) {
            List<AnsiArtSupport.Cell> cells = source.row(row);
            int maxCol = Math.min(slot.width(), cells.size());
            for (int col = 0; col < maxCol; col++) {
                target.put(slot.row() + row, slot.col() + col, cells.get(col));
            }
        }
    }

    private LoadedSkin resolve(String screenName) {
        return skins.resolve(normalize(screenName));
    }

    private static LoadedSkins discover(ObjectMapper json, Path skinsRoot) {
        if (!Files.isDirectory(skinsRoot)) {
            return new LoadedSkins(Map.of(), List.of());
        }
        LinkedHashMap<String, LoadedSkin> out = new LinkedHashMap<>();
        java.util.ArrayList<PatternSkin> patterns = new java.util.ArrayList<>();
        try (var dirs = Files.list(skinsRoot)) {
            dirs.filter(Files::isDirectory)
                    .sorted()
                    .forEach(dir -> readSkin(json, dir).ifPresent(targeted -> register(out, patterns, targeted, dir)));
        } catch (Exception e) {
            log.warn("failed scanning screen skins under {}: {}", skinsRoot, e.toString());
        }
        return new LoadedSkins(Map.copyOf(out), List.copyOf(patterns));
    }

    private static Optional<TargetedSkin> readSkin(ObjectMapper json, Path skinDir) {
        Path manifestPath = skinDir.resolve("voidcore-skin.json");
        if (!Files.isRegularFile(manifestPath)) return Optional.empty();
        try {
            ScreenSkinManifest manifest = json.readValue(manifestPath.toFile(), ScreenSkinManifest.class);
            LoadedSkin skin = new LoadedSkin(
                    loadTargets(manifest, manifestPath),
                    loadRegion(manifest.banner(), skinDir, manifestPath),
                    loadRegion(manifest.main(), skinDir, manifestPath),
                    loadTree(manifest.tree(), manifestPath),
                    ScreenBannerPolicy.parse(manifest.bannerPolicy()));
            if (skin.targets().isEmpty()) {
                log.warn("skipping skin {}: missing screen target", manifestPath);
                return Optional.empty();
            }
            return Optional.of(new TargetedSkin(skin, manifestPath));
        } catch (Exception e) {
            log.warn("failed reading skin manifest {}: {}", manifestPath, e.toString());
            return Optional.empty();
        }
    }

    private static SkinTargets loadTargets(ScreenSkinManifest manifest, Path manifestPath) {
        java.util.LinkedHashSet<String> exactNames = new java.util.LinkedHashSet<>();
        String primary = normalize(manifest.screenName());
        if (!primary.isBlank()) exactNames.add(primary);
        if (manifest.screenNames() != null) {
            for (String screenName : manifest.screenNames()) {
                String normalized = normalize(screenName);
                if (!normalized.isBlank()) exactNames.add(normalized);
            }
        }

        java.util.ArrayList<String> includePatterns = normalizePatterns(manifest.includeScreens());
        java.util.ArrayList<String> excludePatterns = normalizePatterns(manifest.excludeScreens());
        if (!includePatterns.isEmpty() && exactNames.isEmpty()) {
            return new SkinTargets(List.copyOf(exactNames), List.copyOf(includePatterns), List.copyOf(excludePatterns));
        }
        if (!includePatterns.isEmpty()) {
            return new SkinTargets(List.copyOf(exactNames), List.copyOf(includePatterns), List.copyOf(excludePatterns));
        }
        if (!exactNames.isEmpty()) {
            return new SkinTargets(List.copyOf(exactNames), List.of(), List.copyOf(excludePatterns));
        }
        throw new IllegalArgumentException("missing screen target in " + manifestPath);
    }

    private static java.util.ArrayList<String> normalizePatterns(List<String> values) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (values == null) return out;
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) out.add(normalized);
        }
        return out;
    }

    private static LoadedTree loadTree(ScreenTreeSkin tree, Path manifestPath) {
        return loadTree(tree, manifestPath.getParent(), manifestPath);
    }

    private static LoadedTree loadTree(ScreenTreeSkin tree, Path skinDir, Path manifestPath) {
        if (tree == null) return null;
        String variant = tree.variant() == null ? "" : tree.variant().trim().toLowerCase(java.util.Locale.ROOT);
        if (!variant.isEmpty() && !variant.equals("frame") && !variant.equals("console")) {
            throw new IllegalArgumentException("unsupported tree variant " + variant + " in " + manifestPath);
        }
        int paddingLeft = tree.paddingLeft() == null ? 0 : tree.paddingLeft();
        if (paddingLeft < 0) {
            throw new IllegalArgumentException("invalid tree paddingLeft in " + manifestPath);
        }
        String footerStyle = tree.footerStyle() == null || tree.footerStyle().isBlank()
                ? "grey"
                : tree.footerStyle().trim();
        return new LoadedTree(
                variant,
                blankToNull(tree.headerTitle()),
                blankToNull(tree.headerRightAnnotation()),
                paddingLeft,
                loadTreeZone(tree.topAsset(), tree.topAssets(), skinDir, manifestPath),
                loadTreeZone(tree.leftAsset(), tree.leftAssets(), skinDir, manifestPath),
                loadTreeZone(tree.rightAsset(), tree.rightAssets(), skinDir, manifestPath),
                loadTreeZone(tree.bottomAsset(), tree.bottomAssets(), skinDir, manifestPath),
                blankToNull(tree.footerText()),
                footerStyle);
    }

    private static Element loadTreeZone(String singleAsset,
                                        List<String> assetList,
                                        Path skinDir,
                                        Path manifestPath) {
        java.util.ArrayList<Element> parts = new java.util.ArrayList<>();
        Element single = loadTreeArt(singleAsset, skinDir, manifestPath);
        if (single != null) parts.add(single);
        if (assetList != null) {
            for (String asset : assetList) {
                Element zone = loadTreeArt(asset, skinDir, manifestPath);
                if (zone != null) parts.add(zone);
            }
        }
        return stackOfMany(parts, 1);
    }

    private static Element.AnsiBlock loadTreeArt(String asset, Path skinDir, Path manifestPath) {
        String name = blankToNull(asset);
        if (name == null) return null;
        Path assetPath = skinDir.resolve(name).normalize();
        if (!Files.isRegularFile(assetPath)) {
            throw new IllegalArgumentException("missing tree art asset " + assetPath);
        }
        String text;
        try {
            text = Files.readString(assetPath);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed reading tree art asset " + assetPath, e);
        }
        return toAnsiBlock(AnsiArtSupport.toRows(AnsiArtSupport.parse(text)));
    }

    private static LoadedRegion loadRegion(ScreenSkinRegion region, Path skinDir, Path manifestPath) {
        if (region == null) return null;
        if (region.asset() == null || region.asset().isBlank()) {
            throw new IllegalArgumentException("region missing asset in " + manifestPath);
        }
        Path assetPath = skinDir.resolve(region.asset()).normalize();
        if (!Files.isRegularFile(assetPath)) {
            throw new IllegalArgumentException("missing skin asset " + assetPath);
        }
        String text;
        try {
            text = Files.readString(assetPath);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed reading skin asset " + assetPath, e);
        }
        LinkedHashMap<String, LoadedSlot> slots = new LinkedHashMap<>();
        List<ScreenSkinSlot> manifestSlots = region.slots() == null ? List.of() : region.slots();
        for (ScreenSkinSlot slot : manifestSlots) {
            LoadedSlot loaded = toLoadedSlot(slot, manifestPath);
            if (slots.putIfAbsent(loaded.name(), loaded) != null) {
                throw new IllegalArgumentException("duplicate slot " + loaded.name() + " in " + manifestPath);
            }
        }
        return new LoadedRegion(AnsiArtSupport.parse(text), Map.copyOf(slots));
    }

    private static LoadedSlot toLoadedSlot(ScreenSkinSlot slot, Path manifestPath) {
        if (slot == null
                || slot.name() == null || slot.name().isBlank()
                || slot.row() == null || slot.col() == null
                || slot.width() == null || slot.height() == null
                || slot.row() < 1 || slot.col() < 1
                || slot.width() < 1 || slot.height() < 1) {
            throw new IllegalArgumentException("invalid slot in " + manifestPath);
        }
        return new LoadedSlot(slot.name().trim(), slot.row() - 1, slot.col() - 1, slot.width(), slot.height());
    }

    private static void register(Map<String, LoadedSkin> exact,
                                 List<PatternSkin> patterns,
                                 TargetedSkin targeted,
                                 Path dir) {
        LoadedSkin skin = targeted.skin();
        for (String screenName : skin.targets().exactNames()) {
            if (exact.containsKey(screenName)) {
                log.warn("skipping duplicate screen skin {} from {}", screenName, dir);
                continue;
            }
            exact.put(screenName, skin);
        }
        if (!skin.targets().includePatterns().isEmpty()) {
            patterns.add(new PatternSkin(skin.targets(), skin));
        }
    }

    private static String normalize(String screenName) {
        return screenName == null ? "" : screenName.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Element wrapTree(LoadedTree tree, Element body) {
        Element wrappedBody = body;
        if (tree.paddingLeft() > 0) {
            wrappedBody = new Element.Padded(wrappedBody, tree.paddingLeft());
        }

        Element top = stackOf(
                tree.headerTitle() != null || tree.headerRightAnnotation() != null
                        ? new Element.Header(
                        tree.headerTitle() == null ? "" : tree.headerTitle(),
                        tree.headerRightAnnotation())
                        : null,
                tree.topArt());
        Element bottom = stackOf(
                tree.bottomArt(),
                tree.footerText() == null ? null : new Element.Text(tree.footerText(), tree.footerStyle()));

        return new Element.Shell(
                tree.variant() == null || tree.variant().isBlank() ? "default" : tree.variant(),
                top,
                tree.leftZone(),
                wrappedBody,
                tree.rightZone(),
                bottom);
    }

    private static Element stackOf(Element first, Element second) {
        if (first == null) return second;
        if (second == null) return first;
        return new Element.VStack(List.of(first, second), 1);
    }

    private static Element stackOfMany(List<Element> parts, int gap) {
        if (parts == null || parts.isEmpty()) return null;
        if (parts.size() == 1) return parts.getFirst();
        return new Element.VStack(List.copyOf(parts), gap);
    }

    private static Element.AnsiBlock toAnsiBlock(List<Row> rows) {
        java.util.ArrayList<Element.AnsiLine> lines = new java.util.ArrayList<>(rows.size());
        for (Row row : rows) {
            java.util.ArrayList<Element.AnsiSpan> spans = new java.util.ArrayList<>(row.spans().size());
            for (var span : row.spans()) {
                spans.add(new Element.AnsiSpan(span.text(), span.fg(), span.bg(), span.bold()));
            }
            lines.add(new Element.AnsiLine(List.copyOf(spans)));
        }
        return new Element.AnsiBlock(List.copyOf(lines));
    }

    enum RegionKind { BANNER, MAIN }

    record LoadedSkins(Map<String, LoadedSkin> exact, List<PatternSkin> patterns) {
        LoadedSkin resolve(String screenName) {
            LoadedSkin exactHit = exact.get(screenName);
            if (exactHit != null) return exactHit;
            for (PatternSkin pattern : patterns) {
                if (pattern.matches(screenName)) return pattern.skin();
            }
            return null;
        }
    }

    record TargetedSkin(LoadedSkin skin, Path manifestPath) {
    }

    record PatternSkin(SkinTargets targets, LoadedSkin skin) {
        boolean matches(String screenName) {
            if (screenName == null || screenName.isBlank()) return false;
            boolean included = targets.includePatterns().stream().anyMatch(pattern -> globMatches(pattern, screenName));
            if (!included) return false;
            return targets.excludePatterns().stream().noneMatch(pattern -> globMatches(pattern, screenName));
        }
    }

    record SkinTargets(List<String> exactNames, List<String> includePatterns, List<String> excludePatterns) {
        boolean isEmpty() {
            return exactNames.isEmpty() && includePatterns.isEmpty();
        }
    }

    record LoadedSkin(SkinTargets targets,
                      LoadedRegion banner,
                      LoadedRegion main,
                      LoadedTree tree,
                      ScreenBannerPolicy bannerPolicy) {
    }

    record LoadedRegion(AnsiArtSupport.Canvas canvas, Map<String, LoadedSlot> slots) {
    }

    record LoadedSlot(String name, int row, int col, int width, int height) {
    }

    record LoadedTree(String variant,
                      String headerTitle,
                      String headerRightAnnotation,
                      int paddingLeft,
                      Element topArt,
                      Element leftZone,
                      Element rightZone,
                      Element bottomArt,
                      String footerText,
                      String footerStyle) {
    }

    private static boolean globMatches(String pattern, String value) {
        if ("*".equals(pattern)) return true;
        String[] parts = pattern.split("\\*", -1);
        int index = 0;
        boolean anchoredStart = !pattern.startsWith("*");
        boolean anchoredEnd = !pattern.endsWith("*");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;
            int found = value.indexOf(part, index);
            if (found < 0) return false;
            if (i == 0 && anchoredStart && found != 0) return false;
            index = found + part.length();
        }
        if (anchoredEnd && !parts[parts.length - 1].isEmpty()) {
            return value.endsWith(parts[parts.length - 1]);
        }
        return true;
    }
}
