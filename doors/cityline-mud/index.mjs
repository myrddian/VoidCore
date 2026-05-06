import { connectDoor, createScreenStack } from "@voidcore/door-sdk";
import { createApproachScreen } from "./screens/approach.mjs";
import { createDelveScreen } from "./screens/delve.mjs";
import { createDialogueScreen } from "./screens/dialogue.mjs";
import { createInventoryScreen } from "./screens/inventory.mjs";
import { createJobsScreen } from "./screens/jobs.mjs";
import { createRoutesScreen } from "./screens/routes.mjs";

process.on("unhandledRejection", (reason) => {
  console.error("[cityline] unhandledRejection", reason?.stack ?? reason);
});
process.on("uncaughtException", (error) => {
  console.error("[cityline] uncaughtException", error?.stack ?? error);
});
import {
  applyJobOutcome,
  applyPassiveHeatTicks,
  passiveHealRate,
  applyPlotRepNudges,
  applyTalkEffects,
  ARMOR_SLOTS,
  CYBERWARE_SLOTS,
  equipFromInventory,
  findInventoryEquippable,
  kindForItem,
  unequipSlot,
  assignJob,
  awardAchievement,
  totalAchievementPoints,
  delveById,
  delvesInRoom,
  eligibleTemplatesForRoom,
  findBestWeapon,
  findInspectable,
  findActiveJob,
  findJob,
  findVendorItem,
  getRoomDistortions,
  pickEligibleLoreTemplate,
  rollProceduralDelve,
  installCyberware,
  installedChrome,
  inventoryDetails,
  jobsVisibleInRoom,
  leadAgeBand,
  normalizeProfile,
  normalizePlotState,
  rankedThreads,
  scavengeRoom,
  resolveJob,
  shardBulletin,
  threadSummary,
  useInventoryItem,
  availableChrome,
  advancePlotState,
  defaultPlotState,
  vendorForRoom
} from "./game.mjs";
import {
  renderCityline,
  renderChrome,
  renderHelp,
  renderInventory,
  renderDelveList,
  renderJobResolution,
  renderJobs,
  renderAchievements,
  renderLeads,
  renderLoreList,
  renderLorePiece,
  renderMissions,
  renderRumors,
  renderStatus,
  renderThreads,
  renderVendor
} from "./render.mjs";
import { DEFAULT_FEED, FACTIONS, ITEMS, JOBS, LORE_PIECES as LORE_PIECES_REF, NPCS, PLOT_THREADS, ROOMS } from "./world.mjs";

const MANIFEST = {
  door_id: "cityline-mud",
  name: "Cityline MUD",
  version: "0.3.0",
  authors: ["sysop"],
  description: "A shard-city protocol demo with shared state, jobs, rumors, NPC memory, and optional LLM scene resolution.",
  modes_supported: ["normal"],
  default_mode: "normal",
  capabilities: {
    storage_kv: true,
    llm: true,
    notifications: true,
    multi_session: true,
    inter_session_messages: true,
    user_handle_visible: true,
    user_id_visible: true
  },
  max_concurrent_sessions: 16
};

const roomHistory = new Map(Object.keys(ROOMS).map((room) => [room, []]));
const ORACLE_FRAMES = ["-", "\\", "|", "/"];
const MAX_ROOM_JOBS = 5;
const screens = createScreenStack();

const DEBUG_PROMPTS_ON = (() => {
  const raw = process.env.CITYLINE_DEBUG_PROMPTS;
  return raw != null && raw !== "" && raw !== "0" && raw.toLowerCase() !== "false";
})();
console.log(`[cityline] debug-prompts=${DEBUG_PROMPTS_ON ? "ON (CITYLINE_DEBUG_PROMPTS set)" : "off (set CITYLINE_DEBUG_PROMPTS=1 to enable)"}`);

const door = connectDoor({
  manifest: MANIFEST,
  async onWelcome(payload) {
    console.log("door connected:", payload.protocol_version);
  },
  async onAttach(session) {
    installPromptDedupe(session);
    session.data.llmEnabled = await probeLlm(session);
    session.data.feed = await loadFeed(session);
    session.data.plotState = await loadPlotState(session);
    session.data.profile = await loadProfile(session);
    session.data.generatedJobs = await loadJobBoard(session);
    // Backfill the current room into visitedRooms so Cartographer counts
    // the player's spawn room (they "visited" it by being there).
    if (!Array.isArray(session.data.profile.visitedRooms)) session.data.profile.visitedRooms = [];
    if (!session.data.profile.visitedRooms.includes(session.data.profile.room)) {
      session.data.profile.visitedRooms.push(session.data.profile.room);
    }
    syncRoomMeta(session);
    const restored = session.attachReason === "reconnect"
      ? `Signal restored in ${ROOMS[session.data.profile.room].name}.`
      : "Shard handshake accepted.";
    session.effect("set_title", { title: `CITYLINE // ${session.handle}` });
    session.notify(
      session.data.llmEnabled ? "Shard oracle online." : "Shard oracle offline; fallback routines engaged.",
      { level: session.data.llmEnabled ? "info" : "warn", durationMs: 2200 }
    );
    await appendFeed(session, {
      tone: "bright-cyan",
      text: `wire:// ${session.handle} surfaced at ${ROOMS[session.data.profile.room].name}.`
    });
    await screens.setRoot(session, createMainScreen(restored));
  },
  async onLine(session, text) {
    if (!session) return;
    await screens.dispatchLine(session, text);
  },
  async onKey(session, key, modifiers) {
    if (!session) return;
    await screens.dispatchKey(session, key, modifiers);
  },
  async onTime(session) {
    if (!session) return;
    // Apply any due passive-heat plot effects regardless of which screen
    // the player is on. Heat ticks are world consequences — they should
    // accrue whether the player is sitting on the room view or browsing
    // the leads journal. We rely on the per-session lastApplied map to
    // throttle so an effect with a 30s interval really fires every 30s
    // even though ticks come every 5s.
    try {
      session.data.passiveHeatLastApplied = session.data.passiveHeatLastApplied ?? {};
      const nowSec = session.clock?.unixTimeSec ?? Math.floor(Date.now() / 1000);
      const fired = applyPassiveHeatTicks(
        session.data.profile,
        session.data.plotState ?? {},
        session.data.profile.room,
        nowSec,
        session.data.passiveHeatLastApplied
      );
      if (fired.length > 0) {
        await saveProfile(session);
        for (const f of fired) {
          session.notify(`heat ${f.delta >= 0 ? "+" : ""}${f.delta} — ${f.label}`, {
            level: "warn",
            durationMs: 2400
          });
        }
      }

      // Passive heal from grafted chrome (healing_passive total). Tick
      // every 30s so an item with healing_passive:1 restores 1 HP every
      // 30 wall-seconds; items with higher rates compound. No-op when
      // already at max HP.
      const profile = session.data.profile;
      const rate = passiveHealRate(profile);
      if (rate > 0 && (profile.hp ?? 0) < (profile.maxHp ?? 10)) {
        session.data.passiveHealLastTick = session.data.passiveHealLastTick ?? nowSec;
        const elapsed = nowSec - session.data.passiveHealLastTick;
        const intervalSec = 30;
        if (elapsed >= intervalSec) {
          const cycles = Math.floor(elapsed / intervalSec);
          const before = profile.hp ?? 0;
          profile.hp = Math.min(profile.maxHp ?? 10, before + cycles * rate);
          session.data.passiveHealLastTick = nowSec - (elapsed % intervalSec);
          if (profile.hp !== before) {
            await saveProfile(session);
            session.notify(`chrome stitches you up: +${profile.hp - before} HP`, {
              level: "info",
              durationMs: 1800
            });
          }
        }
      }
    } catch (e) { /* harmless on disconnect */ }

    // Time-driven repaint: the BBS sends time.tick periodically. Only refresh
    // when the *room view* is currently on screen — any other rendered view
    // (help, status, threads, leads, rumors, inventory, chrome, vendor,
    // job-resolution, dialogue, routes-menu, jobs-menu, approach/tactic
    // menus, etc.) owns its own paint cadence and would be wiped by a
    // forced repaint.
    //
    // Two-layer gate, both must pass:
    //   1. The screen stack top must be cityline-main (filters all PUSHED
    //      screens: dialogue, every option screen).
    //   2. The last paint-kind must be exactly "room" (filters in-place
    //      paints on cityline-main like help / status / leads — these set
    //      lastPaintKind via paintAuxScreen). Unset also fails closed.
    const top = screens.current(session);
    if (top?.id !== "cityline-main") return;
    if (session.data.lastPaintKind !== "room") return;
    try { await render(session); } catch (e) { /* harmless on disconnect */ }
  }
});

