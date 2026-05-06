import {
  ARMOR_SLOTS,
  CYBERWARE_SLOTS,
  equipFromInventory,
  isItemInstance,
  unequipSlot
} from "../game.mjs";
import { BASE_ITEMS, CYBERWARE } from "../world.mjs";
import { saveProfile } from "../index.mjs";

function isEsc(key) {
  const k = String(key ?? "").trim().toLowerCase();
  return k === "escape" || k === "esc";
}

// Each screen below is a raw Screen object (not an SDK option screen),
// because they paint a slot table at the top and a keyed inventory
// list at the bottom. Numbers equip from the list; letters unequip a
// slot. Esc pops back to the previous screen.

const ROW_W = 78;

function pad(s, n) {
  s = String(s ?? "");
  return s.length >= n ? s.slice(0, n) : s + " ".repeat(n - s.length);
}

function statSummary(item) {
  if (!item) return "";
  if (typeof item === "string") {
    const def = CYBERWARE[item];
    if (!def) return "";
    const parts = [];
    for (const [stat, v] of Object.entries(def.effects?.stats ?? {})) {
      parts.push(`${stat} +${v}`);
    }
    if (def.effects?.maxHp) parts.push(`maxHp +${def.effects.maxHp}`);
    if (def.effects?.heatFloorBonus) parts.push(`heat_floor +${def.effects.heatFloorBonus}`);
    return parts.join(" · ");
  }
  if (isItemInstance(item)) {
    const stats = Object.entries(item.computed ?? {})
      .filter(([, v]) => Number.isFinite(v) && v !== 0)
      .map(([k, v]) => `${k} ${v >= 0 ? "+" : ""}${v}`);
    return stats.join(" · ");
  }
  return "";
}

function nameOf(item) {
  if (!item) return "—";
  if (typeof item === "string") return CYBERWARE[item]?.name ?? item;
  return item.name ?? BASE_ITEMS[item.baseId]?.name ?? item.baseId;
}

function paintLanding(session) {
  const p = session.data.profile;
  const eq = p.equipment ?? {};
  const weaponName = nameOf(eq.weapon);
  const armorOn = ARMOR_SLOTS.filter((s) => eq.armor?.[s]).length;
  const cyberOn = CYBERWARE_SLOTS.filter((s) => eq.cyberware?.[s]).length;
  const carried = (p.inventory ?? []).length;

  const rows = [];
  rows.push({ row: 0, spans: [{ text: "  == INVENTORY ==", fg: "bright-yellow" }] });
  rows.push({ row: 1, spans: [{ text: "" }] });
  rows.push({ row: 2, spans: [{ text: `  weapon : ${weaponName}`, fg: "default" }] });
  rows.push({ row: 3, spans: [{ text: `  armor  : ${armorOn} / ${ARMOR_SLOTS.length} slots filled`, fg: "default" }] });
  rows.push({ row: 4, spans: [{ text: `  chrome : ${cyberOn} / ${CYBERWARE_SLOTS.length} slots filled`, fg: "default" }] });
  rows.push({ row: 5, spans: [{ text: `  pockets: ${carried} / 12 carried`, fg: "default" }] });
  rows.push({ row: 6, spans: [{ text: "" }] });
  rows.push({ row: 7, spans: [
    { text: "  [", fg: "grey" }, { text: "W", fg: "bright-yellow", bold: true }, { text: "] weapons   ", fg: "grey" },
    { text: "[", fg: "grey" }, { text: "A", fg: "bright-yellow", bold: true }, { text: "] armor   ", fg: "grey" },
    { text: "[", fg: "grey" }, { text: "C", fg: "bright-yellow", bold: true }, { text: "] cyberware   ", fg: "grey" },
    { text: "[", fg: "grey" }, { text: "I", fg: "bright-yellow", bold: true }, { text: "] items   ", fg: "grey" },
    { text: "[", fg: "grey" }, { text: "esc", fg: "bright-yellow", bold: true }, { text: "] back", fg: "grey" }
  ]});
  session.paint(rows);
  session.data.lastPaintKind = "inventory";
  session.prompt({ mode: "keystroke", label: "[esc] inventory:", validKeys: "WACI" });
}

