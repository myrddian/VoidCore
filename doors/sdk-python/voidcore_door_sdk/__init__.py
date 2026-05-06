from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Optional

import websockets


JsonDict = dict[str, Any]
AsyncCallback = Callable[..., Awaitable[Any]]
PROTOCOL_VERSION = "voidcore-door-v1"


def connect_door(**options: Any) -> "DoorClient":
    return DoorClient(**options)


def create_envelope(
    *,
    id: Optional[str] = None,
    type: str,
    payload: Optional[JsonDict] = None,
    protocol_version: str = PROTOCOL_VERSION,
    seq: int = 0,
    mac: Optional[str] = None,
) -> JsonDict:
    return {
        "id": id,
        "type": type,
        "protocol_version": protocol_version,
        "seq": seq,
        "mac": mac,
        "payload": payload or {},
    }


def create_screen_stack() -> "ScreenStack":
    return ScreenStack()


def rows_from_lines(
    lines: list[str],
    *,
    fg: str = "default",
    bg: Optional[str] = None,
    bold: Optional[bool] = None,
    start_row: int = 0,
) -> list[JsonDict]:
    return [
        {
            "row": start_row + index,
            "spans": [{"text": text, "fg": fg, "bg": bg, "bold": bold}],
        }
        for index, text in enumerate(lines)
    ]


def start_frame_animation(
    *,
    frames: list[str] | tuple[str, ...] = ("-", "\\", "|", "/"),
    interval_ms: int = 180,
    on_frame: Callable[[str, int], Any],
) -> Callable[[], None]:
    if not callable(on_frame):
        raise ValueError("start_frame_animation requires on_frame")

    task = asyncio.create_task(_run_frame_animation(list(frames), interval_ms, on_frame))

    def stop() -> None:
        task.cancel()

    return stop


async def _run_frame_animation(
    frames: list[str],
    interval_ms: int,
    on_frame: Callable[[str, int], Any],
) -> None:
    index = 0
    try:
        while True:
            await _maybe_await(on_frame(frames[index % len(frames)], index))
            index += 1
            await asyncio.sleep(interval_ms / 1000.0)
    except asyncio.CancelledError:
        return


class ScreenStack:
    def __init__(self) -> None:
        self._stacks: dict[int, list[Any]] = {}

    def current(self, session: "DoorSession") -> Any | None:
        stack = self._stacks.get(id(session), [])
        return stack[-1] if stack else None

    async def set_root(self, session: "DoorSession", screen: Any) -> Any:
        self._stacks[id(session)] = [screen]
        await _call_screen(screen, "on_enter", session, self, None, "root")
        return screen

    async def push(self, session: "DoorSession", screen: Any, reason: str = "push") -> Any:
        stack = self._ensure(session)
        previous = stack[-1] if stack else None
        stack.append(screen)
        await _call_screen(screen, "on_enter", session, self, previous, reason)
        return screen

    async def pop(self, session: "DoorSession", reason: str = "pop") -> bool:
        stack = self._ensure(session)
        if len(stack) <= 1:
            return False
        exiting = stack.pop()
        nxt = stack[-1] if stack else None
        await _call_screen(exiting, "on_exit", session, self, nxt, reason)
        await _call_screen(nxt, "on_resume", session, self, exiting, reason)
        return True

    async def replace(self, session: "DoorSession", screen: Any, reason: str = "replace") -> Any:
        stack = self._ensure(session)
        previous = stack.pop() if stack else None
        await _call_screen(previous, "on_exit", session, self, screen, reason)
        stack.append(screen)
        await _call_screen(screen, "on_enter", session, self, previous, reason)
        return screen

    async def dispatch_line(
        self,
        session: "DoorSession",
        text: str,
        payload: JsonDict | None = None,
        client: "DoorClient" | None = None,
    ) -> bool:
        screen = self.current(session)
        if screen is None:
            return False
        handled = await _call_screen(screen, "on_line", session, text, self, payload, client)
        return handled is not False

    async def dispatch_key(
        self,
        session: "DoorSession",
        key: str,
        modifiers: list[str] | None = None,
        payload: JsonDict | None = None,
        client: "DoorClient" | None = None,
    ) -> bool:
        screen = self.current(session)
        if screen is None:
            return False
        handled = await _call_screen(screen, "on_key", session, key, modifiers or [], self, payload, client)
        return handled is not False

    def _ensure(self, session: "DoorSession") -> list[Any]:
        return self._stacks.setdefault(id(session), [])