function createMainScreen(initialNote = null) {
  return {
    id: "cityline-main",
    async onEnter(session) {
      await render(session, initialNote);
    },
    async onResume(session, _stack, _from, reason) {
      await render(session, reason === "walk-away" ? "You walked away." : null);
    },
    async onLine(session, text) {
      const input = (text ?? "").trim();
      if (!input) {
        await render(session, "Type `help` for command syntax.");
        return true;
      }
      const [verbRaw, ...rest] = input.split(/\s+/);
      const verb = verbRaw.toLowerCase();
      const arg = rest.join(" ").trim();

      switch (verb) {
        case "look":
          await render(session);
          return true;
        case "help":
          paintAuxScreen(session, "help", renderHelp(session));
          return true;
        case "rooms":
          await screens.push(session, createRoutesScreen(), "routes");
          return true;
        case "who":
          await render(session, "Online: " + [...door.sessions.values()].map((s) => `${s.handle}@${ROOMS[s.data.profile?.room ?? "alley"]?.name ?? "offline"}`).join(", "));
          return true;
        case "status":
          paintAuxScreen(session, "status", renderStatus(session, shardBulletin(session.data.profile)));
          return true;
        case "rumors":
        case "rumours":
          paintAuxScreen(session, "rumors", renderRumors(session, session.data.feed));
          return true;
        case "threads":
        case "plot":
          session.data.plotState = await loadPlotState(session);
          paintAuxScreen(session, "threads", renderThreads(session, rankedThreads(session.data.plotState)));
          return true;
        case "leads":
        case "journal":
          paintAuxScreen(session, "leads", renderLeads(session));
          return true;
        case "missions":
        case "tasks":
          paintAuxScreen(session, "missions", renderMissions(session));
          return true;
        case "achievements":
        case "achv":
        case "trophies":
          paintAuxScreen(session, "achievements", renderAchievements(session));
          return true;
        case "lore":
        case "tapes":
        case "archive": {
          const id = arg.trim();
          if (!id) {
            paintAuxScreen(session, "lore", renderLoreList(session));
            return true;
          }
          const piece = (session.data.profile.lore ?? []).find((l) => l.id === id || l.refId === id);
          if (!piece) {
            await render(session, `No lore piece in your collection matches: ${id}. Try \`lore\` for the list.`);
            return true;
          }
          // Mark narrative pieces as read on first viewing — this unlocks
          // Quiet Listener once every narrative piece in the catalog has
          // been actually read, not merely collected.
          if (piece.kind === "narrative" && !piece.read) {
            piece.read = true;
            await saveProfile(session);
            await checkPostEffectAchievements(session);
          }
          paintAuxScreen(session, "lore", renderLorePiece(session, piece));
          return true;
        }
        case "delves":
        case "runs": {
          const staticList = delvesInRoom(session.data.profile.room);
          const procList = listProceduralDelvesInRoom(session, session.data.profile.room);
          const list = [...staticList, ...procList];
          if (list.length === 0) {
            await render(session, "No delves anchored to this room. Try `discover` if a section feels open.");
            return true;
          }
          paintAuxScreen(session, "delves", renderDelveList(session, list));
          return true;
        }
        case "delve":
        case "run-delve": {
          const id = arg.trim();
          const delve = lookupDelve(session, id);
          if (!delve) {
            await render(session, `No delve matches: ${id}. Try \`delves\`.`);
            return true;
          }
          if (delve.room !== session.data.profile.room) {
            await render(session, `${delve.title} launches from ${ROOMS[delve.room].name}, not here.`);
            return true;
          }
          await screens.push(session, createDelveScreen(delve), "delve");
          return true;
        }
        case "discover":
        case "scout": {
          await scoutForDelve(session);
          return true;
        }
        case "dedupe-stats": {
          const d = session.data;
          await render(session, `dedupe — paints skipped: ${d._dedupeSkippedPaints ?? 0} · prompts skipped: ${d._dedupeSkippedPrompts ?? 0} · notifies skipped: ${d._dedupeSkippedNotifies ?? 0}`);
          return true;
        }
        case "inv":
        case "inventory":
          await screens.push(session, createInventoryScreen(), "inventory");
          return true;
        case "chrome":
        case "augments":
          await showChrome(session);
          return true;
        case "wares":
        case "vendor":
        case "shop":
          await showVendor(session);
          return true;
        case "buy":
          await buyItem(session, arg);
          return true;
        case "use":
          await useItem(session, arg);
          return true;
        case "install":
          await installChrome(session, arg);
          return true;
        case "patch":
        case "heal":
        case "mend":
        case "treat":
          await patchUp(session);
          return true;
        case "equip":
        case "wield":
        case "wear":
          await equipFromRoom(session, arg);
          return true;
        case "unequip":
        case "remove":
        case "doff":
          await unequipFromRoom(session, arg);
          return true;
        case "jobs":
          await screens.push(session, createJobsScreen(), "jobs");
          return true;
        case "scan":
          await scanRoom(session);
          return true;
        case "take":
          await takeJob(session, arg);
          return true;
        case "run":
          await beginRunJob(session);
          return true;
        case "search":
        case "scavenge":
          await searchRoom(session);
          return true;
        case "inspect":
        case "examine":
          await inspectTarget(session, arg);
          return true;
        case "go":
          await move(session, arg);
          return true;
        case "talk":
          await talk(session, arg);
          return true;
        case "emote":
        case "pose":
          await emote(session, arg);
          return true;
        case "page":
        case "tell":
        case "msg":
          await pageHandle(session, arg);
          return true;
        case "say":
          await speak(session, arg);
          return true;
        case "jackout":
        case "quit":
        case "exit":
          await jackOut(session);
          return true;
        default:
          await render(session, `Unknown command: ${verb}`);
          return true;
      }
    },
    async onKey(session, key) {
      const k = String(key ?? "").trim().toLowerCase();
      if (k === "escape" || k === "esc") {
        if (session.data.lastPaintKind && session.data.lastPaintKind !== "room") {
          await render(session);
          return true;
        }
        // Esc on the room view itself does nothing — there is no parent
        // to back out to, and we don't want to accidentally jack the
        // player out with a stray keypress.
        return true;
      }
      if (k === "l") {
        await render(session);
        return true;
      }
      return false;
    }
  };
}

export async function move(session, targetRaw) {
  const target = resolveRoom(targetRaw);
  if (!target || !ROOMS[target]) {
    await render(session, "No such room.");
    return;
  }
  const current = ROOMS[session.data.profile.room];
  if (!current.exits.includes(target)) {
    await render(session, "No route from here.");
    return;
  }
  session.data.profile.room = target;
  session.data.profile.stress = Math.min(9, session.data.profile.stress + (target === "switchyard" ? 1 : 0));
  if (!Array.isArray(session.data.profile.visitedRooms)) session.data.profile.visitedRooms = [];
  if (!session.data.profile.visitedRooms.includes(target)) {
    session.data.profile.visitedRooms.push(target);
  }
  await saveProfile(session);
  await checkPostEffectAchievements(session);
  syncRoomMeta(session);
  session.effect("set_title", { title: `CITYLINE // ${ROOMS[target].name}` });
  await appendFeed(session, {
    tone: ROOMS[target].accent,
    text: `wire:// ${session.handle} ghosted into ${ROOMS[target].name}.`
  });
  await render(session, `You ghost into ${ROOMS[target].name}.`);
}

