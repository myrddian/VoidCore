import { ACHIEVEMENTS, CYBERWARE, FACTIONS, ITEMS, NPCS } from "./world.mjs";
import { leadAgeBand, totalAchievementPoints } from "./game.mjs";

const WIDTH = 76;

const SCAN_TTL_MS = 5 * 60 * 1000;

export function renderCityline(session, room, feed, traffic, plotSummary = null, note = null) {
  const rows = [];
  const profile = session.data.profile;
  const accent = room.accent;
  const support = room.support;
  const dim = room.dim;

  pushLine(rows, [
    seg(room.route, accent, null, true),
    seg("  ", "default"),
    seg(room.sigil, support, null, true)
  ]);
  pushLine(rows, statusSpans(session, profile, accent, dim));
  pushLine(rows, [seg(`== ${room.name.toUpperCase()} ==`, accent, null, true)]);
  pushWrapped(rows, room.description, "default");
  pushWrapped(rows, room.detail, dim);

  const occupants = [...session.client.sessions.values()]
    .filter((peer) => peer.data.profile?.room === room.key)
    .map((peer) => peer.handle);
  const npcNames = room.npcs.map((id) => NPCS[id]?.name).filter(Boolean);
  pushWrapped(rows, `nearby: ${npcNames.join(", ") || "nobody trustworthy"} | occupants: ${occupants.join(", ")}`, support);
  pushWrapped(rows, `routes: ${room.exits.map((exit) => exitLabel(exit)).join(" | ")}`, accent);
  if ((room.fixtures ?? []).length > 0) {
    pushWrapped(rows, `fixtures: ${room.fixtures.map((fixture) => fixture.name).join(" | ")}`, "grey");
  }
  if (profile.activeJobId) {
    pushWrapped(rows, `active op: ${profile.activeJobId}`, "bright-yellow");
  }
  if (profile.prep?.note) {
    pushWrapped(rows, `prep: ${profile.prep.note}`, "bright-blue");
  }
  if (plotSummary) {
    pushWrapped(rows, `pressure: ${plotSummary}`, "bright-magenta");
  }

  pushLine(rows, []);
  const trafficLines = traffic.length > 0 ? traffic.slice(-3) : null;
  if (trafficLines) {
    for (const line of trafficLines) pushWrapped(rows, `> ${line}`, dim);
  }

  const feedLines = feed.length > 0 ? feed.slice(-2) : null;
  if (feedLines) {
    for (const item of feedLines) {
      if (typeof item === "string") pushWrapped(rows, item, support);
      else pushWrapped(rows, item.text, item.tone ?? support);
    }
  }

  const scan = session.data?.lastScan;
  if (scan && scan.room === room.key) {
    const nowMs = (session.clock?.unixTimeSec ?? Date.now() / 1000) * 1000;
    const ageMs = nowMs - Date.parse(scan.ts ?? new Date().toISOString());
    if (ageMs < SCAN_TTL_MS) {
      const band = ageMs < 30_000 ? "fresh" : ageMs < 90_000 ? "active" : "fading";
      const fg = band === "fresh" ? "bright-yellow" : band === "active" ? "yellow" : "dark-grey";
      pushLine(rows, []);
      pushWrapped(rows, `scan:// ${scan.text}`, fg);
    }
  }

  if (note) {
    pushLine(rows, []);
    pushWrapped(rows, `// ${note}`, "white");
  }

  pushWrapped(rows, "help · jobs · delves · missions · lore · discover · talk · go · leads · status · jackout", "grey");
  pushWrapped(rows, "[Esc] backs out of any aux screen, dialogue, option menu, or delve.", "grey");

  const maxRows = Math.max(12, session.viewport?.rows ?? 20);
  return fitRowsKeepingFooter(rows, maxRows, 1);
}

export function renderHelp(session) {
  const rows = [];
  pushLine(rows, [seg(" CITYLINE OPS REFERENCE ", "bright-cyan", "blue", true)]);
  pushLine(rows, []);
  for (const line of [
    "look                refresh the current scene",
    "rooms               list shard locations",
    "scan                live shard analysis via the oracle",
    "inspect <thing>     examine a room detail, npc, or route",
    "search              scavenge the current room once per night",
    "go <room>           move to an adjacent location",
    "talk <name>         lean on a local contact",
    "inv                 inspect carried items",
    "chrome              inspect installed and available augments",
    "wares               inspect local vendor stock",
    "buy <item>          buy from the local vendor",
    "use <item>          consume or arm carried gear",
    "install <augment>   install catalogue chrome OR graft a salvaged piece (clinic only)",
    "equip <item>        equip a weapon or armor; cyberware needs the clinic",
    "unequip <slot>      pull a slot back to your pockets (weapon, head, torso, ...)",
    "patch               at the clinic, pay Dr. Ilex to restore HP (4c each)",
    "jobs                show available work in the current room",
    "take <job>          accept a local job",
    "run                 resolve your active job",
    "emote <action>      perform an in-room action",
    "page <handle> <msg> send a private page to another player",
    "status              show your profile and pressure",
    "threads             inspect the live shard arcs",
    "rumors              read the shard wire",
    "leads               read your collected plot leads (faded ones included)",
    "missions            read open missions handed to you by NPCs",
    "lore                browse your collection of recovered lore pieces",
    "lore <id>           read a specific lore piece in full",
    "achievements        view your unlocked achievements and score",
    "delves              list multi-stage delves anchored to this room",
    "delve <id>          push into a delve (esc aborts; combat may follow)",
    "discover            scout this section for a freshly-opened delve",
    "who                 list connected handles",
    "say <msg>           speak to the room",
    "jackout             leave the shard",
    "[Esc]               back / cancel from any sub-screen, dialogue, or delve"
  ]) pushWrapped(rows, line, "default");
  pushLine(rows, []);
  pushWrapped(rows, "Cityline is built like a board ritual: short turns, long memory, consequences that outlive the session.", "grey");
  return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
}

