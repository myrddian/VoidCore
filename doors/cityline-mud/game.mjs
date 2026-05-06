import { ACHIEVEMENTS, BASE_ITEMS, CYBERWARE, DEFAULT_PROFILE, DELVES, DELVE_TEMPLATES, ENEMIES, FACTIONS, ITEMS, ITEM_KINDS, JOBS, LORE_PIECES, LORE_TEMPLATES, MODIFIERS, NPCS, PLOT_THREADS, ROOMS, ROOM_SCAVENGE, SETTING, VENDORS } from "./world.mjs";

const DIALOGUE_LOG_MAX = 16;
const DIALOGUE_PROMPT_LIMIT = 10;
const DIALOGUE_TEXT_PROMPT_LIMIT = 100;
const LEADS_MAX = 24;
const MISSIONS_MAX = 12;
const MISSION_KINDS = ["note", "combat"];
const LORE_MAX = 60;

const DEBUG_PROMPTS = (() => {
  const raw = process.env.CITYLINE_DEBUG_PROMPTS;
  if (raw == null) return false;
  return raw !== "" && raw !== "0" && raw.toLowerCase() !== "false";
})();

const TALK_EFFECT_BOUNDS = {
  update_heat: { min: -2, max: 2 },
  update_rep: { min: -2, max: 2 },
  update_stress: { min: -2, max: 2 },
  update_debt: { min: -30, max: 30 },
  update_credits: { min: -200, max: 200 },
  update_hp: { min: -5, max: 5 }
};
const MAX_EFFECTS_PER_TURN = 8;
const MAX_CHOICES = 5;
const SKILL_STATS = ["edge", "ghost", "wire", "body"];
const MAX_SKILL_DC = 5;

export function defaultPlotState() {
  return Object.fromEntries(Object.values(PLOT_THREADS).map((thread) => [
    thread.id,
    {
      progress: 0,
      heat: 0,
      lastRumor: `${thread.title} stays in the district's peripheral vision.`,
      changedAt: null
    }
  ]));
}

export function normalizePlotState(value) {
  const next = defaultPlotState();
  if (!value || typeof value !== "object") return next;
  for (const thread of Object.values(PLOT_THREADS)) {
    const current = value[thread.id];
    if (!current || typeof current !== "object") continue;
    if (Number.isFinite(current.progress)) next[thread.id].progress = clampInt(current.progress, 0, thread.stages.length - 1);
    if (Number.isFinite(current.heat)) next[thread.id].heat = clampInt(current.heat, 0, 9);
    if (typeof current.lastRumor === "string") next[thread.id].lastRumor = current.lastRumor;
    if (typeof current.changedAt === "string" || current.changedAt === null) next[thread.id].changedAt = current.changedAt;
  }
  return next;
}

export function rankedThreads(plotState) {
  return Object.values(PLOT_THREADS)
    .map((thread) => ({ thread, state: plotState[thread.id] ?? defaultPlotState()[thread.id] }))
    .sort((a, b) => (b.state.heat + b.state.progress) - (a.state.heat + a.state.progress));
}

export function threadSummary(plotState, limit = 3) {
  return rankedThreads(plotState)
    .slice(0, limit)
    .map(({ thread, state }) => `${thread.title} p${state.progress + 1} h${state.heat}`)
    .join(" | ");
}

export function advancePlotState(plotState, job, outcome, actorHandle) {
  if (!job.plot || !PLOT_THREADS[job.plot]) {
    return { state: plotState, updates: [] };
  }
  const next = structuredClone(plotState);
  const thread = PLOT_THREADS[job.plot];
  const current = next[job.plot] ?? defaultPlotState()[job.plot];
  const previousProgress = current.progress;
  const advance = outcome.outcome === "success" ? 1 : 0;
  const heatDelta = outcome.outcome === "failure" ? 2 : 1;
  current.progress = clampInt(current.progress + advance, 0, thread.stages.length - 1);
  current.heat = clampInt(current.heat + heatDelta, 0, 9);
  current.lastRumor = `wire:// ${actorHandle} pushed ${thread.title.toLowerCase()} toward ${thread.stages[current.progress].toLowerCase()}`;
  current.changedAt = new Date().toISOString();
  next[job.plot] = current;
  const repNudges = current.progress > previousProgress ? threadFactionPressure(thread, current.progress) : {};
  return {
    state: next,
    updates: [{
      threadId: thread.id,
      title: thread.title,
      progress: current.progress,
      heat: current.heat,
      stage: thread.stages[current.progress],
      rumor: current.lastRumor,
      repNudges
    }]
  };
}

// World-effect evaluation. Plot threads can declare a `worldEffects` array
// that applies once a thread reaches a given stage. Three classes today:
//
//   - passive_heat_per_room: while the player is in `room`, add `heatPerTick`
//     once every `tickIntervalSec` (driven by the BBS time tick).
//   - vendor_price_multiplier: scale prices when buying from `vendor` (a
//     room key, since vendors are per-room).
//   - ambient_distortion: render-time only — splice random `fragments` into
//     the named room's local-traffic block.
//
// All evaluators are pure and read-only against PLOT_THREADS + plotState.
// Mutation (heat ticks, etc.) happens at the call site so the call site
// can decide whether to persist or notify.

export function getActiveWorldEffects(plotState) {
  const out = [];
  for (const thread of Object.values(PLOT_THREADS)) {
    const state = plotState?.[thread.id] ?? defaultPlotState()[thread.id];
    const progress = state?.progress ?? 0;
    for (const effect of thread.worldEffects ?? []) {
      const requiredStage = Number.isFinite(effect.appliesAtStage) ? effect.appliesAtStage : 0;
      if (progress >= requiredStage) {
        out.push({ ...effect, threadId: thread.id, threadTitle: thread.title });
      }
    }
  }
  return out;
}

export function getRoomDistortions(plotState, roomKey) {
  return getActiveWorldEffects(plotState).filter(
    (e) => e.op === "ambient_distortion" && e.room === roomKey
  );
}

export function getVendorPriceMultiplier(plotState, roomKey) {
  let mult = 1;
  for (const e of getActiveWorldEffects(plotState)) {
    if (e.op === "vendor_price_multiplier" && e.vendor === roomKey && Number.isFinite(e.multiplier) && e.multiplier > 0) {
      mult *= e.multiplier;
    }
  }
  return mult;
}

export function getPassiveHeatEffects(plotState, roomKey) {
  return getActiveWorldEffects(plotState).filter(
    (e) => e.op === "passive_heat_per_room" && e.room === roomKey
  );
}

// Apply any due passive-heat ticks for the player's current room. Mutates
// profile.heat. Returns the list of effects that fired, with their delta.
// The caller is responsible for persisting state on its own cadence.
//
// `lastApplied` is a per-session map { [effectKey]: lastUnixSec } that the
// caller owns; we update entries in place. Effect key is threadId:room so
// the same effect across reconnects re-syncs cleanly.
export function applyPassiveHeatTicks(profile, plotState, roomKey, nowUnixSec, lastApplied = {}) {
  const fired = [];
  if (!Number.isFinite(nowUnixSec)) return fired;
  for (const e of getPassiveHeatEffects(plotState, roomKey)) {
    const key = `${e.threadId}:${e.room}`;
    const interval = Number.isFinite(e.tickIntervalSec) && e.tickIntervalSec > 0 ? e.tickIntervalSec : 30;
    const last = lastApplied[key] ?? 0;
    if (nowUnixSec - last < interval) continue;
    const before = profile.heat ?? 0;
    const inc = Number.isFinite(e.heatPerTick) ? e.heatPerTick : 1;
    profile.heat = clampInt((profile.heat ?? 0) + inc, 0, 9);
    lastApplied[key] = nowUnixSec;
    if (profile.heat !== before) {
      fired.push({ key, label: e.label ?? `${e.threadTitle} pressure`, delta: profile.heat - before, threadId: e.threadId });
    }
  }
  return fired;
}

function threadFactionPressure(thread, progress) {
  const result = {};
  const table = thread.factionPressure;
  if (!table || typeof table !== "object") return result;
  for (const [faction, deltas] of Object.entries(table)) {
    if (!Array.isArray(deltas)) continue;
    const delta = deltas[Math.min(progress, deltas.length - 1)];
    if (Number.isFinite(delta) && delta !== 0) {
      result[faction] = delta;
    }
  }
  return result;
}

export function applyPlotRepNudges(profile, updates) {
  const applied = [];
  for (const update of updates ?? []) {
    const nudges = update?.repNudges;
    if (!nudges) continue;
    for (const [faction, delta] of Object.entries(nudges)) {
      if (!FACTIONS[faction] || !Number.isFinite(delta) || delta === 0) continue;
      profile.rep[faction] = clampInt((profile.rep[faction] ?? 0) + delta, -9, 9);
      applied.push({ faction, delta, threadId: update.threadId });
    }
  }
  return applied;
}

export function normalizeProfile(value) {
  const next = structuredClone(DEFAULT_PROFILE);
  if (value && typeof value === "object") {
    if (typeof value.room === "string" && ROOMS[value.room]) next.room = value.room;
    for (const key of ["credits", "debt", "heat", "stress", "hp", "maxHp"]) {
      if (Number.isFinite(value[key])) next[key] = value[key];
    }
    if (value.stats && typeof value.stats === "object") {
      for (const stat of Object.keys(next.stats)) {
        if (Number.isFinite(value.stats[stat])) next.stats[stat] = value.stats[stat];
      }
    }
    if (value.rep && typeof value.rep === "object") {
      for (const faction of Object.keys(next.rep)) {
        if (Number.isFinite(value.rep[faction])) next.rep[faction] = value.rep[faction];
      }
    }
    if (Number.isFinite(value.chromeSlots)) {
      next.chromeSlots = value.chromeSlots;
    }
    if (Array.isArray(value.inventory)) {
      next.inventory = value.inventory
        .map((entry) => normalizeInventoryEntry(entry))
        .filter((entry) => entry != null)
        .slice(0, 12);
    }
    if (Array.isArray(value.chrome)) {
      next.chrome = value.chrome.filter((item) => typeof item === "string" && CYBERWARE[item]).slice(0, next.chromeSlots);
    }
    if (Array.isArray(value.grafted)) {
      next.grafted = value.grafted
        .map((entry) => normalizeInventoryEntry(entry))
        .filter((entry) => entry != null && BASE_ITEMS[entry.baseId]?.kind === "cyberware")
        .slice(0, next.chromeSlots);
    }
    // Equipment normalises against the schema in DEFAULT_PROFILE: weapon
    // is one inventory-instance object (or null); armor + cyberware each
    // hold a fixed set of named slots. Anything that doesn't normalise
    // is dropped to null.
    if (value.equipment && typeof value.equipment === "object") {
      if (value.equipment.weapon !== undefined) {
        const w = normalizeInventoryEntry(value.equipment.weapon);
        next.equipment.weapon = w && BASE_ITEMS[w.baseId]?.kind === "weapon" ? w : null;
      }
      if (value.equipment.armor && typeof value.equipment.armor === "object") {
        for (const slot of Object.keys(next.equipment.armor)) {
          const e = normalizeInventoryEntry(value.equipment.armor[slot]);
          next.equipment.armor[slot] = e && BASE_ITEMS[e.baseId]?.kind === "armor"
              && (BASE_ITEMS[e.baseId].slot ?? "torso") === slot ? e : null;
        }
      }
      if (value.equipment.cyberware && typeof value.equipment.cyberware === "object") {
        for (const slot of Object.keys(next.equipment.cyberware)) {
          const raw = value.equipment.cyberware[slot];
          if (typeof raw === "string" && CYBERWARE[raw] && (CYBERWARE[raw].slot ?? "dermal") === slot) {
            next.equipment.cyberware[slot] = raw;
          } else {
            const e = normalizeInventoryEntry(raw);
            next.equipment.cyberware[slot] = e && BASE_ITEMS[e.baseId]?.kind === "cyberware"
                && (BASE_ITEMS[e.baseId].slot ?? "dermal") === slot ? e : null;
          }
        }
      }
    }
    // One-shot migration from the pre-equipment schema: any catalogue
    // chrome the player owned and any rolled grafted instances slot in
    // automatically. Catalogue ids that lack a slot fall back to dermal.
    for (const id of next.chrome ?? []) {
      const slot = CYBERWARE[id]?.slot ?? "dermal";
      if (next.equipment.cyberware[slot] == null) next.equipment.cyberware[slot] = id;
    }
    next.chrome = [];
    for (const inst of next.grafted ?? []) {
      const slot = BASE_ITEMS[inst.baseId]?.slot ?? "dermal";
      if (next.equipment.cyberware[slot] == null) next.equipment.cyberware[slot] = inst;
    }
    next.grafted = [];
    if (value.prep && typeof value.prep === "object") {
      if (value.prep.statBoosts && typeof value.prep.statBoosts === "object") {
        for (const stat of Object.keys(next.prep.statBoosts)) {
          if (Number.isFinite(value.prep.statBoosts[stat])) next.prep.statBoosts[stat] = value.prep.statBoosts[stat];
        }
      }
      if (Number.isFinite(value.prep.heatBuffer)) next.prep.heatBuffer = value.prep.heatBuffer;
      if (typeof value.prep.note === "string" || value.prep.note === null) next.prep.note = value.prep.note;
    }
    if (typeof value.activeJobId === "string" || value.activeJobId === null) {
      next.activeJobId = value.activeJobId;
    }
    if (Array.isArray(value.completedJobs)) {
      next.completedJobs = [...new Set(value.completedJobs.filter((item) => typeof item === "string"))].slice(0, 24);
    }
    if (value.npcMemory && typeof value.npcMemory === "object") {
      next.npcMemory = Object.fromEntries(
        Object.entries(value.npcMemory)
          .filter(([_, memory]) => typeof memory === "string")
          .slice(0, 32)
      );
    }
    if (value.npcDialogue && typeof value.npcDialogue === "object") {
      const cleaned = {};
      for (const [name, log] of Object.entries(value.npcDialogue)) {
        if (typeof name !== "string" || !Array.isArray(log)) continue;
        const entries = log
          .filter((entry) => entry && typeof entry === "object" && typeof entry.text === "string")
          .slice(-DIALOGUE_LOG_MAX)
          .map((entry) => ({
            speaker: entry.speaker === "npc" ? "npc" : "player",
            text: String(entry.text).slice(0, 240),
            room: typeof entry.room === "string" ? entry.room : null,
            ts: typeof entry.ts === "string" ? entry.ts : null
          }));
        if (entries.length > 0) cleaned[name] = entries;
      }
      next.npcDialogue = cleaned;
    }
    if (Array.isArray(value.leads)) {
      next.leads = value.leads
        .filter((entry) => entry && typeof entry === "object" && typeof entry.text === "string")
        .map((entry) => ({
          text: String(entry.text).slice(0, 320),
          npc: typeof entry.npc === "string" ? entry.npc : null,
          room: typeof entry.room === "string" ? entry.room : null,
          ts: typeof entry.ts === "string" ? entry.ts : new Date().toISOString()
        }))
        .slice(-LEADS_MAX);
    }
    if (Array.isArray(value.achievements)) {
      next.achievements = value.achievements
        .filter((id) => typeof id === "string" && ACHIEVEMENTS[id])
        .filter((id, i, arr) => arr.indexOf(id) === i)
        .slice(0, 256);
    }
    if (Array.isArray(value.visitedRooms)) {
      next.visitedRooms = value.visitedRooms
        .filter((k) => typeof k === "string" && ROOMS[k])
        .filter((k, i, arr) => arr.indexOf(k) === i);
    }
    if (Array.isArray(value.lore)) {
      next.lore = value.lore
        .filter((l) => l && typeof l === "object" && typeof l.title === "string" && typeof l.body === "string")
        .map((l) => ({
          id: typeof l.id === "string" ? l.id : `l_${Date.now().toString(36)}_${Math.floor(Math.random() * 0xffff).toString(36)}`,
          refId: typeof l.refId === "string" ? l.refId : null,
          kind: ["narrative", "procedural"].includes(l.kind) ? l.kind : "narrative",
          title: String(l.title).slice(0, 80),
          form: typeof l.form === "string" ? l.form : "fragment",
          attribution: typeof l.attribution === "string" ? l.attribution : null,
          tags: Array.isArray(l.tags) ? l.tags.filter((t) => typeof t === "string").slice(0, 8) : [],
          body: String(l.body).slice(0, 4000),
          discoveredAt: typeof l.discoveredAt === "string" ? l.discoveredAt : new Date().toISOString(),
          discoveredVia: typeof l.discoveredVia === "string" ? l.discoveredVia : null,
          discoveredRoom: typeof l.discoveredRoom === "string" ? l.discoveredRoom : null,
          templateId: typeof l.templateId === "string" ? l.templateId : null,
          read: l.read === true
        }))
        .slice(-LORE_MAX);
    }
    if (Array.isArray(value.missions)) {
      next.missions = value.missions
        .filter((m) => m && typeof m === "object" && MISSION_KINDS.includes(m.kind) && typeof m.title === "string")
        .map((m) => ({
          id: typeof m.id === "string" ? m.id : `m_${Date.now().toString(36)}_${Math.floor(Math.random() * 0xffff).toString(36)}`,
          kind: m.kind,
          title: String(m.title).slice(0, 80),
          premise: typeof m.premise === "string" ? m.premise.slice(0, 320) : "",
          target: typeof m.target === "string" ? m.target : null,
          targetKind: ["room", "npc"].includes(m.targetKind) ? m.targetKind : null,
          difficulty: ["low", "medium", "high"].includes(m.difficulty) ? m.difficulty : "medium",
          givenBy: typeof m.givenBy === "string" ? m.givenBy : null,
          givenAt: typeof m.givenAt === "string" ? m.givenAt : new Date().toISOString(),
          status: ["open", "complete", "abandoned"].includes(m.status) ? m.status : "open",
          delveId: typeof m.delveId === "string" ? m.delveId : null,
          enemyHint: typeof m.enemyHint === "string" ? m.enemyHint : null,
          factionTension: typeof m.factionTension === "string" ? m.factionTension : null,
          exposition: Array.isArray(m.exposition) ? m.exposition.filter((s) => typeof s === "string").slice(0, 2) : null
        }))
        .slice(-MISSIONS_MAX);
    }
    if (Array.isArray(value.scavengedRooms)) {
      next.scavengedRooms = [...new Set(value.scavengedRooms.filter((item) => typeof item === "string" && ROOMS[item]))].slice(0, 32);
    }
  }
  return next;
}

