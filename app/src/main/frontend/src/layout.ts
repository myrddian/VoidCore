/**
 * Region geometry for the v1 fixed layout per SPEC §4.3:
 *   banner (8 rows) / main (14) / notifications (1) / status (1)
 *
 * v2's dynamic UI runtime (per ROADMAP) will replace this static map with a
 * server-pushed layout grammar, but v1's commitment is exactly the regions
 * named here. The renderer addresses regions by these names.
 */

export const REGION_IDS = ["banner", "main", "notifications", "status"] as const;
export type RegionId = (typeof REGION_IDS)[number];

export interface RegionDom {
  id: RegionId;
  el: HTMLElement;
}

export function getRegions(): Record<RegionId, RegionDom> {
  const result = {} as Record<RegionId, RegionDom>;
  for (const id of REGION_IDS) {
    const el = document.getElementById(`region-${id}`);
    if (!el) throw new Error(`region element missing: region-${id}`);
    result[id] = { id, el };
  }
  return result;
}