export function renderStatus(session, bulletin = null) {
  const profile = session.data.profile;
  const stats = displayStats(profile);
  const rows = [];
  pushLine(rows, [seg(" PROFILE://CURRENT ", "bright-yellow", "blue", true)]);
  pushLine(rows, []);
  for (const line of [
    `handle : ${session.handle}`,
    `route  : ${exitLabel(profile.room)}`,
    `credits: ${profile.credits}c`,
    `debt   : ${profile.debt}c`,
    `heat   : ${profile.heat}`,
    `stress : ${profile.stress}`,
    `hp     : ${profile.hp}/${profile.maxHp}`,
    `stats  : EDGE ${stats.edge}  GHOST ${stats.ghost}  WIRE ${stats.wire}  BODY ${stats.body}`,
    `chrome : ${profile.chrome.length}/${profile.chromeSlots}`,
    `active : ${profile.activeJobId ?? "none"}`
  ]) pushWrapped(rows, line, "default");
  if (profile.chrome.length > 0) {
    pushWrapped(rows, `rig    : ${profile.chrome.map((item) => item.replace(/_/g, " ")).join(" | ")}`, "bright-blue");
  }
  if (profile.prep?.note) {
    pushWrapped(rows, `prep   : ${profile.prep.note} | heat buffer ${profile.prep.heatBuffer} | boosts ${formatPrepBoosts(profile.prep.statBoosts)}`, "bright-blue");
  }
  pushLine(rows, []);
  pushLine(rows, [seg("faction drift", "bright-cyan", null, true)]);
  for (const [key, value] of Object.entries(profile.rep)) {
    const faction = FACTIONS[key];
    pushWrapped(rows, `${faction.name}: ${value >= 0 ? "+" : ""}${value}`, faction.accent);
  }
  if (bulletin) {
    pushLine(rows, []);
    pushWrapped(rows, bulletin, "grey");
  }
  return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
}

export function renderChrome(session, installed, available, inClinic, note = null) {
  const rows = [];
  const room = session.data.roomMeta;
  const profile = session.data.profile;
  const grafted = Array.isArray(profile.grafted) ? profile.grafted : [];
  pushLine(rows, [seg(" CHROME://BODY-MAP ", "bright-blue", "blue", true)]);
  pushLine(rows, []);
  const slotsUsed = profile.chrome.length + grafted.length;
  pushWrapped(rows, `slots: ${slotsUsed}/${profile.chromeSlots}`, "bright-blue");
  if (installed.length === 0 && grafted.length === 0) {
    pushWrapped(rows, "installed: none. you're still mostly meat and bad intentions.", "grey");
  } else {
    installed.forEach((chrome, index) => {
      pushLine(rows, [seg(`[${index + 1}] ${chrome.name}`, "bright-blue", null, true)]);
      pushWrapped(rows, chrome.summary, "default");
      pushWrapped(rows, chromeEffectSummary(chrome), "grey");
      pushLine(rows, []);
    });
    grafted.forEach((instance, index) => {
      const tag = `[g${index + 1}] ${instance.name ?? instance.baseId} (R${instance.rank ?? 1})`;
      pushLine(rows, [seg(tag, "bright-magenta", null, true)]);
      const computed = instance.computed ?? {};
      const stats = Object.entries(computed)
        .filter(([, v]) => Number.isFinite(v) && v !== 0)
        .map(([k, v]) => `${k} ${v >= 0 ? "+" : ""}${v}`)
        .join(" · ");
      if (stats) pushWrapped(rows, `► ${stats}`, "grey");
      const mods = (instance.mods ?? []).join(", ");
      if (mods) pushWrapped(rows, `► mods: ${mods}`, "dark-grey");
      pushLine(rows, []);
    });
  }
  pushLine(rows, []);
  pushLine(rows, [seg(inClinic ? "clinic tray" : "known clinic catalog", room.accent, null, true)]);
  if (available.length === 0) {
    pushWrapped(rows, "No uninstalled packages remain on your radar.", "grey");
  } else {
    available.forEach((chrome, index) => {
      pushLine(rows, [seg(`[${index + 1}] ${chrome.name}`, room.support, null, true)]);
      pushWrapped(rows, `${chrome.price}c + ${chrome.debtCost} debt | ${chrome.summary}`, "default");
      pushWrapped(rows, chromeEffectSummary(chrome), "grey");
      pushWrapped(rows, inClinic ? `install: install ${chrome.id}` : "install: travel to the clinic", "bright-yellow");
      pushLine(rows, []);
    });
  }
  // Inventory cyberware that hasn't been grafted yet — surface it here so
  // the player can see what they can `install <name>` next.
  const ungrafted = (profile.inventory ?? []).filter((entry) =>
      entry && typeof entry === "object" && entry.baseId && entry.computed);
  if (ungrafted.length > 0) {
    pushLine(rows, []);
    pushLine(rows, [seg("salvaged chrome (in inventory)", "bright-yellow", null, true)]);
    ungrafted.forEach((instance) => {
      const tag = `${instance.name ?? instance.baseId} (R${instance.rank ?? 1})`;
      pushLine(rows, [seg(`* ${tag}`, "bright-yellow", null, true)]);
      const stats = Object.entries(instance.computed ?? {})
        .filter(([, v]) => Number.isFinite(v) && v !== 0)
        .map(([k, v]) => `${k} ${v >= 0 ? "+" : ""}${v}`)
        .join(" · ");
      if (stats) pushWrapped(rows, `► ${stats}`, "grey");
      pushWrapped(rows, inClinic
        ? `graft: install ${instance.baseId}`
        : "graft: travel to the clinic", "bright-yellow");
    });
  }
  if (note) pushWrapped(rows, note, "bright-cyan");
  return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
}