export function createInventoryScreen() {
  return {
    id: "inventory-landing",
    async onEnter(session) {
      paintLanding(session);
    },
    async onResume(session) {
      paintLanding(session);
    },
    async onKey(session, key, _modifiers, stack) {
      if (isEsc(key)) { await stack.pop(session, "inventory-exit"); return true; }
      const k = String(key ?? "").trim().toUpperCase();
      if (k === "W") { await stack.push(session, createWeaponScreen(), "weapon"); return true; }
      if (k === "A") { await stack.push(session, createArmorScreen(), "armor"); return true; }
      if (k === "C") { await stack.push(session, createCyberwareScreen(), "cyberware"); return true; }
      if (k === "I") { await stack.push(session, createItemsScreen(), "items"); return true; }
      return false;
    }
  };
}

// -- weapons ----------------------------------------------------------------

function paintWeapon(session) {
  const p = session.data.profile;
  const equipped = p.equipment?.weapon ?? null;
  const carried = (p.inventory ?? [])
    .map((entry, idx) => ({ entry, idx }))
    .filter(({ entry }) => isItemInstance(entry) && BASE_ITEMS[entry.baseId]?.kind === "weapon");

  const rows = [];
  rows.push({ row: 0, spans: [{ text: "  == WEAPON ==", fg: "bright-yellow" }] });
  rows.push({ row: 1, spans: [{ text: "" }] });
  rows.push({ row: 2, spans: [
    { text: "  main hand  ", fg: "grey" },
    { text: pad(nameOf(equipped), 28), fg: equipped ? "bright-cyan" : "dark-grey" },
    { text: equipped ? statSummary(equipped) : "", fg: "grey" }
  ]});
  if (equipped) {
    rows.push({ row: 3, spans: [{ text: `              [U] unequip`, fg: "dark-grey" }] });
  }
  rows.push({ row: 4, spans: [{ text: "" }] });
  rows.push({ row: 5, spans: [{ text: "  carried weapons:", fg: "grey" }] });
  let r = 6;
  if (carried.length === 0) {
    rows.push({ row: r++, spans: [{ text: "    none — go scavenge or buy.", fg: "dark-grey" }] });
  } else {
    carried.slice(0, 9).forEach(({ entry }, i) => {
      rows.push({ row: r++, spans: [
        { text: "  [", fg: "grey" },
        { text: String(i + 1), fg: "bright-yellow", bold: true },
        { text: "] ", fg: "grey" },
        { text: pad(entry.name ?? entry.baseId, 28), fg: "default" },
        { text: statSummary(entry), fg: "grey" }
      ]});
    });
  }
  rows.push({ row: r++, spans: [{ text: "" }] });
  rows.push({ row: r++, spans: [
    { text: "  [", fg: "grey" }, { text: "1-9", fg: "bright-yellow", bold: true }, { text: "] equip   ", fg: "grey" },
    ...(equipped ? [{ text: "[", fg: "grey" }, { text: "U", fg: "bright-yellow", bold: true }, { text: "] unequip   ", fg: "grey" }] : []),
    { text: "[", fg: "grey" }, { text: "esc", fg: "bright-yellow", bold: true }, { text: "] back", fg: "grey" }
  ]});

  session.paint(rows);
  const validKeys = "123456789" + (equipped ? "U" : "");
  session.prompt({ mode: "keystroke", label: "[esc] weapon:", validKeys });
}