async function talk(session, targetRaw) {
  const room = ROOMS[session.data.profile.room];
  const npcId = resolveNpcInRoom(room, targetRaw);
  if (!npcId) {
    console.log(`[cityline] talk miss target=${targetRaw} room=${room.key} candidates=${room.npcs.join(",")}`);
    await render(session, `Nobody here answers to that name. Try: ${room.npcs.map((id) => NPCS[id]?.name).filter(Boolean).join(", ")}`);
    return;
  }
  const npc = NPCS[npcId];
  await screens.push(session, createDialogueScreen(npc), "conversation");
  await appendFeed(session, {
    tone: room.support,
    text: `wire:// ${session.handle} spent time with ${npc.name} in ${room.name}.`
  });
  session.notify(`You lean into ${npc.name}'s frequency.`, { level: "info", durationMs: 1800 });
}

export async function takeJob(session, targetRaw) {
  session.data.generatedJobs = await loadJobBoard(session);
  if (session.data.profile.activeJobId) {
    await render(session, `You already have live work: ${session.data.profile.activeJobId}. Use \`run\` first.`);
    return;
  }
  const job = findJob(targetRaw, session.data.profile.room, allJobsForSession(session));
  if (!job) {
    await render(session, "No such local job.");
    return;
  }
  assignJob(session.data.profile, job);
  await saveProfile(session);
  await appendFeed(session, {
    tone: ROOMS[job.room].support,
    text: `wire:// ${session.handle} picked up "${job.title}".`
  });
  session.notify(`Op accepted: ${job.title}`, { level: "info", durationMs: 2000 });
  await render(session, `You take the work: ${job.title}. When ready, type \`run\`.`);
}

async function showVendor(session, note = null) {
  const vendor = vendorForRoom(session.data.profile.room, session.data.plotState);
  if (!vendor) {
    await render(session, "No one here is selling anything they admit to.");
    return;
  }
  paintAuxScreen(session, "vendor", renderVendor(session, vendor, note));
}

async function showChrome(session, note = null) {
  const roomKey = session.data.profile.room;
  const inClinic = roomKey === "clinic";
  paintAuxScreen(session, "chrome", renderChrome(
    session,
    installedChrome(session.data.profile),
    availableChrome(session.data.profile),
    inClinic,
    note
  ));
}

async function buyItem(session, targetRaw) {
  if (!targetRaw) {
    await showVendor(session, "Usage: buy <item>");
    return;
  }
  const offer = findVendorItem(session.data.profile.room, targetRaw, session.data.plotState);
  if (!offer) {
    await render(session, "That item is not on the local counter.");
    return;
  }
  if (session.data.profile.inventory.length >= 12) {
    await render(session, "Your pockets are already full of bad ideas.");
    return;
  }
  if (session.data.profile.credits < offer.price) {
    await render(session, `You need ${offer.price}c for ${offer.item.name}.`);
    return;
  }
  session.data.profile.credits -= offer.price;
  session.data.profile.inventory.push(offer.itemId);
  await saveProfile(session);
  await appendFeed(session, {
    tone: session.data.roomMeta.support,
    text: `wire:// ${session.handle} walked out of ${vendorForRoom(session.data.profile.room, session.data.plotState).name} carrying fresh trouble.`
  });
  session.notify(`Purchased ${offer.item.name} for ${offer.price}c.`, { level: "info", durationMs: 1800 });
  paintAuxScreen(session, "inventory", renderInventory(session, inventoryDetails(session.data.profile), `Bought ${offer.item.name}.`));
}

async function useItem(session, targetRaw) {
  if (!targetRaw) {
    paintAuxScreen(session, "inventory", renderInventory(session, inventoryDetails(session.data.profile), "Usage: use <item>"));
    return;
  }
  const result = useInventoryItem(session.data.profile, targetRaw);
  if (!result.ok) {
    await render(session, result.message);
    return;
  }
  await saveProfile(session);
  session.notify(result.message, { level: "info", durationMs: 2200 });
  paintAuxScreen(session, "inventory", renderInventory(session, inventoryDetails(session.data.profile), result.message));
}

async function installChrome(session, targetRaw) {
  if (session.data.profile.room !== "clinic") {
    await render(session, "You need a chair, a lamp, and Dr. Ilex's bad bedside manner for that.");
    return;
  }
  if (!targetRaw) {
    await showChrome(session, "Usage: install <augment>");
    return;
  }
  const result = installCyberware(session.data.profile, targetRaw);
  if (!result.ok) {
    await showChrome(session, result.message);
    return;
  }
  await saveProfile(session);
  await appendFeed(session, {
    tone: "bright-blue",
    text: `wire:// ${session.handle} came out of the clinic walking a little differently.`
  });
  session.notify(`Installed ${result.chrome.name}.`, { level: "info", durationMs: 2200 });
  await showChrome(session, result.message);
}

// Clinic patch-up — Dr. Ilex sells you back your missing HP at a flat
// cost-per-point. Same gating as `install` (must be at the clinic).
// Stress drops a notch as a side effect; the chair is calmer than the
// street is.
const PATCH_COST_PER_HP = 4;

async function patchUp(session) {
  if (session.data.profile.room !== "clinic") {
    await render(session, "Dr. Ilex's chair is at the clinic. You'll have to walk to her.");
    return;
  }
  const profile = session.data.profile;
  const missing = Math.max(0, (profile.maxHp ?? 10) - (profile.hp ?? 0));
  if (missing <= 0) {
    await render(session, "Dr. Ilex looks you over. \"You're in better shape than half the regulars. Come back when you're not.\"");
    return;
  }
  const fullCost = missing * PATCH_COST_PER_HP;
  const credits = profile.credits ?? 0;
  if (credits < PATCH_COST_PER_HP) {
    await render(session, `Dr. Ilex doesn't extend credit at this hour. Patching costs ${PATCH_COST_PER_HP}c per HP — come back with at least that.`);
    return;
  }
  // Heal as much as the player can afford, never more than what's missing.
  const affordablePoints = Math.floor(credits / PATCH_COST_PER_HP);
  const healed = Math.min(missing, affordablePoints);
  const cost = healed * PATCH_COST_PER_HP;
  profile.credits = credits - cost;
  profile.hp = Math.min(profile.maxHp ?? 10, (profile.hp ?? 0) + healed);
  if (typeof profile.stress === "number") {
    profile.stress = Math.max(0, profile.stress - 1);
  }
  await saveProfile(session);
  await appendFeed(session, {
    tone: "bright-blue",
    text: `wire:// ${session.handle} let Dr. Ilex tighten the loose seams.`
  });
  const partial = healed < missing ? ` (you couldn't afford the rest — short ${(missing - healed) * PATCH_COST_PER_HP}c)` : "";
  session.notify(`Patched up: +${healed} HP / -${cost}c${partial ? " · partial" : ""}`, {
    level: "info",
    durationMs: 2400
  });
  await render(session,
    `Dr. Ilex works fast. The room smells of disinfectant and somebody else's bad night. +${healed} HP, -${cost}c${partial}.`);
}

// Verb-level wrappers around equipFromInventory / unequipSlot. Cyberware
// equips are gated to the clinic — this matches the existing `install`
// path. Weapons and armor can be swapped anywhere; you don't need a
// surgeon to put a coat on.
async function equipFromRoom(session, targetRaw) {
  const target = String(targetRaw ?? "").trim();
  if (!target) {
    await render(session, "Equip what? Try `inv` to see what's on hand.");
    return;
  }
  const profile = session.data.profile;
  // Cyberware is surgery — gate it to the clinic before mutating.
  const peek = findInventoryEquippable(profile, target);
  if (peek && kindForItem(peek.entry) === "cyberware" && profile.room !== "clinic") {
    await render(session, "Cyberware is a clinic-chair job. Walk to the clinic first.");
    return;
  }
  const result = equipFromInventory(profile, target);
  if (!result.ok) {
    await render(session, result.message);
    return;
  }
  await saveProfile(session);
  const displacedName = result.displaced
    ? (result.displaced.name ?? result.displaced.baseId ?? String(result.displaced))
    : null;
  const tail = displacedName ? ` (${displacedName} dropped back to your pockets.)` : "";
  session.notify(result.message, { level: "info", durationMs: 2200 });
  await render(session, `${result.message}${tail}`);
}

