import { createOptionScreen as createSdkOptionScreen } from "@voidcore/door-sdk";
import { renderOptionMenu } from "../render.mjs";
import { ROOMS } from "../world.mjs";
import { assignJob, findJob, jobsVisibleInRoom } from "../game.mjs";
import {
  allJobsForSession,
  appendFeed,
  ensureJobsForRoom,
  loadJobBoard,
  saveProfile
} from "../index.mjs";
import { createApproachScreen } from "./approach.mjs";

const MAX_ROOM_JOBS = 5;

// Pick from the board → accept + roll straight into approach/tactic.
// The room verb `take <id>` still does the lightweight "assign without
// run" path for players who want to grab a job and resolve it later.
async function acceptAndRun(session, jobId, stack, paint) {
  if (session.data.profile.activeJobId) {
    paint(session, `You already have live work: ${session.data.profile.activeJobId}. Press [R] to run it.`);
    return;
  }
  const job = findJob(jobId, session.data.profile.room, allJobsForSession(session));
  if (!job) {
    paint(session, `That op slipped off the board. Try again.`);
    return;
  }
  assignJob(session.data.profile, job);
  await saveProfile(session);
  await appendFeed(session, {
    tone: ROOMS[job.room].support,
    text: `wire:// ${session.handle} picked up "${job.title}".`
  });
  session.notify(`Op accepted: ${job.title}`, { level: "info", durationMs: 2000 });
  await stack.replace(session, createApproachScreen(job.id), "job-accepted");
}

function jobOptionsForSession(session) {
  const jobs = jobsVisibleInRoom(session.data.profile.room, session.data.profile, allJobsForSession(session)).slice(0, 5);
  const options = jobs.map((job, index) => ({
    key: String(index + 1),
    label: job.title,
    aliases: [job.id, job.title],
    value: job.id,
    job,
    async action(session, { stack, option, paint }) {
      await acceptAndRun(session, option.value, stack, paint);
    }
  }));

  if (session.data.profile.activeJobId) {
    options.push({
      key: "R",
      label: "Run active op",
      aliases: ["run", session.data.profile.activeJobId],
      meta: `resolve ${session.data.profile.activeJobId}`,
      async action(session, { stack }) {
        await stack.replace(session, createApproachScreen(session.data.profile.activeJobId), "job-run");
      }
    });
  }

  return options;
}

export function createJobsScreen() {
  return createSdkOptionScreen({
    id: "jobs-menu",
    promptLabel: "[esc] jobs:",
    initialNote: null,
    loading: {
      message(session, { frame }) {
        return `oracle opening job board for ${ROOMS[session.data.profile.room].name} ${frame}`;
      }
    },
    async loadOptions(session) {
      session.data.generatedJobs = await loadJobBoard(session);
      const roomKey = session.data.profile.room;
      const room = ROOMS[roomKey];
      const visibleJobs = jobsVisibleInRoom(roomKey, session.data.profile, allJobsForSession(session));
      if (session.data.llmEnabled && visibleJobs.length < MAX_ROOM_JOBS) {
        session.notify(`Oracle opening job board for ${room.name}...`, { level: "info", durationMs: 1800 });
      }
      await ensureJobsForRoom(session, roomKey);
      return jobOptionsForSession(session);
    },
    onUnknown(session, input, { paint }) {
      paint(session, `No local op matches: ${input}`);
    },
    async onEscape(session, { stack }) {
      await stack.pop(session, "jobs-exit");
    },
    render(session, view) {
      const options = view.options.map((option) => ({
        ...option,
        summary: option.job?.summary ?? null,
        meta: option.job
          ? `giver: ${option.job.giver} | reward: ${option.job.reward.credits}c | hooks: ${option.job.hooks.join("/")}`
          : option.meta
      }));
      return renderOptionMenu(session, {
        title: `OPS://${session.data.roomMeta.key.toUpperCase()}`,
        subtitle: session.data.profile.activeJobId
          ? `active op: ${session.data.profile.activeJobId}`
          : null,
        options,
        note: view.note
      });
    },
    options: [{ key: "-", label: "loading...", action: async () => {} }]
  });
}