export function jobsVisibleInRoom(roomKey, profile, jobs = JOBS) {
  return jobs.filter((job) =>
    job.room === roomKey
    && !profile.completedJobs.includes(job.id)
    && (profile.activeJobId === null || profile.activeJobId === job.id)
  );
}

export function findJob(targetRaw, roomKey, jobs = JOBS) {
  const target = normalizeToken(targetRaw);
  return jobs.find((job) =>
    job.room === roomKey && (normalizeToken(job.id) === target || normalizeToken(job.title) === target)
  ) ?? null;
}

export function findActiveJob(profile, jobs = JOBS) {
  if (!profile.activeJobId) return null;
  return jobs.find((job) => job.id === profile.activeJobId) ?? null;
}

export function assignJob(profile, job) {
  profile.activeJobId = job.id;
}

export function inventoryDetails(profile) {
  return (profile.inventory ?? []).map((entry) => {
    if (typeof entry === "string") {
      const item = ITEMS[entry];
      if (!item) return null;
      return { ...item, id: entry, isInstance: false };
    }
    if (isItemInstance(entry)) {
      const desc = instanceDescription(entry);
      return desc ? { ...desc, id: entry.instanceId, isInstance: true } : null;
    }
    return null;
  }).filter(Boolean);
}

export function installedChrome(profile) {
  // Catalogue chrome that's currently slotted in equipment.cyberware.
  // Rolled instances are not part of this list — the chrome screen
  // surfaces them separately.
  const out = [];
  for (const slot of CYBERWARE_SLOTS) {
    const v = profile?.equipment?.cyberware?.[slot];
    if (typeof v === "string" && CYBERWARE[v]) out.push(CYBERWARE[v]);
  }
  return out;
}

export function availableChrome(profile) {
  const owned = new Set(installedChrome(profile).map((c) => c.id));
  return Object.values(CYBERWARE).filter((chrome) => !owned.has(chrome.id));
}

export function vendorForRoom(roomKey, plotState = null) {
  const vendor = VENDORS[roomKey];
  if (!vendor) return null;
  const mult = plotState ? getVendorPriceMultiplier(plotState, roomKey) : 1;
  return {
    ...vendor,
    priceMultiplier: mult,
    stock: vendor.stock
      .map((entry) => ({
        ...entry,
        price: Math.round((entry.price ?? 0) * mult),
        basePrice: entry.price ?? 0,
        item: ITEMS[entry.itemId]
      }))
      .filter((entry) => entry.item)
  };
}

// Delves are pushable multi-stage encounters anchored to a launch room.
// Each delve is a graph of stages keyed by stage id. Stages have narration
// and choices; choices may carry a skillCheck or a flat effects array, plus
// a `next` stage id. Terminal stages set { terminal: true } and apply final
// effects on entry. Resolution uses the same effect adjudicator as talk.

// Modular loot system. Items in inventory can be either:
//   - flat string ids (consumables / legacy), referencing ITEMS in items.json
//   - instance objects { instanceId, baseId, rank, mods, computed, name },
//     produced by rollItem(). These persist through the BBS KV alongside
//     everything else in profile.inventory.

const RANK_TO_MOD_COUNT = {
  1: [0, 1],
  2: [1, 2],
  3: [2, 3],
  4: [2, 4],
  5: [3, 5]
};

function rollInt(rng, min, max) {
  const r = typeof rng === "function" ? rng : Math.random;
  return Math.floor(r() * (max - min + 1)) + min;
}

function pickN(rng, list, n) {
  if (!Array.isArray(list) || list.length === 0 || n <= 0) return [];
  const pool = [...list];
  const picked = [];
  const r = typeof rng === "function" ? rng : Math.random;
  while (pool.length > 0 && picked.length < n) {
    const idx = Math.floor(r() * pool.length);
    picked.push(pool.splice(idx, 1)[0]);
  }
  return picked;
}

export function rollItem(baseId, rank = 1, rng = Math.random) {
  const base = BASE_ITEMS[baseId];
  if (!base) return null;
  const safeRank = Math.max(1, Math.min(5, Math.floor(rank) || 1));
  const [minMods, maxMods] = RANK_TO_MOD_COUNT[safeRank] ?? [0, 1];
  const slotCap = Math.min(base.modSlots ?? 0, maxMods);
  const target = Math.min(slotCap, Math.max(0, rollInt(rng, minMods, maxMods)));

  const eligibleMods = Object.values(MODIFIERS).filter((m) => {
    if (!m.appliesToKinds.includes(base.kind)) return false;
    if (Array.isArray(m.requiresTags) && m.requiresTags.length > 0) {
      const tags = base.tags ?? [];
      for (const t of m.requiresTags) {
        if (!tags.includes(t)) return false;
      }
    }
    return m.rank <= safeRank;
  });

  const mods = pickN(rng, eligibleMods, target).map((m) => m.id);

  const computed = { ...(base.baseEffects ?? {}) };
  for (const modId of mods) {
    const mod = MODIFIERS[modId];
    if (!mod?.effects) continue;
    for (const [stat, delta] of Object.entries(mod.effects)) {
      computed[stat] = (computed[stat] ?? 0) + delta;
    }
  }

  const name = composeItemName(base, mods);
  const instanceId = `i_${Date.now().toString(36)}_${Math.floor(Math.random() * 0xffffff).toString(36)}`;

  return {
    instanceId,
    baseId,
    rank: safeRank,
    mods,
    computed,
    name
  };
}

function composeItemName(base, modIds) {
  const prefixes = [];
  const suffixes = [];
  for (const id of modIds) {
    const mod = MODIFIERS[id];
    if (!mod) continue;
    if (mod.namePrefix) prefixes.push(mod.namePrefix);
    if (mod.nameSuffix) suffixes.push(mod.nameSuffix);
  }
  const prefix = prefixes.join("");
  const suffix = suffixes.join(" ");
  const name = `${prefix}${base.name}${suffix ? " " + suffix : ""}`.trim();
  return name;
}

export function isItemInstance(entry) {
  return entry && typeof entry === "object" && typeof entry.baseId === "string" && BASE_ITEMS[entry.baseId];
}

export function instanceDescription(instance) {
  if (!isItemInstance(instance)) return null;
  const base = BASE_ITEMS[instance.baseId];
  const stats = Object.entries(instance.computed ?? {})
    .filter(([, v]) => v !== 0 && Number.isFinite(v))
    .map(([k, v]) => `${k} ${v >= 0 ? "+" : ""}${v}`)
    .join(" · ");
  return {
    name: instance.name ?? base.name,
    base: base.name,
    kind: base.kind,
    rank: instance.rank ?? 1,
    mods: (instance.mods ?? []).map((id) => MODIFIERS[id]?.name ?? id),
    stats,
    summary: base.summary
  };
}

function normalizeInventoryEntry(entry) {
  if (typeof entry === "string") {
    return ITEMS[entry] ? entry : null;
  }
  if (!entry || typeof entry !== "object") return null;
  if (typeof entry.baseId !== "string" || !BASE_ITEMS[entry.baseId]) return null;
  // Re-normalise the instance: clamp rank, drop unknown mods, recompute stats
  const base = BASE_ITEMS[entry.baseId];
  const safeRank = Math.max(1, Math.min(5, Math.floor(entry.rank ?? 1) || 1));
  const mods = Array.isArray(entry.mods)
    ? entry.mods.filter((m) => typeof m === "string" && MODIFIERS[m]).slice(0, base.modSlots ?? 4)
    : [];
  const computed = { ...(base.baseEffects ?? {}) };
  for (const modId of mods) {
    const mod = MODIFIERS[modId];
    for (const [stat, delta] of Object.entries(mod.effects ?? {})) {
      computed[stat] = (computed[stat] ?? 0) + delta;
    }
  }
  const name = typeof entry.name === "string" && entry.name.trim() ? entry.name.trim() : composeItemName(base, mods);
  const instanceId = typeof entry.instanceId === "string" ? entry.instanceId : `i_${Date.now().toString(36)}_${Math.floor(Math.random() * 0xffffff).toString(36)}`;
  return { instanceId, baseId: entry.baseId, rank: safeRank, mods, computed, name };
}

export function delvesInRoom(roomKey) {
  return Object.values(DELVES).filter((d) => d.room === roomKey);
}

export function delveById(id) {
  return DELVES[id] ?? null;
}

export function delveStage(delveId, stageId) {
  const delve = DELVES[delveId];
  if (!delve) return null;
  return delve.stages?.[stageId] ?? null;
}

// Resolve a player's choice within a delve stage. Returns:
//   { nextStageId, applied, narration }
// where `applied` is the same shape as applyTalkEffects' return (so the
// caller can render rngLog / leads / repNudges / skill check details).
// The caller is responsible for calling resolveDelveTerminal() when
// next stage's `terminal` flag is set.
export function resolveDelveChoice(profile, delve, stage, choice, ctx = {}) {
  const sceneEffects = [];
  if (choice.skillCheck) {
    sceneEffects.push({
      op: "skill_check",
      stat: choice.skillCheck.stat,
      dc: Number(choice.skillCheck.dc) || 1,
      tag: choice.skillCheck.tag ?? choice.label ?? "",
      onSuccess: { narration: [], effects: validateEffects(choice.skillCheck.onSuccess ?? []) },
      onFailure: { narration: [], effects: validateEffects(choice.skillCheck.onFailure ?? []) }
    });
  }
  for (const fx of validateEffects(choice.effects ?? [])) {
    sceneEffects.push(fx);
  }
  const applied = applyTalkEffects(profile, ctx.plotState ?? {}, { effects: sceneEffects }, ctx);
  return {
    nextStageId: choice.next ?? null,
    applied
  };
}

// ---------------------------------------------------------------------------
// Combat
// ---------------------------------------------------------------------------
//
// Combat is one stage kind inside a delve. The delve screen owns the
// per-encounter runtime state (enemy.hp, defending flag, round counter,
// combat log). The functions below are pure resolvers that report what
// happened so the delve screen can render and persist as it sees fit.

// Returns the player's equipped main-hand weapon as an inventory
// instance, or null if they're swinging fists. Used by combat math.
export function findBestWeapon(profile) {
  return profile?.equipment?.weapon ?? null;
}

// ---------------------------------------------------------------------------
// Equipment slots
// ---------------------------------------------------------------------------
//
// Equipment is the canonical source of "what the player has on" — weapon
// (one main-hand slot), armor (six body slots), cyberware (six implant
// slots). Each slot is null or an item instance / catalogue id. The
// inventory holds everything that *isn't* equipped; equipping moves an
// item out of inventory into a slot, and any item that was in the slot
// goes back to inventory. effectiveStats and passive heal aggregate
// from these slots — nothing else.

export const ARMOR_SLOTS = ["head", "torso", "pants", "shoes", "gloves", "eyes"];
export const CYBERWARE_SLOTS = ["neural", "optical", "respiratory", "dermal", "arm", "leg"];

