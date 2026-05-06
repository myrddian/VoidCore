import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const __dirname = dirname(fileURLToPath(import.meta.url));

function loadJsonData(relativePath) {
  return JSON.parse(readFileSync(resolve(__dirname, relativePath), "utf8"));
}

export const SETTING = loadJsonData("data/setting.json");

export const FACTIONS = {
  couriers: { name: "Couriers Guild", accent: "bright-cyan" },
  clinic: { name: "Clinic Circle", accent: "bright-blue" },
  dock: { name: "Dock Runners", accent: "bright-green" },
  archive: { name: "Archive Keepers", accent: "white" },
  club: { name: "Club Syndicate", accent: "bright-magenta" },
  bureau: { name: "Recovery Bureau", accent: "bright-red" }
};

export const PLOT_THREADS = loadJsonData("data/plot_threads.json");
export const NPCS = loadJsonData("data/npcs.json");

function loadRooms() {
  const index = loadJsonData("data/room_index.json");
  const rooms = {};
  for (const [key, indexEntry] of Object.entries(index.rooms ?? {})) {
    const content = loadJsonData(`data/rooms/${key}.json`);
    rooms[key] = {
      key,
      ...content,
      exits: Array.isArray(indexEntry.exits) ? indexEntry.exits : []
    };
  }
  return rooms;
}

function loadRoomScavenge(rooms) {
  const out = {};
  for (const [key, room] of Object.entries(rooms)) {
    if (room.scavenge && typeof room.scavenge === "object") {
      out[key] = room.scavenge;
    }
  }
  return out;
}

export const ROOMS = loadRooms();
export const ROOM_SCAVENGE = loadRoomScavenge(ROOMS);

export const JOBS = loadJsonData("data/jobs.json");
export const ITEMS = loadJsonData("data/items.json");
export const VENDORS = loadJsonData("data/vendors.json");
export const CYBERWARE = loadJsonData("data/cyberware.json");
export const DEFAULT_PROFILE = loadJsonData("data/default_profile.json");
export const DEFAULT_FEED = loadJsonData("data/default_feed.json");
export const DELVES = loadJsonData("data/delves.json");
export const BASE_ITEMS = loadJsonData("data/base_items.json");
export const MODIFIERS = loadJsonData("data/modifiers.json");
export const ENEMIES = loadJsonData("data/enemies.json");
export const DELVE_TEMPLATES = loadJsonData("data/delve_templates.json");
export const LORE_PIECES = loadJsonData("data/lore_pieces.json");
export const LORE_TEMPLATES = loadJsonData("data/lore_templates.json");
export const ACHIEVEMENTS = loadJsonData("data/achievements.json");

export const ITEM_KINDS = ["weapon", "armor", "cyberware"];