@dataclass
class DoorSession:
    client: "DoorClient"
    id: str
    user_id: Optional[str] = None
    handle: str = "visitor"
    role: str = "USER"
    mode: str = "normal"
    viewport: JsonDict = field(default_factory=lambda: {"cols": 80, "rows": 40})
    preferences: JsonDict = field(default_factory=dict)
    attach_reason: str = "fresh"
    data: JsonDict = field(default_factory=dict)
    clock: JsonDict = field(default_factory=lambda: {"unixTimeSec": None, "receivedAtMs": None})

    def __post_init__(self) -> None:
        self.storage = _StorageFacade(self.client, self.id)
        self.llm = _LlmFacade(self.client, self.id)

    async def paint(self, rows: list[JsonDict], viewport_id: str = "main") -> None:
        await self.client.send(
            "paint",
            {
                "session_id": self.id,
                "viewport_id": viewport_id,
                "rows": rows,
            },
        )

    async def paint_text(self, lines: list[str], **options: Any) -> None:
        await self.paint(rows_from_lines(lines, **options))

    async def prompt(
        self,
        *,
        mode: str = "line",
        label: Optional[str] = None,
        max_length: Optional[int] = None,
        valid_keys: Optional[list[str]] = None,
    ) -> None:
        payload: JsonDict = {"session_id": self.id, "mode": mode}
        if label is not None:
            payload["label"] = label
        if max_length is not None:
            payload["max_length"] = max_length
        if valid_keys is not None:
            payload["valid_keys"] = valid_keys
        await self.client.send("prompt", payload)

    async def notify(self, text: str, *, level: str = "info", duration_ms: int = 2500) -> None:
        await self.client.send(
            "notify",
            {
                "session_id": self.id,
                "level": level,
                "text": text,
                "duration_ms": duration_ms,
            },
        )

    async def effect(self, kind: str, params: Optional[JsonDict] = None) -> None:
        await self.client.send(
            "effect",
            {
                "session_id": self.id,
                "kind": kind,
                "params": params or {},
            },
        )

    async def detach(self, reason: str = "completed") -> None:
        await self.client.send(
            "detach",
            {
                "session_id": self.id,
                "reason": reason,
            },
        )
        self.client.sessions.pop(self.id, None)


class _StorageScope:
    def __init__(self, client: "DoorClient", session_id: str, scope: str) -> None:
        self.client = client
        self.session_id = session_id
        self.scope = scope

    async def get(self, key: str) -> JsonDict | None:
        return await self.client.request(
            "storage.get",
            {
                "session_id": self.session_id,
                "scope": self.scope,
                "key": key,
            },
        )

    async def put(self, key: str, value: Any, expected_version: Optional[str] = None) -> JsonDict:
        payload: JsonDict = {
            "session_id": self.session_id,
            "scope": self.scope,
            "key": key,
            "value": value,
        }
        if expected_version is not None:
            payload["expected_version"] = expected_version
        return await self.client.request("storage.put", payload)

    async def delete(self, key: str) -> bool:
        return await self.client.request(
            "storage.del",
            {
                "session_id": self.session_id,
                "scope": self.scope,
                "key": key,
            },
        )

    async def scan(self, prefix: str = "", *, cursor: Optional[str] = None, limit: int = 20) -> JsonDict:
        payload: JsonDict = {
            "session_id": self.session_id,
            "scope": self.scope,
            "prefix": prefix,
            "limit": limit,
        }
        if cursor is not None:
            payload["cursor"] = cursor
        return await self.client.request("storage.scan", payload)


