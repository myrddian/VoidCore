# Node Door SDK

This package provides a tiny sidecar SDK for `voidcore-door-v1`.

Import shape:

- `@voidcore/door-sdk/protocol` for the raw protocol client and envelope helpers
- `@voidcore/door-sdk/ui` for higher-level screen, menu, and dialogue helpers
- `@voidcore/door-sdk` still re-exports the full combined surface for convenience

It handles:

- WebSocket registration (`hello` / `welcome`)
- multiplexed door sessions via `session_id`
- `ready`, `paint`, `prompt`, `notify`, `effect`, and `detach`
- BBS KV storage with promise-based `get` / `put` / `del` / `scan`
- BBS-mediated LLM calls with `session.llm.chat(...)` and `session.llm.stream(...)`
- optional server-driven `time.tick` updates with synced Unix time in seconds
- BBS-initiated `detach` and `shutdown`
- optional screen-stack helpers for multi-screen doors
- reusable dialogue-screen and option-screen helpers for common door UI patterns
- async option-board loading with built-in spinner support for things like job boards
- simple frame animation helpers for waiting spinners and terminal motion

Raw protocol use:

```js
import { connectDoor, createEnvelope } from "@voidcore/door-sdk/protocol";

const door = connectDoor({
  manifest: { /* ... */ },
  async onEnvelope(env) {
    if (env.type === "custom.signal") {
      return true;
    }
    return false;
  },
  async onTime(session, clock) {
    console.log("synced unix time", session.id, clock.unixTimeSec);
  }
});

door.sendEnvelope(createEnvelope({
  type: "notify",
  payload: { session_id: "example", level: "info", text: "hello", duration_ms: 1500 }
}));
```

Convenience/client use:

```js
import { connectDoor } from "@voidcore/door-sdk";

const door = connectDoor({
  manifest: {
    door_id: "example-door",
    name: "Example Door",
    version: "0.1.0",
    authors: ["sysop"],
    description: "Minimal sidecar example",
    modes_supported: ["normal"],
    default_mode: "normal",
    capabilities: {
      storage_kv: true,
      llm: true,
      notifications: true,
      multi_session: true,
      inter_session_messages: false,
      user_handle_visible: true,
      user_id_visible: true
    }
  },
  async onAttach(session) {
    session.paintText(["", "  hello from the sidecar", ""]);
    session.prompt({ label: ">", maxLength: 80 });
  },
  async onLine(session, text) {
    session.paintText(["", `  you typed: ${text}`, ""]);
    session.prompt({ label: ">", maxLength: 80 });
  }
});
```

Screen-stack example:

```js
import { connectDoor, createScreenStack } from "@voidcore/door-sdk";

const screens = createScreenStack();

const door = connectDoor({
  manifest: { /* ... */ },
  async onAttach(session) {
    await screens.setRoot(session, {
      async onEnter(session) {
        session.paintText(["Root screen"]);
        session.prompt({ label: ">", maxLength: 80 });
      },
      async onLine(session, text, stack) {
        if (text === "open") {
          await stack.push(session, {
            async onEnter(session) {
              session.paintText(["Dialog screen", "Press Esc to go back"]);
              session.prompt({ label: "dialog>", maxLength: 80 });
            },
            async onKey(session, key, _mods, stack) {
              if (key === "Escape") {
                await stack.pop(session, "escape");
                return true;
              }
              return false;
            }
          });
          return true;
        }
      }
    });
  },
  onLine: (session, text, payload, client) => screens.dispatchLine(session, text, payload, client),
  onKey: (session, key, mods, payload, client) => screens.dispatchKey(session, key, mods, payload, client)
});
```

Dialogue-screen helper:

```js
import { connectDoor, createDialogueScreen, createScreenStack } from "@voidcore/door-sdk";

const screens = createScreenStack();

const door = connectDoor({
  manifest: { /* ... */ },
  async onAttach(session) {
    await screens.setRoot(session, {
      async onEnter(session) {
        session.paintText(["Bar floor", "Type `talk barkeep`"]);
        session.prompt({ label: ">", maxLength: 80 });
      },
      async onLine(session, text, stack) {
        if (text === "talk barkeep") {
          await stack.push(session, createDialogueScreen({
            id: "barkeep-dialogue",
            promptLabel: "barkeep:",
            thinking: (_session, ctx) => ctx.isInitial ? "opening barkeep" : "waiting on barkeep",
            async open() {
              return {
                entries: [{ label: "BARKEEP", text: "You look thirsty, stranger." }],
                note: "Type to reply. Esc walks away."
              };
            },
            async reply(_session, input) {
              return {
                entries: [{ label: "BARKEEP", text: `That's one way to say '${input}'.` }]
              };
            },
            render(_session, view) {
              return [
                { row: 0, spans: [{ text: "BAR://Barkeep", fg: "bright-yellow", bg: "blue", bold: true }] },
                { row: 1, spans: [{ text: "", fg: "default" }] },
                ...view.transcript.flatMap((entry, index) => ([
                  { row: index * 3 + 2, spans: [{ text: entry.label, fg: "bright-yellow", bold: true }] },
                  { row: index * 3 + 3, spans: [{ text: entry.text, fg: "default" }] },
                  { row: index * 3 + 4, spans: [{ text: "", fg: "default" }] }
                ]))
              ];
            }
          }), "conversation");
          return true;
        }
      }
    });
  },
  onLine: (session, text, payload, client) => screens.dispatchLine(session, text, payload, client),
  onKey: (session, key, mods, payload, client) => screens.dispatchKey(session, key, mods, payload, client)
});
```

Option-screen helper:

```js
import { createOptionScreen } from "@voidcore/door-sdk/ui";

const menu = createOptionScreen({
  id: "dock-menu",
  promptLabel: "dock:",
  options: [
    { key: "1", label: "Take ferry", aliases: ["ferry"], action: async (session) => session.notify("Ferry bell rings.") },
    { key: "2", label: "Check jobs", aliases: ["jobs"], action: async (session) => session.notify("The board is full of bad ideas.") }
  ],
  render(_session, view) {
    return [
      { row: 0, spans: [{ text: "DOCK://OPTIONS", fg: "bright-cyan", bg: "blue", bold: true }] },
      { row: 1, spans: [{ text: "[1] Take ferry", fg: "default" }] },
      { row: 2, spans: [{ text: "[2] Check jobs", fg: "default" }] },
      ...(view.note ? [{ row: 4, spans: [{ text: view.note, fg: "bright-yellow" }] }] : [])
    ];
  }
});
```

Async option-board loading:

```js
import { createOptionScreen } from "@voidcore/door-sdk";

const jobs = createOptionScreen({
  id: "jobs-board",
  promptLabel: "jobs:",
  options: [{ key: "-", label: "loading...", action: async () => {} }],
  loading: {
    message: (_session, { frame }) => `oracle opening job board ${frame}`
  },
  async loadOptions(session) {
    const result = await session.storage.shared.get("jobs");
    return (result?.value?.jobs ?? []).map((job, index) => ({
      key: String(index + 1),
      label: job.title,
      aliases: [job.id],
      action: async () => session.notify(`picked ${job.title}`)
    }));
  },
  render(_session, view) {
    return [
      { row: 0, spans: [{ text: "OPS://BOARD", fg: "bright-cyan", bg: "blue", bold: true }] },
      ...view.options.map((option, index) => ({
        row: index + 1,
        spans: [{ text: `[${option.key}] ${option.label}`, fg: "default" }]
      })),
      ...(view.note ? [{ row: view.options.length + 2, spans: [{ text: view.note, fg: "bright-yellow" }] }] : [])
    ];
  }
});
```

LLM example:

```js
const reply = await session.llm.chat([
  { role: "system", content: "You are a terse noir terminal." },
  { role: "user", content: "Summarise the alley scene." }
]);

await session.llm.stream(
  [{ role: "user", content: "Write a moody room intro." }],
  { onToken: (token) => process.stdout.write(token) }
);
```

There is also a Python sibling SDK under `doors/sdk-python/` that mirrors the
same `voidcore-door-v1` model and screen helpers for non-Node doors.
