import { describe, expect, it } from "vitest";

import type { RegionDom } from "../layout.js";
import { RegionRenderer, compactBannerRows } from "../region.js";
import type { Row } from "../types.js";

function sampleBannerRows(): Row[] {
  return [
    { row: 0, spans: [{ text: " __          _______      ____    __   __    ___    __   __", fg: "bright_cyan" }] },
    { row: 1, spans: [{ text: " \\ \\        / / ____|    |___ \\  / /  / /   / _ \\   \\ \\ / /", fg: "bright_cyan" }] },
    { row: 2, spans: [{ text: "              W S / 3 6 0   O P E R A T I O N S   C O N S O L E    ", fg: "bright_cyan", bold: true }] },
  ];
}

function installRegions(): Record<"banner" | "main" | "notifications" | "status", RegionDom> {
  document.body.innerHTML = `
    <div id="region-banner"></div>
    <div id="region-main"></div>
    <div id="region-notifications"></div>
    <div id="region-status"></div>
  `;
  return {
    banner: { id: "banner", el: document.getElementById("region-banner") as HTMLElement },
    main: { id: "main", el: document.getElementById("region-main") as HTMLElement },
    notifications: { id: "notifications", el: document.getElementById("region-notifications") as HTMLElement },
    status: { id: "status", el: document.getElementById("region-status") as HTMLElement },
  };
}

describe("compactBannerRows", () => {
  it("prefers the human-readable label row over ASCII art", () => {
    const compact = compactBannerRows(sampleBannerRows());
    expect(compact).toHaveLength(1);
    expect(compact[0]?.spans.map((span) => span.text).join("")).toBe(
      "W S / 3 6 0   O P E R A T I O N S   C O N S O L E",
    );
  });
});

describe("RegionRenderer responsive banner", () => {
  it("compacts and restores the banner around main-region overflow", () => {
    const regions = installRegions();
    const renderer = new RegionRenderer(regions);
    const main = regions.main.el;
    const banner = regions.banner.el;
    let clientHeight = 12;
    let scrollHeight = 18;
    Object.defineProperty(main, "clientHeight", {
      configurable: true,
      get: () => clientHeight,
    });
    Object.defineProperty(main, "scrollHeight", {
      configurable: true,
      get: () => scrollHeight,
    });

    renderer.update({
      region: "banner",
      version: 1,
      content: sampleBannerRows(),
    });
    renderer.update({
      region: "main",
      version: 1,
      content: [{ row: 0, spans: [{ text: "hello" }] }],
    });
    renderer.refreshResponsiveLayout();

    expect(banner.classList.contains("region-banner-compact")).toBe(true);
    expect(banner.textContent).toContain("W S / 3 6 0   O P E R A T I O N S   C O N S O L E");
    expect(banner.textContent).not.toContain("_______");

    clientHeight = 24;
    scrollHeight = 12;
    renderer.refreshResponsiveLayout();

    expect(banner.classList.contains("region-banner-compact")).toBe(false);
    expect(banner.textContent).toContain("_______");
  });

  it("honours always_compact without waiting for overflow", () => {
    const regions = installRegions();
    const renderer = new RegionRenderer(regions);
    const main = regions.main.el;
    const banner = regions.banner.el;
    Object.defineProperty(main, "clientHeight", {
      configurable: true,
      get: () => 24,
    });
    Object.defineProperty(main, "scrollHeight", {
      configurable: true,
      get: () => 12,
    });

    renderer.update({
      region: "banner",
      version: 1,
      content: sampleBannerRows(),
      bannerPolicy: "always_compact",
    });
    renderer.update({
      region: "main",
      version: 1,
      content: [{ row: 0, spans: [{ text: "hello" }] }],
    });
    renderer.refreshResponsiveLayout();

    expect(banner.classList.contains("region-banner-compact")).toBe(true);
    expect(banner.textContent).toContain("W S / 3 6 0   O P E R A T I O N S   C O N S O L E");
  });
});