function createWeaponScreen() {
  return {
    id: "inventory-weapons",
    async onEnter(session) { paintWeapon(session); },
    async onResume(session) { paintWeapon(session); },
    async onKey(session, key, _modifiers, stack) {
      if (isEsc(key)) { await stack.pop(session, "weapon-exit"); return true; }
      const k = String(key ?? "").trim();
      const profile = session.data.profile;
      if (k.toUpperCase() === "U") {
        const r = unequipSlot(profile, "weapon");
        if (r.ok) await saveProfile(session);
        session.notify(r.message, { level: r.ok ? "info" : "warn", durationMs: 1800 });
        paintWeapon(session);
        return true;
      }
      if (/^[1-9]$/.test(k)) {
        const idx = Number(k) - 1;
        const carried = (profile.inventory ?? [])
          .filter((e) => isItemInstance(e) && BASE_ITEMS[e.baseId]?.kind === "weapon");
        const target = carried[idx];
        if (!target) return true;
        const r = equipFromInventory(profile, target.baseId);
        if (r.ok) await saveProfile(session);
        session.notify(r.message, { level: r.ok ? "info" : "warn", durationMs: 1800 });
        paintWeapon(session);
        return true;
      }
      return false;
    }
  };
}

// -- armor ------------------------------------------------------------------

const ARMOR_SLOT_KEYS = { H: "head", T: "torso", P: "pants", S: "shoes", G: "gloves", E: "eyes" };
const ARMOR_KEY_FOR_SLOT = Object.fromEntries(Object.entries(ARMOR_SLOT_KEYS).map(([k, v]) => [v, k]));

function paintArmor(session) {
  const p = session.data.profile;
  const eq = p.equipment?.armor ?? {};
  const carried = (p.inventory ?? [])
    .map((entry, idx) => ({ entry, idx }))
    .filter(({ entry }) => isItemInstance(entry) && BASE_ITEMS[entry.baseId]?.kind === "armor");

  const rows = [];
  rows.push({ row: 0, spans: [{ text: "  == ARMOR ==", fg: "bright-yellow" }] });
  rows.push({ row: 1, spans: [{ text: "" }] });
  let r = 2;
  for (const slot of ARMOR_SLOTS) {
    const cur = eq[slot];
    rows.push({ row: r++, spans: [
      { text: `  [${ARMOR_KEY_FOR_SLOT[slot]}] ${pad(slot, 8)}`, fg: cur ? "bright-cyan" : "dark-grey" },
      { text: pad(nameOf(cur), 28), fg: cur ? "default" : "dark-grey" },
      { text: cur ? statSummary(cur) : "—", fg: "grey" }
    ]});
  }
  rows.push({ row: r++, spans: [{ text: "" }] });
  rows.push({ row: r++, spans: [{ text: "  carried armor:", fg: "grey" }] });
  if (carried.length === 0) {
    rows.push({ row: r++, spans: [{ text: "    none — most armor drops via delves.", fg: "dark-grey" }] });
  } else {
    carried.slice(0, 9).forEach(({ entry }, i) => {
      const slot = BASE_ITEMS[entry.baseId]?.slot ?? "?";
      rows.push({ row: r++, spans: [
        { text: "  [", fg: "grey" },
        { text: String(i + 1), fg: "bright-yellow", bold: true },
        { text: "] ", fg: "grey" },
        { text: pad(entry.name ?? entry.baseId, 24), fg: "default" },
        { text: pad(`(${slot})`, 10), fg: "grey" },
        { text: statSummary(entry), fg: "grey" }
      ]});
    });
  }
  rows.push({ row: r++, spans: [{ text: "" }] });
  rows.push({ row: r++, spans: [
    { text: "  [", fg: "grey" }, { text: "1-9", fg: "bright-yellow", bold: true }, { text: "] equip   ", fg: "grey" },
    { text: "[", fg: "grey" }, { text: "H/T/P/S/G/E", fg: "bright-yellow", bold: true }, { text: "] unequip slot   ", fg: "grey" },
    { text: "[", fg: "grey" }, { text: "esc", fg: "bright-yellow", bold: true }, { text: "] back", fg: "grey" }
  ]});

  session.paint(rows);
  session.prompt({ mode: "keystroke", label: "[esc] armor:", validKeys: "123456789HTPSGE" });
}

