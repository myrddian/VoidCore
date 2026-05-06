import { createOptionScreen as createSdkOptionScreen } from "@voidcore/door-sdk";
import { renderOptionMenu } from "../render.mjs";
import { ROOMS } from "../world.mjs";
import { findActiveJob, operationApproaches } from "../game.mjs";
import {
  allJobsForSession,
  loadJobBoard,
  runJob
} from "../index.mjs";

function approachSummary(job, approach) {
  const summaries = {
    edge: "Push the social line, bluff the locals, and move like you belong there.",
    ghost: "Keep the carrier low, use the dark edges, and try not to leave a silhouette.",
    wire: "Work the systems, trip the right signals, and let the infrastructure do the lying.",
    body: "Take the hard route, trust your chrome, and make the room pay attention."
  };
  const base = summaries[approach] ?? "Take a direct angle and trust your instincts.";
  return `${base} Hooked for ${job.title}.`;
}

function jobTacticsFor(chosenApproach) {
  const tacticsByApproach = {
    edge: [
      {
        id: "talk_past_the_desk",
        label: "Talk Past The Desk",
        aliases: ["talk", "desk", "bluff"],
        summary: "Walk in like the lie was filed hours ago and let the room catch up.",
        meta: "social pressure | paperwork heat"
      },
      {
        id: "buy_a_favor",
        label: "Buy A Favor",
        aliases: ["favor", "bribe", "buy"],
        summary: "Spend leverage, trade names, and open the route through someone else's greed.",
        meta: "soft leverage | cleaner exit"
      },
      {
        id: "start_a_scene",
        label: "Start A Scene",
        aliases: ["scene", "distraction", "flare"],
        summary: "Kick the room sideways and move while everyone watches the wrong fire.",
        meta: "loud | fast | messy"
      }
    ],
    ghost: [
      {
        id: "slip_the_dark",
        label: "Slip The Dark",
        aliases: ["dark", "slip", "quiet"],
        summary: "Use blind corners, dead cameras, and everyone else's bad maintenance.",
        meta: "low signal | patient route"
      },
      {
        id: "ride_the_shift_change",
        label: "Ride The Shift Change",
        aliases: ["shift", "timing", "rotation"],
        summary: "Move in the seam between crews while the room is busy forgetting itself.",
        meta: "timing play | narrow window"
      },
      {
        id: "leave_a_false_trail",
        label: "Leave A False Trail",
        aliases: ["trail", "decoy", "mislead"],
        summary: "Seed a silhouette somewhere else and let the watchers chase a ghost.",
        meta: "misdirection | cooler exit"
      }
    ],
    wire: [
      {
        id: "spoof_the_grid",
        label: "Spoof The Grid",
        aliases: ["grid", "spoof", "auth"],
        summary: "Make the local systems bless your lie just long enough to cross the threshold.",
        meta: "systems play | fragile authority"
      },
      {
        id: "trip_a_back_channel",
        label: "Trip A Back Channel",
        aliases: ["backchannel", "relay", "line"],
        summary: "Use an old relay, quiet switch, or dirty maintenance line to get in sideways.",
        meta: "network seam | elegant route"
      },
      {
        id: "blackout_the_witnesses",
        label: "Blackout The Witnesses",
        aliases: ["blackout", "jam", "blind"],
        summary: "Kill the eyes, mute the logs, and trust the dark to do the rest.",
        meta: "harsh cut | raises suspicion"
      }
    ],
    body: [
      {
        id: "kick_the_door",
        label: "Kick The Door",
        aliases: ["door", "breach", "kick"],
        summary: "Use force, speed, and momentum before the room gets a vote.",
        meta: "violent entry | high tempo"
      },
      {
        id: "hold_the_chokepoint",
        label: "Hold The Chokepoint",
        aliases: ["hold", "choke", "anchor"],
        summary: "Pick the one place the room has to pass through and make it your jurisdiction.",
        meta: "control | stubborn pressure"
      },
      {
        id: "burn_through_the_pain",
        label: "Burn Through The Pain",
        aliases: ["pain", "tank", "push"],
        summary: "Absorb the ugly part on purpose and keep moving until the job breaks first.",
        meta: "durability | costly finish"
      }
    ]
  };
  return tacticsByApproach[chosenApproach] ?? tacticsByApproach.edge;
}

