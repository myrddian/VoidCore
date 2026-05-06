import { createDialogueScreen as createSdkDialogueScreen } from "@voidcore/door-sdk";
import { renderDialogue } from "../render.mjs";
import { FACTIONS, NPCS, ROOMS } from "../world.mjs";
import {
  applyTalkEffects,
  readDialogueLog,
  recordDialogueTurn,
  recordLeads,
  rememberNpc,
  replyToNpc,
  talkToNpc
} from "../game.mjs";
import {
  appendFeed,
  checkPostEffectAchievements,
  loadPlotState,
  normalizeTarget,
  registerProceduralDelve,
  saveProfile
} from "../index.mjs";

function dialoguePromptLabel(npc) {
  const tag = normalizeTarget(npc.name.split(/\s+/)[0]) || "signal";
  return `[esc] ${tag}:`;
}

function talkToSceneEntries(session, npc, scene) {
  const labelBase = `${npc.name.toUpperCase()} // ${npc.role.toUpperCase()}`;
  const entries = scene.lines.map((line, idx) => ({
    label: idx === 0 ? labelBase : null,
    text: line,
    labelFg: session.data.roomMeta.support,
    textFg: "default"
  }));

  let note;
  if (Array.isArray(scene.choices) && scene.choices.length > 0) {
    note = "pick a number, slug, or speak freely.";
  } else if ((scene.applied?.leads ?? []).length > 0) {
    note = "a lead surfaces.";
  } else {
    note = null;
  }

  const lead = scene.applied?.leads?.[scene.applied.leads.length - 1] ?? null;

  return { ...scene, note, lead, entries };
}

function adjudicateTalkScene(session, npc, scene) {
  if (!scene) return scene;
  const applied = applyTalkEffects(session.data.profile, session.data.plotState, scene, { npcName: npc?.name ?? null });
  const lines = Array.isArray(scene.lines) ? [...scene.lines] : [];
  for (const extra of applied.extraNarration) lines.push(extra);
  for (const proc of applied.proceduralDelves ?? []) {
    registerProceduralDelve(session, proc);
  }
  scene.lines = lines;
  scene.applied = applied;
  return scene;
}

function matchChoicePick(session, npc, rawInput) {
  const choices = session.data.cityline_lastChoices?.[npc.name];
  if (!Array.isArray(choices) || choices.length === 0) return null;
  const input = String(rawInput ?? "").trim();
  if (!input) return null;
  const lowered = input.toLowerCase();
  if (/^\d+$/.test(input)) {
    const idx = Number(input) - 1;
    if (idx >= 0 && idx < choices.length) return choices[idx];
  }
  for (const choice of choices) {
    if (choice.id.toLowerCase() === lowered) return choice;
  }
  for (const choice of choices) {
    if (choice.label.toLowerCase() === lowered) return choice;
  }
  for (const choice of choices) {
    if (choice.label.toLowerCase().includes(lowered) && lowered.length >= 4) return choice;
  }
  return null;
}