export function renderDelveList(session, delves) {
  const room = session.data.roomMeta;
  const rows = [];
  pushLine(rows, [seg(` DELVES://${room.key.toUpperCase()} `, "bright-yellow", "blue", true)]);
  pushLine(rows, []);
  if (!Array.isArray(delves) || delves.length === 0) {
    pushWrapped(rows, "// no delves anchored here. try `discover` if a section feels open.", "grey");
    return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
  }
  for (const d of delves) {
    const tag = d.isProcedural ? " (discovered)" : "";
    pushLine(rows, [seg(`► ${d.title}${tag}`, d.isProcedural ? "bright-magenta" : "bright-yellow", null, true)]);
    if (d.operator) pushWrapped(rows, `operator: ${d.operator}${d.difficulty ? " · difficulty: " + d.difficulty : ""}`, "grey");
    if (d.summary) pushWrapped(rows, d.summary, "default");
    pushWrapped(rows, `enter: delve ${d.id}`, "bright-cyan");
    pushLine(rows, []);
  }
  return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
}

export function renderCombatStage(session, delve, stage, combat, history) {
  const rows = [];
  pushLine(rows, [
    seg(`DELVE://${delve.id.toUpperCase()}`, "bright-yellow", null, true),
    seg("  ", "default"),
    seg("combat", "bright-red", null, true)
  ]);
  pushLine(rows, []);

  const profile = session.data.profile;
  if (!combat) {
    pushWrapped(rows, "// combat ended.", "grey");
    return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
  }

  // Status bar: enemy + player
  pushLine(rows, [seg(`enemy: ${combat.enemy.name}`, "bright-red", null, true)]);
  pushWrapped(rows, combat.enemy.flavor ?? "", "grey");
  pushWrapped(rows, `hp ${combat.state.enemyHp}/${combat.state.enemyMaxHp}  ·  defense ${combat.enemy.defense ?? 0}  ·  tier ${combat.enemy.tier ?? 1}`, "yellow");
  pushLine(rows, []);

  pushLine(rows, [seg("you", "bright-cyan", null, true)]);
  const stats = profile.stats ?? {};
  pushWrapped(rows, `hp ${profile.hp}/${profile.maxHp}  ·  ghost ${stats.ghost ?? 0}  ·  edge ${stats.edge ?? 0}  ·  body ${stats.body ?? 0}`, "cyan");
  pushLine(rows, []);

  // Last 3 combat log entries
  const recent = (combat.state.log ?? []).slice(-3);
  if (recent.length > 0) {
    pushLine(rows, [seg("exchange", "bright-yellow", null, true)]);
    for (const entry of recent) {
      if (entry.side === "player") {
        if (entry.action === "defend") {
          pushWrapped(rows, `r${entry.round}: you brace.`, "cyan");
        } else if (entry.action === "use_item") {
          pushWrapped(rows, `r${entry.round}: you use ${entry.item}.`, "cyan");
        } else if (entry.action === "flee") {
          pushWrapped(rows, `r${entry.round}: you ${entry.success ? "slip the line" : "fail to break clear"}.`, "cyan");
        } else {
          const verb = entry.hit ? (entry.damage > 0 ? `hit (${entry.damage}dmg)` : "graze") : "miss";
          pushWrapped(rows, `r${entry.round}: ${entry.weapon} → ${verb} (${entry.roll}+...vs ${entry.threshold})`, entry.hit ? "cyan" : "grey");
        }
      } else {
        const verb = entry.hit ? `hits for ${entry.damage}` : "swings, you slip";
        pushWrapped(rows, `r${entry.round}: ${combat.enemy.name} ${entry.action} ${verb} (${entry.roll}+...vs ${entry.threshold})`, entry.hit ? "bright-red" : "grey");
      }
    }
    pushLine(rows, []);
  }

  // Action menu
  pushLine(rows, [seg("actions", "bright-yellow", null, true)]);
  pushWrapped(rows, "[1] attack — edge (precise / ranged)", "bright-yellow");
  pushWrapped(rows, "[2] attack — body (close / brutal)", "bright-yellow");
  pushWrapped(rows, "[3] defend — brace (-1 incoming next round)", "bright-yellow");
  pushWrapped(rows, "[4] use   — first healing item in inventory", "bright-yellow");
  pushWrapped(rows, "[5] flee  — ghost vs tier", "bright-yellow");
  pushLine(rows, []);
  pushWrapped(rows, "// type a number, letter (a/b/d/u/f), or word.", "grey");

  return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
}