function validateWorld() {
  const errors = [];
  const note = (msg) => errors.push(msg);

  for (const [key, npc] of Object.entries(NPCS)) {
    if (!FACTIONS[npc.faction]) note(`npc.${key}: unknown faction "${npc.faction}"`);
    if (npc.room && !ROOMS[npc.room]) note(`npc.${key}: unknown room "${npc.room}"`);
    for (const id of npc.plotHooks ?? []) {
      if (!PLOT_THREADS[id]) note(`npc.${key}: unknown plotHook "${id}"`);
    }
    for (const otherKey of Object.keys(npc.relationships ?? {})) {
      if (!NPCS[otherKey]) note(`npc.${key}: relationship target "${otherKey}" missing`);
    }
  }
  for (const [key, room] of Object.entries(ROOMS)) {
    for (const exit of room.exits ?? []) {
      if (!ROOMS[exit]) note(`room.${key}: dead exit "${exit}"`);
    }
    for (const npcKey of room.npcs ?? []) {
      if (!NPCS[npcKey]) note(`room.${key}: lists missing npc "${npcKey}"`);
    }
    if (!Array.isArray(room.mood) || room.mood.length === 0) {
      note(`room.${key}: missing mood array`);
    }
    const scavenge = room.scavenge;
    if (scavenge?.reward?.itemId && !ITEMS[scavenge.reward.itemId]) {
      note(`room.${key}: scavenge references missing item "${scavenge.reward.itemId}"`);
    }
  }
  for (const [tid, thread] of Object.entries(PLOT_THREADS)) {
    for (const fac of Object.keys(thread.factionPressure ?? {})) {
      if (!FACTIONS[fac]) note(`thread.${tid}: factionPressure unknown faction "${fac}"`);
    }
    for (const actor of thread.actors ?? []) {
      if (!NPCS[actor]) note(`thread.${tid}: actor "${actor}" missing`);
    }
  }
  for (const job of JOBS) {
    if (!ROOMS[job.room]) note(`job.${job.id}: unknown room "${job.room}"`);
    if (!FACTIONS[job.faction]) note(`job.${job.id}: unknown faction "${job.faction}"`);
    if (job.plot && !PLOT_THREADS[job.plot]) note(`job.${job.id}: unknown plot "${job.plot}"`);
    for (const itemId of job.salvage ?? []) {
      if (!ITEMS[itemId]) note(`job.${job.id}: salvage item "${itemId}" missing`);
    }
  }
  for (const [roomKey, vendor] of Object.entries(VENDORS)) {
    if (!ROOMS[roomKey]) note(`vendor for "${roomKey}": room does not exist`);
    if (vendor.npc && !NPCS[vendor.npc]) note(`vendor for "${roomKey}": npc "${vendor.npc}" missing`);
    for (const entry of vendor.stock ?? []) {
      if (!ITEMS[entry.itemId]) note(`vendor for "${roomKey}": stock item "${entry.itemId}" missing`);
    }
  }
  for (const fac of Object.keys(DEFAULT_PROFILE.rep ?? {})) {
    if (!FACTIONS[fac]) note(`default_profile.rep: unknown faction "${fac}"`);
  }
  for (const [id, base] of Object.entries(BASE_ITEMS)) {
    if (!ITEM_KINDS.includes(base.kind)) note(`base_item.${id}: invalid kind "${base.kind}"`);
    if (!Number.isFinite(base.modSlots)) note(`base_item.${id}: modSlots must be a number`);
    if (!base.baseEffects || typeof base.baseEffects !== "object") note(`base_item.${id}: baseEffects must be object`);
  }
  for (const [id, mod] of Object.entries(MODIFIERS)) {
    if (!Array.isArray(mod.appliesToKinds) || mod.appliesToKinds.length === 0) note(`modifier.${id}: appliesToKinds must be a non-empty array`);
    for (const k of mod.appliesToKinds ?? []) {
      if (!ITEM_KINDS.includes(k)) note(`modifier.${id}: appliesToKinds includes invalid kind "${k}"`);
    }
    if (!Number.isFinite(mod.rank) || mod.rank < 1 || mod.rank > 5) note(`modifier.${id}: rank must be 1..5`);
    if (!mod.effects || typeof mod.effects !== "object") note(`modifier.${id}: effects must be object`);
  }
  for (const [delveId, delve] of Object.entries(DELVES)) {
    if (!ROOMS[delve.room]) note(`delve.${delveId}: unknown room "${delve.room}"`);
    if (delve.faction && !FACTIONS[delve.faction]) note(`delve.${delveId}: unknown faction "${delve.faction}"`);
    const stages = delve.stages ?? {};
    if (!stages[delve.entry]) note(`delve.${delveId}: entry stage "${delve.entry}" missing`);
    for (const [stageId, stage] of Object.entries(stages)) {
      if (stage.kind === "combat") {
        if (!ENEMIES[stage.enemyId]) note(`delve.${delveId}.${stageId}: combat references unknown enemy "${stage.enemyId}"`);
        for (const branch of ["onVictory", "onDefeat", "onFlee"]) {
          if (stage[branch] && !stages[stage[branch]]) note(`delve.${delveId}.${stageId}: ${branch} stage "${stage[branch]}" missing`);
        }
        continue;
      }
      for (const choice of stage.choices ?? []) {
        if (choice.next && !stages[choice.next]) {
          note(`delve.${delveId}.${stageId}.${choice.id}: next stage "${choice.next}" missing`);
        }
        for (const fx of choice.effects ?? []) {
          if (fx.op === "grant_item" && !ITEMS[fx.itemId]) {
            note(`delve.${delveId}.${stageId}.${choice.id}: grant_item references missing "${fx.itemId}"`);
          }
          if (fx.op === "update_rep" && !FACTIONS[fx.faction]) {
            note(`delve.${delveId}.${stageId}.${choice.id}: update_rep unknown faction "${fx.faction}"`);
          }
        }
      }
      for (const fx of stage.effects ?? []) {
        if (fx.op === "grant_item" && !ITEMS[fx.itemId]) {
          note(`delve.${delveId}.${stageId} terminal: grant_item references missing "${fx.itemId}"`);
        }
      }
    }
  }
  for (const [tmplId, tmpl] of Object.entries(DELVE_TEMPLATES)) {
    for (const room of tmpl.appliesTo ?? []) {
      if (!ROOMS[room]) note(`delve_template.${tmplId}: appliesTo unknown room "${room}"`);
    }
    const stages = tmpl.stages ?? {};
    if (!stages[tmpl.entry]) note(`delve_template.${tmplId}: entry stage "${tmpl.entry}" missing`);
    for (const stage of Object.values(stages)) {
      if (stage.kind === "combat") {
        // enemyId may be a slot placeholder; it will be resolved at generation
        const enemyRef = stage.enemyId ?? "";
        if (!enemyRef.includes("{") && !ENEMIES[enemyRef]) {
          note(`delve_template.${tmplId}: combat references unknown enemy "${enemyRef}"`);
        }
      }
    }
    for (const slot of Object.keys(tmpl.slots ?? {})) {
      // Lazy: just confirm it's an array with at least one option
      if (!Array.isArray(tmpl.slots[slot]) || tmpl.slots[slot].length === 0) {
        note(`delve_template.${tmplId}: slot "${slot}" must be a non-empty array`);
      }
    }
  }
  for (const [enemyId, enemy] of Object.entries(ENEMIES)) {
    if (!Number.isFinite(enemy.hp) || enemy.hp <= 0) note(`enemy.${enemyId}: hp must be > 0`);
    if (!Array.isArray(enemy.attacks) || enemy.attacks.length === 0) note(`enemy.${enemyId}: attacks must be a non-empty array`);
  }
  for (const [loreId, piece] of Object.entries(LORE_PIECES)) {
    if (typeof piece.title !== "string" || !piece.title) note(`lore_piece.${loreId}: missing title`);
    if (typeof piece.body !== "string" || !piece.body) note(`lore_piece.${loreId}: missing body`);
    if (piece.kind !== "narrative") note(`lore_piece.${loreId}: kind must be "narrative"`);
  }
  for (const [tmplId, tmpl] of Object.entries(LORE_TEMPLATES)) {
    if (tmpl.kind !== "procedural") note(`lore_template.${tmplId}: kind must be "procedural"`);
    if (typeof tmpl.body !== "string" || !tmpl.body) note(`lore_template.${tmplId}: missing body`);
    for (const room of tmpl.appliesIn ?? []) {
      if (!ROOMS[room]) note(`lore_template.${tmplId}: appliesIn unknown room "${room}"`);
    }
    for (const [slot, options] of Object.entries(tmpl.slots ?? {})) {
      if (!Array.isArray(options) || options.length === 0) {
        note(`lore_template.${tmplId}: slot "${slot}" must be non-empty array`);
      }
    }
  }
  for (const [achId, ach] of Object.entries(ACHIEVEMENTS)) {
    if (typeof ach.title !== "string" || !ach.title) note(`achievement.${achId}: missing title`);
    if (!Number.isFinite(ach.points) || ach.points < 0) note(`achievement.${achId}: points must be a non-negative number`);
    if (ach.id !== achId) note(`achievement.${achId}: id field "${ach.id}" must match key "${achId}"`);
  }

  if (errors.length > 0) {
    const msg = "[cityline] world data validation failed:\n  - " + errors.join("\n  - ");
    throw new Error(msg);
  }
}

validateWorld();
