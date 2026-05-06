from __future__ import annotations

import random
from typing import Any

from voidcore_door_sdk.ui import rows_from_lines


def _line(text: str, fg: str = "default", *, bg: str | None = None, bold: bool | None = None) -> dict[str, Any]:
    return {"text": text, "fg": fg, "bg": bg, "bold": bold}


def _rows(lines: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {"row": idx, "spans": [line]}
        for idx, line in enumerate(lines)
    ]


def render_banner(title: str, subtitle: str) -> list[dict[str, Any]]:
    return _rows([
        _line("REVENGE OF THE DRAGON", "bright-red", bold=True),
        _line(subtitle, "bright-yellow"),
        _line(""),
        _line(title, "bright-cyan", bg="blue", bold=True),
    ])


def render_main_menu(session, player: dict[str, Any], note: str | None = None) -> list[dict[str, Any]]:
    lines = [
        "REVENGE OF THE DRAGON",
        f"{player['handle']}  lvl {player['level']}  hp {player['hp']}/{player['max_hp']}  gold {player['gold']}  gems {player['gems']}",
        f"turns {player['turns']}  forest fights {player['forest_fights']}  dragon favor {player['dragon_favor']}",
        "-" * 72,
        "[1] Village",
        "[2] Forest",
        "[3] Character Sheet",
        "[4] Hall of Fame",
        "[5] Town Crier",
        "[6] Dragon Shrine",
        "[Q] Jack out",
    ]
    if note:
        lines.extend(["", note])
    return rows_from_lines(lines)


def render_location(
    session,
    player: dict[str, Any],
    location: dict[str, Any],
    options: list[dict[str, Any]],
    dragon_state: dict[str, Any] | None = None,
    note: str | None = None,
) -> list[dict[str, Any]]:
    def section(category: str) -> list[str]:
        return [f"[{option['key']}] {option['label']}" for option in options if option.get("category") == category]

    def hint_line() -> str:
        hints: list[str] = []
        if roads:
            hints.extend(["north/south/east/west", "back"])
        if locals_:
            hints.extend(["talk", "read", "listen"])
        if wilds:
            hints.extend(["venture"])
        hints.extend(["look", "study", "examine", "map", "sheet"])
        seen: list[str] = []
        for hint in hints:
            if hint not in seen:
                seen.append(hint)
        return "verbs: " + ", ".join(seen[:8])

    body = [
        f"{player['handle']}  lvl {player['level']}  hp {player['hp']}/{player['max_hp']}  gold {player['gold']}  gems {player['gems']}",
        f"turns {player['turns']}  fights {player['forest_fights']}  dragon favor {player['dragon_favor']}",
    ]
    if dragon_state:
        body.append(f"dragon: {dragon_state['stage_name']}  //  {dragon_state['weather']}")
        if dragon_state.get("last_slayer"):
            body.append(f"slayer: {dragon_state['last_slayer']}  //  total slays {dragon_state.get('slay_count', 0)}")
    roads = section("route")
    locals_ = section("local")
    wilds = section("wild")
    self_ = section("self")
    body.extend([
        "",
        f"{location['name']} // {location['kind']}",
        location["description"],
        "",
        f"sign: {random.choice(location.get('discoveries', ['the road keeps its own counsel here']))}",
    ])
    if roads:
        body.extend(["", "roads:", *roads])
    if locals_:
        body.extend(["", "here:", *locals_])
    if wilds:
        body.extend(["", "beyond:", *wilds])
    if self_:
        body.extend(["", "self:", *self_])
    body.extend([
        "",
        hint_line(),
    ])
    return render_simple_menu(f"{location['name'].upper()} // {location['kind'].upper()}", body, note)


def render_class_menu(_session, view: dict[str, Any]) -> list[dict[str, Any]]:
    rows = [
        {"row": 0, "spans": [_line("CHOOSE YOUR CALLING", "bright-cyan", bg="blue", bold=True)]},
        {"row": 1, "spans": [_line("A name becomes a story here. Pick how yours begins.", "bright-yellow")]},
    ]
    row = 3
    for option in view["options"]:
        rows.append({"row": row, "spans": [_line(f"[{option['key']}] {option['label']}", "default")]})
        row += 1
        if option.get("note"):
            rows.append({"row": row, "spans": [_line(f"    {option['note']}", "bright-black")]})
            row += 1
    if view.get("note"):
        rows.append({"row": row + 1, "spans": [_line(view["note"], "bright-yellow")]})
    return rows


def render_simple_menu(title: str, body: list[str], note: str | None = None) -> list[dict[str, Any]]:
    lines = [title, "-" * 72, *body]
    if note:
        lines.extend(["", note])
    return rows_from_lines(lines)


def render_oracle_wait(title: str, body: list[str], spinner_line: str) -> list[dict[str, Any]]:
    return render_simple_menu(title, [*body, "", spinner_line])