export function renderDelveStage(session, delve, stage, stageId, history, lastApplied) {
  const rows = [];
  const room = session.data.roomMeta;
  pushLine(rows, [
    seg(`DELVE://${delve.id.toUpperCase()}`, "bright-yellow", null, true),
    seg("  ", "default"),
    seg(`stage: ${stageId}`, "grey")
  ]);
  pushLine(rows, []);

  // Show the last few history entries for context (player's path through the delve so far)
  const recent = (history ?? []).slice(-4);
  for (const h of recent) {
    if (h.kind === "choice" && h.label) {
      pushLine(rows, [seg("SYSOP", "bright-cyan", null, true)]);
      pushWrapped(rows, `► ${h.label}`, "default");
      pushLine(rows, []);
    } else if (h.kind === "noise") {
      for (const line of h.lines ?? []) pushWrapped(rows, line, "dark-grey");
    }
  }

  if (!stage) {
    pushWrapped(rows, "// the delve has no further stage. drop out.", "grey");
    return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
  }

  // Current narration
  for (const line of stage.narrate ?? []) {
    pushWrapped(rows, line, "default");
  }
  pushLine(rows, []);

  // Show last applied skill check / heat / hp / etc.
  for (const sc of lastApplied?.skillChecks ?? []) {
    pushWrapped(rows, `[ ${sc.tag || sc.stat}: ${sc.success ? "success" : "miss"} (${sc.statValue}+${sc.roll} vs ${sc.threshold}) ]`, sc.success ? "bright-cyan" : "bright-red");
  }
  for (const note of lastApplied?.rngLog ?? []) {
    pushWrapped(rows, `► ${note}`, "yellow");
  }
  if ((lastApplied?.skillChecks?.length ?? 0) > 0 || (lastApplied?.rngLog?.length ?? 0) > 0) {
    pushLine(rows, []);
  }

  if (stage.terminal) {
    pushWrapped(rows, "// delve complete. press any key to return.", "grey");
    return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
  }

  // Choices
  const choices = stage.choices ?? [];
  if (choices.length > 0) {
    pushLine(rows, [seg("choices", "bright-yellow", null, true)]);
    choices.forEach((c, idx) => {
      pushWrapped(rows, `[${idx + 1}] ${c.label}`, "bright-yellow");
      if (c.skillCheck) {
        pushWrapped(rows, `   ${c.skillCheck.stat} vs dc ${c.skillCheck.dc}`, "grey");
      }
    });
    pushLine(rows, []);
    pushWrapped(rows, "// pick a number, slug, or substring. esc aborts.", "grey");
  }

  return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
}

export function renderAchievements(session) {
  const profile = session.data.profile;
  const unlocked = new Set(profile?.achievements ?? []);
  const points = totalAchievementPoints(profile);
  const allEntries = Object.values(ACHIEVEMENTS);
  const totalPossible = allEntries.reduce((acc, a) => acc + (a.points ?? 0), 0);

  const rows = [];
  pushLine(rows, [seg(" ACHIEVEMENTS://CITYLINE ", "bright-yellow", "blue", true)]);
  pushLine(rows, []);
  pushWrapped(rows, `score: ${points} / ${totalPossible}  ·  unlocked: ${unlocked.size} / ${allEntries.length}`, "bright-yellow");
  pushLine(rows, []);

  // Group by category, unlocked first per group.
  const byCategory = new Map();
  for (const a of allEntries) {
    const cat = a.category ?? "misc";
    if (!byCategory.has(cat)) byCategory.set(cat, []);
    byCategory.get(cat).push(a);
  }
  const categoryOrder = ["combat", "delve", "exploration", "social", "lore", "faction", "story", "shadow", "misc"];

  for (const cat of categoryOrder) {
    const list = byCategory.get(cat);
    if (!list || list.length === 0) continue;
    pushLine(rows, [seg(cat.toUpperCase(), "bright-cyan", null, true)]);
    // Unlocked first, then locked
    list.sort((a, b) => Number(unlocked.has(b.id)) - Number(unlocked.has(a.id)));
    for (const a of list) {
      const isUnlocked = unlocked.has(a.id);
      const isHidden = a.hidden && !isUnlocked;
      if (isHidden) {
        pushWrapped(rows, `[ locked · hidden ]  +${a.points} pts`, "dark-grey");
      } else if (isUnlocked) {
        pushWrapped(rows, `[ ✦ ${a.title} ]  +${a.points} pts`, "bright-yellow");
        if (a.flavor) pushWrapped(rows, `  ${a.flavor}`, "default");
      } else {
        pushWrapped(rows, `[ ○ ${a.title} ]  +${a.points} pts`, "grey");
        if (a.flavor) pushWrapped(rows, `  ${a.flavor}`, "dark-grey");
      }
    }
    pushLine(rows, []);
  }

  pushWrapped(rows, "// hidden achievements only show their flavour after unlock. score is summed locally; the BBS keeps its own per-user record.", "dark-grey");
  return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
}

export function renderLoreList(session) {
  const lore = Array.isArray(session.data.profile?.lore) ? session.data.profile.lore : [];
  const rows = [];
  pushLine(rows, [seg(" LORE://COLLECTION ", "bright-cyan", "blue", true)]);
  pushLine(rows, []);
  if (lore.length === 0) {
    pushWrapped(rows, "// no lore collected yet. search rooms; press the right NPCs; some delves drop fragments. read pinned notices and overheard fragments — the City keeps logging itself.", "grey");
    return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
  }
  // Newest first
  const reversed = [...lore].reverse();
  for (const piece of reversed) {
    const tag = piece.kind === "narrative" ? "[narrative]" : "[procedural]";
    const tone = piece.kind === "narrative" ? "bright-cyan" : "yellow";
    pushLine(rows, [seg(`${tag} ${piece.title}`, tone, null, true)]);
    pushWrapped(rows, `form: ${piece.form ?? "fragment"}${piece.attribution ? "  ·  " + piece.attribution : ""}`, "grey");
    if (Array.isArray(piece.tags) && piece.tags.length > 0) {
      pushWrapped(rows, `tags: ${piece.tags.join(" · ")}`, "dark-grey");
    }
    pushWrapped(rows, `read: lore ${piece.id}`, "bright-cyan");
    pushLine(rows, []);
  }
  pushWrapped(rows, "// narrative pieces are authored artifacts. procedural pieces reflect the City's current breath — bulletins, overheards, marginalia.", "dark-grey");
  return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
}

