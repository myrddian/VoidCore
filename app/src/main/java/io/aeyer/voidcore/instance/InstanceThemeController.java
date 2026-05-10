package io.aeyer.voidcore.instance;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/instance")
public class InstanceThemeController {

    private final ThemeRegistry themes;

    public InstanceThemeController(ThemeRegistry themes) {
        this.themes = themes;
    }

    @GetMapping("/themes")
    public ThemePayload themes() {
        return new ThemePayload(themes.themeNames(), themes.overlayThemeLabels(), themes.overlayCss());
    }

    public record ThemePayload(List<String> knownThemes,
                               Map<String, String> labels,
                               String overlayCss) {
    }
}