class _StorageFacade:
    def __init__(self, client: "DoorClient", session_id: str) -> None:
        self.user = _StorageScope(client, session_id, "user")
        self.shared = _StorageScope(client, session_id, "shared")
        self.global_ = _StorageScope(client, session_id, "global")


class _LlmFacade:
    def __init__(self, client: "DoorClient", session_id: str) -> None:
        self.client = client
        self.session_id = session_id

    async def chat(
        self,
        messages: list[JsonDict],
        *,
        temperature: float = 0.7,
        system: Optional[str] = None,
        prompt: Optional[str] = None,
    ) -> JsonDict:
        return await self.client.request("llm.chat", _llm_payload(self.session_id, messages, temperature, system, prompt))

    async def stream(
        self,
        messages: list[JsonDict],
        *,
        temperature: float = 0.7,
        on_token: Optional[Callable[[str], Any]] = None,
        system: Optional[str] = None,
        prompt: Optional[str] = None,
    ) -> JsonDict:
        return await self.client.request_with_tokens(
            "llm.stream",
            _llm_payload(self.session_id, messages, temperature, system, prompt),
            on_token,
        )


class DoorClient:
    def __init__(
        self,
        *,
        url: Optional[str] = None,
        manifest: JsonDict,
        on_welcome: Optional[AsyncCallback] = None,
        on_envelope: Optional[AsyncCallback] = None,
        on_attach: Optional[AsyncCallback] = None,
        on_line: Optional[AsyncCallback] = None,
        on_key: Optional[AsyncCallback] = None,
        on_time: Optional[AsyncCallback] = None,
        on_detach: Optional[AsyncCallback] = None,
        on_shutdown: Optional[AsyncCallback] = None,
        logger: Optional[logging.Logger] = None,
        reconnect_delay_ms: int = 3000,
    ) -> None:
        if not manifest or not manifest.get("door_id"):
            raise ValueError("manifest.door_id is required")
        self.url = url or "ws://localhost:8080/ws/door"
        self.manifest = manifest
        self._on_welcome_cb = on_welcome
        self._on_envelope_cb = on_envelope
        self._on_attach_cb = on_attach
        self._on_line_cb = on_line
        self._on_key_cb = on_key
        self._on_time_cb = on_time
        self._on_detach_cb = on_detach
        self._on_shutdown_cb = on_shutdown
        self.logger = logger or logging.getLogger("voidcore_door_sdk")
        self.reconnect_delay_ms = reconnect_delay_ms
        self.sessions: dict[str, DoorSession] = {}
        self.pending: dict[str, JsonDict] = {}
        self.door_session_token: Optional[str] = None
        self.ws: Any = None
        self.closed = False
        self._inflight_tasks: set[asyncio.Task[Any]] = set()

    def on_welcome(self, func: AsyncCallback) -> AsyncCallback:
        self._on_welcome_cb = func
        return func

    def on_envelope(self, func: AsyncCallback) -> AsyncCallback:
        self._on_envelope_cb = func
        return func

    def on_attach(self, func: AsyncCallback) -> AsyncCallback:
        self._on_attach_cb = func
        return func

    def on_line(self, func: AsyncCallback) -> AsyncCallback:
        self._on_line_cb = func
        return func

    def on_key(self, func: AsyncCallback) -> AsyncCallback:
        self._on_key_cb = func
        return func

    def on_time(self, func: AsyncCallback) -> AsyncCallback:
        self._on_time_cb = func
        return func

    def on_detach(self, func: AsyncCallback) -> AsyncCallback:
        self._on_detach_cb = func
        return func

    def on_shutdown(self, func: AsyncCallback) -> AsyncCallback:
        self._on_shutdown_cb = func
        return func

    async def send(self, msg_type: str, payload: JsonDict, msg_id: Optional[str] = None) -> None:
        if self.ws is None:
            raise RuntimeError("door websocket is not connected")
        await self.send_envelope(create_envelope(id=msg_id, type=msg_type, payload=payload))

    async def send_envelope(self, envelope: JsonDict) -> None:
        if self.ws is None:
            raise RuntimeError("door websocket is not connected")
        await self.ws.send(json.dumps(envelope))

    async def close(self) -> None:
        self.closed = True
        if self.ws is not None:
            await self.ws.close()

    async def run(self) -> None:
        while not self.closed:
            try:
                async with websockets.connect(self.url, subprotocols=[PROTOCOL_VERSION]) as ws:
                    self.ws = ws
                    self.logger.info("door websocket connected")
                    await self.send(
                        "hello",
                        {
                            "door_id": self.manifest["door_id"],
                            "version": self.manifest.get("version", "0.0.0"),
                            "manifest": self.manifest,
                        },
                    )
                    async for raw in ws:
                        self._spawn_inflight(self.handle_message(raw))
            except asyncio.CancelledError:
                raise
            except Exception as error:
                self.logger.error("door websocket error %s", error)
            finally:
                await self._handle_disconnect()
            if not self.closed:
                self.logger.info("reconnecting door websocket to %s", self.url)
                await asyncio.sleep(self.reconnect_delay_ms / 1000.0)

    async def handle_message(self, raw: str | bytes) -> None:
        env = json.loads(_read_message_data(raw))
        payload = env.get("payload") or {}

        if env.get("id") and env["id"] in self.pending:
            if await self._resolve_pending(env):
                return

        if self._on_envelope_cb:
            handled = await _maybe_await(self._on_envelope_cb(env, self))
            if handled is True:
                return

        msg_type = env.get("type")
        if msg_type == "welcome":
            self.door_session_token = payload.get("door_session_token")
            await _maybe_await(self._on_welcome_cb(payload, self) if self._on_welcome_cb else None)
            return
        if msg_type == "manifest_rejected":
            raise RuntimeError(payload.get("reason", "door manifest rejected"))
        if msg_type == "attach":
            await self._handle_attach(payload)
            return
        if msg_type == "input.line":
            session = self.sessions.get(payload.get("session_id"))
            if session and self._on_line_cb:
                await _maybe_await(self._on_line_cb(session, payload.get("text", ""), payload, self))
            return
        if msg_type == "input.key":
            session = self.sessions.get(payload.get("session_id"))
            if session and self._on_key_cb:
                await _maybe_await(
                    self._on_key_cb(
                        session,
                        payload.get("key", ""),
                        payload.get("modifiers", []),
                        payload,
                        self,
                    )
                )
            return
        if msg_type == "time.tick":
            await self._handle_time_tick(payload)
            return
        if msg_type == "detach":
            await self._handle_detach(payload)
            return
        if msg_type == "shutdown":
            await self._handle_shutdown(payload)
            return

    async def request(self, msg_type: str, payload: JsonDict) -> Any:
        msg_id = str(uuid.uuid4())
        loop = asyncio.get_running_loop()
        future: asyncio.Future[Any] = loop.create_future()
        self.pending[msg_id] = {"future": future}
        await self.send(msg_type, payload, msg_id)
        return await future

    async def request_with_tokens(
        self,
        msg_type: str,
        payload: JsonDict,
        on_token: Optional[Callable[[str], Any]],
    ) -> Any:
        msg_id = str(uuid.uuid4())
        loop = asyncio.get_running_loop()
        future: asyncio.Future[Any] = loop.create_future()
        self.pending[msg_id] = {"future": future, "on_token": on_token}
        await self.send(msg_type, payload, msg_id)
        return await future

    async def _resolve_pending(self, env: JsonDict) -> bool:
        pending = self.pending.get(env["id"])
        if pending is None:
            return False
        msg_type = env.get("type")
        payload = env.get("payload") or {}
        future: asyncio.Future[Any] = pending["future"]
        if msg_type == "storage.value":
            self.pending.pop(env["id"], None)
            future.set_result(
                {
                    "found": True,
                    "scope": payload.get("scope"),
                    "key": payload.get("key"),
                    "value": payload.get("value"),
                    "version": payload.get("version"),
                }
            )
            return True
        if msg_type == "storage.miss":
            self.pending.pop(env["id"], None)
            future.set_result(None)
            return True
        if msg_type == "storage.put_ok":
            self.pending.pop(env["id"], None)
            future.set_result(
                {
                    "key": payload.get("key"),
                    "scope": payload.get("scope"),
                    "version": payload.get("version"),
                }
            )
            return True
        if msg_type == "storage.put_conflict":
            self.pending.pop(env["id"], None)
            error = RuntimeError(f"storage put conflict on {payload.get('key')}")
            setattr(error, "code", "STORAGE_CONFLICT")
            setattr(error, "current_version", payload.get("current_version"))
            future.set_exception(error)
            return True
        if msg_type == "storage.del_ok":
            self.pending.pop(env["id"], None)
            future.set_result(True)
            return True
        if msg_type == "storage.scan_page":
            self.pending.pop(env["id"], None)
            future.set_result({"entries": payload.get("entries", []), "cursor": payload.get("cursor")})
            return True
        if msg_type == "llm.delta":
            on_token = pending.get("on_token")
            if on_token:
                await _maybe_await(on_token(payload.get("content", "")))
            return True
        if msg_type == "llm.result":
            self.pending.pop(env["id"], None)
            future.set_result(
                {
                    "content": payload.get("content", ""),
                    "finish_reason": payload.get("finish_reason"),
                    "usage": payload.get("usage"),
                }
            )
            return True
        if msg_type in {"llm.error", "error"}:
            self.pending.pop(env["id"], None)
            error = RuntimeError(payload.get("message", "door protocol error"))
            setattr(error, "code", payload.get("code", "INTERNAL"))
            setattr(error, "details", payload.get("details"))
            future.set_exception(error)
            return True
        return False

    async def _reject_all_pending(self, error: Exception) -> None:
        for pending in self.pending.values():
            future: asyncio.Future[Any] = pending["future"]
            if not future.done():
                future.set_exception(error)
        self.pending.clear()

    async def _handle_attach(self, payload: JsonDict) -> None:
        session_id = payload["session_id"]
        session = self.sessions.get(session_id)
        if session is None:
            session = DoorSession(self, session_id)
            self.sessions[session_id] = session
        session.user_id = payload.get("user_id")
        session.handle = payload.get("handle", "visitor")
        session.role = payload.get("role", "USER")
        session.mode = payload.get("mode", "normal")
        session.viewport = payload.get("viewport") or {"cols": 80, "rows": 40}
        session.preferences = payload.get("preferences") or {}
        session.attach_reason = payload.get("reason", "fresh")
        await self.send("ready", {"session_id": session_id})
        await _maybe_await(self._on_attach_cb(session, payload, self) if self._on_attach_cb else None)

    async def _handle_time_tick(self, payload: JsonDict) -> None:
        session = self.sessions.get(payload.get("session_id"))
        if session is None:
            return
        session.clock = {
            "unixTimeSec": payload.get("unix_time_sec"),
            "receivedAtMs": int(time.time() * 1000),
        }
        if self._on_time_cb:
            await _maybe_await(self._on_time_cb(session, session.clock, payload, self))

    async def _handle_detach(self, payload: JsonDict) -> None:
        session = self.sessions.get(payload.get("session_id"))
        if session is None:
            return
        await _maybe_await(
            self._on_detach_cb(session, payload.get("reason", "user_left"), payload, self)
            if self._on_detach_cb
            else None
        )
        self.sessions.pop(session.id, None)

    async def _handle_shutdown(self, payload: JsonDict) -> None:
        await _maybe_await(self._on_shutdown_cb(payload, self) if self._on_shutdown_cb else None)
        await self.send("goodbye", {})
        await self.close()

    async def _handle_disconnect(self) -> None:
        self.ws = None
        self.sessions.clear()
        for task in list(self._inflight_tasks):
            task.cancel()
        self._inflight_tasks.clear()
        await self._reject_all_pending(RuntimeError("door websocket closed"))

    def _spawn_inflight(self, coro: Awaitable[Any]) -> None:
        task = asyncio.create_task(coro)
        self._inflight_tasks.add(task)

        def _done(done_task: asyncio.Task[Any]) -> None:
            self._inflight_tasks.discard(done_task)
            try:
                done_task.result()
            except asyncio.CancelledError:
                return
            except Exception as error:
                self.logger.exception("door message handler failed: %s", error)

        task.add_done_callback(_done)