export function renderLorePiece(session, piece) {
  const rows = [];
  const tone = piece.kind === "narrative" ? "bright-cyan" : "yellow";
  pushLine(rows, [seg(` LORE://${(piece.title || "").toUpperCase()} `, tone, "blue", true)]);
  if (piece.attribution) pushWrapped(rows, `// ${piece.attribution}`, "grey");
  if (Array.isArray(piece.tags) && piece.tags.length > 0) {
    pushWrapped(rows, `tags: ${piece.tags.join(" · ")}`, "dark-grey");
  }
  if (piece.discoveredVia || piece.discoveredRoom) {
    const where = piece.discoveredRoom ? ` in ${piece.discoveredRoom}` : "";
    const how = piece.discoveredVia ? ` via ${piece.discoveredVia}` : "";
    pushWrapped(rows, `// found${how}${where}`, "dark-grey");
  }
  pushLine(rows, []);
  // The body may already contain newlines we want to preserve.
  for (const line of String(piece.body ?? "").split("\n")) {
    if (line.trim()) {
      pushWrapped(rows, line, "default");
    } else {
      pushLine(rows, []);
    }
  }
  pushLine(rows, []);
  pushWrapped(rows, "// `lore` lists everything in your collection.", "grey");
  return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
}

export function renderMissions(session) {
  const missions = Array.isArray(session.data.profile?.missions) ? session.data.profile.missions : [];
  const rows = [];
  pushLine(rows, [seg(" MISSIONS://OPEN ", "bright-yellow", "blue", true)]);
  pushLine(rows, []);
  if (missions.length === 0) {
    pushWrapped(rows, "// no missions on the board. talk to people; they hand out work when they trust you a little.", "grey");
    return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
  }
  // Newest first
  for (const m of [...missions].reverse()) {
    const tag = m.kind === "combat" ? "[combat]" : "[note]";
    const tone = m.kind === "combat" ? "bright-red" : "bright-cyan";
    pushLine(rows, [seg(`${tag} ${m.title}`, tone, null, true)]);
    if (m.givenBy) pushWrapped(rows, `from: ${m.givenBy}${m.target ? "  ·  target: " + m.target : ""}  ·  ${m.difficulty}`, "grey");
    pushWrapped(rows, m.premise, "default");
    if (m.factionTension) pushWrapped(rows, `tension: ${FACTIONS[m.factionTension]?.name ?? m.factionTension}`, "yellow");
    if (Array.isArray(m.exposition) && m.exposition.length > 0) {
      for (const line of m.exposition) pushWrapped(rows, `► ${line}`, "grey");
    }
    if (m.delveId) pushWrapped(rows, `enter: delve ${m.delveId}`, "bright-cyan");
    pushLine(rows, []);
  }
  pushWrapped(rows, "// note missions are leads with structure. combat missions spawn a delve you can `delve <id>` into.", "dark-grey");
  return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
}

export function renderLeads(session) {
  const leads = Array.isArray(session.data.profile?.leads) ? session.data.profile.leads : [];
  const rows = [];
  pushLine(rows, [seg(" LEADS://JOURNAL ", "bright-cyan", "blue", true)]);
  pushLine(rows, []);
  if (leads.length === 0) {
    pushWrapped(rows, "// no leads collected yet. talk to people; press them when they get specific.", "grey");
    return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
  }
  const nowMs = (session.clock?.unixTimeSec ?? Date.now() / 1000) * 1000;
  const palette = {
    fresh: "bright-cyan",
    active: "cyan",
    dim: "grey",
    faded: "dark-grey"
  };
  // Newest first
  const reversed = [...leads].reverse();
  for (const lead of reversed) {
    const band = leadAgeBand(lead, nowMs);
    const fg = palette[band] ?? "grey";
    const stamp = lead.npc ? `[${lead.npc}]` : "[unknown]";
    pushWrapped(rows, `${stamp}  ${lead.text}`, fg);
    pushWrapped(rows, `   ${formatLeadMeta(lead, band, nowMs)}`, "dark-grey");
    pushLine(rows, []);
  }
  pushWrapped(rows, "// fresh = last minute · active = last 5 min · dim = last 30 min · faded = older", "dark-grey");
  return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
}

function formatLeadMeta(lead, band, nowMs) {
  const ageSec = Math.max(0, Math.floor((nowMs - Date.parse(lead.ts ?? new Date().toISOString())) / 1000));
  const ageStr = ageSec < 60 ? `${ageSec}s` : ageSec < 3600 ? `${Math.floor(ageSec / 60)}m` : `${Math.floor(ageSec / 3600)}h`;
  const room = lead.room ? ` · ${lead.room}` : "";
  return `${band} · ${ageStr} ago${room}`;
}