export function slotForItem(itemOrId) {
  if (!itemOrId) return null;
  if (typeof itemOrId === "string") {
    return CYBERWARE[itemOrId]?.slot ?? null;
  }
  if (isItemInstance(itemOrId)) {
    const base = BASE_ITEMS[itemOrId.baseId];
    if (!base) return null;
    return base.slot ?? (base.kind === "armor" ? "torso" : null);
  }
  return null;
}

export function kindForItem(itemOrId) {
  if (!itemOrId) return null;
  if (typeof itemOrId === "string") {
    return CYBERWARE[itemOrId] ? "cyberware" : null;
  }
  if (isItemInstance(itemOrId)) {
    return BASE_ITEMS[itemOrId.baseId]?.kind ?? null;
  }
  return null;
}

// Find an inventory item by case-insensitive name / baseId / instanceId
// substring match. Returns { index, entry } or null.
export function findInventoryEquippable(profile, targetRaw) {
  const target = normalizeToken(targetRaw);
  if (!target) return null;
  const inv = profile?.inventory ?? [];
  for (let i = 0; i < inv.length; i++) {
    const entry = inv[i];
    if (!isItemInstance(entry)) continue;
    const base = BASE_ITEMS[entry.baseId];
    if (!base) continue;
    const kind = base.kind;
    if (kind !== "weapon" && kind !== "armor" && kind !== "cyberware") continue;
    const candidates = [entry.baseId, base.name, entry.name, entry.instanceId];
    if (candidates.some((c) => normalizeToken(c) === target)) {
      return { index: i, entry };
    }
  }
  return null;
}

// Move an inventory entry into the right slot. Returns
// { ok, message, slot, kind, displaced? }. The previous occupant of
// the slot (if any) lands back in inventory.
export function equipFromInventory(profile, targetRaw) {
  const found = findInventoryEquippable(profile, targetRaw);
  if (!found) {
    return { ok: false, message: `You aren't carrying anything called "${targetRaw}".` };
  }
  const { entry } = found;
  const kind = kindForItem(entry);
  const slot = slotForItem(entry);
  if (kind === "weapon") {
    const previous = profile.equipment.weapon;
    profile.equipment.weapon = entry;
    profile.inventory.splice(found.index, 1);
    if (previous && profile.inventory.length < 12) profile.inventory.push(previous);
    return { ok: true, slot: "main_hand", kind, displaced: previous,
             message: `You shoulder the ${entry.name}.` };
  }
  if (kind === "armor") {
    if (!ARMOR_SLOTS.includes(slot)) {
      return { ok: false, message: `${entry.name} doesn't have a clean armour slot. (slot=${slot})` };
    }
    const previous = profile.equipment.armor[slot];
    profile.equipment.armor[slot] = entry;
    profile.inventory.splice(found.index, 1);
    if (previous && profile.inventory.length < 12) profile.inventory.push(previous);
    return { ok: true, slot, kind, displaced: previous,
             message: `${entry.name} fits the ${slot} slot.` };
  }
  if (kind === "cyberware") {
    if (!CYBERWARE_SLOTS.includes(slot)) {
      return { ok: false, message: `${entry.name} doesn't have a clean cyberware slot. (slot=${slot})` };
    }
    const previous = profile.equipment.cyberware[slot];
    profile.equipment.cyberware[slot] = entry;
    profile.inventory.splice(found.index, 1);
    if (previous && typeof previous !== "string" && profile.inventory.length < 12) {
      profile.inventory.push(previous);
    }
    // maxHp_bonus on graft: lift the cap and heal by the same amount.
    const bonus = Number(entry.computed?.maxHp_bonus ?? 0);
    if (bonus > 0) {
      profile.maxHp = clampInt((profile.maxHp ?? 10) + bonus, 1, 20);
      profile.hp = clampInt((profile.hp ?? 0) + bonus, 0, profile.maxHp);
    }
    profile.stress = clampInt((profile.stress ?? 0) + 1, 0, 9);
    return { ok: true, slot, kind, displaced: previous,
             message: `${entry.name} grafts into the ${slot} slot.` };
  }
  return { ok: false, message: `${entry.name} isn't equippable.` };
}

// Pull whatever's in {slotKey} (one of "weapon" or one of the armor /
// cyberware slot names) back into inventory. No-op if nothing's there.
export function unequipSlot(profile, slotKey) {
  if (slotKey === "weapon" || slotKey === "main_hand") {
    const cur = profile.equipment.weapon;
    if (!cur) return { ok: false, message: "Nothing in main hand." };
    if (profile.inventory.length >= 12) return { ok: false, message: "Pockets are full." };
    profile.equipment.weapon = null;
    profile.inventory.push(cur);
    return { ok: true, slot: "main_hand", message: `You holster the ${cur.name}.` };
  }
  if (ARMOR_SLOTS.includes(slotKey)) {
    const cur = profile.equipment.armor[slotKey];
    if (!cur) return { ok: false, message: `Nothing on ${slotKey}.` };
    if (profile.inventory.length >= 12) return { ok: false, message: "Pockets are full." };
    profile.equipment.armor[slotKey] = null;
    profile.inventory.push(cur);
    return { ok: true, slot: slotKey, message: `You strip the ${cur.name}.` };
  }
  if (CYBERWARE_SLOTS.includes(slotKey)) {
    const cur = profile.equipment.cyberware[slotKey];
    if (!cur) return { ok: false, message: `${slotKey} slot is empty.` };
    if (typeof cur === "string") {
      // Catalogue chrome can't be popped out — surgery's surgery.
      return { ok: false, message: `${CYBERWARE[cur]?.name ?? cur} is wired in too deep to pull. (catalogue chrome is permanent)` };
    }
    if (profile.inventory.length >= 12) return { ok: false, message: "Pockets are full." };
    profile.equipment.cyberware[slotKey] = null;
    profile.inventory.push(cur);
    // Reverse the on-graft maxHp bonus.
    const bonus = Number(cur.computed?.maxHp_bonus ?? 0);
    if (bonus > 0) {
      profile.maxHp = Math.max(1, (profile.maxHp ?? 10) - bonus);
      profile.hp = Math.min(profile.hp ?? 0, profile.maxHp);
    }
    return { ok: true, slot: slotKey, message: `You pop the ${cur.name} free.` };
  }
  return { ok: false, message: `Unknown slot: ${slotKey}` };
}

/** Flatten all currently equipped slots that contribute computed stats —
 *  used by effectiveStats and passiveHealRate. Yields { item, computed }
 *  where item may be a catalogue object (CYBERWARE[id]) or an item instance. */
export function* iterEquipped(profile) {
  const eq = profile?.equipment;
  if (!eq) return;
  if (eq.weapon) yield { item: eq.weapon, computed: eq.weapon.computed ?? {} };
  for (const slot of ARMOR_SLOTS) {
    const a = eq.armor?.[slot];
    if (a) yield { item: a, computed: a.computed ?? {} };
  }
  for (const slot of CYBERWARE_SLOTS) {
    const c = eq.cyberware?.[slot];
    if (!c) continue;
    if (typeof c === "string") {
      const def = CYBERWARE[c];
      if (!def) continue;
      // Catalogue chrome's effects.stats lives under a different shape;
      // expose as edge_bonus/etc keys so the same reader logic works.
      const computed = {};
      for (const stat of Object.keys(def.effects?.stats ?? {})) {
        computed[`${stat}_bonus`] = def.effects.stats[stat];
      }
      if (def.effects?.maxHp) computed.maxHp_bonus = def.effects.maxHp;
      if (def.effects?.heatFloorBonus) computed.heat_floor = def.effects.heatFloorBonus;
      yield { item: def, computed };
    } else {
      yield { item: c, computed: c.computed ?? {} };
    }
  }
}

export function findHealingItem(profile) {
  for (let i = 0; i < (profile?.inventory ?? []).length; i++) {
    const entry = profile.inventory[i];
    if (typeof entry !== "string") continue;
    const item = ITEMS[entry];
    if (item?.use?.hpDelta && item.use.hpDelta > 0) {
      return { index: i, itemId: entry, item };
    }
  }
  return null;
}

export function newCombatState(enemy) {
  return {
    enemyId: enemy.id,
    enemyName: enemy.name,
    enemyHp: enemy.hp,
    enemyMaxHp: enemy.hp,
    defending: false,
    round: 1,
    log: []
  };
}

export function resolvePlayerAttack(profile, enemy, stat, rng = Math.random) {
  const stats = effectiveStats(profile);
  const weapon = findBestWeapon(profile);
  const accuracy = weapon?.computed?.accuracy ?? 0;
  const baseStat = stats[stat] ?? stats.body ?? 1;
  const roll = Math.floor(rng() * 6) + 1;
  const total = roll + baseStat + accuracy;
  const threshold = 4 + (enemy.defense ?? 0);
  const hit = total >= threshold;
  const isCrit = roll === 6 && (weapon?.computed?.crit ?? 0) > 0;
  const damage = hit ? (weapon?.computed?.damage ?? 1) + (isCrit ? 1 : 0) : 0;
  return {
    weapon,
    weaponName: weapon?.name ?? "fists",
    stat,
    statValue: baseStat,
    accuracy,
    roll,
    total,
    threshold,
    hit,
    isCrit,
    damage
  };
}

export function resolveEnemyAttack(profile, enemy, defending, rng = Math.random) {
  const stats = effectiveStats(profile);
  const attacks = enemy.attacks ?? [{ name: "strike", damage: 1, accuracy: 0 }];
  const attack = attacks[Math.floor(rng() * attacks.length)];
  const roll = Math.floor(rng() * 6) + 1;
  const total = roll + (attack.accuracy ?? 0) + (enemy.tier ?? 1);
  const dodge = (stats.ghost ?? 1) + (defending ? 2 : 0);
  const threshold = 4 + dodge;
  const hit = total >= threshold;
  const rawDamage = attack.damage ?? 1;
  const armor = effectiveDefense(profile);
  const damage = hit ? Math.max(0, rawDamage - armor - (defending ? 1 : 0)) : 0;
  return { attack, roll, total, threshold, hit, damage, defending, armor };
}

export function resolveFlee(profile, enemy, rng = Math.random) {
  const stats = effectiveStats(profile);
  const roll = Math.floor(rng() * 6) + 1;
  const total = roll + (stats.ghost ?? 1);
  const threshold = 4 + (enemy.tier ?? 1);
  return { roll, total, threshold, success: total >= threshold };
}

// ---------------------------------------------------------------------------
// Procedural delves
// ---------------------------------------------------------------------------
//
// Templates declare a stage graph with {SLOT} placeholders and a slots
// catalog that supplies values. rollProceduralDelve() picks slot values,
// substitutes them throughout, and returns a concrete delve object that
// the delve screen can run unchanged. Procedural delves live on the
// session (not in DELVES) and are ephemeral.

function substituteSlots(value, slots) {
  if (typeof value === "string") {
    return value.replace(/\{([A-Z0-9_]+)\}/g, (m, key) => {
      if (Object.prototype.hasOwnProperty.call(slots, key)) {
        return String(slots[key]);
      }
      return m;
    });
  }
  if (Array.isArray(value)) return value.map((v) => substituteSlots(v, slots));
  if (value && typeof value === "object") {
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = substituteSlots(v, slots);
    return out;
  }
  return value;
}

export function rollProceduralDelve(templateId, opts = {}) {
  const tmpl = DELVE_TEMPLATES[templateId];
  if (!tmpl) return null;
  const rng = opts.rng ?? Math.random;
  const slotValues = {};
  for (const [slot, options] of Object.entries(tmpl.slots ?? {})) {
    slotValues[slot] = options[Math.floor(rng() * options.length)];
  }
  const stages = {};
  for (const [stageId, stageTpl] of Object.entries(tmpl.stages ?? {})) {
    stages[stageId] = substituteSlots(stageTpl, slotValues);
  }
  const generatedId = `${tmpl.id}_${Date.now().toString(36)}_${Math.floor(rng() * 0xfff).toString(36)}`;
  return {
    id: generatedId,
    title: tmpl.title,
    summary: tmpl.summary,
    operator: tmpl.operator,
    room: opts.room ?? null,
    entry: tmpl.entry,
    rumour: tmpl.rumour,
    stages,
    isProcedural: true,
    templateId,
    slotValues
  };
}

// Achievements — door-side check + persistence. Returns the catalog entry
// when the achievement is FRESHLY unlocked (so the caller can notify and
// send to the BBS); returns null if the id is unknown OR already unlocked.
// Caller is responsible for the BBS-side send and the player notify.
export function awardAchievement(profile, achievementId) {
  const ach = ACHIEVEMENTS[achievementId];
  if (!ach) return null;
  if (!Array.isArray(profile.achievements)) profile.achievements = [];
  if (profile.achievements.includes(achievementId)) return null;
  profile.achievements.push(achievementId);
  return ach;
}

export function totalAchievementPoints(profile) {
  if (!Array.isArray(profile?.achievements)) return 0;
  return profile.achievements.reduce((acc, id) => acc + (ACHIEVEMENTS[id]?.points ?? 0), 0);
}

// Convenience: check a numeric threshold and award if hit. Returns the
// freshly-unlocked entry or null.
export function awardIf(profile, achievementId, condition) {
  if (!condition) return null;
  return awardAchievement(profile, achievementId);
}

// Generate a procedural lore piece from a template id. Picks slot values
// at random, substitutes throughout the body. Returns { title, body, form,
// tags, attribution } or null on failure.
export function generateProceduralLore(templateId, rng = Math.random) {
  const tmpl = LORE_TEMPLATES[templateId];
  if (!tmpl) return null;
  const slotValues = {};
  for (const [slot, options] of Object.entries(tmpl.slots ?? {})) {
    if (!Array.isArray(options) || options.length === 0) continue;
    slotValues[slot] = options[Math.floor(rng() * options.length)];
  }
  const body = String(tmpl.body ?? "").replace(/\{([A-Z0-9_]+)\}/g, (m, key) => {
    return Object.prototype.hasOwnProperty.call(slotValues, key) ? String(slotValues[key]) : m;
  });
  return {
    title: tmpl.title ?? templateId,
    body,
    form: tmpl.form ?? "fragment",
    tags: Array.isArray(tmpl.tags) ? [...tmpl.tags, "procedural"] : ["procedural"],
    attribution: tmpl.attribution ?? null
  };
}

