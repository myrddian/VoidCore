import { renderCombatStage, renderDelveStage } from "../render.mjs";
import { ENEMIES, FACTIONS, ROOMS } from "../world.mjs";
import {
  applyTerminalStage,
  findHealingItem,
  newCombatState,
  resolveDelveChoice,
  resolveEnemyAttack,
  resolveFlee,
  resolvePlayerAttack,
  useInventoryItem
} from "../game.mjs";
import {
  appendFeed,
  safePrompt,
  saveProfile,
  tryAchievement
} from "../index.mjs";

function lookupDelveStage(delve, stageId) {
  if (!delve) return null;
  return delve.stages?.[stageId] ?? null;
}

function matchDelveChoice(choices, input) {
  if (!input) return null;
  const lowered = input.toLowerCase();
  if (/^\d+$/.test(input)) {
    const idx = Number(input) - 1;
    if (idx >= 0 && idx < choices.length) return choices[idx];
  }
  for (const c of choices) if (c.id?.toLowerCase() === lowered) return c;
  for (const c of choices) if (c.label?.toLowerCase() === lowered) return c;
  for (const c of choices) if (c.label?.toLowerCase().includes(lowered) && lowered.length >= 4) return c;
  return null;
}

// Combat actions accept number, slug, or label-substring.
//   1 / a / attack       -> attack with edge stat (default ranged)
//   2 / b / body         -> attack with body stat (melee / brawl)
//   3 / d / defend       -> defend, +ghost next round, halve damage
//   4 / u / use          -> use first healing item in inventory
//   5 / f / flee         -> ghost-check escape
function matchCombatAction(input) {
  const lowered = String(input ?? "").trim().toLowerCase();
  if (!lowered) return null;
  if (/^(1|a|attack|edge|shoot)$/.test(lowered)) return { kind: "attack", stat: "edge" };
  if (/^(2|b|body|brawl|melee)$/.test(lowered)) return { kind: "attack", stat: "body" };
  if (/^(3|d|def|defend|guard|brace)$/.test(lowered)) return { kind: "defend" };
  if (/^(4|u|use|heal|item)$/.test(lowered)) return { kind: "use_item" };
  if (/^(5|f|flee|run|back)$/.test(lowered)) return { kind: "flee" };
  return null;
}