def create_dialogue_screen(
    *,
    id: str,
    prompt_label: str | Callable[[DoorSession], str] = "talk:",
    history_limit: int = 40,
    exit_commands: list[str] | tuple[str, ...] = ("leave", "walk", "back", "bye", "exit"),
    initial_lead: Optional[str] = None,
    initial_note: str = "The line opens.",
    blank_input_note: str = "Say something, or hit Esc to step away.",
    player_entry: Callable[[DoorSession, str], JsonDict] = None,
    thinking: Optional[Callable[[DoorSession, JsonDict], str] | str] = None,
    open: Callable[[DoorSession], Awaitable[JsonDict]],
    reply: Callable[[DoorSession, str], Awaitable[JsonDict]],
    render: Callable[[DoorSession, JsonDict], list[JsonDict]],
    on_scene: Optional[Callable[[DoorSession, JsonDict, JsonDict, JsonDict], Awaitable[Any]]] = None,
    on_exit: Optional[Callable[[DoorSession, JsonDict, JsonDict], Awaitable[Any]]] = None,
):
    if player_entry is None:
        player_entry = _default_player_entry

    class DialogueScreen:
        def __init__(self) -> None:
            self.id = id
            self.transcript: list[JsonDict] = []
            self.lead = initial_lead
            self.note = initial_note
            self.stop_animation: Optional[Callable[[], None]] = None

        async def _paint(self, session: DoorSession, overrides: Optional[JsonDict] = None) -> None:
            overrides = overrides or {}
            await session.paint(
                render(
                    session,
                    {
                        "transcript": self.transcript,
                        "lead": self.lead,
                        "note": overrides.get("note", self.note),
                        "thinking": overrides.get("thinking"),
                    },
                )
            )
            await session.prompt(label=_resolve_prompt_label(prompt_label, session), max_length=160)

        def _append_entries(self, entries: list[JsonDict]) -> None:
            for entry in entries:
                if entry and entry.get("text"):
                    self.transcript.append(entry)
            if len(self.transcript) > history_limit:
                self.transcript = self.transcript[-history_limit:]

        def _close_animation(self) -> None:
            if self.stop_animation:
                self.stop_animation()
                self.stop_animation = None

        def _start_thinking(self, session: DoorSession, context: JsonDict) -> Optional[Callable[[], None]]:
            if thinking is None:
                return None
            base = thinking(session, context) if callable(thinking) else str(thinking)

            async def on_frame(frame: str, _index: int) -> None:
                await self._paint(session, {"thinking": f"{base} {frame}"})

            return start_frame_animation(on_frame=on_frame)

        async def _apply_scene(self, session: DoorSession, scene: JsonDict, context: JsonDict) -> None:
            self._append_entries(scene.get("entries", []))
            if scene.get("lead"):
                self.lead = scene["lead"]
            if scene.get("note"):
                self.note = scene["note"]
            if on_scene:
                await _maybe_await(on_scene(session, scene, {"transcript": self.transcript, "lead": self.lead, "note": self.note}, context))
            await self._paint(session)

        async def _run_scene(self, session: DoorSession, producer: Callable[[], Awaitable[JsonDict]], context: JsonDict) -> JsonDict:
            self.stop_animation = self._start_thinking(session, context)
            try:
                scene = await producer()
                self._close_animation()
                await self._apply_scene(session, scene, context)
                return scene
            except Exception:
                self._close_animation()
                raise

        async def on_enter(self, session: DoorSession, _stack: ScreenStack, _previous: Any, _reason: str) -> None:
            await self._run_scene(session, lambda: open(session), {"is_initial": True, "input": None})

        async def on_exit(self, session: DoorSession, stack: ScreenStack, next_screen: Any, reason: str) -> None:
            self._close_animation()
            if on_exit:
                await _maybe_await(on_exit(session, {"transcript": self.transcript, "lead": self.lead, "note": self.note}, {"stack": stack, "next": next_screen, "reason": reason}))

        async def on_line(self, session: DoorSession, text: str, stack: ScreenStack, *_args: Any) -> bool:
            input_text = str(text or "").strip()
            if not input_text:
                await self._paint(session, {"note": blank_input_note})
                return True
            if _normalize_choice(input_text) in [_normalize_choice(value) for value in exit_commands]:
                await stack.pop(session, "walk-away")
                return True
            self._append_entries([player_entry(session, input_text)])
            fallback_thinking = thinking(session, {"is_initial": False, "input": input_text}) if callable(thinking) else "oracle -"
            await self._paint(session, {"thinking": fallback_thinking})
            await self._run_scene(session, lambda: reply(session, input_text), {"is_initial": False, "input": input_text})
            return True

        async def on_key(self, session: DoorSession, key: str, _modifiers: list[str], stack: ScreenStack, *_args: Any) -> bool:
            if _is_escape_key(key):
                await stack.pop(session, "walk-away")
                return True
            return False

    return DialogueScreen()