async function unequipFromRoom(session, slotRaw) {
  const slot = String(slotRaw ?? "").trim().toLowerCase();
  if (!slot) {
    await render(session, "Unequip which slot? Use one of: weapon, " + ARMOR_SLOTS.join(", ") + ", " + CYBERWARE_SLOTS.join(", ") + ".");
    return;
  }
  const result = unequipSlot(session.data.profile, slot);
  if (!result.ok) {
    await render(session, result.message);
    return;
  }
  await saveProfile(session);
  session.notify(result.message, { level: "info", durationMs: 1800 });
  await render(session, result.message);
}

async function scanRoom(session) {
  if (!session.data.llmEnabled) {
    console.log(`[cityline] scan fallback room=${session.data.profile.room}`);
    setLastScan(session, "your burner sketches the room in static and old instincts. Nothing sharper without the oracle.");
    await render(session);
    return;
  }
  const room = ROOMS[session.data.profile.room];
  const traffic = roomHistory.get(room.key) ?? [];
  let partial = "";
  let lastPaint = 0;
  let sawToken = false;
  const stopWait = startOracleWait(session, `sampling ${room.key}`);
  console.log(`[cityline] scan start room=${room.key}`);
  session.notify("Shard oracle sampling the local signal...", { level: "info", durationMs: 1800 });
  const result = await session.llm.stream([
    {
      role: "system",
      content: "You are the shard oracle for a cyberpunk BBS door game. Write one short second-person scan of the current room. No markdown. No lists. Keep it under 220 characters."
    },
    {
      role: "user",
      content: JSON.stringify({
        room: {
          name: room.name,
          description: room.description,
          detail: room.detail
        },
        npcs: room.npcs.map((id) => NPCS[id]?.name).filter(Boolean),
        traffic: traffic.slice(-2),
        rumors: session.data.feed.slice(-2)
      })
    }
  ], {
    temperature: 0.9,
    onToken(token) {
      if (!sawToken) {
        sawToken = true;
        stopWait();
        console.log(`[cityline] scan first-token room=${room.key}`);
      }
      partial += token;
      if (partial.length - lastPaint >= 24) {
        lastPaint = partial.length;
        setLastScan(session, partial.trim());
        render(session).catch((error) => console.error("scan render failed", error));
      }
    }
  });
  stopWait();
  const final = (result.content ?? partial).trim();
  console.log(`[cityline] scan done room=${room.key} chars=${final.length}`);
  setLastScan(session, final);
  await render(session);
}

function setLastScan(session, text) {
  if (!text) return;
  session.data.lastScan = {
    text,
    room: session.data.profile.room,
    ts: new Date().toISOString()
  };
}

async function beginRunJob(session) {
  session.data.generatedJobs = await loadJobBoard(session);
  const job = findActiveJob(session.data.profile, allJobsForSession(session));
  if (!job) {
    await render(session, "No active job. Use `jobs` and `take <job>` first.");
    return;
  }
  await screens.push(session, createApproachScreen(job.id), "run");
}

export async function runJob(session, chosenApproach = null, chosenTactic = null) {
  session.data.generatedJobs = await loadJobBoard(session);
  const job = findActiveJob(session.data.profile, allJobsForSession(session));
  if (!job) {
    await render(session, "No active job. Use `jobs` and `take <job>` first.");
    return;
  }
  const room = ROOMS[session.data.profile.room];
  session.notify("Routing job through shard adjudication...", { level: "info", durationMs: 2200 });
  session.data.plotState = await loadPlotState(session);
  console.log(`[cityline] run start job=${job.id} llmEnabled=${session.data.llmEnabled} room=${room.key} approach=${chosenApproach ?? "auto"} tactic=${chosenTactic ?? "auto"}`);
  const stopWait = session.data.llmEnabled
    ? startOracleWait(session, `adjudicating ${job.id}`)
    : null;
  const outcome = await resolveJob(session, job, room, session.data.llmEnabled, session.data.plotState, chosenApproach, chosenTactic);
  stopWait?.();
  applyJobOutcome(session.data.profile, job, outcome);
  const plotAdvance = advancePlotState(session.data.plotState, job, outcome, session.handle);
  session.data.plotState = plotAdvance.state;
  const repApplied = applyPlotRepNudges(session.data.profile, plotAdvance.updates);
  await saveProfile(session);
  await savePlotState(session);
  await checkPostEffectAchievements(session);
  await appendFeed(session, { tone: ROOMS[job.room].accent, text: outcome.rumor });
  for (const update of plotAdvance.updates) {
    await appendFeed(session, { tone: "bright-magenta", text: update.rumor });
  }
  for (const nudge of repApplied) {
    await appendFeed(session, {
      tone: "bright-yellow",
      text: `wire:// ${FACTIONS[nudge.faction].name} ${nudge.delta >= 0 ? "+" : ""}${nudge.delta} as ${PLOT_THREADS[nudge.threadId]?.title ?? nudge.threadId} shifts.`
    });
  }
  paintAuxScreen(session, "job-resolution", renderJobResolution(session, job, outcome));
  session.notify(
    `Outcome: ${outcome.outcome} via ${(outcome.approachUsed ?? chosenApproach ?? "edge").toUpperCase()} | credits ${signed(outcome.creditDelta)} | heat ${signed(outcome.heatDeltaApplied ?? outcome.heatDelta)}`,
    { level: outcome.outcome === "failure" ? "warn" : "info", durationMs: 2600 }
  );
  console.log(`[cityline] run done job=${job.id} outcome=${outcome.outcome} approach=${outcome.approachUsed ?? chosenApproach ?? "auto"} tactic=${outcome.tacticUsed ?? chosenTactic ?? "auto"} plotUpdates=${plotAdvance.updates.length}`);
  broadcastRoom(session.data.profile.room);
}

async function searchRoom(session) {
  const result = scavengeRoom(session.data.profile, session.data.profile.room);
  if (!result.ok) {
    await render(session, result.message);
    return;
  }
  // 60% chance: also drop a procedural lore piece for the lived-in feel.
  // The City always has notes pinned, bulletins fresh, conversations overheard;
  // search picks them up alongside any item drop.
  let loreNote = null;
  if (Math.random() < 0.6) {
    const tmpl = pickEligibleLoreTemplate(session.data.profile.room);
    if (tmpl) {
      const proceduralEffect = { op: "grant_lore", templateId: tmpl.id };
      const adjudicated = applyTalkEffects(
        session.data.profile,
        session.data.plotState,
        { effects: [proceduralEffect] },
        { roomKey: session.data.profile.room, discoveredVia: "search" }
      );
      const added = adjudicated.loreAdded?.[0];
      if (added) {
        loreNote = `lore: ${added.title}  (read with \`lore ${added.id}\`)`;
      }
    }
  }
  await saveProfile(session);
  if (result.reward.itemId || result.reward.credits) {
    await appendFeed(session, {
      tone: session.data.roomMeta.support,
      text: `wire:// ${session.handle} shook something useful out of ${session.data.roomMeta.name}.`
    });
  }
  const message = loreNote ? `${result.message}  •  ${loreNote}` : result.message;
  session.notify(result.message, { level: "info", durationMs: 2200 });
  if (loreNote) session.notify(loreNote, { level: "info", durationMs: 2400 });
  await render(session, message);
}

async function inspectTarget(session, targetRaw) {
  if (!targetRaw) {
    await render(session, "Usage: inspect <room|name|fixture>");
    return;
  }
  const result = findInspectable(session.data.profile.room, targetRaw);
  if (!result) {
    await render(session, "Nothing here answers to that description.");
    return;
  }
  await render(session, `${result.title}: ${result.text}`);
}

async function emote(session, text) {
  if (!text) {
    await render(session, "Emote what?");
    return;
  }
  const history = roomHistory.get(session.data.profile.room);
  history.push(`* ${session.handle} ${text}`);
  while (history.length > 8) history.shift();
  await broadcastRoom(session.data.profile.room);
}