export function renderRumors(session, feed) {
  const rows = [];
  pushLine(rows, [seg(" SHARD WIRE ", "bright-magenta", "blue", true)]);
  pushLine(rows, []);
  for (const item of feed.slice(-6)) {
    if (typeof item === "string") pushWrapped(rows, item, "bright-magenta");
    else pushWrapped(rows, item.text, item.tone ?? "bright-magenta");
    pushLine(rows, []);
  }
  return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
}

export function renderThreads(session, ranked) {
  const rows = [];
  pushLine(rows, [seg(" THREADS://LIVE-ARCS ", "bright-magenta", "blue", true)]);
  pushLine(rows, []);
  ranked.forEach(({ thread, state }, index) => {
    pushLine(rows, [seg(`[${index + 1}] ${thread.title}`, "bright-magenta", null, true)]);
    pushWrapped(rows, thread.summary, "default");
    pushWrapped(rows, `stage ${state.progress + 1}/${thread.stages.length} | heat ${state.heat} | ${thread.stages[state.progress]}`, "bright-yellow");
    pushWrapped(rows, `stakes: ${thread.stakes}`, "grey");
    pushWrapped(rows, `latest: ${state.lastRumor}`, "cyan");
    const live = (thread.worldEffects ?? []).filter((e) => state.progress >= (e.appliesAtStage ?? 0));
    for (const e of live) {
      pushWrapped(rows, `► ${e.label ?? e.op}`, "bright-red");
    }
    pushLine(rows, []);
  });
  return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
}

export function renderTalk(session, npc, responseLines, lead = null) {
  const room = session.data.roomMeta;
  const rows = [];
  pushLine(rows, [seg(` ${npc.name.toUpperCase()} // ${npc.role.toUpperCase()} `, room.accent, "blue", true)]);
  pushLine(rows, []);
  pushWrapped(rows, npc.look, room.support);
  pushLine(rows, []);
  for (const line of responseLines) {
    pushWrapped(rows, line, "default");
    pushLine(rows, []);
  }
  if (lead) {
    pushLine(rows, [seg("lead", room.accent, null, true)]);
    pushWrapped(rows, lead, "bright-cyan");
  }
  return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
}

export function renderDialogue(session, npc, transcript, { lead = null, note = null, thinking = null } = {}) {
  const room = session.data.roomMeta;
  const head = [];
  const tail = [];
  const transcriptRows = [];
  const maxRows = Math.max(12, session.viewport?.rows ?? 20);

  pushLine(head, [
    seg(`LINK://${npc.name.toUpperCase()}`, room.accent, null, true),
    seg("  ", "default"),
    seg(npc.role, "grey")
  ]);
  pushWrapped(head, npc.look, room.support);
  pushLine(head, []);

  if (!Array.isArray(transcript) || transcript.length === 0) {
    const priorLog = session.data.profile?.npcDialogue?.[npc.name];
    if (Array.isArray(priorLog) && priorLog.length > 0) {
      pushWrapped(transcriptRows, `// ${npc.name} carries ${priorLog.length} prior exchange${priorLog.length === 1 ? "" : "s"} with you into this scene.`, "grey");
    } else {
      pushWrapped(transcriptRows, "// the line is open.", "grey");
    }
  } else {
    for (const entry of transcript) {
      if (entry.label) {
        pushLine(transcriptRows, [seg(entry.label, entry.labelFg ?? room.support, null, true)]);
      }
      pushWrapped(transcriptRows, entry.text ?? "", entry.textFg ?? "default");
      pushLine(transcriptRows, []);
    }
  }

  if (lead) {
    pushWrapped(tail, `► lead: ${lead}`, "bright-cyan");
  }

  const activeChoices = session.data.cityline_lastChoices?.[npc.name];
  if (Array.isArray(activeChoices) && activeChoices.length > 0) {
    activeChoices.forEach((choice, idx) => {
      pushWrapped(tail, `► [${idx + 1}] ${choice.label}`, "bright-yellow");
    });
  }

  if (thinking) {
    pushWrapped(tail, `~ ${thinking}`, "bright-yellow");
  } else if (note) {
    pushWrapped(tail, `// ${note}`, "grey");
  }

  const reserved = head.length + tail.length;
  const transcriptBudget = Math.max(1, maxRows - reserved);
  let visibleTranscript = transcriptRows;

  if (transcriptRows.length > transcriptBudget) {
    const sliceBudget = Math.max(1, transcriptBudget - 1);
    visibleTranscript = transcriptRows.slice(-sliceBudget);
    visibleTranscript.unshift({
      row: 0,
      spans: [seg("^ older exchange scrolls above ^", "dark-grey")]
    });
  }

  const rows = [...head, ...visibleTranscript, ...tail];
  return rows.map((row, index) => ({ ...row, row: index }));
}

export function renderOptionMenu(session, {
  title,
  subtitle = null,
  options = [],
  note = null,
  footer = null
}) {
  const room = session.data.roomMeta;
  const rows = [];
  pushLine(rows, [seg(` ${title} `, room.accent, "blue", true)]);
  if (subtitle) {
    pushWrapped(rows, subtitle, "grey");
  }
  pushLine(rows, []);
  if (options.length === 0) {
    pushWrapped(rows, "No live entries.", "grey");
  } else {
    for (const option of options) {
      pushLine(rows, [seg(`[${option.key}] ${option.label}`, room.support, null, true)]);
      if (option.summary) pushWrapped(rows, option.summary, "default");
      if (option.meta) pushWrapped(rows, option.meta, "grey");
      pushLine(rows, []);
    }
  }
  if (note) {
    pushWrapped(rows, note, "bright-cyan");
  }
  if (footer) {
    pushWrapped(rows, footer, "grey");
  }
  return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
}