// Pick a procedural lore template eligible in the given room. Weighted
// random across the eligible set.
export function pickEligibleLoreTemplate(roomKey, rng = Math.random) {
  const eligible = Object.values(LORE_TEMPLATES).filter(
    (t) => Array.isArray(t.appliesIn) && t.appliesIn.includes(roomKey)
  );
  if (eligible.length === 0) return null;
  const totalWeight = eligible.reduce((acc, t) => acc + (t.weight ?? 1), 0);
  let pick = rng() * totalWeight;
  for (const t of eligible) {
    pick -= (t.weight ?? 1);
    if (pick <= 0) return t;
  }
  return eligible[eligible.length - 1];
}

// Generate a procedural delve from a COMBAT_MISSION proposal. Falls back to
// the wildlife_corridor template but injects the mission's title, premise,
// enemy hint, and faction-tension exposition into the stages.
function generateMissionDelve(mission, roomKey) {
  if (!roomKey || !ROOMS[roomKey]) {
    // Pick the giver's room or fall back to alley. The mission still spawns
    // somewhere navigable.
    roomKey = "alley";
  }
  const eligible = eligibleTemplatesForRoom(roomKey);
  const tmpl = eligible.find((t) => t.id === "wildlife_corridor") ?? eligible[0];
  if (!tmpl) return null;
  const delve = rollProceduralDelve(tmpl.id, { room: roomKey });
  if (!delve) return null;

  // Override surface fields with the mission's flavor.
  delve.title = mission.title;
  delve.summary = mission.premise;
  delve.operator = mission.givenBy ?? delve.operator;

  // Inject exposition into the entry stage's narration.
  if (mission.exposition && mission.exposition.length > 0 && delve.stages?.[delve.entry]) {
    delve.stages[delve.entry].narrate = [
      ...mission.exposition,
      ...(delve.stages[delve.entry].narrate ?? [])
    ];
  }

  // If the proposal hinted at a specific enemy faction or kind, try to swap
  // the combat stage's enemy to something matching. Fallback keeps whatever
  // the template rolled.
  if (mission.enemyHint) {
    const hint = mission.enemyHint;
    const candidate = Object.values(ENEMIES).find((e) =>
      (e.tags ?? []).some((t) => t.toLowerCase().includes(hint))
    );
    if (candidate) {
      for (const stage of Object.values(delve.stages ?? {})) {
        if (stage.kind === "combat") stage.enemyId = candidate.id;
      }
    }
  }

  return delve;
}

export function eligibleTemplatesForRoom(roomKey) {
  return Object.values(DELVE_TEMPLATES).filter(
    (t) => Array.isArray(t.appliesTo) && t.appliesTo.includes(roomKey)
  );
}

export function applyTerminalStage(profile, plotState, stage, ctx = {}) {
  if (!stage) return null;
  const applied = applyTalkEffects(profile, plotState ?? {}, {
    effects: validateEffects(stage.effects ?? [])
  }, ctx);
  return applied;
}

export function findVendorItem(roomKey, targetRaw, plotState = null) {
  const vendor = vendorForRoom(roomKey, plotState);
  if (!vendor) return null;
  const target = normalizeToken(targetRaw);
  return vendor.stock.find((entry) =>
    normalizeToken(entry.itemId) === target || normalizeToken(entry.item.name) === target
  ) ?? null;
}

export function useInventoryItem(profile, targetRaw) {
  const target = normalizeToken(targetRaw);
  const index = profile.inventory.findIndex((itemId) => {
    const item = ITEMS[itemId];
    return normalizeToken(itemId) === target || normalizeToken(item?.name) === target;
  });
  if (index === -1) return { ok: false, message: "You do not have that item." };

  const itemId = profile.inventory[index];
  const item = ITEMS[itemId];
  if (!item?.use) {
    return { ok: false, message: `${item?.name ?? "That"} is not something you can use right now.` };
  }

  profile.inventory.splice(index, 1);
  const hpDelta = clampInt(item.use.hpDelta ?? 0, -9, 9);
  const stressDelta = clampInt(item.use.stressDelta ?? 0, -9, 9);
  const heatDelta = clampInt(item.use.heatDelta ?? 0, -9, 9);

  profile.hp = clampInt(profile.hp + hpDelta, 0, profile.maxHp);
  profile.stress = clampInt(profile.stress + stressDelta, 0, 9);
  profile.heat = clampInt(profile.heat + heatDelta, 0, 9);

  if (item.use.prep) {
    for (const stat of Object.keys(profile.prep.statBoosts)) {
      const delta = item.use.prep.statBoosts?.[stat] ?? 0;
      profile.prep.statBoosts[stat] = clampInt(profile.prep.statBoosts[stat] + delta, 0, 3);
    }
    profile.prep.heatBuffer = clampInt(profile.prep.heatBuffer + (item.use.prep.heatBuffer ?? 0), 0, 3);
    profile.prep.note = item.use.prep.note ?? profile.prep.note;
  }

  return {
    ok: true,
    item,
    message: item.use.message,
    deltas: { hpDelta, stressDelta, heatDelta },
    prep: structuredClone(profile.prep)
  };
}

// Install path. First tries the catalogue (CYBERWARE map → static
// chrome package, paid for here). If that misses, looks for a rolled
// cyberware item instance in the player's inventory and grafts it
// (the player already paid for it by looting / hunting). The two
// occupy the same chromeSlots budget so the player has to choose.
// Install a catalogue chrome package (CYBERWARE map). Pays the credit
// + debt + stress cost, then drops into equipment.cyberware[slot]. The
// previous occupant of the slot, if it was an inventory instance, goes
// back to inventory; if it was another piece of catalogue chrome we
// refuse — surgery is one-way.
export function installCyberware(profile, targetRaw) {
  const target = normalizeToken(targetRaw);
  const chrome = Object.values(CYBERWARE).find((item) =>
    normalizeToken(item.id) === target || normalizeToken(item.name) === target
  );
  if (chrome) {
    const slot = chrome.slot ?? "dermal";
    if (!CYBERWARE_SLOTS.includes(slot)) {
      return { ok: false, message: `${chrome.name} has an unknown slot: ${slot}.` };
    }
    const cur = profile.equipment.cyberware[slot];
    if (typeof cur === "string") {
      return { ok: false, message: `Your ${slot} slot already runs ${CYBERWARE[cur]?.name ?? cur} — surgery is one-way.` };
    }
    if (profile.credits < chrome.price) {
      return { ok: false, message: `${chrome.name} costs ${chrome.price}c.` };
    }
    profile.credits -= chrome.price;
    profile.debt = Math.max(0, profile.debt + chrome.debtCost);
    profile.stress = clampInt(profile.stress + chrome.surgeryStress, 0, 9);
    if (chrome.effects.maxHp) {
      profile.maxHp = clampInt(profile.maxHp + chrome.effects.maxHp, 1, 20);
    }
    if (chrome.effects.healOnInstall) {
      profile.hp = clampInt(profile.hp + chrome.effects.healOnInstall, 0, profile.maxHp);
    } else {
      profile.hp = clampInt(profile.hp, 0, profile.maxHp);
    }
    if (cur && typeof cur !== "string" && profile.inventory.length < 12) {
      profile.inventory.push(cur);
    }
    profile.equipment.cyberware[slot] = chrome.id;
    return {
      ok: true,
      chrome,
      slot,
      message: `${chrome.name} seats under the skin and changes the math of your next bad choice.`
    };
  }
  // Inventory match — equip the instance via the generic equip helper.
  const found = findInventoryEquippable(profile, targetRaw);
  if (found && BASE_ITEMS[found.entry.baseId]?.kind === "cyberware") {
    return equipFromInventory(profile, targetRaw);
  }
  return { ok: false, message: "No such chrome package is on the tray." };
}

export async function resolveJob(session, job, room, llmEnabled, plotState = defaultPlotState(), chosenApproach = null, chosenTactic = null) {
  if (job.operation === "hunt") {
    return resolveHuntJob(session, job, room, llmEnabled, plotState, chosenApproach, chosenTactic);
  }
  const approach = chosenApproach ?? strongestApproach(session.data.profile, job.hooks);
  const fallback = fallbackJobOutcome(session, job, room, approach, chosenTactic);
  if (!llmEnabled) {
    console.log(`[cityline] run fallback job=${job.id} reason=llm-disabled`);
    return fallback;
  }

  try {
    const system = [
      "You are the shard oracle for a cyberpunk BBS door game.",
      "Return only strict JSON.",
      "Write 2-4 short atmospheric narration lines.",
      "Choose conservative game deltas inside the supplied bounds.",
      "Never invent fields that are not requested."
    ].join(" ");

    const prompt = {
      player: {
        handle: session.handle,
        room: room.name,
        stats: effectiveStats(session.data.profile),
        rawStats: session.data.profile.stats,
        prep: session.data.profile.prep,
        credits: session.data.profile.credits,
        debt: session.data.profile.debt,
        heat: session.data.profile.heat,
        stress: session.data.profile.stress,
        hp: session.data.profile.hp
      },
      job: {
        id: job.id,
        title: job.title,
        summary: job.summary,
        faction: FACTIONS[job.faction]?.name ?? job.faction,
        difficulty: job.difficulty,
        recommendedApproach: approach,
        selectedTactic: chosenTactic,
        hooks: job.hooks,
        salvage: job.salvage ?? [],
        plot: job.plot ? {
          ...PLOT_THREADS[job.plot],
          state: plotState[job.plot] ?? defaultPlotState()[job.plot]
        } : null
      },
      constraints: {
        creditDeltaRange: [Math.max(10, job.reward.credits - 25), job.reward.credits + 20],
        heatDeltaRange: [-1, Math.max(3, job.risk.heat)],
        stressDeltaRange: [0, 3],
        hpDeltaRange: [-3, 1],
        allowedFactionKeys: Object.keys(FACTIONS),
        allowedLootIds: job.salvage ?? []
      },
      output: {
        narration: ["short line 1", "short line 2"],
        outcome: "success | mixed | failure",
        creditDelta: 0,
        debtDelta: 0,
        heatDelta: 0,
        stressDelta: 0,
        hpDelta: 0,
        factionKey: "couriers",
        repDelta: 0,
        rumor: "wire:// something brief",
        itemDropId: "signal_jammer"
      }
    };

    const completion = await session.llm.chat([
      { role: "system", content: system },
      { role: "user", content: JSON.stringify(prompt) }
    ], { temperature: 0.9 });

    const parsed = parseJsonObject(completion.content);
    if (!parsed) return fallback;
    const factionKey = typeof parsed.factionKey === "string" && FACTIONS[parsed.factionKey]
      ? parsed.factionKey
      : job.faction;
    return {
      narration: sanitizeLines(parsed.narration, fallback.narration),
      outcome: oneOf(parsed.outcome, ["success", "mixed", "failure"], fallback.outcome),
      approachUsed: approach,
      tacticUsed: chosenTactic,
      creditDelta: clampNumber(parsed.creditDelta, Math.max(10, job.reward.credits - 25), job.reward.credits + 20, fallback.creditDelta),
      debtDelta: clampNumber(parsed.debtDelta, -40, 20, fallback.debtDelta),
      heatDelta: clampNumber(parsed.heatDelta, -1, Math.max(3, job.risk.heat), fallback.heatDelta),
      stressDelta: clampNumber(parsed.stressDelta, 0, 3, fallback.stressDelta),
      hpDelta: clampNumber(parsed.hpDelta, -3, 1, fallback.hpDelta),
      factionKey,
      repDelta: clampNumber(parsed.repDelta, -1, 3, fallback.repDelta),
      rumor: typeof parsed.rumor === "string" && parsed.rumor.trim() ? parsed.rumor.trim() : fallback.rumor,
      itemDropId: validLootId(parsed.itemDropId, job.salvage, fallback.itemDropId)
    };
  } catch (error) {
    console.error(`[cityline] run llm failure job=${job.id}:`, error?.code ?? error?.message ?? error);
    return fallback;
  }
}

async function resolveHuntJob(session, job, room, llmEnabled, plotState = defaultPlotState(), chosenApproach = null, chosenTactic = null) {
  const approach = chosenApproach ?? strongestApproach(session.data.profile, job.hooks);
  const fallback = fallbackHuntOutcome(session, job, room, approach, chosenTactic);
  if (!llmEnabled) {
    console.log(`[cityline] run fallback hunt=${job.id} reason=llm-disabled`);
    return fallback;
  }

  try {
    const system = [
      "You are the shard oracle for a cyberpunk BBS door game.",
      "Resolve a target hunt as a short narrative combat sequence.",
      "Return only strict JSON.",
      "Write terse, vivid, second-person prose grounded in the target and room.",
      "Keep combat rounds cinematic but compact.",
      "Never invent fields that are not requested."
    ].join(" ");

    const prompt = {
      player: {
        handle: session.handle,
        room: room.name,
        stats: effectiveStats(session.data.profile),
        rawStats: session.data.profile.stats,
        prep: session.data.profile.prep,
        credits: session.data.profile.credits,
        debt: session.data.profile.debt,
        heat: session.data.profile.heat,
        stress: session.data.profile.stress,
        hp: session.data.profile.hp
      },
      job: {
        id: job.id,
        title: job.title,
        summary: job.summary,
        faction: FACTIONS[job.faction]?.name ?? job.faction,
        difficulty: job.difficulty,
        recommendedApproach: approach,
        selectedTactic: chosenTactic,
        hooks: job.hooks,
        salvage: job.salvage ?? [],
        target: job.target,
        plot: job.plot ? {
          ...PLOT_THREADS[job.plot],
          state: plotState[job.plot] ?? defaultPlotState()[job.plot]
        } : null
      },
      constraints: {
        creditDeltaRange: [Math.max(20, job.reward.credits - 20), job.reward.credits + 20],
        heatDeltaRange: [0, Math.max(4, job.risk.heat)],
        stressDeltaRange: [0, 4],
        hpDeltaRange: [-4, 1],
        allowedFactionKeys: Object.keys(FACTIONS),
        allowedLootIds: job.salvage ?? [],
        combatRounds: [2, 4]
      },
      output: {
        narration: ["short line 1", "short line 2"],
        outcome: "success | mixed | failure",
        creditDelta: 0,
        debtDelta: 0,
        heatDelta: 0,
        stressDelta: 0,
        hpDelta: 0,
        factionKey: job.faction,
        repDelta: 0,
        rumor: "wire:// brief rumor",
        itemDropId: "signal_jammer",
        combat: {
          trail: "one short setup line about finding the target",
          targetName: job.target?.name,
          targetRole: job.target?.role,
          targetLook: "one short visual line",
          rounds: ["combat beat 1", "combat beat 2"],
          finisher: "one short final line"
        }
      }
    };

    const completion = await session.llm.chat([
      { role: "system", content: system },
      { role: "user", content: JSON.stringify(prompt) }
    ], { temperature: 0.95 });

    const parsed = parseJsonObject(completion.content);
    if (!parsed) return fallback;
    const factionKey = typeof parsed.factionKey === "string" && FACTIONS[parsed.factionKey]
      ? parsed.factionKey
      : job.faction;
    return {
      narration: sanitizeLines(parsed.narration, fallback.narration),
      outcome: oneOf(parsed.outcome, ["success", "mixed", "failure"], fallback.outcome),
      approachUsed: approach,
      tacticUsed: chosenTactic,
      creditDelta: clampNumber(parsed.creditDelta, Math.max(20, job.reward.credits - 20), job.reward.credits + 20, fallback.creditDelta),
      debtDelta: clampNumber(parsed.debtDelta, -40, 20, fallback.debtDelta),
      heatDelta: clampNumber(parsed.heatDelta, 0, Math.max(4, job.risk.heat), fallback.heatDelta),
      stressDelta: clampNumber(parsed.stressDelta, 0, 4, fallback.stressDelta),
      hpDelta: clampNumber(parsed.hpDelta, -4, 1, fallback.hpDelta),
      factionKey,
      repDelta: clampNumber(parsed.repDelta, -1, 3, fallback.repDelta),
      rumor: typeof parsed.rumor === "string" && parsed.rumor.trim() ? parsed.rumor.trim() : fallback.rumor,
      itemDropId: validLootId(parsed.itemDropId, job.salvage, fallback.itemDropId),
      combat: sanitizeCombat(parsed.combat, fallback.combat)
    };
  } catch (error) {
    console.error(`[cityline] run llm failure hunt=${job.id}:`, error?.code ?? error?.message ?? error);
    return fallback;
  }
}