function createArmorScreen() {
  return {
    id: "inventory-armor",
    async onEnter(session) { paintArmor(session); },
    async onResume(session) { paintArmor(session); },
    async onKey(session, key, _modifiers, stack) {
      if (isEsc(key)) { await stack.pop(session, "armor-exit"); return true; }
      const k = String(key ?? "").trim();
      const profile = session.data.profile;
      const upper = k.toUpperCase();
      if (ARMOR_SLOT_KEYS[upper]) {
        const r = unequipSlot(profile, ARMOR_SLOT_KEYS[upper]);
        if (r.ok) await saveProfile(session);
        session.notify(r.message, { level: r.ok ? "info" : "warn", durationMs: 1800 });
        paintArmor(session);
        return true;
      }
      if (/^[1-9]$/.test(k)) {
        const idx = Number(k) - 1;
        const carried = (profile.inventory ?? [])
          .filter((e) => isItemInstance(e) && BASE_ITEMS[e.baseId]?.kind === "armor");
        const target = carried[idx];
        if (!target) return true;
        const r = equipFromInventory(profile, target.baseId);
        if (r.ok) await saveProfile(session);
        session.notify(r.message, { level: r.ok ? "info" : "warn", durationMs: 2000 });
        paintArmor(session);
        return true;
      }
      return false;
    }
  };
}

// -- cyberware --------------------------------------------------------------

const CYBER_SLOT_KEYS = { N: "neural", O: "optical", R: "respiratory", D: "dermal", A: "arm", L: "leg" };
const CYBER_KEY_FOR_SLOT = Object.fromEntries(Object.entries(CYBER_SLOT_KEYS).map(([k, v]) => [v, k]));

function paintCyber(session) {
  const p = session.data.profile;
  const eq = p.equipment?.cyberware ?? {};
  const carried = (p.inventory ?? [])
    .map((entry, idx) => ({ entry, idx }))
    .filter(({ entry }) => isItemInstance(entry) && BASE_ITEMS[entry.baseId]?.kind === "cyberware");

  const rows = [];
  rows.push({ row: 0, spans: [{ text: "  == CYBERWARE ==", fg: "bright-yellow" }] });
  rows.push({ row: 1, spans: [{ text: "  (clinic-only graft / install)", fg: "dark-grey" }] });
  rows.push({ row: 2, spans: [{ text: "" }] });
  let r = 3;
  for (const slot of CYBERWARE_SLOTS) {
    const cur = eq[slot];
    const isCatalogue = typeof cur === "string";
    rows.push({ row: r++, spans: [
      { text: `  [${CYBER_KEY_FOR_SLOT[slot]}] ${pad(slot, 12)}`, fg: cur ? "bright-magenta" : "dark-grey" },
      { text: pad(nameOf(cur), 26), fg: cur ? "default" : "dark-grey" },
      { text: isCatalogue ? "(installed)" : (cur ? "(grafted)" : "—"), fg: "grey" },
      { text: " ", fg: "grey" },
      { text: cur ? statSummary(cur) : "", fg: "grey" }
    ]});
  }
  rows.push({ row: r++, spans: [{ text: "" }] });
  rows.push({ row: r++, spans: [{ text: "  salvaged chrome:", fg: "grey" }] });
  if (carried.length === 0) {
    rows.push({ row: r++, spans: [{ text: "    none in pockets.", fg: "dark-grey" }] });
  } else {
    carried.slice(0, 9).forEach(({ entry }, i) => {
      const slot = BASE_ITEMS[entry.baseId]?.slot ?? "?";
      rows.push({ row: r++, spans: [
        { text: "  [", fg: "grey" },
        { text: String(i + 1), fg: "bright-yellow", bold: true },
        { text: "] ", fg: "grey" },
        { text: pad(entry.name ?? entry.baseId, 24), fg: "default" },
        { text: pad(`(${slot})`, 12), fg: "grey" },
        { text: statSummary(entry), fg: "grey" }
      ]});
    });
  }
  rows.push({ row: r++, spans: [{ text: "" }] });
  const inClinic = p.room === "clinic";
  if (!inClinic) {
    rows.push({ row: r++, spans: [{ text: "  not at clinic — equipping requires Dr. Ilex's chair.", fg: "dark-grey" }] });
  }
  rows.push({ row: r++, spans: [
    { text: "  [", fg: "grey" }, { text: "1-9", fg: "bright-yellow", bold: true },
    { text: inClinic ? "] graft   " : "] (clinic only)   ", fg: inClinic ? "grey" : "dark-grey" },
    { text: "[", fg: "grey" }, { text: "N/O/R/D/A/L", fg: "bright-yellow", bold: true }, { text: "] unequip slot   ", fg: "grey" },
    { text: "[", fg: "grey" }, { text: "esc", fg: "bright-yellow", bold: true }, { text: "] back", fg: "grey" }
  ]});

  session.paint(rows);
  session.prompt({ mode: "keystroke", label: "[esc] cyberware:", validKeys: "123456789NORDAL" });
}