def create_option_screen(
    *,
    id: str,
    prompt_label: str | Callable[[DoorSession], str] = "select:",
    prompt_mode: Optional[str] = None,
    options: list[JsonDict],
    render: Callable[[DoorSession, JsonDict], list[JsonDict]],
    initial_note: Optional[str] = None,
    on_unknown: Optional[Callable[[DoorSession, str, JsonDict], Awaitable[Any]]] = None,
    on_enter: Optional[Callable[[DoorSession, JsonDict], Awaitable[Any]]] = None,
    on_escape: Optional[Callable[[DoorSession, JsonDict], Awaitable[Any]]] = None,
    load_options: Optional[Callable[[DoorSession, JsonDict], Awaitable[list[JsonDict] | None]]] = None,
    loading: Optional[JsonDict | Callable[[DoorSession, JsonDict], str]] = None,
):
    if not options:
        raise ValueError("create_option_screen requires a non-empty options array")

    class OptionScreen:
        def __init__(self) -> None:
            self.id = id
            self.note = initial_note
            self.options = list(options)

        async def _paint(self, session: DoorSession, override: Optional[str] = None) -> None:
            await session.paint(render(session, {"options": self.options, "note": override if override is not None else self.note}))
            mode = self._resolved_prompt_mode()
            if mode == "key":
                await session.prompt(
                    mode="key",
                    label=_resolve_prompt_label(prompt_label, session),
                    valid_keys=self._valid_keys(),
                )
            else:
                await session.prompt(label=_resolve_prompt_label(prompt_label, session), max_length=80)

        def _set_options(self, next_options: list[JsonDict]) -> None:
            self.options[:] = list(next_options)

        def _set_note(self, next_note: Optional[str]) -> None:
            self.note = next_note

        def _resolved_prompt_mode(self) -> str:
            if prompt_mode:
                return prompt_mode
            keys = [str(option.get("key", "")).strip() for option in self.options]
            if keys and all(len(key) == 1 for key in keys):
                return "key"
            return "line"

        def _valid_keys(self) -> list[str]:
            keys = [str(option.get("key", "")).strip().upper() for option in self.options if str(option.get("key", "")).strip()]
            if "Q" not in keys:
                return keys
            return keys

        def _match_option(self, value: str) -> JsonDict | None:
            target = _normalize_choice(value)
            for option in self.options:
                aliases = [_normalize_choice(option.get("key")), *[_normalize_choice(a) for a in option.get("aliases", [])], _normalize_choice(option.get("label"))]
                if target in aliases:
                    return option
            return None

        async def on_enter(self, session: DoorSession, stack: ScreenStack, previous: Any, reason: str) -> None:
            ctx = {
                "stack": stack,
                "previous": previous,
                "reason": reason,
                "options": self.options,
                "note": self.note,
                "paint": self._paint,
                "set_options": self._set_options,
                "set_note": self._set_note,
            }
            stop_animation: Optional[Callable[[], None]] = None
            if load_options:
                if loading:
                    async def on_frame(frame: str, _index: int) -> None:
                        if callable(loading):
                            message = loading(session, {**ctx, "frame": frame})
                        else:
                            message_factory = loading.get("message")
                            if callable(message_factory):
                                message = message_factory(session, {**ctx, "frame": frame})
                            else:
                                message = f"{loading.get('message', 'loading')} {frame}"
                        await self._paint(session, message)

                    stop_animation = start_frame_animation(
                        frames=(loading.get("frames") if isinstance(loading, dict) else None) or ["-", "\\", "|", "/"],
                        interval_ms=(loading.get("interval_ms") if isinstance(loading, dict) else None) or 180,
                        on_frame=on_frame,
                    )
                try:
                    loaded_options = await load_options(session, ctx)
                    if isinstance(loaded_options, list):
                        self._set_options(loaded_options)
                finally:
                    if stop_animation:
                        stop_animation()
            if on_enter:
                await _maybe_await(on_enter(session, ctx))
            await self._paint(session)

        async def on_line(self, session: DoorSession, text: str, stack: ScreenStack, *_args: Any) -> bool:
            input_text = str(text or "").strip()
            option = self._match_option(input_text)
            if option is None:
                if on_unknown:
                    await _maybe_await(on_unknown(session, input_text, {"stack": stack, "options": self.options, "paint": self._paint}))
                else:
                    await self._paint(session, f"Unknown option: {input_text}")
                return True
            action = option.get("action")
            if action:
                await _maybe_await(action(session, {"stack": stack, "option": option, "options": self.options, "paint": self._paint}))
            return True

        async def on_key(self, session: DoorSession, key: str, _modifiers: list[str], stack: ScreenStack, *_args: Any) -> bool:
            if _is_escape_key(key):
                if on_escape:
                    await _maybe_await(on_escape(session, {"stack": stack, "options": self.options, "paint": self._paint}))
                return on_escape is not None
            option = self._match_option(key)
            if option is None:
                return False
            action = option.get("action")
            if action:
                await _maybe_await(action(session, {"stack": stack, "option": option, "options": self.options, "paint": self._paint}))
            return True

    return OptionScreen()