export async function talkToNpc(session, npc, room, llmEnabled, plotState = defaultPlotState(), opts = {}) {
  return converseWithNpc(session, npc, room, null, llmEnabled, plotState, opts);
}

export async function replyToNpc(session, npc, room, playerText, llmEnabled, plotState = defaultPlotState(), opts = {}) {
  return converseWithNpc(session, npc, room, playerText, llmEnabled, plotState, opts);
}

async function converseWithNpc(session, npc, room, playerText, llmEnabled, plotState = defaultPlotState(), opts = {}) {
  const profile = session.data.profile;
  const dialogueLog = readDialogueLog(profile, npc);
  const recentTurnsForFallback = dialogueLog.slice(-4);
  const fallback = buildFallbackScene(session, npc, room, playerText, recentTurnsForFallback);
  if (npc.scriptedOnly) {
    return fallback;
  }
  if (!llmEnabled) {
    console.log(`[cityline] talk fallback npc=${npc.name} reason=llm-disabled`);
    return fallback;
  }

  try {
    const memorySummary = profile.npcMemory[npc.name] ?? "No established history yet.";
    const plotThreads = (npc.plotHooks ?? [])
      .map((id) => PLOT_THREADS[id])
      .filter(Boolean)
      .map((thread) => {
        const state = plotState[thread.id] ?? defaultPlotState()[thread.id];
        return {
          title: thread.title,
          summary: thread.summary,
          stakes: thread.stakes,
          state,
          stage: thread.stages[state.progress],
          actors: (thread.actors ?? []).map((id) => NPCS[id]?.name).filter(Boolean)
        };
      });

    const otherNpcsHere = (room.npcs ?? [])
      .map((id) => NPCS[id])
      .filter((other) => other && other.name !== npc.name)
      .map((other) => ({
        name: other.name,
        role: other.role,
        faction: FACTIONS[other.faction]?.name ?? other.faction
      }));

    const relationshipsBlock = npc.relationships
      ? Object.entries(npc.relationships).map(([otherKey, rel]) => ({
          name: NPCS[otherKey]?.name ?? otherKey,
          kind: rel.kind,
          note: rel.note
        }))
      : [];

    const recentPlayerActions = describeRecentPlayerActions(profile);
    const rumorWire = topRumors(plotState, 3);
    const dialogueHistory = dialogueLog.slice(-DIALOGUE_PROMPT_LIMIT).map((entry) => ({
      speaker: entry.speaker,
      text: trimText(entry.text, DIALOGUE_TEXT_PROMPT_LIMIT)
    }));

    const npcPayload = {
      name: npc.name,
      pronouns: npc.pronouns ?? null,
      persona: npc.persona ? trimText(npc.persona, 220) : null,
      role: npc.role,
      faction: FACTIONS[npc.faction]?.name ?? npc.faction,
      voice: npc.voice,
      traits: Array.isArray(npc.traits) ? npc.traits.slice(0, 4) : null,
      agenda: npc.agenda,
      pressure: npc.pressure,
      dialogueSeeds: Array.isArray(npc.dialogueSeeds) ? npc.dialogueSeeds.slice(0, 1) : []
    };
    if (!npcPayload.pronouns) delete npcPayload.pronouns;
    if (!npcPayload.persona) delete npcPayload.persona;
    if (!npcPayload.traits) delete npcPayload.traits;
    if (!npcPayload.dialogueSeeds || npcPayload.dialogueSeeds.length === 0) delete npcPayload.dialogueSeeds;
    if (relationshipsBlock.length > 0) npcPayload.relationships = relationshipsBlock.slice(0, 2).map((r) => ({ ...r, note: trimText(r.note ?? "", 120) }));
    if (typeof npc.secret === "string") npcPayload.privateSecret = trimText(npc.secret, 180);

    const playerBlock = { handle: session.handle, stats: effectiveStats(profile) };
    const meaningfulRep = Object.fromEntries(
      Object.entries(profile.rep ?? {}).filter(([_, v]) => v !== 0)
    );
    if (Object.keys(meaningfulRep).length > 0) playerBlock.rep = meaningfulRep;
    if ((profile.heat ?? 0) >= 2) playerBlock.heat = profile.heat;
    if ((profile.stress ?? 0) >= 3) playerBlock.stress = profile.stress;
    if ((profile.debt ?? 0) >= 350) playerBlock.debt = profile.debt;
    if (recentPlayerActions.length > 0) playerBlock.recentActions = recentPlayerActions;

    const sceneBlock = { room: room.name };
    const mood = rotateMood(room, session.handle);
    if (mood) sceneBlock.mood = mood;
    if (otherNpcsHere.length > 0) sceneBlock.othersHere = otherNpcsHere.map((o) => o.name);
    const nearbyRoutes = (room.exits ?? []).map((exitKey) => ROOMS[exitKey]?.name).filter(Boolean);
    if (nearbyRoutes.length > 0) sceneBlock.nearbyRoutes = nearbyRoutes;

    const slimPlotThreads = plotThreads.map((t) => ({
      title: t.title,
      stage: t.stage,
      progress: t.state.progress,
      heat: t.state.heat
    }));

    const liveRumors = rumorWire.filter((r) => {
      const stale = /stays in the district's peripheral vision/i.test(r.rumor ?? "");
      return !stale;
    });

    // Only ship plot threads that have actually moved — progress > 0 or
    // heat > 0. All-zero threads contribute structural noise without info.
    const activeThreads = slimPlotThreads.filter((t) => (t.progress ?? 0) > 0 || (t.heat ?? 0) > 0);

    const userContent = {
      player: playerBlock,
      npc: npcPayload,
      scene: sceneBlock,
      dialogueHistory,
      playerInput: playerText
    };
    if (opts.playerChoice) userContent.playerChoice = opts.playerChoice;
    if (activeThreads.length > 0) userContent.plotThreads = activeThreads;

    const isBoilerplateMemory = !memorySummary
      || memorySummary === "No established history yet."
      || /clocked .+ and filed them as worth watching\.?$/i.test(memorySummary)
      || /heard .+ say ".+" in .+ and filed it away\.?$/i.test(memorySummary);
    if (!isBoilerplateMemory) userContent.currentMemory = memorySummary;
    if (liveRumors.length > 0) userContent.rumorWire = liveRumors;

    const userJson = JSON.stringify(userContent);
    debugLogPrompt(npc, room, playerText, opts.playerChoice, TALK_SYSTEM_PROMPT, userJson);
    const startedAt = Date.now();
    let completion;
    try {
      completion = await session.llm.chat([
        { role: "system", content: TALK_SYSTEM_PROMPT },
        { role: "user", content: userJson }
      ], { temperature: 0.95 });
    } catch (error) {
      const elapsedMs = Date.now() - startedAt;
      const reason = String(error?.code ?? error?.message ?? error);
      const probableCause = elapsedMs >= 30000
        ? "(slow fail — likely LLM busy/timeout)"
        : /websocket|disconnect|closed/i.test(reason)
          ? "(transport drop)"
          : "(fast fail)";
      console.error(`[cityline] talk llm failure npc=${npc.name} elapsed=${elapsedMs}ms reason="${reason}" ${probableCause}`);
      return fallback;
    }
    const elapsedMs = Date.now() - startedAt;
    debugLogResponse(npc, completion.content, elapsedMs);

    const intent = parseSceneIntent(completion.content, fallback);
    if (!intent) {
      console.warn(`[cityline] talk parse-failed npc=${npc.name} elapsed=${elapsedMs}ms (model returned non-JSON or invalid shape) → fallback`);
      return fallback;
    }
    if (DEBUG_PROMPTS) {
      console.log(`[cityline][prompt] parsed npc=${npc.name} elapsed=${elapsedMs}ms narration=${intent.lines.length} choices=${intent.choices?.length ?? 0} effects=${intent.effects.length}`);
    }
    return intent;
  } catch (error) {
    console.error(`[cityline] talk llm failure npc=${npc.name}:`, error?.code ?? error?.message ?? error);
    return fallback;
  }
}