function huntTacticsFor(chosenApproach) {
  const tacticsByApproach = {
    edge: [
      {
        id: "bait_the_target",
        label: "Bait The Target",
        aliases: ["bait", "taunt", "draw"],
        summary: "Put pressure on their ego and make them step into the open on your terms.",
        meta: "provocation | visible line"
      },
      {
        id: "flip_the_witness",
        label: "Flip The Witness",
        aliases: ["witness", "informant", "turn"],
        summary: "Get a local to sell the target's habits before the target notices the room changed.",
        meta: "social intel | cleaner trail"
      },
      {
        id: "call_the_debt",
        label: "Call The Debt",
        aliases: ["debt", "leverage", "cashin"],
        summary: "Use somebody's old debt to collapse the target's exit plan in real time.",
        meta: "leverage hit | sharp fallout"
      }
    ],
    ghost: [
      {
        id: "shadow_the_route",
        label: "Shadow The Route",
        aliases: ["shadow", "trail", "tail"],
        summary: "Trail them through the quiet route until they stop thinking they are being watched.",
        meta: "patient pursuit | precise strike"
      },
      {
        id: "cut_the_escape",
        label: "Cut The Escape",
        aliases: ["escape", "cutoff", "funnel"],
        summary: "Map the exits first, then close the only one that mattered.",
        meta: "route denial | tight timing"
      },
      {
        id: "bleed_into_cover",
        label: "Bleed Into Cover",
        aliases: ["cover", "blend", "melt"],
        summary: "Stay inside the crowd long enough that the target mistakes you for scenery.",
        meta: "crowd work | low profile"
      }
    ],
    wire: [
      {
        id: "ghost_their_comms",
        label: "Ghost Their Comms",
        aliases: ["comms", "spoof", "ghost"],
        summary: "Corrupt the target's feed so their backup arrives late or somewhere stupid.",
        meta: "signal hijack | delayed panic"
      },
      {
        id: "lock_the_grid",
        label: "Lock The Grid",
        aliases: ["grid", "lock", "seal"],
        summary: "Trap the room with doors, lights, and systems that suddenly answer to you.",
        meta: "environment control | brittle authority"
      },
      {
        id: "trip_the_kill_switch",
        label: "Trip The Kill Switch",
        aliases: ["switch", "trip", "drop"],
        summary: "Cut one critical system at the exact second the target needs it most.",
        meta: "technical ambush | high risk"
      }
    ],
    body: [
      {
        id: "rush_the_center",
        label: "Rush The Center",
        aliases: ["rush", "center", "charge"],
        summary: "Take the middle of the room and force the target to survive your tempo.",
        meta: "impact entry | brutal pace"
      },
      {
        id: "break_the_guard",
        label: "Break The Guard",
        aliases: ["guard", "break", "smash"],
        summary: "Crack the first defense hard enough that the rest of the encounter forgets its shape.",
        meta: "hard breach | decisive violence"
      },
      {
        id: "finish_at_close_range",
        label: "Finish At Close Range",
        aliases: ["close", "finish", "grapple"],
        summary: "Close the distance until the fight becomes breath, weight, and bad choices.",
        meta: "up close | ugly ending"
      }
    ]
  };
  return tacticsByApproach[chosenApproach] ?? tacticsByApproach.edge;
}

function tacticProfilesFor(job, chosenApproach) {
  if (job.operation === "hunt") {
    return huntTacticsFor(chosenApproach);
  }
  return jobTacticsFor(chosenApproach);
}