export function createDialogueScreen(npc) {
  return createSdkDialogueScreen({
    id: `dialogue-${normalizeTarget(npc.name)}`,
    promptLabel: () => dialoguePromptLabel(npc),
    historyLimit: 40,
    exitCommands: ["leave", "walk", "back", "bye", "exit"],
    initialLead: null,
    initialNote: null,
    blankInputNote: "Say something, or hit Esc to step away.",
    playerEntry(session, input) {
      const choices = session.data.cityline_lastChoices?.[npc.name] ?? [];
      const chosen = matchChoicePick(session, npc, input);
      let text = input;
      if (chosen) {
        const idx = choices.findIndex((c) => c.id === chosen.id);
        text = idx >= 0 ? `[${idx + 1}] ${chosen.label}` : chosen.label;
      }
      return {
        label: session.handle.toUpperCase(),
        text,
        labelFg: session.data.roomMeta.accent,
        textFg: "white"
      };
    },
    thinking(session, context) {
      return context.isInitial
        ? `opening ${npc.name.toLowerCase()}`
        : `reading ${session.handle.toLowerCase()}`;
    },
    async open(session) {
      session.data.plotState = await loadPlotState(session);
      console.log(`[cityline] talk start npc=${npc.name} llmEnabled=${session.data.llmEnabled} room=${session.data.profile.room}`);
      const room = ROOMS[session.data.profile.room];
      const scene = await talkToNpc(session, npc, room, session.data.llmEnabled, session.data.plotState);
      const finalScene = adjudicateTalkScene(session, npc, scene);
      return talkToSceneEntries(session, npc, finalScene);
    },
    async reply(session, playerText) {
      session.data.plotState = await loadPlotState(session);
      const room = ROOMS[session.data.profile.room];
      const chosen = matchChoicePick(session, npc, playerText);
      const inputForLlm = chosen ? chosen.label : playerText;
      const opts = chosen ? { playerChoice: { id: chosen.id, label: chosen.label } } : {};
      const scene = await replyToNpc(session, npc, room, inputForLlm, session.data.llmEnabled, session.data.plotState, opts);
      if (chosen) scene.chosenChoice = chosen;
      const finalScene = adjudicateTalkScene(session, npc, scene);
      return talkToSceneEntries(session, npc, finalScene);
    },
    render(session, view) {
      return renderDialogue(session, npc, view.transcript, {
        lead: view.lead,
        note: view.note,
        thinking: view.thinking ? `oracle ${view.thinking}` : null
      });
    },
    async onScene(session, scene, _viewState, context = {}) {
      const room = ROOMS[session.data.profile.room];
      const applied = scene.applied ?? null;
      const choicesCount = Array.isArray(scene.choices) ? scene.choices.length : 0;
      const fromFallback = scene.fallback === true;
      console.log(
        `[cityline] talk done npc=${npc.name} mode=${fromFallback ? "fallback" : (session.data.llmEnabled ? "llm" : "fallback-no-llm")} effects=${applied?.applied?.length ?? scene.effects?.length ?? 0} choices=${choicesCount} skillChecks=${applied?.skillChecks?.length ?? 0} historyLen=${readDialogueLog(session.data.profile, npc).length}`
      );
      if (context.input) {
        const playerLine = scene?.chosenChoice?.label ?? context.input;
        recordDialogueTurn(session.data.profile, npc, "player", playerLine, room);
      }
      if (!fromFallback) {
        const npcText = Array.isArray(scene.lines) ? scene.lines.filter(Boolean).join(" ") : "";
        if (npcText) {
          recordDialogueTurn(session.data.profile, npc, "npc", npcText, room);
        }
      }
      const memory = applied?.memoryUpdate ?? scene.fallbackMemory ?? null;
      if (memory) rememberNpc(session.data.profile, npc, memory);
      const newLeads = applied?.leads ?? [];
      if (newLeads.length > 0) {
        recordLeads(session.data.profile, npc, room, newLeads);
      }
      await saveProfile(session);
      await checkPostEffectAchievements(session);

      const rumorTone = ROOMS[session.data.profile.room].accent;
      const rumors = applied?.rumors ?? [];
      if (rumors.length > 0) {
        for (const rumor of rumors) {
          await appendFeed(session, { tone: rumorTone, text: `wire:// ${rumor}` });
        }
      } else if (scene.fallbackRumor) {
        await appendFeed(session, { tone: rumorTone, text: `wire:// ${scene.fallbackRumor}` });
      }

      for (const nudge of applied?.repNudges ?? []) {
        const faction = FACTIONS[nudge.faction];
        if (!faction) continue;
        session.notify(`rep ${faction.name} ${nudge.delta >= 0 ? "+" : ""}${nudge.delta}`, { level: "info", durationMs: 1800 });
      }
      if ((applied?.rngLog ?? []).length > 0) {
        session.notify(`shift: ${applied.rngLog.join(" | ")}`, { level: "warn", durationMs: 2000 });
      }
      for (const check of applied?.skillChecks ?? []) {
        session.notify(
          `${check.tag || check.stat} check: ${check.success ? "success" : "miss"} (${check.statValue}+${check.roll} vs ${check.threshold})`,
          { level: check.success ? "info" : "warn", durationMs: 2200 }
        );
      }

      session.data.cityline_lastChoices = session.data.cityline_lastChoices ?? {};
      if (Array.isArray(scene.choices) && scene.choices.length > 0) {
        session.data.cityline_lastChoices[npc.name] = scene.choices;
      } else {
        delete session.data.cityline_lastChoices[npc.name];
      }
    },
    async onExit(session) {
      if (session.data.cityline_lastChoices) {
        delete session.data.cityline_lastChoices[npc.name];
      }
    }
  });
}