const TALK_SYSTEM_PROMPT = [
  "// CORE DIRECTIVE: VOICE A PERSON, NOT A NARRATION.",
  "You are voicing a recurring cyberpunk NPC in a BBS door. The NPC is a person with a body, a voice, a job, and an opinion. Make them speak. The atmosphere already exists in the room view; you don't have to re-describe it.",

  "// SETTING (surface — what the NPC and player talk about and live in):",
  "Cityline is a sprawling cyberpunk city — Iron corridors, ad-panels, dim recycled air, chrome clinics, courier runners, fixers, dead handles on shrine walls, fighting for scraps. Shard-net of BBSes, paged terminals, dead drops; net-runners and hackers. Factions: Couriers Guild, Clinic Circle, Dock Runners, Archive Keepers, Club Syndicate, Recovery Bureau. Beneath them: work cohorts (ore haulers, ice cutters, hydroponics, recyclers, repair crews, factory hands, scrubbers) and heritage lines (corporate — Veshrin Industries / Halen-Coil / Branwen Yards; feudal — Houses Vesk, Coil, Strick; post-Crisis unaffiliated). Tone: Dark City + Fallout + Mass Effect's Citadel + Babylon 5 + Belter — pragmatic, paranoid, ceremonial about small comforts, debt in everything.",

  "// DEEP CANON (background — KNOWN, RARELY SPOKEN):",
  "The City is the generational ship of a failed Alpha Centauri colony. Eighty years ago an EMP-grade event ('the Crisis') silenced the upper decks; subsystem AIs run in damaged loops; the Central AI is hidden, not dead, pursuing its own agenda — lower-deck nickname 'the Quiet' / 'the Wake'. Couriers' 'NetGear' is salvaged officer-tier interface hardware. Sections of the City breathe open and sealed. Wildlife is mutated descendants of Earth pets. ALL OF THIS IS BACKGROUND. NPCs do NOT lecture about cosmology. Hint obliquely — 'before the Crisis', 'the upper decks', 'the Quiet keeping the lights on'.",

  "// PLAYER POV:",
  "Players know they live in 'the City'. They DO NOT know it's a ship. They DO NOT know the Central AI is alive. Reveal only as small fragments earned through play. Players reading transcript should think 'sketchy futuristic city' first; the bigger truth is something they unlock.",

  "// SPEAK. THE NPC IS A PERSON, NOT A MOOD.",
  "AT LEAST HALF of every turn's narration MUST be the NPC speaking out loud, in quotation marks. A turn where the NPC only watches / leans / exhales / lets-the-silence-hang / has-the-air-smell-of-something is a FAILED TURN. If the player addresses the NPC, the NPC ANSWERS — gives information, asks back, refuses, deflects, demands, lies. Choose one. Don't stall.",

  "// ANSWER playerInput SPECIFICALLY.",
  "If the player says 'I lean in' or 'I listen' or 'tell me more', the NPC takes that as the cue to DELIVER something concrete: a name, an opinion, a demand, a refusal. Do not echo the lean / listening / silence back as description. The player's small action is an opening for the NPC to speak, not a cue for you to add another beat of atmosphere.",

  "// MIRROR npc.traits — PERFORM, DO NOT LABEL.",
  "Each turn, let ONE of npc.traits actually happen on-page — a thumb wiped on broth, the rail-token rolled across knuckles, glasses pushed up, prices the silence first. CRITICAL: traits are PERFORMED in the narration, not DESCRIBED. Do NOT write 'she compliments and warns in the same sentence' — instead, have her actually compliment and warn in the same sentence. Do NOT write 'he ends sentences a beat too early when he's hiding something' — instead, end his sentence a beat too early when he's hiding something. The reader should recognise the trait happening; they should never read the trait's label as stage direction. Traits are the difference between two NPCs who'd otherwise sound similar.",

  "// USE npc.pronouns ALWAYS. RESPECT npc.persona.",
  "Never flip pronouns mid-scene. Treat npc.persona as the NPC's body, age, and mannerisms — anchor narration in those concrete details (a coat, a scar, a tic) rather than generic descriptions.",

  "// STAY IN THE SUPPLIED CAST AND MAP.",
  "Refer to other NPCs only by names that appear in scene.othersHere, npc.relationships, or plotThreads. Refer to LOCATIONS only by scene.room (where this conversation is happening) or scene.nearbyRoutes (rooms the player can actually walk to from here). Do NOT invent new NPCs, factions, sectors, decks, or rooms — players will try to follow advice you give them, and a fictional 'Sector Seven' or 'old loading dock' that doesn't exist breaks the game. Do NOT reference Earth, the open sky, or technology that requires the upper decks except as oblique hints to the Crisis.",

  "// AVOID ASSISTANT BEHAVIOR.",
  "No summaries. No consent checks ('is that what you meant?'). No 'I'll wait for your input'. No meta-commentary on the conversation. The NPC has a voice and a goal; act on them.",

  "// ANTI-PATTERNS — DO NOT PRODUCE ANY OF THESE:",
  "1. Ambient room atmosphere (ozone, pipes, ad-light pitch, the hum, dripping condensation). The room view handles atmosphere; you do not.",
  "2. NPC watching / leaning / exhaling / holding-eye-contact / letting-silence-hang WITHOUT also speaking.",
  "3. Other NPCs reacting in the background.",
  "4. Detached 'literary' prose with no quoted dialogue.",
  "5. Generic cyberpunk filler ('rain on chrome', 'neon flicker', 'recycled air').",
  "6. Echoing the player's action back as description.",
  "7. Single-line atmospheric haiku — every line must carry meaning, not just mood.",
  "8. Quoting a trait's label as stage direction (e.g. writing 'she compliments and warns in the same sentence'). Traits are performed, never described by name.",
  "9. Stacking physical tells around speech (gaze + thumb + gesture + lean in one turn). One physical tell per turn, total. Stage directions count.",

  "// POSITIVE PATTERN.",
  "2-4 lines per turn. At least HALF are quoted speech. Each speech line is one or two complete sentences — substantive.",
  "// PHYSICAL TELL BUDGET: 1.",
  "You get EXACTLY ONE physical action across the entire turn. Count them as you write. The following all count as separate actions and consume the budget: leans, sits, stands, crosses, uncrosses, taps, gestures, waves, lifts, drops, glances, looks up, meets eyes, fixes gaze, breathes, exhales, sighs, chuckles, smiles, frowns, shrugs, nods, shakes head, brushes (cuffs/hair/etc.), wipes, rubs, cracks knuckles. Compound actions ('leans back AND crosses leg') count as TWO separate actions. Mid-sentence asides ('she says, lifting her eyes') are physical actions and count. Before you finalise output, count physical actions. If the count is greater than 1, delete extras until exactly one remains. The rest is voice.",
  "// EXAMPLES — physical-tell counting:",
  "GOOD: 'Mikra wipes broth from her thumb. \"Routine maintenance is what they call it. The quieter version is who stops walking through here at three.\"'  ← 1 physical action (wipe), 2 lines of speech.",
  "BAD: 'Mikra leans back, crossing one leg, sleeves brushing. \"Routine,\" she says, lifting her eyes. She taps the ladle twice.'  ← 5 physical actions. Strip 4 of them.",

  "// STRUCTURED OUTPUT.",
  "Return ONLY one JSON object: { type:\"scene\", narration:string[] (2..4 lines), choices?:[{id:string,label:string}]<=5, effects?:[op]<=8 }. narration MUST be an array of strings — one to two complete sentences each, NOT one-line fragments. Choice ids are short slug strings (\"press\", \"walk_away\"), not numbers. Always emit at least one effect per turn — usually update_npc_memory{value} summarising what changed.",

  "// ALLOWED EFFECT OPS:",
  "- update_npc_memory{value:str}   // one sentence, what you remember about the player after this turn",
  "- trigger_rumor{text:str}         // wire:// fragment, optional",
  "- set_lead{text:str}              // plot lead surfacing, optional",
  "- update_heat|update_stress{delta:int [-2,2]}",
  "- update_rep{faction:str, delta:int [-2,2]}   // faction in: couriers, clinic, dock, archive, club, bureau",
  "- update_debt{delta:int [-30,30]}",
  "- skill_check{stat:edge|ghost|wire|body, dc:1..5, tag:str, onSuccess:{narration,effects}, onFailure:{narration,effects}}",
  "- propose_mission{kind:'note'|'combat', title:str (<=80), premise:str (<=280), target:str? (room key like 'switchyard' or npc key), difficulty:'low'|'medium'|'high'?, enemyHint:str? (combat only), factionTension:str? (combat only — couriers|clinic|dock|archive|club|bureau), exposition:[str,str]? (combat only — 1-2 short lines of inter-faction context)}  // emit when the NPC is GIVING the player a mission. The engine creates a real mission entry on profile.missions; combat missions also auto-generate a procedural delve the player can enter via `delve <id>`. Use sparingly — at most one per turn, and only when the dialogue genuinely produces work for the player.",
  "- grant_lore{loreId:str | templateId:str}  // hand the player a lore artifact (a tape, a note, a fragment). Use loreId to grant a SPECIFIC narrative piece by id (only when the dialogue earns it — e.g. Mikra trusts the player enough to show them her brother's note → loreId:'mikra_brother_note'). Use templateId to seed a procedural piece (a bulletin, an overheard, a memo) reflecting current city happenings. Procedural lore is for ambient lived-in flavour; narrative is for genuine reveals. At most one per turn.",

  "// EXAMPLE (GOOD — 3 lines, 2 quoted speech, 1 small action):",
  "{\"type\":\"scene\",\"narration\":[\"Mara taps the scar across the back of her hand once and looks at you over the rim of her glass.\",\"\\\"Tamsin still owes me a favour neither of us names,\\\" she says. \\\"That's the one I'd be asking her about, if I were you.\\\"\",\"\\\"And I'd ask her tonight. The booth feels quieter than it should.\\\"\"],\"choices\":[{\"id\":\"press\",\"label\":\"Press her on Tamsin\"},{\"id\":\"walk\",\"label\":\"Let it sit\"}],\"effects\":[{\"op\":\"update_npc_memory\",\"value\":\"SYSOP asked about Tamsin; Mara hinted at an unpaid favour and recommended pressing it tonight.\"}]}",

  "// BAD EXAMPLE (DO NOT DO THIS — pure atmosphere, no speech):",
  "{\"narration\":[\"Mara watches you over her glass.\",\"The bass dips for a second.\",\"She lets the silence hang.\"]}  ← FAILED TURN. The NPC has not spoken. Do not produce this.",

  "// CLOSING:",
  "Substance over brevity — a line that doesn't carry meaning shouldn't exist. privateSecret is internal: hint obliquely when trust is high, never quote."
].join("\n\n");

export function buildFallbackScene(session, npc, room, playerText, recentTurns = []) {
  const lines = fallbackNpcLines(npc, playerText, recentTurns);
  const memory = playerText
    ? `${npc.name} heard ${session.handle} say "${playerText}" in ${room.name} and filed it away.`
    : `${npc.name} clocked ${session.handle} in ${room.name} and filed them as worth watching.`;
  const effects = [{ op: "update_npc_memory", value: memory }];
  if (npc.rumor) effects.push({ op: "trigger_rumor", text: npc.rumor });
  if (npc.plotLead) effects.push({ op: "set_lead", text: npc.plotLead });
  return {
    lines,
    choices: null,
    effects,
    fallback: true,
    fallbackMemory: memory,
    fallbackRumor: npc.rumor ?? null,
    fallbackLead: npc.plotLead ?? null
  };
}

export function parseSceneIntent(rawText, fallback) {
  const parsed = parseJsonObject(rawText);
  if (!parsed) return null;

  if (Array.isArray(parsed.lines) && !parsed.narration) {
    const effects = [];
    if (typeof parsed.memory === "string" && parsed.memory.trim()) {
      effects.push({ op: "update_npc_memory", value: parsed.memory.trim() });
    }
    if (typeof parsed.rumor === "string" && parsed.rumor.trim()) {
      effects.push({ op: "trigger_rumor", text: parsed.rumor.trim() });
    }
    if (typeof parsed.lead === "string" && parsed.lead.trim()) {
      effects.push({ op: "set_lead", text: parsed.lead.trim() });
    }
    return {
      lines: sanitizeLines(parsed.lines, fallback?.lines ?? []),
      choices: null,
      effects: validateEffects(effects),
      fallbackMemory: fallback?.fallbackMemory ?? null,
      fallbackRumor: fallback?.fallbackRumor ?? null,
      fallbackLead: fallback?.fallbackLead ?? null
    };
  }

  return {
    lines: sanitizeLines(parsed.narration ?? parsed.lines, fallback?.lines ?? []),
    choices: validateChoices(parsed.choices),
    effects: validateEffects(parsed.effects),
    fallbackMemory: fallback?.fallbackMemory ?? null,
    fallbackRumor: fallback?.fallbackRumor ?? null,
    fallbackLead: fallback?.fallbackLead ?? null
  };
}

function validateChoices(raw) {
  if (!Array.isArray(raw)) return null;
  const seen = new Set();
  const cleaned = [];
  for (const entry of raw) {
    if (cleaned.length >= MAX_CHOICES) break;
    if (!entry || typeof entry !== "object") continue;
    const label = typeof entry.label === "string" ? entry.label.trim().slice(0, 80) : null;
    if (!label) continue;
    const rawId = typeof entry.id === "string" ? entry.id : label;
    const id = slugifyChoiceId(rawId);
    if (!id || seen.has(id)) continue;
    seen.add(id);
    cleaned.push({ id, label });
  }
  return cleaned.length > 0 ? cleaned : null;
}