export function renderJobs(session, jobs) {
  const rows = [];
  const room = session.data.roomMeta;
  pushLine(rows, [seg(` OPS://${room.key.toUpperCase()} `, room.accent, "blue", true)]);
  pushLine(rows, []);
  if (jobs.length === 0) {
    pushWrapped(rows, "No clean work is being offered here right now.", "grey");
  } else {
    jobs.forEach((job, index) => {
      pushLine(rows, [seg(`[${index + 1}] ${job.title}`, room.support, null, true)]);
      pushWrapped(rows, `giver: ${job.giver} | faction: ${FACTIONS[job.faction].name} | hooks: ${job.hooks.join("/")}`, "grey");
      pushWrapped(rows, job.summary, "default");
      pushWrapped(rows, `reward: ${job.reward.credits}c | typical heat: ${job.risk.heat} | use: take ${job.id}`, "bright-yellow");
      pushLine(rows, []);
    });
  }
  return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
}

export function renderInventory(session, items, note = null) {
  const rows = [];
  pushLine(rows, [seg(" INVENTORY://CARRIED ", "bright-green", "blue", true)]);
  pushLine(rows, []);
  if (items.length === 0) {
    pushWrapped(rows, "You are carrying nothing worth bragging about.", "grey");
  } else {
    items.forEach((item, index) => {
      if (item.isInstance) {
        pushLine(rows, [seg(`[${index + 1}] ${item.name}  ·  R${item.rank} ${item.base}`, "bright-yellow", null, true)]);
        if (item.summary) pushWrapped(rows, item.summary, "default");
        if (item.stats) pushWrapped(rows, `► ${item.stats}`, "yellow");
        if (Array.isArray(item.mods) && item.mods.length > 0) {
          pushWrapped(rows, `► mods: ${item.mods.join(" · ")}`, "grey");
        }
      } else {
        pushLine(rows, [seg(`[${index + 1}] ${item.name}`, "bright-green", null, true)]);
        pushWrapped(rows, `${item.kind} | ${item.summary}`, "default");
        pushWrapped(rows, item.use ? `use: use ${item.id}` : "use: passive / not directly usable", "grey");
      }
      pushLine(rows, []);
    });
  }
  if (note) pushWrapped(rows, note, "bright-cyan");
  return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
}

export function renderVendor(session, vendor, note = null) {
  const rows = [];
  const room = session.data.roomMeta;
  pushLine(rows, [seg(` MARKET://${vendor.name.toUpperCase()} `, room.accent, "blue", true)]);
  pushLine(rows, []);
  vendor.stock.forEach((entry, index) => {
    pushLine(rows, [seg(`[${index + 1}] ${entry.item.name}`, room.support, null, true)]);
    pushWrapped(rows, `${entry.price}c | ${entry.item.kind} | ${entry.item.summary}`, "default");
    pushWrapped(rows, `buy: buy ${entry.item.id}`, "grey");
    pushLine(rows, []);
  });
  if (vendor.stock.length === 0) {
    pushWrapped(rows, "The vendor has nothing clean on offer right now.", "grey");
  }
  if (note) pushWrapped(rows, note, "bright-cyan");
  return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
}

export function renderJobResolution(session, job, outcome) {
  const rows = [];
  const room = session.data.roomMeta;
  pushLine(rows, [seg(` RESOLUTION://${job.id.toUpperCase()} `, room.accent, "blue", true)]);
  pushLine(rows, []);
  for (const line of outcome.narration) {
    pushWrapped(rows, line, "default");
    pushLine(rows, []);
  }
  pushWrapped(rows, `outcome: ${outcome.outcome}`, outcome.outcome === "failure" ? "bright-red" : "bright-cyan");
  if (outcome.approachUsed) {
    pushWrapped(rows, `approach: ${String(outcome.approachUsed).toUpperCase()}`, "bright-yellow");
  }
  if (outcome.tacticUsed) {
    pushWrapped(rows, `tactic: ${String(outcome.tacticUsed).replace(/_/g, " ")}`, "bright-blue");
  }
  pushWrapped(rows, `credits ${signed(outcome.creditDelta)} | debt ${signed(outcome.debtDelta)} | heat ${signed(outcome.heatDeltaApplied ?? outcome.heatDelta)} | stress ${signed(outcome.stressDelta)} | hp ${signed(outcome.hpDelta)} | rep ${FACTIONS[outcome.factionKey].name} ${signed(outcome.repDelta)}`, "grey");
  if (outcome.combat) {
    pushLine(rows, []);
    pushLine(rows, [seg("target lock", room.accent, null, true)]);
    pushWrapped(rows, `${outcome.combat.targetName} // ${outcome.combat.targetRole}`, "bright-yellow");
    pushWrapped(rows, outcome.combat.targetLook, "grey");
    pushWrapped(rows, outcome.combat.trail, "default");
    pushLine(rows, []);
    pushLine(rows, [seg("combat exchange", room.support, null, true)]);
    for (const line of outcome.combat.rounds ?? []) {
      pushWrapped(rows, `> ${line}`, "default");
      pushLine(rows, []);
    }
    if (outcome.combat.finisher) {
      pushWrapped(rows, outcome.combat.finisher, "bright-red");
    }
  }
  if (outcome.itemDropId) {
    pushWrapped(rows, `salvage: ${ITEMS[outcome.itemDropId]?.name ?? outcome.itemDropId}`, "bright-yellow");
  }
  for (const note of outcome.consequenceNotes ?? []) {
    pushWrapped(rows, `aftershock: ${note}`, "bright-magenta");
  }
  return fitRows(rows, Math.max(12, session.viewport?.rows ?? 20));
}