function createCyberwareScreen() {
  return {
    id: "inventory-cyberware",
    async onEnter(session) { paintCyber(session); },
    async onResume(session) { paintCyber(session); },
    async onKey(session, key, _modifiers, stack) {
      if (isEsc(key)) { await stack.pop(session, "cyberware-exit"); return true; }
      const k = String(key ?? "").trim();
      const profile = session.data.profile;
      const upper = k.toUpperCase();
      if (CYBER_SLOT_KEYS[upper]) {
        const r = unequipSlot(profile, CYBER_SLOT_KEYS[upper]);
        if (r.ok) await saveProfile(session);
        session.notify(r.message, { level: r.ok ? "info" : "warn", durationMs: 2000 });
        paintCyber(session);
        return true;
      }
      if (/^[1-9]$/.test(k)) {
        if (profile.room !== "clinic") {
          session.notify("Cyberware is a clinic-chair job. Walk to the clinic first.", { level: "warn", durationMs: 2400 });
          return true;
        }
        const idx = Number(k) - 1;
        const carried = (profile.inventory ?? [])
          .filter((e) => isItemInstance(e) && BASE_ITEMS[e.baseId]?.kind === "cyberware");
        const target = carried[idx];
        if (!target) return true;
        const r = equipFromInventory(profile, target.baseId);
        if (r.ok) await saveProfile(session);
        session.notify(r.message, { level: r.ok ? "info" : "warn", durationMs: 2000 });
        paintCyber(session);
        return true;
      }
      return false;
    }
  };
}

// -- misc items -------------------------------------------------------------

function paintItems(session) {
  const p = session.data.profile;
  const carried = p.inventory ?? [];
  const rows = [];
  rows.push({ row: 0, spans: [{ text: "  == ITEMS ==", fg: "bright-yellow" }] });
  rows.push({ row: 1, spans: [{ text: "  (consumables, tools, prep — anything not in a slot)", fg: "dark-grey" }] });
  rows.push({ row: 2, spans: [{ text: "" }] });
  let r = 3;
  if (carried.length === 0) {
    rows.push({ row: r++, spans: [{ text: "  pockets are empty.", fg: "dark-grey" }] });
  } else {
    carried.forEach((entry, i) => {
      const display = typeof entry === "string"
        ? entry
        : (entry.name ?? BASE_ITEMS[entry.baseId]?.name ?? entry.baseId);
      const note = isItemInstance(entry) ? statSummary(entry) : "";
      rows.push({ row: r++, spans: [
        { text: "  · ", fg: "grey" },
        { text: pad(display, 32), fg: "default" },
        { text: note, fg: "grey" }
      ]});
    });
  }
  rows.push({ row: r++, spans: [{ text: "" }] });
  rows.push({ row: r++, spans: [{ text: "  type `use <name>` from the room view to consume.", fg: "dark-grey" }] });
  rows.push({ row: r++, spans: [
    { text: "  [", fg: "grey" }, { text: "esc", fg: "bright-yellow", bold: true }, { text: "] back", fg: "grey" }
  ]});

  session.paint(rows);
  session.prompt({ mode: "keystroke", label: "[esc] items:", validKeys: "" });
}

function createItemsScreen() {
  return {
    id: "inventory-items",
    async onEnter(session) { paintItems(session); },
    async onResume(session) { paintItems(session); },
    async onKey(session, key, _mods, stack) {
      if (isEsc(key)) { await stack.pop(session, "items-exit"); return true; }
      return false;
    }
  };
}