def render_sheet(player: dict[str, Any], note: str | None = None) -> list[dict[str, Any]]:
    body = [
        f"{player['handle']} the {player['class_name']}",
        f"Level {player['level']}  XP {player['xp']}",
        f"HP {player['hp']}/{player['max_hp']}",
        f"ATK {player['attack']}  DEF {player['defense']}  CHA {player['charm']}  CUN {player['cunning']}  LUCK {player['luck']}",
        f"Gold {player['gold']}  Gems {player['gems']}",
        f"Weapon {player['weapon_name']}  Armor {player['armor_name']}",
        f"Potions {player['potions']}",
        f"Curios {len(player.get('curios', []))}",
        f"Turns {player['turns']}  Forest fights {player['forest_fights']}",
        f"Victories {player['victories']}  Defeats {player['defeats']}  Dragon slays {player.get('dragon_slays', 0)}",
        f"Contracts completed {player.get('contracts_completed', 0)}",
        "",
        "[1] Open relic satchel",
        "[Q] Back",
    ]
    return render_simple_menu("CHARACTER SHEET", body, note)


def render_curios(curios: list[dict[str, Any]], title: str, note: str | None = None, *, show_values: bool = False) -> list[dict[str, Any]]:
    body = []
    if not curios:
        body.append("Your satchel is light. No relics or oddments ride with you.")
    else:
        for idx, curio in enumerate(curios, start=1):
            suffix = ""
            if show_values:
                suffix = f"  sell {curio['sale_gold']}g / shrine {curio['shrine_favor']}"
            body.append(f"[{idx}] {curio['name']}{suffix}")
            body.append(f"    {curio['blurb']}")
            body.append(f"    origin: {curio['origin']}")
    body.append("")
    body.append("[Q] Back")
    return render_simple_menu(title, body, note)


def render_hall(entries: list[dict[str, Any]], note: str | None = None) -> list[dict[str, Any]]:
    body = ["Hall of Fame"]
    if not entries:
        body.append("No names have carved themselves into the boards yet.")
    else:
        for idx, entry in enumerate(entries, start=1):
            body.append(
                f"{idx:>2}. {entry['handle']:<14} lvl {entry['level']:<2}  xp {entry['xp']:<4}  dragon {entry['dragon_favor']:<2}  slays {entry.get('dragon_slays', 0)}"
            )
    return render_simple_menu("HALL OF FAME", body, note)


def render_rumors(entries: list[str], note: str | None = None) -> list[dict[str, Any]]:
    body = ["Inn board whispers:"]
    if not entries:
        body.append("The tavern chalkboard is clean for once.")
    else:
        body.extend(f"- {entry}" for entry in entries)
    return render_simple_menu("RUMOR BOARD", body, note)


def render_crier(title: str, lines: list[str], note: str | None = None) -> list[dict[str, Any]]:
    return render_simple_menu(title, lines, note)


def render_contracts(contracts: list[dict[str, Any]], note: str | None = None) -> list[dict[str, Any]]:
    body = ["Road contracts posted for tonight:"]
    if not contracts:
        body.append("No one is paying for trouble tonight.")
    else:
        for idx, contract in enumerate(contracts, start=1):
            status = "claimed" if contract.get("claimed") else "complete" if contract.get("completed") else f"{contract.get('progress', 0)}/{contract.get('count', 1)}"
            body.append(f"[{idx}] {contract['title']} // {contract['branch_name']}")
            body.append(f"    {contract['objective']}")
            body.append(f"    {contract['issuer']}  reward {contract['reward_gold']}g / {contract['reward_xp']} xp / {contract['reward_favor']} favor  [{status}]")
    body.extend(["", "[Q] Back"])
    return render_simple_menu("ROAD CONTRACTS", body, note)


def render_dragon_chronicle(state: dict[str, Any], note: str | None = None) -> list[dict[str, Any]]:
    body = [
        f"{state['name']} {state['title']}",
        f"Stage {state['stage_name']}  //  {state['weather']}",
        f"Total slays on this board: {state.get('slay_count', 0)}",
    ]
    if state.get("last_slayer"):
        body.append(f"Last slayer: {state['last_slayer']} on {state.get('last_slay_day') or 'an older day'}")
    body.extend(["", "Recent dragon history:"])
    history = state.get("history", [])
    if not history:
        body.append("- The mountain keeps its own counsel.")
    else:
        for entry in history[:8]:
            if entry.get("type") == "slay":
                body.append(f"- {entry['slayer']} slew {entry['dragon']} on {entry['day']}.")
            else:
                body.append(f"- {entry.get('stage_name', 'Unknown')} by {entry.get('source', 'the wild roads')}.")
    return render_simple_menu("DRAGON CHRONICLE", body, note)


def render_npc_scene(title: str, lines: list[str], note: str | None = None) -> list[dict[str, Any]]:
    return render_simple_menu(title, lines, note)


def render_combat(player: dict[str, Any], monster: dict[str, Any], transcript: list[str], note: str | None = None) -> list[dict[str, Any]]:
    body = [
        f"{player['handle']}  hp {player['hp']}/{player['max_hp']}  potions {player['potions']}",
        f"{monster['name']}  hp {monster['hp']}/{monster['max_hp']}",
        "-" * 72,
        *transcript[-8:],
        "",
        "[1] Attack",
        "[2] Defend",
        "[3] Use Potion",
        "[4] Flee",
        "",
        "or type attack, defend, potion, flee",
    ]
    return render_simple_menu("FOREST COMBAT", body, note)
