# Python Door SDK

This package provides a small Python sidecar SDK for `voidcore-door-v1`.

Import shape:

- `voidcore_door_sdk.protocol` for the raw protocol client and envelope helpers
- `voidcore_door_sdk.ui` for higher-level screen, menu, and dialogue helpers
- `voidcore_door_sdk` still re-exports the combined surface for convenience

It mirrors the Node SDK surface closely:

- WebSocket registration (`hello` / `welcome`)
- multiplexed door sessions via `session_id`
- `ready`, `paint`, `prompt`, `notify`, `effect`, and `detach`
- BBS KV storage with async `get` / `put` / `delete` / `scan`
- BBS-mediated LLM calls with `session.llm.chat(...)` and `session.llm.stream(...)`
- optional server-driven `time.tick` updates with synced Unix time in seconds
- `ScreenStack`, `create_dialogue_screen(...)`, and `create_option_screen(...)`
- frame animation helpers for spinner-style waiting states

Raw protocol use:

```python
from voidcore_door_sdk.protocol import connect_door, create_envelope


client = connect_door(
    manifest={
        "door_id": "example-door",
        "name": "Example Door",
        "version": "0.1.0",
        "modes_supported": ["normal"],
        "default_mode": "normal",
        "capabilities": {"storage_kv": True},
    }
)


@client.on_time
async def handle_time(session, clock, _payload, _client):
    print("synced unix time", session.id, clock["unixTimeSec"])


@client.on_attach
async def handle_attach(session, _payload, _client):
    await client.send_envelope(
        create_envelope(
            type="notify",
            payload={"session_id": session.id, "level": "info", "text": "hello", "duration_ms": 1500},
        )
    )
```

Convenience/client use:

```python
import asyncio

from voidcore_door_sdk import connect_door


client = connect_door(
    manifest={
        "door_id": "example-door",
        "name": "Example Door",
        "version": "0.1.0",
        "authors": ["sysop"],
        "description": "Minimal Python sidecar example",
        "modes_supported": ["normal"],
        "default_mode": "normal",
        "capabilities": {
            "storage_kv": True,
            "llm": True,
            "notifications": True,
            "multi_session": True,
            "inter_session_messages": False,
            "user_handle_visible": True,
            "user_id_visible": True,
        },
    },
)


@client.on_attach
async def handle_attach(session, payload, _client):
    await session.paint_text(["", "  hello from python", ""])
    await session.prompt(label=">", max_length=80)


@client.on_line
async def handle_line(session, text, _payload, _client):
    await session.paint_text(["", f"  you typed: {text}", ""])
    await session.prompt(label=">", max_length=80)


asyncio.run(client.run())
```

Async option-board loading:

```python
from voidcore_door_sdk.ui import create_option_screen


jobs = create_option_screen(
    id="jobs-board",
    prompt_label="jobs:",
    options=[{"key": "-", "label": "loading..."}],
    loading={"message": lambda _session, ctx: f"oracle opening job board {ctx['frame']}"},
    load_options=lambda session, _ctx: load_jobs(session),
    render=lambda _session, view: [
        {"row": 0, "spans": [{"text": "OPS://BOARD", "fg": "bright_cyan", "bg": "blue", "bold": True}]},
        *[
            {"row": idx + 1, "spans": [{"text": f"[{option['key']}] {option['label']}", "fg": "default"}]}
            for idx, option in enumerate(view["options"])
        ],
    ],
)
```