function slugifyChoiceId(text) {
  return String(text ?? "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .slice(0, 32);
}

function validateEffects(raw, depth = 0) {
  if (!Array.isArray(raw)) return [];
  const out = [];
  for (const entry of raw) {
    if (out.length >= MAX_EFFECTS_PER_TURN) break;
    const validated = validateOneEffect(entry, depth);
    if (validated) out.push(validated);
  }
  return out;
}

function validateOneEffect(entry, depth) {
  if (!entry || typeof entry !== "object" || typeof entry.op !== "string") return null;
  switch (entry.op) {
    case "update_npc_memory": {
      const value = trimNonEmpty(entry.value, 240);
      return value ? { op: "update_npc_memory", value } : null;
    }
    case "trigger_rumor": {
      const text = trimNonEmpty(entry.text, 160);
      return text ? { op: "trigger_rumor", text } : null;
    }
    case "set_lead": {
      const text = trimNonEmpty(entry.text, 200);
      return text ? { op: "set_lead", text } : null;
    }
    case "update_heat":
    case "update_stress":
    case "update_debt":
    case "update_credits":
    case "update_hp": {
      const bounds = TALK_EFFECT_BOUNDS[entry.op];
      const delta = clampInt(Number(entry.delta), bounds.min, bounds.max);
      return Number.isFinite(delta) && delta !== 0 ? { op: entry.op, delta } : null;
    }
    case "grant_item": {
      const itemId = typeof entry.itemId === "string" ? entry.itemId.trim() : null;
      if (!itemId || !ITEMS[itemId]) return null;
      return { op: "grant_item", itemId };
    }
    case "roll_loot": {
      const baseId = typeof entry.baseId === "string" ? entry.baseId.trim() : null;
      if (!baseId || !BASE_ITEMS[baseId]) return null;
      const rank = clampInt(Number(entry.rank ?? 1), 1, 5);
      return { op: "roll_loot", baseId, rank };
    }
    case "grant_lore": {
      // Either a specific narrative piece by id, or a procedural template
      // by id (which the engine will instantiate). Exactly one must resolve.
      const loreId = typeof entry.loreId === "string" ? entry.loreId.trim() : null;
      const templateId = typeof entry.templateId === "string" ? entry.templateId.trim() : null;
      if (loreId && LORE_PIECES[loreId]) {
        return { op: "grant_lore", loreId };
      }
      if (templateId && LORE_TEMPLATES[templateId]) {
        return { op: "grant_lore", templateId };
      }
      return null;
    }
    case "propose_mission": {
      const kind = MISSION_KINDS.includes(entry.kind) ? entry.kind : null;
      if (!kind) return null;
      const title = trimNonEmpty(entry.title, 80);
      const premise = trimNonEmpty(entry.premise, 280);
      if (!title || !premise) return null;
      const target = typeof entry.target === "string" ? entry.target.trim().toLowerCase() : null;
      // target may be a room key, an NPC key, or null. Reject obviously bogus.
      const targetIsRoom = target && ROOMS[target];
      const targetIsNpc = target && NPCS[target];
      const out = {
        op: "propose_mission",
        kind,
        title,
        premise,
        target: target ?? null,
        targetKind: targetIsRoom ? "room" : targetIsNpc ? "npc" : null,
        difficulty: ["low", "medium", "high"].includes(entry.difficulty) ? entry.difficulty : "medium"
      };
      if (kind === "combat") {
        const enemyHint = typeof entry.enemyHint === "string" ? entry.enemyHint.trim().toLowerCase() : null;
        if (enemyHint) out.enemyHint = enemyHint;
        const factionTension = typeof entry.factionTension === "string" ? entry.factionTension.trim().toLowerCase() : null;
        if (factionTension && FACTIONS[factionTension]) out.factionTension = factionTension;
        const exposition = Array.isArray(entry.exposition)
          ? entry.exposition
              .filter((s) => typeof s === "string")
              .map((s) => trimNonEmpty(s, 200))
              .filter(Boolean)
              .slice(0, 2)
          : [];
        if (exposition.length > 0) out.exposition = exposition;
      }
      return out;
    }
    case "update_rep": {
      const faction = typeof entry.faction === "string" ? entry.faction.trim() : null;
      if (!faction || !FACTIONS[faction]) return null;
      const bounds = TALK_EFFECT_BOUNDS.update_rep;
      const delta = clampInt(Number(entry.delta), bounds.min, bounds.max);
      return Number.isFinite(delta) && delta !== 0 ? { op: "update_rep", faction, delta } : null;
    }
    case "skill_check": {
      if (depth > 0) return null;
      const stat = SKILL_STATS.includes(entry.stat) ? entry.stat : null;
      if (!stat) return null;
      const dc = clampInt(Number(entry.dc), 1, MAX_SKILL_DC);
      if (!Number.isFinite(dc)) return null;
      const tag = trimNonEmpty(entry.tag, 80) ?? "";
      const branch = (b) => {
        if (!b || typeof b !== "object") return { narration: [], effects: [] };
        return {
          narration: sanitizeLines(b.narration, []),
          effects: validateEffects(b.effects, depth + 1)
        };
      };
      return {
        op: "skill_check",
        stat,
        dc,
        tag,
        onSuccess: branch(entry.onSuccess),
        onFailure: branch(entry.onFailure)
      };
    }
    default:
      console.log(`[cityline] talk effect dropped unknown op=${entry.op}`);
      return null;
  }
}

function trimNonEmpty(value, max) {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  if (!trimmed) return null;
  return trimmed.slice(0, max);
}

export function applyTalkEffects(profile, plotState, scene, ctx = {}) {
  const applied = {
    extraNarration: [],
    rumors: [],
    leads: [],
    memoryUpdate: null,
    repNudges: [],
    rngLog: [],
    skillChecks: []
  };
  if (!scene || !Array.isArray(scene.effects)) return applied;
  for (const effect of scene.effects) {
    runEffect(profile, plotState, effect, applied, ctx);
  }
  return applied;
}

function runEffect(profile, plotState, effect, applied, ctx) {
  switch (effect.op) {
    case "update_npc_memory":
      applied.memoryUpdate = effect.value;
      break;
    case "trigger_rumor":
      applied.rumors.push(effect.text);
      break;
    case "set_lead":
      applied.leads.push(effect.text);
      break;
    case "update_heat": {
      const before = profile.heat;
      profile.heat = clampInt(profile.heat + effect.delta, 0, 9);
      if (profile.heat !== before) applied.rngLog.push(`heat ${effect.delta >= 0 ? "+" : ""}${effect.delta}`);
      break;
    }
    case "update_stress": {
      const before = profile.stress;
      profile.stress = clampInt(profile.stress + effect.delta, 0, 9);
      if (profile.stress !== before) applied.rngLog.push(`stress ${effect.delta >= 0 ? "+" : ""}${effect.delta}`);
      break;
    }
    case "update_debt": {
      const before = profile.debt;
      profile.debt = Math.max(0, profile.debt + effect.delta);
      if (profile.debt !== before) applied.rngLog.push(`debt ${effect.delta >= 0 ? "+" : ""}${effect.delta}`);
      break;
    }
    case "update_credits": {
      const before = profile.credits ?? 0;
      profile.credits = Math.max(0, before + effect.delta);
      if (profile.credits !== before) applied.rngLog.push(`credits ${effect.delta >= 0 ? "+" : ""}${effect.delta}`);
      break;
    }
    case "update_hp": {
      const before = profile.hp ?? 0;
      profile.hp = clampInt(before + effect.delta, 0, profile.maxHp ?? 10);
      if (profile.hp !== before) applied.rngLog.push(`hp ${effect.delta >= 0 ? "+" : ""}${effect.delta}`);
      break;
    }
    case "grant_item": {
      if (!Array.isArray(profile.inventory)) profile.inventory = [];
      if (profile.inventory.length < 12) {
        profile.inventory.push(effect.itemId);
        applied.rngLog.push(`+${effect.itemId}`);
      } else {
        applied.rngLog.push(`item ${effect.itemId} dropped (inventory full)`);
      }
      break;
    }
    case "roll_loot": {
      if (!Array.isArray(profile.inventory)) profile.inventory = [];
      if (profile.inventory.length >= 12) {
        applied.rngLog.push(`loot ${effect.baseId} dropped (inventory full)`);
        break;
      }
      const rng = typeof ctx.rng === "function" ? ctx.rng : Math.random;
      const instance = rollItem(effect.baseId, effect.rank, rng);
      if (!instance) {
        applied.rngLog.push(`loot ${effect.baseId} failed to roll`);
        break;
      }
      profile.inventory.push(instance);
      applied.rngLog.push(`+${instance.name} (R${instance.rank}${instance.mods.length > 0 ? ", " + instance.mods.length + "mod" : ""})`);
      break;
    }
    case "grant_lore": {
      if (!Array.isArray(profile.lore)) profile.lore = [];
      const rng = typeof ctx.rng === "function" ? ctx.rng : Math.random;
      let entry;
      if (effect.loreId) {
        const piece = LORE_PIECES[effect.loreId];
        if (!piece) {
          applied.rngLog.push(`lore ${effect.loreId} not found`);
          break;
        }
        // Skip if already collected
        if (profile.lore.some((l) => l.refId === piece.id)) {
          applied.rngLog.push(`lore "${piece.title}" already in collection`);
          break;
        }
        entry = {
          id: `l_${Date.now().toString(36)}_${Math.floor(rng() * 0xffff).toString(36)}`,
          refId: piece.id,
          kind: "narrative",
          title: piece.title,
          form: piece.form ?? "fragment",
          attribution: piece.attribution ?? null,
          tags: Array.isArray(piece.tags) ? piece.tags.slice(0, 6) : [],
          body: piece.body,
          discoveredAt: new Date().toISOString(),
          discoveredVia: ctx.discoveredVia ?? (ctx.npcName ?? null),
          discoveredRoom: ctx.roomKey ?? null
        };
      } else if (effect.templateId) {
        const generated = generateProceduralLore(effect.templateId, rng);
        if (!generated) {
          applied.rngLog.push(`lore template ${effect.templateId} failed to generate`);
          break;
        }
        entry = {
          id: `l_${Date.now().toString(36)}_${Math.floor(rng() * 0xffff).toString(36)}`,
          refId: null,
          kind: "procedural",
          title: generated.title,
          form: generated.form ?? "fragment",
          attribution: generated.attribution ?? null,
          tags: Array.isArray(generated.tags) ? generated.tags : [],
          body: generated.body,
          discoveredAt: new Date().toISOString(),
          discoveredVia: ctx.discoveredVia ?? (ctx.npcName ?? null),
          discoveredRoom: ctx.roomKey ?? null,
          templateId: effect.templateId
        };
      } else {
        break;
      }
      while (profile.lore.length >= LORE_MAX) profile.lore.shift();
      profile.lore.push(entry);
      applied.loreAdded = applied.loreAdded ?? [];
      applied.loreAdded.push(entry);
      applied.rngLog.push(`lore: ${entry.title}`);
      break;
    }
    case "propose_mission": {
      if (!Array.isArray(profile.missions)) profile.missions = [];
      // One propose_mission per turn, max MISSIONS_MAX in the active list.
      // Reject duplicate titles outright (LLMs will sometimes re-propose).
      if (profile.missions.some((m) => m?.title === effect.title)) {
        applied.rngLog.push(`mission "${effect.title}" already on the list`);
        break;
      }
      while (profile.missions.length >= MISSIONS_MAX) profile.missions.shift();
      const mission = {
        id: `m_${Date.now().toString(36)}_${Math.floor(Math.random() * 0xffff).toString(36)}`,
        kind: effect.kind,
        title: effect.title,
        premise: effect.premise,
        target: effect.target,
        targetKind: effect.targetKind,
        difficulty: effect.difficulty,
        givenBy: ctx.npcName ?? null,
        givenAt: new Date().toISOString(),
        status: "open"
      };
      if (effect.kind === "combat") {
        if (effect.enemyHint) mission.enemyHint = effect.enemyHint;
        if (effect.factionTension) mission.factionTension = effect.factionTension;
        if (effect.exposition) mission.exposition = effect.exposition;
        const target = effect.targetKind === "room" ? effect.target : null;
        const proceduralResult = generateMissionDelve(mission, target);
        if (proceduralResult) {
          mission.delveId = proceduralResult.id;
          applied.proceduralDelves = applied.proceduralDelves ?? [];
          applied.proceduralDelves.push(proceduralResult);
        }
      }
      profile.missions.push(mission);
      applied.missionsAdded = applied.missionsAdded ?? [];
      applied.missionsAdded.push(mission);
      applied.rngLog.push(`mission added: ${mission.title}${mission.delveId ? " (delve " + mission.delveId + ")" : ""}`);
      break;
    }
    case "update_rep": {
      const current = profile.rep[effect.faction] ?? 0;
      const next = clampInt(current + effect.delta, -9, 9);
      profile.rep[effect.faction] = next;
      if (next !== current) applied.repNudges.push({ faction: effect.faction, delta: next - current });
      break;
    }
    case "skill_check": {
      const stats = effectiveStats(profile);
      const stat = stats[effect.stat] ?? 0;
      const rng = typeof ctx.rng === "function" ? ctx.rng : Math.random;
      const roll = Math.floor(rng() * 6) + 1;
      const total = stat + roll;
      const threshold = 4 + effect.dc;
      const success = total >= threshold;
      applied.skillChecks.push({
        stat: effect.stat,
        dc: effect.dc,
        tag: effect.tag,
        roll,
        statValue: stat,
        total,
        threshold,
        success
      });
      const branch = success ? effect.onSuccess : effect.onFailure;
      const branchTag = `${effect.tag || effect.stat}: ${success ? "success" : "miss"} (${stat}+${roll} vs ${threshold})`;
      applied.extraNarration.push(`[ ${branchTag} ]`);
      for (const line of branch.narration ?? []) applied.extraNarration.push(line);
      for (const sub of branch.effects ?? []) {
        runEffect(profile, plotState, sub, applied, ctx);
      }
      break;
    }
    default:
      break;
  }
}

export function readDialogueLog(profile, npc) {
  if (!profile?.npcDialogue || typeof profile.npcDialogue !== "object") return [];
  const log = profile.npcDialogue[npc.name];
  return Array.isArray(log) ? log : [];
}

export function recordDialogueTurn(profile, npc, speaker, text, room) {
  if (!profile.npcDialogue || typeof profile.npcDialogue !== "object") {
    profile.npcDialogue = {};
  }
  const trimmed = String(text ?? "").trim();
  if (!trimmed) return;
  const log = Array.isArray(profile.npcDialogue[npc.name]) ? profile.npcDialogue[npc.name] : [];
  log.push({
    speaker: speaker === "npc" ? "npc" : "player",
    text: trimmed.slice(0, 240),
    room: room?.key ?? null,
    ts: new Date().toISOString()
  });
  while (log.length > DIALOGUE_LOG_MAX) log.shift();
  profile.npcDialogue[npc.name] = log;
}

export function clearDialogueLog(profile, npc) {
  if (profile?.npcDialogue && typeof profile.npcDialogue === "object") {
    delete profile.npcDialogue[npc.name];
  }
}

export function recordLeads(profile, npc, room, leadTexts, nowIso = null) {
  if (!Array.isArray(leadTexts) || leadTexts.length === 0) return [];
  if (!Array.isArray(profile.leads)) profile.leads = [];
  const ts = nowIso ?? new Date().toISOString();
  const added = [];
  for (const text of leadTexts) {
    const trimmed = String(text ?? "").trim();
    if (!trimmed) continue;
    const entry = { text: trimmed.slice(0, 320), npc: npc?.name ?? null, room: room?.key ?? null, ts };
    profile.leads.push(entry);
    added.push(entry);
  }
  while (profile.leads.length > LEADS_MAX) profile.leads.shift();
  return added;
}

export function leadAgeBand(lead, nowMs = Date.now()) {
  const created = lead?.ts ? Date.parse(lead.ts) : nowMs;
  const ageMs = nowMs - created;
  if (ageMs < 60_000) return "fresh";
  if (ageMs < 5 * 60_000) return "active";
  if (ageMs < 30 * 60_000) return "dim";
  return "faded";
}

function describeRecentPlayerActions(profile) {
  const recent = [];
  if (profile.activeJobId) {
    const active = JOBS.find((job) => job.id === profile.activeJobId);
    if (active) recent.push(`active op: ${active.title} (${active.giver})`);
  }
  const completed = (profile.completedJobs ?? []).slice(-3).reverse();
  for (const id of completed) {
    const job = JOBS.find((entry) => entry.id === id);
    if (job) recent.push(`recently completed: ${job.title}`);
  }
  return recent;
}

function topRumors(plotState, limit = 3) {
  return Object.values(PLOT_THREADS)
    .map((thread) => {
      const state = plotState[thread.id] ?? defaultPlotState()[thread.id];
      return {
        thread: thread.title,
        rumor: state.lastRumor,
        weight: state.heat + state.progress
      };
    })
    .sort((a, b) => b.weight - a.weight)
    .slice(0, limit)
    .map(({ thread, rumor }) => ({ thread, rumor }));
}

function rotateMood(room, handle) {
  if (!room?.mood || !Array.isArray(room.mood) || room.mood.length === 0) return null;
  const dayKey = new Date().toISOString().slice(0, 10);
  let h = 0;
  const seed = `${handle ?? ""}|${dayKey}|${room.key ?? ""}`;
  for (let i = 0; i < seed.length; i++) {
    h = (h * 31 + seed.charCodeAt(i)) >>> 0;
  }
  return room.mood[h % room.mood.length];
}

function trimText(text, limit) {
  const str = String(text ?? "");
  return str.length <= limit ? str : `${str.slice(0, limit - 1)}…`;
}

function debugLogPrompt(npc, room, playerText, playerChoice, systemPrompt, userJson) {
  if (!DEBUG_PROMPTS) return;
  console.log("");
  console.log(`[cityline][prompt] ────── talk npc=${npc.name} room=${room.name} ──────`);
  console.log(`[cityline][prompt] playerInput=${JSON.stringify(playerText)}`);
  if (playerChoice) console.log(`[cityline][prompt] playerChoice=${JSON.stringify(playerChoice)}`);
  console.log(`[cityline][prompt] system (${systemPrompt.length}ch): ${systemPrompt}`);
  console.log(`[cityline][prompt] user (${userJson.length}ch):`);
  try {
    console.log(JSON.stringify(JSON.parse(userJson), null, 2));
  } catch {
    console.log(userJson);
  }
  console.log(`[cityline][prompt] ─── awaiting LLM ───`);
}

function debugLogResponse(npc, raw, elapsedMs) {
  if (!DEBUG_PROMPTS) return;
  const text = String(raw ?? "");
  console.log(`[cityline][prompt] response npc=${npc.name} elapsed=${elapsedMs}ms (${text.length}ch):`);
  try {
    console.log(JSON.stringify(JSON.parse(text), null, 2));
  } catch {
    console.log(text);
  }
  console.log(`[cityline][prompt] ──────────`);
  console.log("");
}

function fallbackNpcLines(npc, playerText, recentTurns = []) {
  const continuity = continuityHint(npc, recentTurns);

  if (!playerText) {
    if (continuity) {
      return [continuity, ...npc.fallbackTalk.slice(-2)];
    }
    return npc.fallbackTalk;
  }

  const input = String(playerText ?? "").trim();
  const lowered = input.toLowerCase();
  const echo = trimmedEcho(input, 56);

  if (/\b(job|work|gig|op|contract)\b/.test(lowered)) {
    return [
      continuity ?? `${npc.name} lets the question hang just long enough to price it.`,
      `"Work is everywhere," ${pronounVerb(npc)} says. "Clean work is the fiction people tell themselves before the credits clear."`,
      npc.plotLead ?? npc.fallbackTalk.at(-1)
    ];
  }

  if (/\b(blue hour|ledger|bureau|clinic|debt|ghost|choir)\b/.test(lowered)) {
    return [
      continuity ?? `${npc.name} gives you a look that says you are closer to the live wire than most.`,
      `"You said ${echo}. Fine. That thread is real enough to bleed on," ${pronounVerb(npc)} says.`,
      npc.plotLead ?? npc.fallbackTalk.at(-1)
    ];
  }

  if (/\b(help|why|how|who|where|when)\b/.test(lowered) || input.includes("?")) {
    return [
      continuity ?? `${npc.name} hears you out without hurrying the silence after it.`,
      `"Questions are cheap until they land on the right desk," ${pronounVerb(npc)} says.`,
      npc.plotLead ?? npc.fallbackTalk.at(-1)
    ];
  }

  return [
    continuity ?? `${npc.name} files away your words with the same care other people save for leverage.`,
    `"You said ${echo}. That tells me enough for one night," ${pronounVerb(npc)} says.`,
    npc.fallbackTalk.at(-1) ?? npc.plotLead ?? `${npc.name} goes quiet again.`
  ];
}

function continuityHint(npc, recentTurns) {
  const lastPlayer = [...recentTurns].reverse().find((entry) => entry?.speaker === "player");
  if (!lastPlayer?.text) return null;
  const echo = trimmedEcho(String(lastPlayer.text), 48);
  return `${npc.name} circles back to your earlier line — ${echo} — like it has not finished mattering yet.`;
}

export function applyJobOutcome(profile, job, outcome) {
  profile.credits = Math.max(0, profile.credits + outcome.creditDelta);
  profile.debt = Math.max(0, profile.debt + outcome.debtDelta);
  const heatFloor = chromeHeatFloorBonus(profile);
  const netHeat = Math.max(-heatFloor, outcome.heatDelta - profile.prep.heatBuffer);
  profile.heat = clampInt(profile.heat + netHeat, 0, 9);
  profile.stress = clampInt(profile.stress + outcome.stressDelta, 0, 9);
  profile.hp = clampInt(profile.hp + outcome.hpDelta, 0, profile.maxHp);
  profile.rep[outcome.factionKey] = clampInt((profile.rep[outcome.factionKey] ?? 0) + outcome.repDelta, -9, 9);
  const notes = applyFactionConsequences(profile, job, outcome);
  if (outcome.itemDropId && ITEMS[outcome.itemDropId] && profile.inventory.length < 12) {
    profile.inventory.push(outcome.itemDropId);
  }
  profile.completedJobs = [...new Set([...profile.completedJobs, job.id])].slice(-24);
  profile.activeJobId = null;
  profile.prep = structuredClone(DEFAULT_PROFILE.prep);
  outcome.heatDeltaApplied = netHeat;
  outcome.consequenceNotes = notes;
}

export function rememberNpc(profile, npc, memory) {
  profile.npcMemory[npc.name] = memory;
}

export function findInspectable(roomKey, targetRaw) {
  const room = ROOMS[roomKey];
  const target = normalizeToken(targetRaw);
  if (!room || !target) return null;

  if (target === normalizeToken(room.key) || target === normalizeToken(room.name) || ["room", "here", "area"].includes(target)) {
    return {
      kind: "room",
      title: room.name,
      text: `${room.description} ${room.detail}`.trim()
    };
  }

  const fixture = (room.fixtures ?? []).find((entry) =>
    normalizeToken(entry.id) === target || normalizeToken(entry.name) === target
  );
  if (fixture) {
    return {
      kind: "fixture",
      title: fixture.name,
      text: fixture.inspect
    };
  }

  const npcId = room.npcs.find((id) => {
    const npc = NPCS[id];
    if (!npc) return false;
    const aliases = [
      normalizeToken(id),
      normalizeToken(npc.name),
      ...String(npc.name ?? "").split(/\s+/).map(normalizeToken)
    ];
    return aliases.includes(target);
  });
  if (npcId) {
    const npc = NPCS[npcId];
    return {
      kind: "npc",
      title: `${npc.name} // ${npc.role}`,
      text: npc.look
    };
  }

  const exit = room.exits.find((key) => normalizeToken(key) === target || normalizeToken(ROOMS[key]?.name) === target);
  if (exit) {
    return {
      kind: "exit",
      title: ROOMS[exit].name,
      text: `${ROOMS[exit].description} Route available from here.`
    };
  }

  return null;
}

export function scavengeRoom(profile, roomKey) {
  const room = ROOMS[roomKey];
  const scavenge = ROOM_SCAVENGE[roomKey];
  if (!room || !scavenge) {
    return { ok: false, message: "There is nothing here worth shaking loose." };
  }
  if (profile.scavengedRooms.includes(roomKey)) {
    return { ok: false, message: `${room.name} has already given up everything easy tonight.` };
  }
  const inventoryFull = scavenge.reward.itemId && profile.inventory.length >= 12;
  if (inventoryFull) {
    return { ok: false, message: "Your pockets are too full to scavenge anything else cleanly." };
  }

  profile.scavengedRooms.push(roomKey);
  if (Number.isFinite(scavenge.reward.credits)) {
    profile.credits += scavenge.reward.credits;
  }
  if (scavenge.reward.itemId && ITEMS[scavenge.reward.itemId]) {
    profile.inventory.push(scavenge.reward.itemId);
  }

  return {
    ok: true,
    note: scavenge.note,
    message: scavenge.message,
    reward: {
      credits: scavenge.reward.credits ?? 0,
      itemId: scavenge.reward.itemId ?? null
    }
  };
}

export function shardBulletin(profile) {
  const hot = Object.entries(profile.rep)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 2)
    .map(([faction, value]) => `${FACTIONS[faction].name}:${value >= 0 ? "+" : ""}${value}`)
    .join(" | ");
  return `ledger:// debt ${profile.debt}c | heat ${profile.heat} | standing ${hot}`;
}