function approachOptionsForJob(session, job) {
  const approaches = operationApproaches(session.data.profile, job.hooks);
  return approaches.map((approach, index) => ({
    key: String(index + 1),
    label: `Lean on ${approach.toUpperCase()}`,
    aliases: [approach, `${index + 1}`],
    value: approach,
    summary: approachSummary(job, approach),
    meta: `hook: ${approach.toUpperCase()} | room: ${ROOMS[job.room]?.name ?? job.room}`,
    async action(session, { stack, option }) {
      await stack.push(session, createTacticScreen(job.id, option.value), "approach-picked");
    }
  }));
}

function tacticOptionsForJob(job, chosenApproach) {
  const tactics = tacticProfilesFor(job, chosenApproach);
  return tactics.map((tactic, index) => ({
    key: String(index + 1),
    label: tactic.label,
    aliases: [tactic.id, ...tactic.aliases, `${index + 1}`],
    value: tactic.id,
    summary: tactic.summary,
    meta: tactic.meta,
    async action(session, { stack, option }) {
      await stack.pop(session, "tactic-picked");
      await stack.pop(session, "approach-resolved");
      await runJob(session, chosenApproach, option.value);
    }
  }));
}

export function createApproachScreen(jobId) {
  return createSdkOptionScreen({
    id: `job-approach-${jobId}`,
    promptLabel: "[esc] approach:",
    initialNote: null,
    onUnknown(session, input, { paint }) {
      paint(session, `No approach matches: ${input}`);
    },
    async onEscape(session, { stack }) {
      await stack.pop(session, "approach-exit");
    },
    render(session, view) {
      const room = ROOMS[session.data.profile.room];
      const job = findActiveJob(session.data.profile, allJobsForSession(session));
      const options = view.options.map((option) => ({
        ...option,
        summary: option.summary ?? job?.summary ?? null,
        meta: option.meta ?? null
      }));
      return renderOptionMenu(session, {
        title: `APPROACH://${room.key.toUpperCase()}`,
        subtitle: job
          ? `${job.title} // choose how you want to press the op`
          : "No live op is attached to this route anymore.",
        options,
        note: view.note
      });
    },
    options: [{ key: "-", label: "loading...", action: async () => {} }],
    async onEnter(session, ctx) {
      session.data.generatedJobs = await loadJobBoard(session);
      const job = findActiveJob(session.data.profile, allJobsForSession(session));
      if (!job) {
        ctx.options.splice(0, ctx.options.length);
        ctx.paint(session, "The op fell out of your queue before you got moving. Press [Esc] to step back.");
        return;
      }
      ctx.options.splice(0, ctx.options.length, ...approachOptionsForJob(session, job));
      ctx.paint(session);
    }
  });
}

export function createTacticScreen(jobId, chosenApproach) {
  return createSdkOptionScreen({
    id: `job-tactic-${jobId}-${chosenApproach}`,
    promptLabel: "[esc] tactic:",
    initialNote: null,
    onUnknown(session, input, { paint }) {
      paint(session, `No tactic matches: ${input}`);
    },
    async onEscape(session, { stack }) {
      await stack.pop(session, "tactic-exit");
    },
    render(session, view) {
      const room = ROOMS[session.data.profile.room];
      const job = findActiveJob(session.data.profile, allJobsForSession(session));
      return renderOptionMenu(session, {
        title: `TACTIC://${room.key.toUpperCase()}`,
        subtitle: job
          ? `${job.title} // ${String(chosenApproach).toUpperCase()} angle`
          : "No live op is attached to this route anymore.",
        options: view.options,
        note: view.note
      });
    },
    options: [{ key: "-", label: "loading...", action: async () => {} }],
    async onEnter(session, ctx) {
      session.data.generatedJobs = await loadJobBoard(session);
      const job = findActiveJob(session.data.profile, allJobsForSession(session));
      if (!job) {
        ctx.options.splice(0, ctx.options.length);
        ctx.paint(session, "The op vanished before you finished lining up the hit. Press [Esc] to step back.");
        return;
      }
      ctx.options.splice(0, ctx.options.length, ...tacticOptionsForJob(job, chosenApproach));
      ctx.paint(session);
    }
  });
}