async function pageHandle(session, text) {
  const input = String(text ?? "").trim();
  const [targetRaw, ...rest] = input.split(/\s+/);
  const message = rest.join(" ").trim();
  if (!targetRaw || !message) {
    await render(session, "Usage: page <handle> <message>");
    return;
  }
  const target = normalizeTarget(targetRaw);
  const peer = [...door.sessions.values()].find((candidate) =>
    candidate !== session && normalizeTarget(candidate.handle) === target
  );
  if (!peer) {
    await render(session, `No live handle matches ${targetRaw}.`);
    return;
  }
  peer.notify(`page:// ${session.handle}: ${message}`, { level: "info", durationMs: 3500 });
  session.notify(`Sent to ${peer.handle}.`, { level: "info", durationMs: 1600 });
  const senderHistory = roomHistory.get(session.data.profile.room);
  senderHistory.push(`[page->${peer.handle}] ${message}`);
  while (senderHistory.length > 8) senderHistory.shift();
  await render(session, `You page ${peer.handle}: ${message}`);
}

async function speak(session, text) {
  if (!text) {
    await render(session, "Say what?");
    return;
  }
  const history = roomHistory.get(session.data.profile.room);
  history.push(`${session.handle}: ${text}`);
  while (history.length > 8) history.shift();

  const maybeFeed = text.length > 24 || /\bclinic|route|archive|bureau|club|job|ripper\b/i.test(text);
  if (maybeFeed) {
    await appendFeed(session, {
      tone: "bright-magenta",
      text: `wire:// overheard in ${ROOMS[session.data.profile.room].name}: "${trimQuote(text, 44)}"`
    });
  }

  await broadcastRoom(session.data.profile.room);
}

async function render(session, note = null) {
  session.data.feed = await loadFeed(session);
  session.data.plotState = await loadPlotState(session);
  syncRoomMeta(session);
  const roomKey = session.data.profile.room;
  const room = ROOMS[roomKey];
  const baseTraffic = roomHistory.get(roomKey) ?? [];
  const traffic = mergeAmbientDistortions(baseTraffic, getRoomDistortions(session.data.plotState ?? {}, roomKey), session);
  session.paint(renderCityline(session, room, session.data.feed, traffic, threadSummary(session.data.plotState), note));
  session.data.lastPaintKind = "room";
  safePrompt(session, { label: cityPromptLabel(roomKey), maxLength: 120 });
}

// Splice ambient distortion fragments (driven by active world effects) into
// the room's local-traffic block. We pick one fragment per active effect,
// rotated deterministically from the BBS clock so the same fragment lingers
// across a few ticks instead of changing every 5s and feeling jittery.
function mergeAmbientDistortions(traffic, distortions, session) {
  if (!distortions || distortions.length === 0) return traffic;
  const nowSec = session.clock?.unixTimeSec ?? Math.floor(Date.now() / 1000);
  // 30-second window: the same fragment is shown for ~30s before rotating.
  const window = Math.floor(nowSec / 30);
  const merged = Array.isArray(traffic) ? [...traffic] : [];
  for (const d of distortions) {
    const fragments = Array.isArray(d.fragments) ? d.fragments : [];
    if (fragments.length === 0) continue;
    const idx = (window + Math.abs(hashKey(d.threadId))) % fragments.length;
    merged.push(`~ ${fragments[idx]}`);
  }
  return merged;
}

function hashKey(text) {
  let h = 0;
  for (let i = 0; i < (text ?? "").length; i++) h = ((h << 5) - h + text.charCodeAt(i)) | 0;
  return h;
}

function paintAuxScreen(session, kind, rows) {
  session.paint(rows);
  session.data.lastPaintKind = kind;
  safePrompt(session, { label: cityPromptLabel(session.data.profile.room, "esc"), maxLength: 120 });
}

function cityPromptLabel(roomKey, exitHint = null) {
  // Room view has no parent, so no exit hint. Aux screens pass "esc" so
  // the prompt advertises the universal back key.
  const tag = roomKey ?? "cityline";
  return exitHint ? `[${exitHint}] ${tag}:` : `${tag}:`;
}

// Dedupe prompt() AND paint() calls. The BBS treats certain repeated
// envelopes as a reset of the input field — calling them on every repaint
// (time tick, oracle spinner, multiplayer broadcast, thinking-frame
// animation) wipes whatever the player has typed but not yet submitted.
// Wrapping the SDK's prompt and paint once per session so every call from
// anywhere — our own paint helpers, the SDK's dialogue and option screens,
// the spinner — runs through the same dedupe.
//
// Both wrappers compare the stringified args against the last successful
// send for this session. Identical args -> drop. Different args -> forward.
// Hot path: the rendered-rows JSON for a busy room is ~2-3KB, JSON.stringify
// + string compare in V8 is microseconds. Cheap.
function installPromptDedupe(session) {
  if (session.data._promptWrapped) return;
  session.data._promptWrapped = true;

  const originalPrompt = session.prompt.bind(session);
  session.prompt = (args) => {
    const key = JSON.stringify(args ?? {});
    if (session.data._lastPromptKey === key) {
      session.data._dedupeSkippedPrompts = (session.data._dedupeSkippedPrompts ?? 0) + 1;
      return;
    }
    session.data._lastPromptKey = key;
    originalPrompt(args);
  };

  const originalPaint = session.paint.bind(session);
  session.paint = (rows, viewportId = "main") => {
    const key = JSON.stringify({ v: viewportId, r: rows });
    if (session.data._lastPaintKey === key) {
      session.data._dedupeSkippedPaints = (session.data._dedupeSkippedPaints ?? 0) + 1;
      return;
    }
    session.data._lastPaintKey = key;
    originalPaint(rows, viewportId);
  };

  // Notify dedupe: collapse identical toasts that fire within ~2 seconds of
  // each other. Prevents passive-heat ticks or other periodic effects from
  // hammering the BBS with the same notification (which on some clients
  // resets focus). Different toasts always go through.
  const originalNotify = session.notify.bind(session);
  session.notify = (text, opts) => {
    const sig = `${text}|${opts?.level ?? "info"}`;
    const now = Date.now();
    const last = session.data._lastNotify;
    if (last && last.sig === sig && (now - last.at) < 2000) {
      session.data._dedupeSkippedNotifies = (session.data._dedupeSkippedNotifies ?? 0) + 1;
      return;
    }
    session.data._lastNotify = { sig, at: now };
    originalNotify(text, opts);
  };
}

// Force the next prompt/paint through, regardless of dedupe. Use on screen
// transitions where the input field needs to re-enable even though the
// label happens to match the previous one.
function invalidatePromptCache(session) {
  session.data._lastPromptKey = null;
  session.data._lastPaintKey = null;
}

// Public wrapper for callers that prefer named indirection. With the SDK
// patched above, this is now equivalent to session.prompt() — kept as a
// single import point so future call sites read consistently.
export function safePrompt(session, args) {
  session.prompt(args);
}

// Walk through state-driven achievements that can be checked any time the
// profile mutates: faction rep thresholds, debt thresholds, lore counts,
// plot-thread progress, NPCs talked to. Idempotent — calls tryAchievement
// for each candidate, which itself dedupes.
export async function checkPostEffectAchievements(session) {
  const profile = session.data.profile;

  // Lore counts
  const loreCount = (profile.lore ?? []).length;
  if (loreCount >= 1) await tryAchievement(session, "first_tape");
  if (loreCount >= 8) await tryAchievement(session, "tape_hoarder");

  // NPCs talked to (use npcMemory keys as the proxy)
  const distinctNpcs = Object.keys(profile.npcMemory ?? {}).length;
  if (distinctNpcs >= 5) await tryAchievement(session, "old_hand");

  // Faction rep thresholds
  if ((profile.rep?.couriers ?? 0) >= 3) await tryAchievement(session, "in_with_couriers");
  if ((profile.rep?.bureau ?? 0) >= 3) await tryAchievement(session, "in_with_bureau");

  // Debt threshold (hidden — Marrow Owed)
  if ((profile.debt ?? 0) >= 500) await tryAchievement(session, "marrow_owed");

  // Plot thread progress (hidden — Shard Bender)
  for (const state of Object.values(session.data.plotState ?? {})) {
    if ((state?.progress ?? 0) >= 2) {
      await tryAchievement(session, "shard_bender");
      break;
    }
  }

  // Cartographer — visited every room in the District.
  const totalRooms = Object.keys(ROOMS).length;
  const visited = (profile.visitedRooms ?? []).filter((k) => ROOMS[k]).length;
  if (visited >= totalRooms) await tryAchievement(session, "cartographer");

  // Quiet Listener — read every narrative lore piece in the catalog.
  const narrativeIds = Object.values(LORE_PIECES_REF).filter((p) => p.kind === "narrative").map((p) => p.id);
  const readNarratives = new Set(
    (profile.lore ?? [])
      .filter((l) => l.kind === "narrative" && l.read === true && l.refId)
      .map((l) => l.refId)
  );
  if (narrativeIds.length > 0 && narrativeIds.every((id) => readNarratives.has(id))) {
    await tryAchievement(session, "quiet_listener");
  }
}