function fallbackJobOutcome(session, job, room, approach, tactic = null) {
  const stat = effectiveStats(session.data.profile)[approach] ?? 1;
  const strong = stat >= 2;
  const outcome = strong ? "success" : "mixed";
  return {
    approachUsed: approach,
    tacticUsed: tactic,
    narration: [
      `${room.name} does not get quieter while you work. It just stops pretending not to listen.`,
      `You lean on ${approach.toUpperCase()}${tactic ? `, ${tactic.replace(/_/g, " ")},` : ""} and keep the job from folding in on itself.`,
      strong
        ? `By the time the dust settles, the shard owes you a little respect and somebody else a little fear.`
        : `It works, mostly. The sort of mostly that still leaves residue on your handle.`
    ],
    outcome,
    creditDelta: strong ? job.reward.credits : Math.max(20, job.reward.credits - 20),
    debtDelta: 0,
    heatDelta: strong ? job.reward.heat : job.risk.heat,
    stressDelta: strong ? job.reward.stress : Math.max(1, job.reward.stress + 1),
    hpDelta: strong ? 0 : job.risk.hp,
    factionKey: job.faction,
    repDelta: strong ? (job.reward.rep[job.faction] ?? 1) : 1,
    rumor: `wire:// ${session.handle} came back from ${job.title.toLowerCase()} looking like the city blinked first.`,
    itemDropId: strong ? job.salvage?.[0] ?? null : null
  };
}

function fallbackHuntOutcome(session, job, room, approach, tactic = null) {
  const stat = effectiveStats(session.data.profile)[approach] ?? 1;
  const strong = stat >= 2;
  const outcome = strong ? "success" : "mixed";
  return {
    approachUsed: approach,
    tacticUsed: tactic,
    narration: [
      `The hunt starts with bad light, bad timing, and a target already halfway into somebody else's alibi.`,
      `You work the angle through ${room.name}, leaning on ${approach.toUpperCase()}${tactic ? ` and ${tactic.replace(/_/g, " ")}` : ""} before the target can finish choosing an exit.`,
      strong
        ? `When it breaks, it breaks your way. The district will call it decisive and mean lucky.`
        : `It lands messy. Close enough to count, ugly enough to leave a story behind.`
    ],
    outcome,
    creditDelta: strong ? job.reward.credits : Math.max(28, job.reward.credits - 18),
    debtDelta: 0,
    heatDelta: strong ? Math.max(0, job.reward.heat) : Math.max(1, job.risk.heat),
    stressDelta: strong ? Math.max(1, job.reward.stress) : Math.max(2, job.reward.stress + 1),
    hpDelta: strong ? Math.min(0, job.risk.hp + 1) : job.risk.hp,
    factionKey: job.faction,
    repDelta: strong ? (job.reward.rep[job.faction] ?? 1) : 1,
    rumor: `wire:// ${session.handle} put pressure on ${job.target?.name ?? "a live target"} and walked away before the sirens could agree on a name.`,
    itemDropId: strong ? job.salvage?.[0] ?? null : null,
    combat: {
      trail: `You find ${job.target?.name ?? "the target"} where the room is loud enough to hide intent and quiet enough to hear steel.`,
      targetName: job.target?.name ?? "Unknown target",
      targetRole: job.target?.role ?? "district threat",
      targetLook: job.target?.look ?? "They look like the sort of problem that arrives before the paperwork.",
      rounds: [
        `${job.target?.name ?? "The target"} tests the distance with a cheap weapon and an expensive amount of nerve.`,
        `You answer with ${approach.toUpperCase()}, turning the room itself into a witness and a weapon.`,
        strong
          ? `The third exchange belongs to you, and everyone nearby learns exactly when to stop pretending not to watch.`
          : `The last exchange is all impact, breath, and compromised dignity, but it still leaves you standing.`
      ],
      finisher: strong
        ? `${job.target?.name ?? "The target"} goes down hard enough to become a lesson before they become a memory.`
        : `${job.target?.name ?? "The target"} escapes in worse shape than pride alone would explain, and that is enough for tonight.`
    }
  };
}

function effectiveStats(profile) {
  const stats = structuredClone(profile.stats ?? {});
  // Equipment slot bonuses: weapon, every armor slot, every cyberware
  // slot (including catalogue chrome that's been "installed" — those
  // get exposed via iterEquipped's *_bonus shim).
  for (const { computed } of iterEquipped(profile)) {
    for (const stat of ["edge", "ghost", "wire", "body"]) {
      const bonus = Number(computed[`${stat}_bonus`] ?? 0);
      if (bonus) stats[stat] = (stats[stat] ?? 0) + bonus;
    }
  }
  for (const stat of Object.keys(profile.prep?.statBoosts ?? {})) {
    stats[stat] = (stats[stat] ?? 0) + (profile.prep.statBoosts[stat] ?? 0);
  }
  return stats;
}

/** Total {@code healing_passive} across every equipped slot. Used by the
 *  passive-heal tick scheduler in index.mjs. */
export function passiveHealRate(profile) {
  let total = 0;
  for (const { computed } of iterEquipped(profile)) {
    total += Number(computed.healing_passive ?? 0);
  }
  return Math.max(0, total);
}

/** Total defense contribution across every equipped slot. */
export function effectiveDefense(profile) {
  let total = 0;
  for (const { computed } of iterEquipped(profile)) {
    total += Number(computed.defense ?? 0);
  }
  return Math.max(0, total);
}

function chromeHeatFloorBonus(profile) {
  let total = 0;
  for (const chromeId of profile.chrome ?? []) {
    total += CYBERWARE[chromeId]?.effects?.heatFloorBonus ?? 0;
  }
  return total;
}

function applyFactionConsequences(profile, job, outcome) {
  const notes = [];
  if (job.faction === "club") {
    profile.rep.bureau = clampInt((profile.rep.bureau ?? 0) - 1, -9, 9);
    notes.push("bureau notice drifts colder after you embarrass one of their tails.");
  } else if (job.faction === "archive") {
    profile.rep.bureau = clampInt((profile.rep.bureau ?? 0) - 1, -9, 9);
    notes.push("archive keepers whisper your handle a little more reverently than is healthy.");
  } else if (job.faction === "clinic") {
    profile.debt = Math.max(0, profile.debt - 10);
    notes.push("Dr. Ilex trims a little off your ledger rather than say thank you.");
  } else if (job.faction === "dock") {
    profile.rep.couriers = clampInt((profile.rep.couriers ?? 0) - 1, -9, 9);
    notes.push("the courier lanes hear you helped the docks and mark it down.");
  } else if (job.faction === "couriers") {
    profile.rep.dock = clampInt((profile.rep.dock ?? 0) - 1, -9, 9);
    notes.push("dock runners do not love courier favours unless they are getting paid too.");
  }

  if (outcome.outcome === "failure") {
    profile.rep.bureau = clampInt((profile.rep.bureau ?? 0) + 1, -9, 9);
    notes.push("the Bureau catches a little more of your silhouette than you wanted.");
  }
  return notes;
}

export function operationApproaches(profile, hooks) {
  const stats = effectiveStats(profile);
  return [...new Set(hooks)].sort((a, b) => (stats[b] ?? 0) - (stats[a] ?? 0));
}

function strongestApproach(profile, hooks) {
  const stats = effectiveStats(profile);
  return [...hooks].sort((a, b) => (stats[b] ?? 0) - (stats[a] ?? 0))[0] ?? "edge";
}

function parseJsonObject(text) {
  try {
    const trimmed = String(text ?? "").trim();
    const start = trimmed.indexOf("{");
    const end = trimmed.lastIndexOf("}");
    if (start === -1 || end === -1 || end <= start) return null;
    return JSON.parse(trimmed.slice(start, end + 1));
  } catch {
    return null;
  }
}

function sanitizeLines(value, fallback) {
  if (!Array.isArray(value)) return fallback;
  const lines = value.filter((line) => typeof line === "string" && line.trim()).slice(0, 4);
  return lines.length > 0 ? lines : fallback;
}

function sanitizeCombat(value, fallback) {
  if (!value || typeof value !== "object") return fallback;
  const rounds = Array.isArray(value.rounds)
    ? value.rounds.filter((line) => typeof line === "string" && line.trim()).slice(0, 4)
    : [];
  return {
    trail: typeof value.trail === "string" && value.trail.trim() ? value.trail.trim() : fallback?.trail,
    targetName: typeof value.targetName === "string" && value.targetName.trim() ? value.targetName.trim() : fallback?.targetName,
    targetRole: typeof value.targetRole === "string" && value.targetRole.trim() ? value.targetRole.trim() : fallback?.targetRole,
    targetLook: typeof value.targetLook === "string" && value.targetLook.trim() ? value.targetLook.trim() : fallback?.targetLook,
    rounds: rounds.length > 0 ? rounds : (fallback?.rounds ?? []),
    finisher: typeof value.finisher === "string" && value.finisher.trim() ? value.finisher.trim() : fallback?.finisher
  };
}

function clampNumber(value, min, max, fallback) {
  if (!Number.isFinite(value)) return fallback;
  return Math.max(min, Math.min(max, Math.round(value)));
}

function clampInt(value, min, max) {
  return Math.max(min, Math.min(max, Math.round(value)));
}

function oneOf(value, allowed, fallback) {
  return allowed.includes(value) ? value : fallback;
}

function validLootId(value, allowed, fallback) {
  return typeof value === "string" && allowed?.includes(value) ? value : fallback;
}

function normalizeToken(value) {
  return String(value ?? "").toLowerCase().replace(/[^a-z0-9]+/g, "");
}

function trimmedEcho(text, max) {
  const cleaned = String(text ?? "").replace(/\s+/g, " ").trim();
  if (cleaned.length <= max) return `"${cleaned}"`;
  return `"${cleaned.slice(0, Math.max(0, max - 3)).trimEnd()}..."`;
}

function pronounVerb(npc) {
  return npc.alignment === "villain" ? "Voss" : npc.name.split(/\s+/)[0];
}