export function createDelveScreen(delve) {
  let currentStageId = delve.entry;
  let history = []; // for the on-screen scroll-back: { kind, lines }
  let lastApplied = null;
  let combat = null; // { enemy, state } when a combat stage is active

  const paint = async (session) => {
    const stage = lookupDelveStage(delve, currentStageId);
    if (stage?.kind === "combat") {
      session.paint(renderCombatStage(session, delve, stage, combat, history));
    } else {
      session.paint(renderDelveStage(session, delve, stage, currentStageId, history, lastApplied));
    }
    session.data.lastPaintKind = "delve";
    safePrompt(session, { label: `[esc] ${delve.id}:`, maxLength: 80 });
  };

  const enterCombatStage = (session, stage) => {
    const enemy = ENEMIES[stage.enemyId] ?? null;
    if (!enemy) {
      currentStageId = stage.onDefeat ?? null;
      combat = null;
      return;
    }
    combat = { enemy: { ...enemy }, state: newCombatState(enemy) };
    history.push({ kind: "combat-open", lines: [enemy.flavor] });
  };

  const finishTerminal = async (session, stage) => {
    const finalApplied = applyTerminalStage(session.data.profile, session.data.plotState, stage);
    history.push({ kind: "terminal", lines: stage.narrate ?? [], applied: finalApplied });
    if (finalApplied?.repNudges?.length > 0) {
      for (const n of finalApplied.repNudges) {
        const fac = FACTIONS[n.faction];
        if (fac) session.notify(`rep ${fac.name} ${n.delta >= 0 ? "+" : ""}${n.delta}`, { level: "info", durationMs: 1800 });
      }
    }
    if (finalApplied?.rngLog?.length > 0) {
      session.notify(`delve outcome: ${finalApplied.rngLog.join(" | ")}`, { level: "info", durationMs: 2400 });
    }
    if (delve.rumour) {
      await appendFeed(session, { tone: "bright-yellow", text: delve.rumour });
    }
    await saveProfile(session);
    session.paint(renderDelveStage(session, delve, stage, currentStageId, history, finalApplied));
    session.data.lastPaintKind = "delve";
    safePrompt(session, { label: `[any] back to ${ROOMS[delve.room].key}:`, maxLength: 40 });
  };

  const advanceTo = async (session, nextStageId) => {
    currentStageId = nextStageId;
    const next = lookupDelveStage(delve, currentStageId);
    if (!next) return;
    if (next.kind === "combat") {
      enterCombatStage(session, next);
      return;
    }
    if (next.terminal) {
      await finishTerminal(session, next);
    }
  };

  const runCombatRound = async (session, action) => {
    if (!combat) return;
    const stage = lookupDelveStage(delve, currentStageId);
    const profile = session.data.profile;

    if (action.kind === "attack") {
      const result = resolvePlayerAttack(profile, combat.enemy, action.stat);
      combat.state.enemyHp = Math.max(0, combat.state.enemyHp - result.damage);
      const tag = result.hit
        ? (result.isCrit ? `crit with ${result.weaponName}` : `hit with ${result.weaponName}`)
        : `miss with ${result.weaponName}`;
      combat.state.log.push({
        round: combat.state.round, side: "player", action: action.stat,
        roll: result.roll, total: result.total, threshold: result.threshold,
        hit: result.hit, damage: result.damage, weapon: result.weaponName, tag
      });
      session.notify(`${tag} (${result.roll}+${result.statValue + result.accuracy} vs ${result.threshold})${result.hit ? ` → ${result.damage}dmg` : ""}`, {
        level: result.hit ? "info" : "warn",
        durationMs: 2200
      });
    } else if (action.kind === "defend") {
      combat.state.defending = true;
      combat.state.log.push({ round: combat.state.round, side: "player", action: "defend" });
      session.notify("you brace for the next swing.", { level: "info", durationMs: 1600 });
    } else if (action.kind === "use_item") {
      const heal = findHealingItem(profile);
      if (!heal) {
        session.notify("no healing item to use.", { level: "warn", durationMs: 1800 });
        return;
      }
      const result = useInventoryItem(profile, heal.itemId);
      combat.state.log.push({ round: combat.state.round, side: "player", action: "use_item", item: heal.item.name, message: result.message });
      session.notify(`used ${heal.item.name}. ${result.message}`, { level: "info", durationMs: 2400 });
    } else if (action.kind === "flee") {
      const flee = resolveFlee(profile, combat.enemy);
      if (flee.success) {
        combat.state.log.push({ round: combat.state.round, side: "player", action: "flee", success: true });
        session.notify(`you slip free (${flee.roll}+${(profile.stats?.ghost ?? 0)} vs ${flee.threshold}).`, { level: "info", durationMs: 2200 });
        await saveProfile(session);
        history.push({ kind: "combat-flee", lines: [combat.enemy.fleeFlavor ?? "you back out before they commit."] });
        const nextId = stage.onFlee ?? null;
        combat = null;
        await tryAchievement(session, "ghost_hand");
        await advanceTo(session, nextId);
        return;
      }
      session.notify(`flee failed (${flee.roll}+${(profile.stats?.ghost ?? 0)} vs ${flee.threshold}). they cut you off.`, { level: "warn", durationMs: 2400 });
      combat.state.log.push({ round: combat.state.round, side: "player", action: "flee", success: false });
    }

    if (combat.state.enemyHp <= 0) {
      history.push({ kind: "combat-win", lines: [combat.enemy.deathFlavor ?? "the enemy folds."] });
      await saveProfile(session);
      await tryAchievement(session, "first_blood");
      const nextId = stage.onVictory ?? null;
      combat = null;
      await advanceTo(session, nextId);
      return;
    }

    const counter = resolveEnemyAttack(profile, combat.enemy, combat.state.defending);
    profile.hp = Math.max(0, (profile.hp ?? 0) - counter.damage);
    combat.state.log.push({
      round: combat.state.round, side: "enemy", action: counter.attack.name,
      roll: counter.roll, total: counter.total, threshold: counter.threshold,
      hit: counter.hit, damage: counter.damage
    });
    if (counter.hit) {
      session.notify(`${combat.enemy.name} ${counter.attack.name} → ${counter.damage}dmg.`, { level: "warn", durationMs: 2200 });
    } else {
      session.notify(`${combat.enemy.name} swings, you slip it.`, { level: "info", durationMs: 1800 });
    }

    combat.state.defending = false;
    combat.state.round += 1;
    await saveProfile(session);

    if (profile.hp <= 0) {
      history.push({ kind: "combat-lose", lines: [`${combat.enemy.name} stands over you. The dark folds in.`] });
      const nextId = stage.onDefeat ?? null;
      combat = null;
      await advanceTo(session, nextId);
      return;
    }
  };

  return {
    id: `delve-${delve.id}`,
    async onEnter(session) {
      const entry = lookupDelveStage(delve, currentStageId);
      if (entry?.kind === "combat") enterCombatStage(session, entry);
      await paint(session);
    },
    async onLine(session, text, stack) {
      const input = String(text ?? "").trim();
      const stage = lookupDelveStage(delve, currentStageId);
      if (!stage) {
        await stack.pop(session, "delve-broken");
        return true;
      }

      if (stage.kind === "combat" && combat) {
        const action = matchCombatAction(input);
        if (!action) {
          history.push({ kind: "noise", lines: [`// "${input}" isn't a combat action. Try: attack | defend | use | flee.`] });
          await paint(session);
          return true;
        }
        await runCombatRound(session, action);
        await paint(session);
        return true;
      }

      if (stage.terminal) {
        await stack.pop(session, "delve-complete");
        return true;
      }

      const choice = matchDelveChoice(stage.choices ?? [], input);
      if (!choice) {
        history.push({ kind: "noise", lines: [`// "${input}" doesn't match a choice. Type a number or slug.`] });
        await paint(session);
        return true;
      }
      const result = resolveDelveChoice(session.data.profile, delve, stage, choice, { plotState: session.data.plotState });
      lastApplied = result.applied;
      history.push({
        kind: "choice",
        label: choice.label,
        lines: stage.narrate ?? [],
        applied: result.applied
      });
      if (result.applied?.rngLog?.length > 0) {
        session.notify(`delve: ${result.applied.rngLog.join(" | ")}`, { level: "info", durationMs: 2200 });
      }
      for (const sc of result.applied?.skillChecks ?? []) {
        session.notify(`${sc.tag || sc.stat}: ${sc.success ? "success" : "miss"} (${sc.statValue}+${sc.roll} vs ${sc.threshold})`, {
          level: sc.success ? "info" : "warn",
          durationMs: 2400
        });
      }
      await saveProfile(session);
      await advanceTo(session, result.nextStageId);
      await paint(session);
      return true;
    },
    async onKey(session, key, _modifiers, stack) {
      const k = String(key ?? "").trim().toLowerCase();
      if (k === "escape" || k === "esc") {
        await stack.pop(session, "delve-aborted");
        return true;
      }
      return false;
    },
    async onExit(session, _stack, _next, reason) {
      if (reason === "delve-complete") {
        session.notify(`Delve complete: ${delve.title}.`, { level: "info", durationMs: 2200 });
        await tryAchievement(session, "first_run");
      } else if (reason === "delve-aborted") {
        session.notify("You back out of the delve.", { level: "warn", durationMs: 1800 });
      }
    }
  };
}