// Unified achievement helper: door-side check + persist, BBS-side announce,
// player-side notify. Idempotent — re-calling for an already-unlocked ID is
// a no-op. Call as `tryAchievement(session, "first_blood")`.
export async function tryAchievement(session, achievementId) {
  const fresh = awardAchievement(session.data.profile, achievementId);
  if (!fresh) return false;
  // Send to BBS for the durable per-user record. The BBS dedupes too; if
  // it doesn't yet support achievement.unlock, the envelope is silently
  // dropped and our local state still stands.
  session.achievement?.unlock?.({
    id: fresh.id,
    title: fresh.title,
    flavor: fresh.flavor,
    points: fresh.points,
    category: fresh.category
  });
  session.notify(`✦ ACHIEVEMENT — ${fresh.title} (+${fresh.points})`, { level: "info", durationMs: 3500 });
  await saveProfile(session);
  return true;
}

async function jackOut(session) {
  await appendFeed(session, {
    tone: "grey",
    text: `wire:// ${session.handle} dropped carrier without leaving a clean trail.`
  });
  session.notify("Carrier dropped. See you next night.", { level: "info", durationMs: 1800 });
  session.detach("completed");
}

async function broadcastRoom(roomKey) {
  for (const peer of door.sessions.values()) {
    if (peer.data.profile?.room === roomKey) {
      peer.data.feed = await loadFeed(peer);
      await render(peer);
    }
  }
}

async function loadProfile(session) {
  const saved = await session.storage.user.get("profile");
  return normalizeProfile(saved?.value);
}

export async function saveProfile(session) {
  await persistSafely(session, "profile", () => session.storage.user.put("profile", session.data.profile));
}

function isTransportError(error) {
  const message = String(error?.message ?? error ?? "");
  return /websocket|disconnect|not connected|closed/i.test(message);
}

async function persistSafely(session, label, op) {
  try {
    await op();
  } catch (error) {
    if (isTransportError(error)) {
      console.warn(`[cityline] persist skip ${label}: door websocket unavailable; state will resync on reconnect`);
      return;
    }
    throw error;
  }
}

async function loadFeed(session) {
  const saved = await session.storage.shared.get("shard-feed");
  if (saved?.value?.entries && Array.isArray(saved.value.entries)) {
    return saved.value.entries;
  }
  await session.storage.shared.put("shard-feed", { entries: DEFAULT_FEED });
  return [...DEFAULT_FEED];
}

export async function loadPlotState(session) {
  const saved = await session.storage.shared.get("plot-state");
  if (saved?.value?.threads) {
    return normalizePlotState(saved.value.threads);
  }
  const initial = defaultPlotState();
  await session.storage.shared.put("plot-state", { threads: initial });
  return initial;
}

async function savePlotState(session) {
  await persistSafely(session, "plot-state", () => session.storage.shared.put("plot-state", { threads: session.data.plotState }));
}

export async function loadJobBoard(session) {
  const saved = await session.storage.shared.get("generated-jobs");
  const jobs = Array.isArray(saved?.value?.jobs) ? saved.value.jobs : [];
  return jobs.filter(isGeneratedJob);
}

async function saveJobBoard(session) {
  const next = pruneGeneratedJobs(session.data.generatedJobs ?? []);
  session.data.generatedJobs = next;
  await persistSafely(session, "generated-jobs", () => session.storage.shared.put("generated-jobs", { jobs: next }));
}

export function allJobsForSession(session) {
  return [...JOBS, ...(session.data.generatedJobs ?? [])];
}

export async function ensureJobsForRoom(session, roomKey) {
  session.data.generatedJobs = await loadJobBoard(session);
  const visible = jobsVisibleInRoom(roomKey, session.data.profile, allJobsForSession(session));
  const missing = Math.max(0, MAX_ROOM_JOBS - visible.length);
  if (missing === 0) return;

  const generated = await generateRoomJobs(session, roomKey, missing);
  if (generated.length === 0) return;
  session.data.generatedJobs.push(...generated);
  await saveJobBoard(session);
}

async function generateRoomJobs(session, roomKey, count) {
  const room = ROOMS[roomKey];
  if (!room || count <= 0) return [];
  if (!session.data.llmEnabled) {
    return fallbackGeneratedJobs(roomKey, count);
  }

  const system = [
    "You are generating short cyberpunk BBS door-game missions for one room.",
    "Return strict JSON only.",
    "Generate concise, playable missions with local names and concrete stakes.",
    "Prefer terse summaries and hooks that map to edge, ghost, wire, or body.",
    "At most one hunt mission in a batch unless the room is obviously violent."
  ].join(" ");

  const prompt = {
    room: {
      key: room.key,
      name: room.name,
      route: room.route,
      description: room.description,
      detail: room.detail,
      exits: room.exits.map((key) => ROOMS[key]?.name ?? key),
      npcs: room.npcs.map((id) => ({
        id,
        name: NPCS[id]?.name,
        role: NPCS[id]?.role,
        faction: NPCS[id]?.faction
      }))
    },
    plotThreads: rankedThreads(session.data.plotState ?? defaultPlotState())
      .slice(0, 3)
      .map(({ thread, state }) => ({
        id: thread.id,
        title: thread.title,
        stage: thread.stages[state.progress],
        heat: state.heat
      })),
    constraints: {
      count,
      allowedHooks: ["edge", "ghost", "wire", "body"],
      allowedFactions: Object.keys(FACTIONS),
      allowedPlots: Object.keys(PLOT_THREADS),
      allowedSalvageIds: Object.keys(ITEMS),
      allowedOperations: ["job", "hunt"]
    },
    output: {
      jobs: [
        {
          title: "Mission title",
          giver: "NPC or local contact",
          faction: "club",
          difficulty: "low | medium | high",
          summary: "One short room-specific mission summary",
          hooks: ["ghost", "wire", "edge"],
          salvage: ["signal_jammer"],
          plot: "blue_hour",
          operation: "job | hunt",
          rewardCredits: 72,
          rewardHeat: 0,
          rewardStress: 1,
          repDelta: 2,
          riskHeat: 2,
          riskHp: -1,
          riskDebt: 0,
          target: {
            name: "Only for hunt missions",
            role: "target role",
            look: "one short visual line",
            motive: "one short motive",
            threat: "one short threat line"
          }
        }
      ]
    }
  };

  try {
    const completion = await session.llm.chat([
      { role: "system", content: system },
      { role: "user", content: JSON.stringify(prompt) }
    ], { temperature: 0.95 });
    const parsed = parseJsonObject(completion.content);
    const jobs = Array.isArray(parsed?.jobs) ? parsed.jobs : [];
    const sanitized = jobs
      .map((job, index) => sanitizeGeneratedJob(job, roomKey, index))
      .filter(Boolean)
      .slice(0, count);
    if (sanitized.length > 0) {
      return sanitized;
    }
  } catch (error) {
    console.error(`[cityline] job generation failed room=${roomKey}:`, error?.code ?? error?.message ?? error);
  }

  return fallbackGeneratedJobs(roomKey, count);
}