def _llm_payload(
    session_id: str,
    messages: list[JsonDict],
    temperature: float,
    system: Optional[str],
    prompt: Optional[str],
) -> JsonDict:
    payload: JsonDict = {"session_id": session_id, "temperature": temperature}
    if messages:
        payload["messages"] = messages
    else:
        if system is not None:
            payload["system"] = system
        if prompt is not None:
            payload["prompt"] = prompt
    return payload


def _resolve_prompt_label(value: str | Callable[[DoorSession], str], session: DoorSession) -> str:
    return value(session) if callable(value) else value


def _default_player_entry(session: DoorSession, text: str) -> JsonDict:
    return {
        "label": str(session.handle or "YOU").upper(),
        "text": text,
        "labelFg": "bright-cyan",
        "textFg": "white",
    }


async def _call_screen(screen: Any, method: str, *args: Any) -> Any:
    if screen is None:
        return None
    fn = getattr(screen, method, None)
    if fn is None:
        return None
    return await _maybe_await(fn(*args))


async def _maybe_await(value: Any) -> Any:
    if asyncio.isfuture(value) or asyncio.iscoroutine(value):
        return await value
    return value


def _read_message_data(raw: str | bytes) -> str:
    if isinstance(raw, bytes):
        return raw.decode("utf-8")
    return str(raw)


def _normalize_choice(value: Any) -> str:
    return "".join(ch for ch in str(value or "").strip().lower() if ch.isalnum())


def _is_escape_key(value: Any) -> bool:
    key = str(value or "").strip().lower()
    return key in {"escape", "esc"}