function statusSpans(session, profile, accent, dim) {
  const stats = displayStats(profile);
  return [
    seg(session.handle, accent, null, true),
    seg("  cr ", dim),
    seg(String(profile.credits), "bright-green"),
    seg("  debt ", dim),
    seg(String(profile.debt), profile.debt >= 300 ? "bright-red" : "yellow"),
    seg("  heat ", dim),
    seg(String(profile.heat), profile.heat >= 3 ? "bright-red" : "bright-yellow"),
    seg("  stress ", dim),
    seg(String(profile.stress), profile.stress >= 4 ? "bright-red" : "yellow"),
    seg("  hp ", dim),
    seg(`${profile.hp}/${profile.maxHp}`, profile.hp <= 4 ? "bright-red" : "bright-cyan"),
    seg("  edge/ghost/wire/body ", dim),
    seg(`${stats.edge}/${stats.ghost}/${stats.wire}/${stats.body}`, "white")
  ];
}

function commandLines(room) {
  const exits = room.exits.join(" | ");
  return [
    `ops: look | inspect | search | talk <name> | jobs | run`,
    `move: go <room> [${exits}] | emote <action> | say <msg> | page <handle>`,
    `more: scan | inv | chrome | wares | buy/use | rumors | status | who | jackout`
  ];
}

function exitLabel(key) {
  return key.replace(/(^|_)([a-z])/g, (_, prefix, ch) => `${prefix ? " " : ""}${ch.toUpperCase()}`);
}

function signed(value) {
  return `${value >= 0 ? "+" : ""}${value}`;
}

function formatPrepBoosts(statBoosts) {
  const parts = Object.entries(statBoosts ?? {})
    .filter(([_, value]) => value > 0)
    .map(([stat, value]) => `${stat.toUpperCase()} +${value}`);
  return parts.length > 0 ? parts.join(", ") : "none";
}

function displayStats(profile) {
  const stats = structuredClone(profile.stats ?? {});
  for (const chromeId of profile.chrome ?? []) {
    const chrome = CYBERWARE[chromeId];
    for (const [stat, value] of Object.entries(chrome?.effects?.stats ?? {})) {
      stats[stat] = (stats[stat] ?? 0) + value;
    }
  }
  for (const [stat, value] of Object.entries(profile.prep?.statBoosts ?? {})) {
    stats[stat] = (stats[stat] ?? 0) + value;
  }
  return stats;
}

function chromeEffectSummary(chrome) {
  const parts = [];
  for (const [stat, value] of Object.entries(chrome.effects?.stats ?? {})) {
    parts.push(`${stat.toUpperCase()} +${value}`);
  }
  if (chrome.effects?.maxHp) parts.push(`MAX HP +${chrome.effects.maxHp}`);
  if (chrome.effects?.heatFloorBonus) parts.push(`heat buffer floor +${chrome.effects.heatFloorBonus}`);
  return parts.length > 0 ? parts.join(" | ") : "no obvious gain";
}

function seg(text, fg = "default", bg = null, bold = null) {
  return { text, fg, bg, bold };
}

function pushRule(rows, fg) {
  pushLine(rows, [seg("-".repeat(WIDTH), fg)]);
}

function pushLine(rows, spans) {
  rows.push({ row: rows.length, spans: spans.length > 0 ? spans : [{ text: "", fg: "default", bg: null, bold: null }] });
}

function pushWrapped(rows, text, fg = "default", prefix = "") {
  for (const line of wrap(text, WIDTH - prefix.length)) {
    pushLine(rows, [seg(prefix + line, fg)]);
  }
}

function fitRows(rows, maxRows) {
  if (rows.length <= maxRows) return rows;
  const kept = rows.slice(0, maxRows - 2);
  kept.push({ row: kept.length, spans: [seg("", "default")] });
  kept.push({ row: kept.length, spans: [seg("... signal clipped ...", "dark-grey")] });
  return kept.map((row, index) => ({ ...row, row: index }));
}

function fitRowsKeepingFooter(rows, maxRows, footerCount = 1) {
  if (rows.length <= maxRows) return rows;
  if (maxRows <= 4) return fitRows(rows, maxRows);

  const clampedFooterCount = Math.max(1, footerCount);
  const footer = rows.slice(-clampedFooterCount);
  const kept = rows.slice(0, maxRows - (clampedFooterCount + 2));
  kept.push({ row: kept.length, spans: [seg("... signal clipped ...", "dark-grey")] });
  kept.push({ row: kept.length, spans: [seg("", "default")] });
  for (const footerRow of footer) {
    kept.push({ row: kept.length, spans: footerRow?.spans ?? [seg("", "default")] });
  }
  return kept.map((row, index) => ({ ...row, row: index }));
}

function wrap(text, width) {
  const source = String(text ?? "").trim();
  if (!source) return [""];
  const words = source.split(/\s+/);
  const lines = [];
  let current = "";
  for (const word of words) {
    if (!current) {
      current = word;
      continue;
    }
    if ((current + " " + word).length <= width) {
      current += " " + word;
    } else {
      lines.push(current);
      current = word;
    }
  }
  if (current) lines.push(current);
  return lines;
}