export async function appendFeed(session, entry) {
  try {
    const existing = await loadFeed(session);
    const next = [...existing, entry].slice(-12);
    await session.storage.shared.put("shard-feed", { entries: next });
    session.data.feed = next;
  } catch (error) {
    if (isTransportError(error)) {
      console.warn(`[cityline] feed append skipped: door websocket unavailable`);
      const local = Array.isArray(session.data.feed) ? session.data.feed : [];
      session.data.feed = [...local, entry].slice(-12);
      return;
    }
    throw error;
  }
}

async function probeLlm(session) {
  try {
    await session.llm.chat([{ role: "user", content: "Reply with READY only." }], { temperature: 0 });
    console.log("[cityline] oracle probe success");
    return true;
  } catch (error) {
    console.error("[cityline] oracle probe failed:", error?.code ?? error?.message ?? error);
    return false;
  }
}

function startOracleWait(session, label) {
  let frame = 0;
  let active = true;
  const paint = () => {
    if (!active) return;
    const room = ROOMS[session.data.profile.room];
    const traffic = roomHistory.get(room.key) ?? [];
    const summary = threadSummary(session.data.plotState ?? defaultPlotState());
    const spinner = ORACLE_FRAMES[frame % ORACLE_FRAMES.length];
    frame += 1;
    session.paint(renderCityline(
      session,
      room,
      session.data.feed ?? [],
      traffic,
      summary,
      `oracle ${spinner} ${label}`
    ));
    safePrompt(session, { label: "cityline:", maxLength: 120 });
  };
  paint();
  const timer = setInterval(paint, 180);
  return () => {
    if (!active) return;
    active = false;
    clearInterval(timer);
  };
}

function syncRoomMeta(session) {
  session.data.roomMeta = ROOMS[session.data.profile.room];
}

function sanitizeGeneratedJob(value, roomKey, index) {
  if (!value || typeof value !== "object") return null;
  const title = trimText(value.title, 56);
  const giver = trimText(value.giver, 40);
  const summary = trimText(value.summary, 180);
  if (!title || !giver || !summary) return null;
  const hooks = Array.isArray(value.hooks)
    ? value.hooks.filter((hook) => ["edge", "ghost", "wire", "body"].includes(hook)).slice(0, 3)
    : [];
  const salvage = Array.isArray(value.salvage)
    ? value.salvage.filter((itemId) => ITEMS[itemId]).slice(0, 2)
    : [];
  const faction = typeof value.faction === "string" && FACTIONS[value.faction] ? value.faction : dominantRoomFaction(roomKey);
  const operation = value.operation === "hunt" ? "hunt" : "job";
  const plot = typeof value.plot === "string" && PLOT_THREADS[value.plot] ? value.plot : null;
  const repDelta = clampInt(Number(value.repDelta ?? 2), 1, 3);
  const rewardCredits = clampInt(Number(value.rewardCredits ?? 60), 28, 110);
  const rewardHeat = clampInt(Number(value.rewardHeat ?? 0), -1, 2);
  const rewardStress = clampInt(Number(value.rewardStress ?? 1), 0, 2);
  const riskHeat = clampInt(Number(value.riskHeat ?? 2), 1, 4);
  const riskHp = clampInt(Number(value.riskHp ?? -1), -4, 0);
  const riskDebt = clampInt(Number(value.riskDebt ?? 0), -20, 20);
  const difficulty = ["low", "medium", "high"].includes(value.difficulty) ? value.difficulty : "medium";
  const job = {
    id: `generated_${roomKey}_${Date.now().toString(36)}_${index}`,
    generated: true,
    room: roomKey,
    title,
    giver,
    faction,
    difficulty,
    summary,
    hooks: hooks.length > 0 ? hooks : defaultHooksForRoom(roomKey),
    salvage,
    plot,
    operation,
    reward: {
      credits: rewardCredits,
      rep: { [faction]: repDelta },
      heat: rewardHeat,
      stress: rewardStress
    },
    risk: {
      heat: riskHeat,
      hp: riskHp,
      debt: riskDebt
    },
    createdAt: new Date().toISOString()
  };
  if (operation === "hunt") {
    job.target = sanitizeTarget(value.target, roomKey);
  }
  return job;
}

function sanitizeTarget(value, roomKey) {
  const room = ROOMS[roomKey];
  const target = value && typeof value === "object" ? value : {};
  return {
    name: trimText(target.name, 32) ?? `${room.name} target`,
    role: trimText(target.role, 32) ?? "district target",
    look: trimText(target.look, 120) ?? `They wear ${room.name} like someone else's alibi.`,
    motive: trimText(target.motive, 120) ?? "Stay ahead of whoever is paying to drag their name into the open.",
    threat: trimText(target.threat, 120) ?? "They know the room, the exits, and how much blood the walls can ignore."
  };
}

function fallbackGeneratedJobs(roomKey, count) {
  const room = ROOMS[roomKey];
  const templates = generatedJobTemplatesForRoom(roomKey);
  return templates.slice(0, count).map((template, index) => ({
    id: `generated_${roomKey}_${Date.now().toString(36)}_${index}`,
    generated: true,
    room: roomKey,
    title: template.title,
    giver: template.giver,
    faction: template.faction,
    difficulty: template.difficulty,
    summary: template.summary,
    hooks: template.hooks,
    salvage: template.salvage,
    plot: template.plot,
    operation: template.operation ?? "job",
    reward: {
      credits: template.rewardCredits,
      rep: { [template.faction]: template.repDelta },
      heat: template.rewardHeat ?? 0,
      stress: template.rewardStress ?? 1
    },
    risk: {
      heat: template.riskHeat ?? 2,
      hp: template.riskHp ?? -1,
      debt: template.riskDebt ?? 0
    },
    target: template.operation === "hunt"
      ? {
          name: template.targetName,
          role: template.targetRole,
          look: template.targetLook,
          motive: template.targetMotive,
          threat: template.targetThreat
        }
      : undefined,
    createdAt: new Date().toISOString()
  }));
}

function generatedJobTemplatesForRoom(roomKey) {
  const faction = dominantRoomFaction(roomKey);
  const defaults = [
    {
      title: `Quiet work in ${ROOMS[roomKey].name}`,
      giver: NPCS[ROOMS[roomKey].npcs[0]]?.name ?? "Local contact",
      faction,
      difficulty: "medium",
      summary: `A local contact needs discreet work handled in ${ROOMS[roomKey].name} before the district starts asking louder questions.`,
      hooks: defaultHooksForRoom(roomKey),
      salvage: defaultSalvageForRoom(roomKey),
      plot: defaultPlotForRoom(roomKey),
      rewardCredits: 64,
      rewardStress: 1,
      rewardHeat: 0,
      repDelta: 2,
      riskHeat: 2,
      riskHp: -1
    }
  ];
  const roomTemplates = {
    docks: [
      {
        title: "Shadow a Flood Barge",
        giver: "Chain Ferryman",
        faction: "dock",
        difficulty: "medium",
        summary: "A flood barge is unloading sealed freight two hours too early. Someone wants the manifest, someone else wants the witnesses gone.",
        hooks: ["ghost", "body", "edge"],
        salvage: ["dock_token"],
        plot: "marrow_debt",
        rewardCredits: 71,
        rewardStress: 1,
        rewardHeat: 0,
        repDelta: 2,
        riskHeat: 2,
        riskHp: -1
      }
    ],
    underpass: [
      {
        title: "Corner the Hatch Runner",
        giver: "Sable Quoin",
        faction: "dock",
        difficulty: "high",
        operation: "hunt",
        summary: "Somebody is sprinting clinic crates through the maintenance dark and Sable wants them stopped before the route becomes policy.",
        hooks: ["ghost", "wire", "body"],
        salvage: ["lockspike_set"],
        plot: "blue_hour",
        rewardCredits: 89,
        rewardStress: 2,
        rewardHeat: 1,
        repDelta: 2,
        riskHeat: 3,
        riskHp: -2,
        targetName: "Glass Vein",
        targetRole: "hatch runner",
        targetLook: "A lean runner in a soaked maintenance shell with pry tools duct-taped to one thigh.",
        targetMotive: "Keep the underpass route open and anonymous long enough to cash out.",
        targetThreat: "Knows every hatch and blackout pocket in the underpass."
      }
    ],
    shrine: [
      {
        title: "Find the Living Handle",
        giver: "Widow Byte",
        faction: "archive",
        difficulty: "medium",
        summary: "A dead handle at the shrine is still breathing somewhere on the network, and the shrine wants proof before the city notices first.",
        hooks: ["wire", "ghost", "edge"],
        salvage: ["saint_token"],
        plot: "ghost_ledger",
        rewardCredits: 73,
        rewardStress: 1,
        rewardHeat: 0,
        repDelta: 2,
        riskHeat: 2,
        riskHp: 0
      }
    ],
    vault: [
      {
        title: "Hunt the Drawer Thief",
        giver: "Echo Clerk",
        faction: "archive",
        difficulty: "high",
        operation: "hunt",
        summary: "An intruder has started lifting index drawers in the buried vault. The Clerk wants the thief found before the theft becomes a precedent.",
        hooks: ["wire", "ghost", "edge"],
        salvage: ["black_index"],
        plot: "ghost_ledger",
        rewardCredits: 96,
        rewardStress: 2,
        rewardHeat: 1,
        repDelta: 3,
        riskHeat: 3,
        riskHp: -2,
        targetName: "Pale Marker",
        targetRole: "vault thief",
        targetLook: "A thin silhouette in archive gloves, moving like they memorized the room from a map stolen off a corpse.",
        targetMotive: "Strip buyer names out of the buried index before anyone else can price them.",
        targetThreat: "Fast hands, dead-steady nerves, and enough vault knowledge to be insulting."
      }
    ]
  };
  return roomTemplates[roomKey] ?? defaults;
}

function defaultHooksForRoom(roomKey) {
  const byRoom = {
    alley: ["ghost", "edge", "wire"],
    club: ["edge", "ghost", "wire"],
    market: ["wire", "edge", "body"],
    switchyard: ["ghost", "wire", "edge"],
    clinic: ["body", "ghost", "edge"],
    archive: ["wire", "ghost", "edge"],
    relay: ["wire", "ghost", "edge"],
    docks: ["body", "ghost", "edge"],
    underpass: ["ghost", "wire", "body"],
    boiler: ["body", "edge", "ghost"],
    shrine: ["wire", "ghost", "edge"],
    vault: ["wire", "ghost", "edge"]
  };
  return byRoom[roomKey] ?? ["edge", "ghost", "wire"];
}

function defaultSalvageForRoom(roomKey) {
  const byRoom = {
    alley: ["hush_patch"],
    club: ["bleach_tabs"],
    market: ["wiretap_kit"],
    switchyard: ["signal_jammer"],
    clinic: ["synthblood_ampule"],
    archive: ["ghost_mesh"],
    relay: ["ghost_mesh"],
    docks: ["dock_token"],
    underpass: ["lockspike_set"],
    boiler: ["ration_gel"],
    shrine: ["saint_token"],
    vault: ["black_index"]
  };
  return byRoom[roomKey] ? [byRoom[roomKey]] : [];
}

function dominantRoomFaction(roomKey) {
  const room = ROOMS[roomKey];
  const factions = room?.npcs
    ?.map((id) => NPCS[id]?.faction)
    .filter((value) => typeof value === "string" && FACTIONS[value]);
  return factions?.[0] ?? "couriers";
}

function defaultPlotForRoom(roomKey) {
  const byRoom = {
    alley: "blue_hour",
    club: "ghost_ledger",
    market: "ghost_ledger",
    switchyard: "blue_hour",
    clinic: "marrow_debt",
    archive: "ghost_ledger",
    relay: "blue_hour",
    docks: "marrow_debt",
    underpass: "blue_hour",
    boiler: "marrow_debt",
    shrine: "ghost_ledger",
    vault: "ghost_ledger"
  };
  return byRoom[roomKey] ?? null;
}

function pruneGeneratedJobs(jobs) {
  const grouped = new Map();
  for (const job of jobs.filter(isGeneratedJob)) {
    const list = grouped.get(job.room) ?? [];
    list.push(job);
    grouped.set(job.room, list);
  }
  return [...grouped.entries()].flatMap(([_, roomJobs]) =>
    roomJobs
      .sort((a, b) => String(b.createdAt ?? "").localeCompare(String(a.createdAt ?? "")))
      .slice(0, 8)
  );
}

function isGeneratedJob(job) {
  return !!job
    && typeof job === "object"
    && job.generated === true
    && typeof job.id === "string"
    && typeof job.room === "string"
    && typeof job.title === "string"
    && typeof job.giver === "string"
    && typeof job.summary === "string"
    && Array.isArray(job.hooks)
    && job.reward
    && typeof job.reward === "object"
    && job.risk
    && typeof job.risk === "object";
}

function trimText(value, max) {
  const text = String(value ?? "").replace(/\s+/g, " ").trim();
  if (!text) return null;
  return text.length <= max ? text : `${text.slice(0, Math.max(0, max - 3)).trimEnd()}...`;
}

function clampInt(value, min, max) {
  const number = Number.isFinite(value) ? Math.round(value) : min;
  return Math.max(min, Math.min(max, number));
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

function resolveRoom(input) {
  const normalized = normalizeTarget(input);
  return Object.keys(ROOMS).find((key) => {
    const room = ROOMS[key];
    return key === normalized
      || normalizeTarget(room.name) === normalized
      || normalizeTarget(room.route.split("/").at(-1)) === normalized;
  }) ?? null;
}

export function normalizeTarget(value) {
  return String(value ?? "").toLowerCase().replace(/[^a-z0-9]+/g, "");
}

function resolveNpcInRoom(room, targetRaw) {
  const target = normalizeTarget(targetRaw);
  if (!target) return null;
  return room.npcs.find((id) => {
    const npc = NPCS[id];
    if (!npc) return false;
    const tokens = String(npc.name ?? "")
      .split(/\s+/)
      .map(normalizeTarget)
      .filter(Boolean);
    const aliases = new Set([
      normalizeTarget(id),
      normalizeTarget(npc.name),
      ...tokens
    ]);
    return aliases.has(target);
  }) ?? null;
}

function trimQuote(text, max) {
  const cleaned = text.replace(/\s+/g, " ").trim();
  if (cleaned.length <= max) return cleaned;
  return cleaned.slice(0, Math.max(0, max - 3)).trimEnd() + "...";
}

function signed(value) {
  return `${value >= 0 ? "+" : ""}${value}`;
}

// Procedural delve registry (per-session, ephemeral). When the player
// `discover`s a section, the rolled delve lives here until they enter or
// abandon it. Procedural delves are not in DELVES; the registry is the
// only place they exist for the lifetime of the session.

export function registerProceduralDelve(session, delve) {
  session.data.proceduralDelves = session.data.proceduralDelves ?? {};
  session.data.proceduralDelves[delve.id] = delve;
}

function listProceduralDelvesInRoom(session, roomKey) {
  const reg = session.data.proceduralDelves ?? {};
  return Object.values(reg).filter((d) => d.room === roomKey);
}

function lookupDelve(session, id) {
  return delveById(id) ?? session.data.proceduralDelves?.[id] ?? null;
}

async function scoutForDelve(session) {
  const room = session.data.profile.room;
  const eligible = eligibleTemplatesForRoom(room);
  if (eligible.length === 0) {
    await render(session, "Nothing in this section responds to a scout.");
    return;
  }
  // 50% chance of finding something. The setting calls this "the City breathing".
  if (Math.random() < 0.5) {
    await render(session, "You scout the seams. Nothing breathes that shouldn't, this hour.");
    return;
  }
  const tmpl = eligible[Math.floor(Math.random() * eligible.length)];
  const delve = rollProceduralDelve(tmpl.id, { room });
  if (!delve) {
    await render(session, "You scout the seams. Something flickers, then doesn't.");
    return;
  }
  registerProceduralDelve(session, delve);
  if (delve.rumour) {
    await appendFeed(session, { tone: "bright-yellow", text: delve.rumour });
  }
  await tryAchievement(session, "patient_cartographer");
  await render(session, `// ${delve.summary}\nenter: delve ${delve.id}`);
}
