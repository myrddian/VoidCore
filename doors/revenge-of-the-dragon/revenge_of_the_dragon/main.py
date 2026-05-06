from __future__ import annotations

import asyncio
import json
import logging
import os
import random
from typing import Any

from voidcore_door_sdk import connect_door
from voidcore_door_sdk.ui import create_option_screen as sdk_create_option_screen, create_screen_stack, start_frame_animation

from .game import (
    advance_dragon_state,
    apply_daily_reset,
    buy_armor,
    buy_potion,
    buy_weapon,
    claim_contract_reward,
    choose_monster,
    combat_round,
    create_curio,
    create_default_dragon_state,
    create_player,
    current_day_key,
    dragon_progress_summary,
    ensure_daily_contracts,
    fallback_crier,
    heal_player,
    maybe_forest_event,
    normalize_dragon_state,
    progress_contracts,
    public_summary,
    reset_dragon_after_slay,
    spend_forest_turn,
)
from .render import (
    render_class_menu,
    render_combat,
    render_contracts,
    render_curios,
    render_dragon_chronicle,
    render_hall,
    render_location,
    render_npc_scene,
    render_oracle_wait,
    render_rumors,
    render_sheet,
    render_simple_menu,
)
from .world import ARMOR, BRANCH_ELITES, CLASSES, CONTRACT_TEMPLATES, CURIO_TEMPLATES, DRAGON_CAPSTONE, LOCATIONS, MONSTERS, VILLAGE_FIGURES, WEAPONS


DOOR_ID = "revenge-of-the-dragon"
PLAYER_KEY = "player"
RUMOR_KEY = "rumor-feed"
DRAGON_KEY = "dragon-state"
PUBLIC_PREFIX = "public-player:"
CRIER_PREFIX = "town-crier:"
screens = create_screen_stack()
logger = logging.getLogger("revenge_of_the_dragon")


def create_option_screen(*args, **kwargs):
    kwargs.setdefault("prompt_mode", "line")
    if "options" not in kwargs or kwargs["options"] is None:
        kwargs["options"] = [
            {
                "key": "Q",
                "label": "Back",
                "aliases": ["back"],
                "action": lambda s, _ctx: screens.pop(s, "back"),
            }
        ]
    return sdk_create_option_screen(*args, **kwargs)

EXAMINE_FIXTURES: dict[str, dict[str, list[str]]] = {
    "market_square": {
        "cistern": [
            "The cistern water sits dark and still.",
            "Coins at the bottom catch no luck now, only old cloudlight.",
        ],
        "banners": [
            "The banners were sewn over older burns in a hurry and with stubborn hands.",
            "Up close, the cloth still remembers heat.",
        ],
    },
    "criers_dais": {
        "notices": [
            "Most notices are warnings, debts, or promises dressed up as orders.",
            "The ink is fresh enough to stain your thumb if you let it.",
        ],
        "bell": [
            "The bell rope is polished dark by panic and ceremony in equal measure.",
            "It looks like something that has rung for fire more than celebration.",
        ],
    },
    "lantern_inn": {
        "chalkboard": [
            "The rumor board is a battlefield of chalk, crossed-out lies, and profitable truths.",
            "Someone has drawn a dragon's eye in one corner and then tried to rub it away.",
        ],
        "bench": [
            "The bench is carved with names, boasts, and insults that outlived the people who cut them.",
        ],
    },
    "healers_lane": {
        "bottles": [
            "The bottles catch lane-light in reds and yellows that look cheerful until you smell the iron under them.",
        ],
        "bandages": [
            "The drying bandages turn in the air like surrender flags no one accepted.",
        ],
    },
    "armorers_row": {
        "shields": [
            "The dented shields tell more honest stories than the men who once carried them.",
        ],
        "board": [
            "The soot-black board lists prices without apology.",
            "Steel is costly because being caught without it is costlier.",
        ],
    },
    "hall_steps": {
        "plaques": [
            "The brass plaques gleam harder the closer they get to the top.",
            "Half the names are heroes. The other half just survived long enough to be carved.",
        ],
        "chalk": [
            "Fresh chalk scratches at the edge of the official record, trying to become history before the rain can object.",
        ],
    },
    "north_road": {
        "markers": [
            "The mile markers are chipped and clawed, as if the road itself had something to say back.",
        ],
        "tracks": [
            "The wagon tracks thin into root-shadow and rumor.",
            "North always looks farther than it did yesterday.",
        ],
    },
    "forest_gate": {
        "charms": [
            "The charms are fresh-tied. Whatever they keep out, someone expects it soon.",
        ],
        "splinters": [
            "The gate wood is dark with old wet and older blood.",
            "It has been repaired too many times to trust.",
        ],
    },
    "old_wood_edge": {
        "antlers": [
            "The broken antlers caught in the cane look less like trophies than warnings.",
        ],
        "marker": [
            "The hunter's mark is cut deep and recent.",
            "Someone wanted even the trees to remember this place properly.",
        ],
    },
    "grave_cut": {
        "markers": [
            "The split grave markers lean like tired teeth over the cut.",
            "The names on them are mostly surrendered to moss.",
        ],
        "feathers": [
            "The trapped crow feathers tremble whenever the wind moves through the bank.",
        ],
    },
    "bandit_road": {
        "chain": [
            "The old toll chain lies in rust and mud, but the road still feels like it expects payment.",
        ],
        "prints": [
            "The boot prints overlap too often to count.",
            "This road belongs to people who prefer to meet travelers already surrounded.",
        ],
    },
    "ruined_watch": {
        "slit": [
            "The archer slit is packed with nettles and patience.",
            "You can still see how a watcher might have loved this field of fire.",
        ],
        "char": [
            "The char on the stair never fully dulled.",
            "This place burned hot and inward, like betrayal.",
        ],
    },
    "shrine_path": {
        "ribbons": [
            "The prayer ribbons are charred at the ends, but some fool or saint still ties new ones.",
        ],
        "pebbles": [
            "The white pebbles look arranged, then accidental, then arranged again.",
        ],
    },
    "dragon_shrine": {
        "glass": [
            "The glass eyes in the altar catch dawn and accusation with equal talent.",
        ],
        "altar": [
            "The altar stone is warm in places where no sun falls.",
            "People have leaned here and asked dangerous things.",
        ],
    },
    "ashen_pass": {
        "glass": [
            "The black glass pops under your boots with the tiny sound of old promises failing.",
        ],
        "watchfires": [
            "The bent ribs of the old watchfires still hold ash, as if the last sentries never quite got to leave.",
        ],
    },
    "ember_peak": {
        "landing": [
            "The landing shelf is gouged in arcs broad enough for talons and tribute carts alike.",
        ],
        "eggshell": [
            "The black glass shards are too thin for common stone. Something hatched or burned here under a worse sky.",
        ],
    },
}

PLACE_ACTIONS: dict[str, dict[str, dict[str, Any]]] = {
    "market_square": {
        "study stalls": {
            "aliases": ["study stalls", "stalls", "market"],
            "lines": [
                "You study the stalls until barter starts sounding like threat with better clothes on.",
                "The square is where every road lies about how safe it felt getting here.",
            ],
        },
    },
    "criers_dais": {
        "ring bell": {
            "aliases": ["ring", "ring bell", "bell"],
            "lines": [
                "You ring the crier's bell and the square turns its head as one.",
                "For a heartbeat it feels like the whole village is listening for your next mistake.",
            ],
        },
    },
    "lantern_inn": {
        "sit bench": {
            "aliases": ["sit", "sit bench", "bench"],
            "lines": [
                "You sit on the carved bench and read three names, two boasts, and one promise of revenge.",
                "The wood is warm with the kind of history that never makes it into plaques.",
            ],
        },
    },
    "ruined_watch": {
        "climb watch": {
            "aliases": ["climb", "climb watch", "watch", "climb tower"],
            "lines": [
                "You climb the broken watch and look out over a country that still expects warning.",
                "From up here the shrine path is a white scar and the pass above it looks newly awake.",
            ],
        },
    },
    "dragon_shrine": {
        "touch altar": {
            "aliases": ["touch altar", "altar", "touch stone"],
            "lines": [
                "You lay a hand on the altar and feel heat answer from deeper in the hill.",
                "The stone does not bless you. It simply remembers that you asked.",
            ],
        },
    },
    "bandit_road": {
        "search road": {
            "aliases": ["search", "search road", "road"],
            "lines": [
                "You search the wheel ruts and find where fear taught people to walk in the ditch instead.",
                "This road has never been empty, only selective about who gets to keep traveling it.",
            ],
        },
    },
    "grave_cut": {
        "search cut": {
            "aliases": ["search", "search cut", "cut"],
            "lines": [
                "You search the banks and find roots around stone, stone around names, and names around nothing good.",
                "The cut is full of the sort of memory that wants company.",
            ],
        },
    },
}

CONTRACT_SCENE_TEXT: dict[str, dict[str, list[str]]] = {
    "grave_quiet": {
        "event": [
            "A string of grave-charms turns in dead air and points you downhill.",
            "Whatever is fouling the Cut tonight is close enough to smell your breath.",
        ],
        "combat": [
            "The contract has brought you here for a reason. The dark in the Cut seems to know it too.",
        ],
    },
    "seal_recovery": {
        "event": [
            "A split marker shows fresh scrape where something sealed was dragged through the mud.",
            "If a grave house-mark is here tonight, the Cut is not done with it yet.",
        ],
    },
    "moon_glass_for_the_dead": {
        "event": [
            "Moonlight catches on something pale under the bank and then vanishes again.",
            "The dead here are keeping secrets in glass tonight.",
        ],
    },
    "break_the_toll": {
        "combat": [
            "You can feel the contract in the road under your boots. Somebody is about to collect the wrong toll.",
        ],
    },
    "tally_for_tally": {
        "event": [
            "A stripped willow stake nearby has fresh knife-marks on it.",
            "The road thieves have been counting something again, and you are close enough to interrupt the arithmetic.",
        ],
    },
    "road_coin_run": {
        "event": [
            "A devotional glint flashes from the ditch and vanishes beneath wheel-rutted water.",
            "Someone lost faith in a hurry here, or was relieved of it.",
        ],
    },
    "ash_reading": {
        "event": [
            "The ruin stair sweats black motes that gather around one hot scale of air.",
            "If the Watch has a message tonight, it is writing it in ember-dust.",
        ],
    },
    "thin_the_brood": {
        "combat": [
            "Heat crawls along the broken stone. The brood is close, and this contract finally has a face to blame.",
        ],
    },
    "glass_from_the_watch": {
        "event": [
            "Something pale gleams between ash pockets in the stairwell rubble.",
            "The Watch keeps old fire the way other places keep ghosts.",
        ],
    },
}

ASHEN_PASS_SCENES: list[dict[str, Any]] = [
    {
        "title": "Wind of scales",
        "text": [
            "A crosswind lifts old ash and drives it into scale-like patterns over the stone.",
            "When it clears, you know where the safer footholds are for a few more yards.",
        ],
        "advance": 1,
    },
    {
        "title": "Burned watchfire",
        "text": [
            "You kneel at a dead watchfire and find heat under the coals that should not still be there.",
            "Some earlier climber left in a hurry and left nerve behind with the ash.",
        ],
        "reward_gold": 9,
        "advance": 1,
    },
    {
        "title": "Prayer in slag",
        "text": [
            "A pilgrim knot has been pressed into black glass and fused there by hotter weather than prayer deserves.",
            "You free it and keep climbing with your heart talking louder than your boots.",
        ],
        "reward_favor": 1,
        "advance": 1,
    },
]


def manifest() -> dict[str, Any]:
    return {
        "door_id": DOOR_ID,
        "name": "Revenge of the Dragon",
        "version": "0.1.0",
        "authors": ["sysop"],
        "description": "Fantasy daily door focused on shared rumors, halls, and dragon prestige.",
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
    }


async def load_player(session) -> dict[str, Any] | None:
    logger.info("load-player-start handle=%s", session.handle)
    record = await session.storage.user.get(PLAYER_KEY)
    logger.info("load-player handle=%s found=%s", session.handle, bool(record))
    return normalize_player_record(record["value"]) if record else None


async def save_player(session, player: dict[str, Any]) -> None:
    player = normalize_player_record(player)
    logger.info(
        "save-player handle=%s level=%s gold=%s turns=%s forest_fights=%s",
        player["handle"], player["level"], player["gold"], player["turns"], player["forest_fights"]
    )
    await session.storage.user.put(PLAYER_KEY, player)
    await session.storage.shared.put(f"{PUBLIC_PREFIX}{player['handle'].lower()}", public_summary(player))


async def append_rumor(session, text: str) -> None:
    logger.info("append-rumor handle=%s text=%s", session.handle, text)
    record = await session.storage.shared.get(RUMOR_KEY)
    rumors = list((record or {}).get("value", []))
    rumors.insert(0, text)
    rumors = rumors[:20]
    await session.storage.shared.put(RUMOR_KEY, rumors)


async def load_rumors(session) -> list[str]:
    record = await session.storage.shared.get(RUMOR_KEY)
    return list((record or {}).get("value", []))


async def load_dragon_state(session) -> dict[str, Any]:
    record = await session.storage.shared.get(DRAGON_KEY)
    if record:
        logger.info("load-dragon-state handle=%s existing=true", session.handle)
        return normalize_dragon_state(record["value"])
    logger.info("load-dragon-state handle=%s existing=false create-default=true", session.handle)
    state = create_default_dragon_state()
    await session.storage.shared.put(DRAGON_KEY, state)
    return state


async def save_dragon_state(session, state: dict[str, Any]) -> None:
    state = normalize_dragon_state(state)
    logger.info("save-dragon-state handle=%s dragon=%s title=%s", session.handle, state["name"], state["title"])
    await session.storage.shared.put(DRAGON_KEY, state)


async def load_hall(session) -> list[dict[str, Any]]:
    page = await session.storage.shared.scan(PUBLIC_PREFIX, limit=50)
    entries = [entry["value"] for entry in page.get("entries", [])]
    entries.sort(
        key=lambda item: (
            -item.get("dragon_slays", 0),
            -item.get("level", 0),
            -item.get("xp", 0),
            -item.get("contracts_completed", 0),
            -item.get("dragon_favor", 0),
            item.get("handle", ""),
        )
    )
    return entries[:10]


def crier_cache_key(day: str, dragon_state: dict[str, Any]) -> str:
    band = min(int(dragon_state.get("pressure", 0)) // 3, 9)
    return f"{CRIER_PREFIX}{day}:{dragon_state.get('stage_id', 'slumber')}:{band}"


async def sync_dragon_state(session, state: dict[str, Any]) -> None:
    session.data["dragon_state"] = normalize_dragon_state(state)


async def adjust_dragon_state(
    session,
    *,
    pressure_delta: int = 0,
    omen_delta: int = 0,
    calm_delta: int = 0,
    source: str | None = None,
) -> dict[str, Any]:
    state = session.data.get("dragon_state") or await load_dragon_state(session)
    updated, shift_note = advance_dragon_state(
        state,
        pressure_delta=pressure_delta,
        omen_delta=omen_delta,
        calm_delta=calm_delta,
        source=source,
    )
    await save_dragon_state(session, updated)
    await sync_dragon_state(session, updated)
    if shift_note:
        await append_rumor(session, shift_note)
        session.data["resume_note"] = shift_note
    return updated


async def daily_reset_if_needed(session, player: dict[str, Any]) -> tuple[dict[str, Any], str | None]:
    player = normalize_player_record(player)
    updated, reset = apply_daily_reset(player, current_day_key(session))
    if reset:
        logger.info("daily-reset handle=%s", player["handle"])
        await append_rumor(session, f"{player['handle']} returns to the road at first light.")
        await save_player(session, updated)
        return updated, "A new day breaks. Your strength and turns return."
    return updated, None


async def oracle_content(
    session,
    system: str,
    prompt: str,
    *,
    wait_title: str,
    wait_body: list[str],
) -> str | None:
    stop_animation = start_frame_animation(
        on_frame=lambda frame, _index: session.paint(
            render_oracle_wait(wait_title, wait_body, f"oracle {frame}")
        )
    )
    try:
        logger.info("oracle-request handle=%s prompt-len=%s", session.handle, len(prompt))
        result = await session.llm.chat(
            [
                {"role": "system", "content": system},
                {"role": "user", "content": prompt},
            ],
            temperature=0.8,
        )
    except Exception as exc:
        logger.warning("oracle-fallback handle=%s reason=%s", session.handle, exc)
        return None
    finally:
        stop_animation()
    content = (result or {}).get("content", "").strip()
    if not content:
        logger.info("oracle-empty handle=%s fallback=true", session.handle)
        return None
    logger.info("oracle-success handle=%s content-len=%s", session.handle, len(content))
    return content


async def oracle_lines(
    session,
    system: str,
    prompt: str,
    fallback: list[str],
    *,
    wait_title: str,
    wait_body: list[str],
) -> list[str]:
    content = await oracle_content(
        session,
        system,
        prompt,
        wait_title=wait_title,
        wait_body=wait_body,
    )
    if not content:
        return fallback
    logger.info("oracle-success handle=%s lines=%s", session.handle, len(content.splitlines()))
    lines = [line.strip() for line in content.splitlines() if line.strip()]
    return lines[:8] if lines else fallback


def choose_curio_template(location: dict[str, Any]) -> list[str]:
    if location.get("curio_templates"):
        return list(location["curio_templates"])
    name = location["name"].lower()
    if "grave" in name:
        return ["grave_seal", "saint_coin", "moon_glass"]
    if "bandit" in name or "road" in name:
        return ["bandit_tally", "saint_coin", "moon_glass"]
    if "shrine" in name:
        return ["pilgrim_knot", "saint_coin", "wyrm_scale"]
    if "ruined" in name or "watch" in name:
        return ["wyrm_scale", "grave_seal", "bandit_tally"]
    return ["saint_coin", "moon_glass", "pilgrim_knot"]


def compose_wild_rumor(location: dict[str, Any], player: dict[str, Any], *, event: bool, monster_name: str | None = None) -> str:
    if event and location.get("rumor_event"):
        return location["rumor_event"].format(handle=player["handle"])
    if not event and location.get("rumor_victory"):
        return location["rumor_victory"].format(handle=player["handle"], monster=monster_name or "something ugly")
    if event:
        return f"{player['handle']} comes back from {location['name']} with luck on their side."
    return f"{player['handle']} slew a {monster_name or 'beast'} in the wild."


def active_branch_contract(player: dict[str, Any], branch_id: str) -> dict[str, Any] | None:
    contract = (((player.get("contracts") or {}).get("branches") or {}).get(branch_id))
    if not contract or contract.get("completed") or contract.get("claimed"):
        return None
    return contract


def elite_monster(monster_id: str) -> dict[str, Any]:
    template = next(monster for monster in MONSTERS if monster["id"] == monster_id)
    return {
        **template,
        "hp": template["max_hp"],
    }


async def maybe_special_wild_scene(session, location: dict[str, Any]) -> dict[str, Any] | None:
    player = session.data["player"]
    location_id = player["location"]
    if location_id == "ashen_pass" and random.random() < 0.45:
        scene = random.choice(ASHEN_PASS_SCENES)
        event = {"id": f"ashen-{scene['title'].lower().replace(' ', '-')}", "title": scene["title"], "text": " ".join(scene["text"])}
        if scene.get("reward_gold"):
            player["gold"] += int(scene["reward_gold"])
            event.setdefault("bonus_lines", []).append(f"You scrape up {scene['reward_gold']} gold worth of fire-blind offerings.")
        if scene.get("reward_favor"):
            player["dragon_favor"] += int(scene["reward_favor"])
            event.setdefault("bonus_lines", []).append(f"The mountain yields {scene['reward_favor']} dragon favor.")
        if scene.get("advance"):
            event.setdefault("bonus_lines", []).append(advance_dragon_approach(player))
        await save_player(session, player)
        return event

    contract = active_branch_contract(player, location_id)
    elites = BRANCH_ELITES.get(location_id, [])
    if elites and random.random() < 0.18:
        elite = random.choice(elites)
        return {
            "id": f"elite-{elite['monster_id']}",
            "title": elite["title"],
            "combat_monster": elite_monster(elite["monster_id"]),
            "text": " ".join(elite["scene"]),
        }
    if not contract or random.random() >= 0.33:
        return None
    scene_text = CONTRACT_SCENE_TEXT.get(contract["id"], {})
    event_lines = scene_text.get("event")
    combat_lines = scene_text.get("combat")
    if contract["goal_kind"] == "kill_family" and combat_lines:
        family = (contract.get("families") or [None])[0]
        matching = [entry for entry in elites if elite_monster(entry["monster_id"]).get("family") == family]
        if matching:
            elite = random.choice(matching)
            return {
                "id": f"contract-combat-{contract['id']}",
                "title": contract["title"],
                "combat_monster": elite_monster(elite["monster_id"]),
                "text": " ".join(combat_lines),
            }
    if contract["goal_kind"] == "recover_template" and event_lines:
        event = {
            "id": f"contract-{contract['id']}",
            "title": contract["title"],
            "text": " ".join(event_lines),
        }
        curio = await award_curio(
            session,
            "contract recovery",
            f"{player['handle']} follows the trail for contract {contract['title']} in {location['name']}.",
            template_ids_override=list(contract.get("template_ids", [])),
            forced=True,
        )
        if curio:
            event.setdefault("bonus_lines", []).extend([
                f"You recover {curio['name']} for the contract.",
                curio["blurb"],
            ])
        notes = progress_contracts(player, branch_id=location_id, curio=curio) if curio else []
        if notes:
            event.setdefault("bonus_lines", []).extend(notes)
            await save_player(session, player)
        return event
    return None


async def maybe_award_curio(session, source: str, summary: str) -> dict[str, Any] | None:
    return await award_curio(session, source, summary)


async def award_curio(
    session,
    source: str,
    summary: str,
    *,
    template_ids_override: list[str] | None = None,
    forced: bool = False,
) -> dict[str, Any] | None:
    player = session.data["player"]
    location = current_location(player)
    curio_chance = 1.0 if forced else 0.32 + (0.08 if player.get("class_id") == "hunter" else 0.0)
    if random.random() > curio_chance:
        return None
    template_ids = list(template_ids_override or choose_curio_template(location))
    template_listing = [
        {
            "template_id": template_id,
            "name": CURIO_TEMPLATES[template_id]["name"],
            "tags": CURIO_TEMPLATES[template_id]["tags"],
        }
        for template_id in template_ids
    ]
    content = await oracle_content(
        session,
        "You are a dark fantasy item oracle. Return JSON only. Either return "
        '{"template_id":"...", "name":"...", "blurb":"...", "rumor":"..."} '
        'using one of the allowed template_ids, or {"template_id": null} if nothing notable is found.',
        (
            f"Player: {player['handle']} the {player['class_name']}. "
            f"Location: {location['name']} ({location['kind']}). "
            f"Tone: {location.get('description')}. "
            f"Source: {source}. Summary: {summary}. "
            f"Allowed templates: {template_listing}."
        ),
        wait_title="RELIC SATCHEL",
        wait_body=[
            f"{player['handle']} searches {location['name']} for a true keepsake.",
            "The oracle is weighing whether this road leaves something behind.",
        ],
    )
    if not content:
        return None
    try:
        start = content.index("{")
        end = content.rindex("}") + 1
        proposal = json.loads(content[start:end])
    except Exception as exc:
        logger.warning("curio-proposal-invalid handle=%s reason=%s", session.handle, exc)
        return None
    template_id = proposal.get("template_id")
    if template_id not in CURIO_TEMPLATES:
        return None
    curio = create_curio(
        template_id,
        origin=location["name"],
        name=(proposal.get("name") or "").strip() or None,
        blurb=(proposal.get("blurb") or "").strip() or None,
    )
    player.setdefault("curios", []).append(curio)
    logger.info("curio-awarded handle=%s template=%s origin=%s", player["handle"], template_id, location["name"])
    await save_player(session, player)
    rumor = (proposal.get("rumor") or "").strip()
    if rumor:
        await append_rumor(session, rumor)
    branch_id = player.get("location")
    if branch_id in CONTRACT_TEMPLATES:
        notes = progress_contracts(player, branch_id=branch_id, curio=curio)
        if notes:
            session.data["resume_note"] = " ".join(notes)
            await save_player(session, player)
    return curio


async def listen_location(session, stack):
    player = session.data["player"]
    location = current_location(player)
    location_id = player["location"]
    if location_id == "criers_dais":
        await stack.push(session, town_crier_screen(), "listen-crier")
        return
    fallback = [
        f"You listen at {location['name']}.",
        "The place answers in weather, timber, cloth, and whatever the road is trying not to say aloud.",
    ]
    ambient = {
        "lantern_inn": [
            "You catch dice on wood, low laughter, and one rumor lowering its voice too late.",
            "The inn is never truly quiet; it only chooses who gets to hear it.",
        ],
        "grave_cut": [
            "The cut answers with soft feather-noise and the drag of roots under wet soil.",
            "It sounds like a graveyard remembering itself.",
        ],
        "ruined_watch": [
            "The ruin creaks in its broken joints and whispers ash down the stair.",
            "For a heartbeat it sounds like old sentries trading bad news.",
        ],
    }
    lines = ambient.get(location_id, fallback)
    await stack.push(session, static_text_screen(f"LISTENING // {location['name']}", lines), "listen")


async def pray_here(session, stack):
    location_id = session.data["player"]["location"]
    if location_id in {"dragon_shrine", "shrine_path"}:
        await stack.push(session, dragon_shrine_screen(), "pray")
        return
    await stack.push(
        session,
        static_text_screen(
            "A SMALL PRAYER",
            [
                "You give the road a quiet prayer.",
                "It takes the words, but does not promise to return them improved.",
            ],
        ),
        "pray",
    )


async def rest_here(session, stack):
    player = session.data["player"]
    location_id = player["location"]
    if location_id == "lantern_inn":
        if player["gold"] < 6:
            await paint_location(session, "A bench and warm broth cost 6 gold, and you do not have it.")
            return
        if player["hp"] >= player["max_hp"]:
            await paint_location(session, "You are already as rested as the inn can make you.")
            return
        player["gold"] -= 6
        healed = min(player["max_hp"] - player["hp"], 4)
        player["hp"] += healed
        await save_player(session, player)
        await stack.push(
            session,
            static_text_screen(
                "LANTERN INN YARD",
                [
                    f"You buy a bowl, a bench, and ten honest minutes.",
                    f"The rest returns {healed} hp and leaves you smelling faintly of broth and smoke.",
                ],
            ),
            "rest",
        )
        return
    await stack.push(
        session,
        static_text_screen(
            "NO REST HERE",
            [
                "You look for a safe corner and find only the road looking back.",
                "This is not a place that lets people sleep properly.",
            ],
        ),
        "rest",
    )


async def perform_place_action(session, stack, command: str):
    player = session.data["player"]
    location_id = player["location"]
    actions = PLACE_ACTIONS.get(location_id, {})
    needle = normalize_lookup(command)
    for action in actions.values():
        aliases = action.get("aliases", [])
        if needle in {normalize_lookup(alias) for alias in aliases}:
            lines = list(action["lines"])
            bonus_note = None
            if location_id == "ruined_watch" and action is actions.get("climb watch"):
                state = session.data.get("dragon_state") or {}
                if state.get("stage_id") in {"hunting", "shadowfall"}:
                    lines.append("Above the ridge, you can pick out the black rise of Ashen Pass under the dragon's weather.")
            if location_id == "dragon_shrine" and action is actions.get("touch altar"):
                ready, reason = dragon_capstone_ready(session)
                if ready:
                    lines.append("The altar answers with a hot pulse that points your bones toward the ash road.")
                elif reason:
                    lines.append(reason)
            if location_id == "bandit_road" and action is actions.get("search road"):
                player["gold"] += 4
                await save_player(session, player)
                bonus_note = "You also shake 4 forgotten road-gold loose from the ditch."
            if location_id == "grave_cut" and action is actions.get("search cut"):
                player["dragon_favor"] += 1
                await save_player(session, player)
                bonus_note = "Something in the dark under the bank approves. You gain 1 dragon favor."
            if bonus_note:
                lines.extend(["", bonus_note])
            await stack.push(session, static_text_screen(command.upper(), lines), f"place-action-{location_id}")
            return True
    return False


def normalize_world_command(raw: str) -> str:
    text = (raw or "").strip().lower()
    for prefix in ("go ", "walk ", "run ", "head ", "move ", "travel ", "enter ", "take ", "talk to ", "talk ", "speak to ", "speak "):
        if text.startswith(prefix):
            return text[len(prefix):].strip()
    return text


def normalize_lookup(text: str) -> str:
    return "".join(ch for ch in (text or "").strip().lower() if ch.isalnum())


def find_route(location: dict[str, Any], target: str) -> dict[str, Any] | None:
    needle = normalize_lookup(target)
    for route in location.get("_active_routes", location.get("routes", [])):
        aliases = [route["to"], route["label"], *route.get("aliases", [])]
        if needle in {normalize_lookup(alias) for alias in aliases}:
            return route
    return None


def examine_lines(location: dict[str, Any], target: str) -> list[str] | None:
    if not target:
        return None
    route = find_route(location, target)
    if route is not None:
        destination = LOCATIONS[route["to"]]
        return [
            f"That way lies {destination['name']}.",
            destination["description"],
        ]
    fixtures = EXAMINE_FIXTURES.get(next((key for key, value in LOCATIONS.items() if value is location), ""), {})
    needle = normalize_lookup(target)
    for alias, lines in fixtures.items():
        if needle == normalize_lookup(alias):
            return lines
    for discovery in location.get("discoveries", []):
        if needle and needle in normalize_lookup(discovery):
            return [discovery, "Up close, it gives the place away more than the people in it do."]
    return None


def current_location_id(player: dict[str, Any]) -> str:
    for key, value in LOCATIONS.items():
        if value is current_location(player):
            return key
    return player.get("location", "market_square")


async def read_here(session, stack, target: str | None = None):
    player = session.data["player"]
    location_id = current_location_id(player)
    needle = normalize_lookup(target or "")
    if location_id == "lantern_inn" and needle in {"", "board", "rumorboard", "chalkboard", "rumors"}:
        await stack.push(session, rumor_board_screen(), "read-rumor-board")
        return
    if location_id == "criers_dais" and needle in {"", "notices", "notice", "board", "proclamation", "crier"}:
        await stack.push(session, town_crier_screen(), "read-notices")
        return
    if location_id == "criers_dais" and needle in {"contracts", "jobs", "bounties"}:
        await stack.push(session, contracts_board_screen(), "read-contracts")
        return
    if location_id == "hall_steps" and needle in {"slayers", "chronicle", "history", "dragon"}:
        await stack.push(session, dragon_chronicle_screen(), "read-chronicle")
        return
    if target:
        lines = examine_lines(current_location(player), target)
        if lines:
            await stack.push(session, static_text_screen(f"READ // {target.upper()}", lines), "read")
            return
    await paint_location(session, "There is nothing here that wants to be read that way.")


async def buy_tonic_here(session, stack):
    player = session.data["player"]
    if current_location_id(player) != "healers_lane":
        await paint_location(session, "There is no healer here willing to sell you a tonic.")
        return
    ok, note = buy_potion(player)
    logger.info("healer-potion-direct handle=%s ok=%s note=%s", player["handle"], ok, note)
    if ok:
        await save_player(session, player)
    await stack.push(session, static_text_screen("HEALER'S LANE", [note]), "buy-tonic")


async def offer_relic_here(session, stack):
    player = session.data["player"]
    if current_location_id(player) != "dragon_shrine":
        await paint_location(session, "If you want to offer a relic, the shrine must hear it.")
        return
    if not player.get("curios"):
        await paint_location(session, "Your satchel holds no relic fit to offer.")
        return
    await stack.push(session, offer_curio_screen(), "offer-relic")


async def show_where(session, stack):
    player = session.data["player"]
    location = current_location(player)
    route_lines = [f"- {route['label']}" for route in dynamic_routes(session, player, location)]
    lines = [
        f"You stand at {location['name']}.",
        location["description"],
        "",
        "From here you can reach:",
        *route_lines,
    ]
    await stack.push(session, static_text_screen("WHERE YOU STAND", lines), "where")


async def quick_look(session):
    await paint_location(session)


def world_screen():
    class WorldScreen:
        id = "world"

        async def on_enter(self, session, *_args):
            await ensure_player_root(session)

        async def on_resume(self, session, *_args):
            await ensure_player_root(session)

        async def on_line(self, session, text, stack, *_args):
            raw = (text or "").strip()
            lowered = raw.lower()
            if lowered in {"talk", "speak"} and VILLAGE_FIGURES.get(session.data["player"]["location"]):
                await stack.push(session, npc_screen(session.data["player"]["location"]), "npc")
                return True
            if lowered in {"look", "l"}:
                await quick_look(session)
                return True
            if lowered in {"study", "survey"}:
                await study_location(session, stack)
                return True
            if lowered in {"where", "map", "roads", "road", "exits"}:
                await show_where(session, stack)
                return True
            if lowered in {"inventory", "inv", "i"}:
                await stack.push(session, curio_satchel_screen(), "inventory")
                return True
            if lowered in {"sheet", "stats", "character", "c"}:
                await stack.push(session, character_sheet_screen(), "sheet")
                return True
            if lowered in {"listen", "hear"}:
                await listen_location(session, stack)
                return True
            if lowered in {"pray"}:
                await pray_here(session, stack)
                return True
            if lowered in {"rest", "sleep"}:
                await rest_here(session, stack)
                return True
            if lowered in {"buy tonic", "buy potion", "tonic", "potion"}:
                await buy_tonic_here(session, stack)
                return True
            if lowered in {"offer relic", "offer curio", "offer"}:
                await offer_relic_here(session, stack)
                return True
            if lowered in {"read", "read board", "read rumors", "read notices"}:
                await read_here(session, stack, lowered.replace("read", "", 1).strip() or None)
                return True
            if lowered in {"read contracts", "read jobs", "read bounties"}:
                await stack.push(session, contracts_board_screen(), "contracts")
                return True
            if lowered in {"read slayers", "read chronicle", "read history"}:
                await stack.push(session, dragon_chronicle_screen(), "chronicle")
                return True
            if lowered.startswith("read "):
                await read_here(session, stack, raw[5:].strip())
                return True
            if await perform_place_action(session, stack, lowered):
                return True
            for prefix in ("examine ", "inspect ", "x "):
                if lowered.startswith(prefix):
                    target = raw[len(prefix):].strip()
                    lines = examine_lines(current_location(session.data["player"]), target)
                    if lines:
                        await stack.push(session, static_text_screen(f"EXAMINE // {target.upper()}", lines), "examine")
                    else:
                        await paint_location(session, f"You do not find much to examine about '{target}'.")
                    return True

            choice = normalize_world_command(raw)
            logger.info("world-screen handle=%s location=%s choice=%s", session.handle, session.data["player"]["location"], choice)
            options = build_location_options(session)
            option = match_option(options, choice)
            if option is not None:
                await option["action"](session, stack)
                return True
            if choice in {"q", "quit", "leave", "jackout"}:
                await session.detach("jackout")
                return True
            await paint_location(session, "Choose one of the routes or actions shown below.")
            return True

    return WorldScreen()


async def ensure_player_root(session):
    logger.info("ensure-player-root handle=%s", session.handle)
    player = await load_player(session)
    if not player:
        logger.info("ensure-player-root handle=%s new-player=true", session.handle)
        await screens.replace(session, class_select_screen(), "new-player")
        return
    player, note = await daily_reset_if_needed(session, player)
    resume_note = session.data.pop("resume_note", None)
    if resume_note:
        note = f"{note} {resume_note}".strip() if note else resume_note
    ensure_daily_contracts(player, current_day_key(session))
    session.data["player"] = player
    await sync_dragon_state(session, await load_dragon_state(session))
    if player["location"] in {"ashen_pass", "ember_peak"} and (session.data.get("dragon_state") or {}).get("stage_id") != DRAGON_CAPSTONE["required_stage"]:
        player["location"] = "dragon_shrine"
        note = f"{note} The ash road has gone cold, and you find yourself back at the shrine.".strip() if note else "The ash road has gone cold, and you find yourself back at the shrine."
    await save_player(session, player)
    await paint_location(session, note)


def normalize_player_record(player: dict[str, Any]) -> dict[str, Any]:
    if "location" not in player:
        player["location"] = "market_square"
    if "status" not in player:
        player["status"] = "alive"
    if "weapon_bonus" not in player:
        player["weapon_bonus"] = 0
    if "armor_bonus" not in player:
        player["armor_bonus"] = 0
    if "victories" not in player:
        player["victories"] = 0
    if "defeats" not in player:
        player["defeats"] = 0
    if "dragon_favor" not in player:
        player["dragon_favor"] = 0
    if "gems" not in player:
        player["gems"] = 1
    if "curios" not in player:
        player["curios"] = []
    if "dragon_slays" not in player:
        player["dragon_slays"] = 0
    if "contracts_completed" not in player:
        player["contracts_completed"] = 0
    if "blessings" not in player:
        player["blessings"] = {}
    if "daily_flags" not in player:
        player["daily_flags"] = {}
    if "contracts" not in player:
        player["contracts"] = {"day": player.get("last_day") or "1970-01-01", "branches": {}}
    return player


def current_location(player: dict[str, Any]) -> dict[str, Any]:
    return LOCATIONS[player.get("location", "market_square")]


def branch_name(branch_id: str) -> str:
    return LOCATIONS.get(branch_id, {}).get("name", branch_id.replace("_", " ").title())


def contract_entries(player: dict[str, Any]) -> list[dict[str, Any]]:
    branches = ((player.get("contracts") or {}).get("branches") or {})
    entries = []
    for branch_id in ("grave_cut", "bandit_road", "ruined_watch"):
        contract = branches.get(branch_id)
        if not contract:
            continue
        entries.append({**contract, "branch_name": branch_name(branch_id)})
    return entries


def dragon_capstone_ready(session) -> tuple[bool, str | None]:
    player = session.data["player"]
    state = session.data.get("dragon_state") or {}
    required_stage = DRAGON_CAPSTONE["required_stage"]
    if state.get("stage_id") != required_stage:
        return False, f"The pass is sealed until the dragon reaches {required_stage.title()}."
    if player["level"] < DRAGON_CAPSTONE["min_level"]:
        return False, f"You need to reach level {DRAGON_CAPSTONE['min_level']} before climbing the ash road."
    if player["dragon_favor"] < DRAGON_CAPSTONE["min_favor"]:
        return False, f"You need {DRAGON_CAPSTONE['min_favor']} dragon favor before the shrine will open the way."
    return True, None


def dragon_approach_progress(player: dict[str, Any]) -> int:
    return int(player.setdefault("daily_flags", {}).get("dragon_approach_steps", 0))


def advance_dragon_approach(player: dict[str, Any]) -> str:
    flags = player.setdefault("daily_flags", {})
    flags["dragon_approach_steps"] = min(2, int(flags.get("dragon_approach_steps", 0)) + 1)
    if int(flags["dragon_approach_steps"]) >= 2:
        return "The pass gives way. Ember Peak is within reach now."
    return "You win a little more height on the mountain. One more hard push should open the peak."


def dynamic_routes(session, player: dict[str, Any], location: dict[str, Any]) -> list[dict[str, Any]]:
    routes = []
    for route in location.get("routes", []):
        if location.get("name") == "Ashen Pass" and route["to"] == "ember_peak" and dragon_approach_progress(player) < 2:
            continue
        routes.append(route)
    location_id = player["location"]
    ready, _reason = dragon_capstone_ready(session)
    if location_id == "dragon_shrine" and ready:
        routes.append({"to": "ashen_pass", "label": "Ashen Pass", "aliases": ["north", "n", "pass", "ashen pass"]})
    return routes


def match_option(options: list[dict[str, Any]], raw: str) -> dict[str, Any] | None:
    needle = "".join(ch for ch in str(raw or "").strip().lower() if ch.isalnum())
    for option in options:
        aliases = [option["key"], *option.get("aliases", [])]
        if needle in {"".join(ch for ch in str(alias).strip().lower() if ch.isalnum()) for alias in aliases}:
            return option
    return None


def route_word(route: dict[str, Any]) -> str | None:
    aliases = [str(alias).strip().lower() for alias in route.get("aliases", [])]
    for word in ("north", "south", "east", "west", "up", "down"):
        if word in aliases:
            return word
    if "back" in aliases:
        return "back"
    return None


def build_location_options(session) -> list[dict[str, Any]]:
    player = session.data["player"]
    location_id = player["location"]
    location = current_location(player)
    location["_active_routes"] = dynamic_routes(session, player, location)
    options: list[dict[str, Any]] = []
    key = 1
    for route in location.get("_active_routes", []):
        route_aliases = [route["to"], route["label"], *route.get("aliases", [])]
        direction = route_word(route)
        route_text = f"{direction} -> {route['label']}" if direction else route["label"]
        options.append({
            "key": str(key),
            "label": route_text,
            "aliases": route_aliases,
            "category": "route",
            "action": move_to_location(route["to"]),
        })
        key += 1

    figure = VILLAGE_FIGURES.get(location_id)
    if figure:
        options.append({
            "key": str(key),
            "label": f"talk -> {figure['name']}",
            "aliases": ["talk", "speak", figure["name"], figure["name"].split()[0], figure["role"]],
            "category": "local",
            "action": lambda s, st, loc=location_id: st.push(s, npc_screen(loc), f"npc-{loc}"),
        })
        key += 1

    if location_id == "lantern_inn":
        options.append({"key": str(key), "label": "read -> rumor board", "aliases": ["rumors", "board"], "category": "local", "action": lambda s, st: st.push(s, rumor_board_screen(), "rumors")})
        key += 1
        if player.get("curios"):
            options.append({"key": str(key), "label": "trade -> inn curios", "aliases": ["trade", "curio"], "category": "local", "action": lambda s, st: st.push(s, trade_curio_screen(), "trade-curio")})
            key += 1
    if location_id == "healers_lane":
        options.append({"key": str(key), "label": "enter -> healer's rooms", "aliases": ["healer", "heal"], "category": "local", "action": lambda s, st: st.push(s, healer_screen(), "healer")})
        key += 1
    if location_id == "armorers_row":
        options.append({"key": str(key), "label": "enter -> armory", "aliases": ["armory", "forge"], "category": "local", "action": lambda s, st: st.push(s, armory_screen(), "armory")})
        key += 1
    if location_id == "hall_steps":
        options.append({"key": str(key), "label": "read -> hall of fame", "aliases": ["hall", "fame"], "category": "local", "action": lambda s, st: st.push(s, hall_of_fame_screen(), "hall")})
        key += 1
        options.append({"key": str(key), "label": "read -> dragon chronicle", "aliases": ["chronicle", "slayers", "history"], "category": "local", "action": lambda s, st: st.push(s, dragon_chronicle_screen(), "chronicle")})
        key += 1
    if location_id == "criers_dais":
        options.append({"key": str(key), "label": "hear -> town crier", "aliases": ["crier", "hear"], "category": "local", "action": lambda s, st: st.push(s, town_crier_screen(), "crier")})
        key += 1
        options.append({"key": str(key), "label": "read -> contracts board", "aliases": ["contracts", "jobs", "board"], "category": "local", "action": lambda s, st: st.push(s, contracts_board_screen(), "contracts")})
        key += 1
    if location_id == "dragon_shrine":
        options.append({"key": str(key), "label": "pray -> seek omen", "aliases": ["omen", "pray"], "category": "local", "action": lambda s, st: st.push(s, dragon_shrine_screen(), "shrine")})
        key += 1
        if player.get("curios"):
            options.append({"key": str(key), "label": "offer -> shrine relic", "aliases": ["offer", "relic"], "category": "local", "action": lambda s, st: st.push(s, offer_curio_screen(), "offer-curio")})
            key += 1
        ready, reason = dragon_capstone_ready(session)
        if ready:
            options.append({"key": str(key), "label": "north -> Ashen Pass", "aliases": ["ashen pass", "dragon road", "pass"], "category": "route", "action": move_to_location("ashen_pass")})
            key += 1
        elif reason:
            options.append({"key": str(key), "label": "ash road sealed", "aliases": [], "category": "local", "action": lambda s, _st, note=reason: paint_location(s, note)})
            key += 1
    if location_id == "ashen_pass":
        progress = dragon_approach_progress(player)
        if progress >= 2:
            options.append({"key": str(key), "label": "north -> Ember Peak", "aliases": ["forward", "peak", "eyrie"], "category": "route", "action": move_to_location("ember_peak")})
            key += 1
        else:
            options.append({
                "key": str(key),
                "label": "peak still shut above you",
                "aliases": [],
                "category": "local",
                "action": lambda s, _st, progress=progress: paint_location(s, f"You need {2 - progress} more hard push on the pass before Ember Peak opens."),
            })
            key += 1
    if location_id == "ember_peak":
        options.append({"key": str(key), "label": "challenge -> the dragon", "aliases": ["dragon", "challenge", "slay"], "category": "wild", "action": lambda s, _st: begin_dragon_capstone(s)})
        key += 1
    if location.get("wild_action"):
        options.append({
            "key": str(key),
            "label": f"venture -> {location['wild_action']}",
            "aliases": ["hunt", "explore", "fight", "venture"],
            "category": "wild",
            "action": lambda s, _st: start_forest_run(s),
        })
        key += 1

    options.append({"key": str(key), "label": "look -> study the place", "aliases": ["look", "survey", "study"], "category": "self", "action": lambda s, st: study_location(s, st)})
    key += 1
    options.append({"key": str(key), "label": "sheet -> character", "aliases": ["sheet", "character"], "category": "self", "action": lambda s, st: st.push(s, character_sheet_screen(), "sheet")})
    key += 1
    options.append({"key": str(key), "label": "satchel -> relics", "aliases": ["curios", "satchel", "relics"], "category": "self", "action": lambda s, st: st.push(s, curio_satchel_screen(), "satchel")})
    return options


async def paint_location(session, note: str | None = None):
    player = session.data["player"]
    location = current_location(player)
    options = build_location_options(session)
    dragon_state = session.data.get("dragon_state")
    await session.paint(render_location(session, player, location, options, dragon_state, note))
    await session.prompt(label="dragon:", max_length=40)


def move_to_location(location_id: str):
    async def action(session, _stack):
        player = session.data["player"]
        player["location"] = location_id
        logger.info("move-location handle=%s to=%s", player["handle"], location_id)
        await save_player(session, player)
        await paint_location(session)
    return action


async def study_location(session, stack):
    player = session.data["player"]
    location = current_location(player)
    fallback = [
        location["description"],
        f"You note {random_discovery(location)}.",
        "The road remembers more names than the hall does.",
    ]
    lines = await oracle_lines(
        session,
        "You are a dark fantasy location narrator. Describe a place in 3 or 4 short lines, specific and moody.",
        f"Place: {location['name']} ({location['kind']}). Description: {location['description']}. Discoveries: {location['discoveries']}. Player: {player['handle']} the {player['class_name']}.",
        fallback,
        wait_title=location["name"].upper(),
        wait_body=[
            f"{player['handle']} studies {location['name']} for signs and stories.",
            "The oracle is turning local weather, stone, and memory into language.",
        ],
    )
    await stack.push(session, static_text_screen(location["name"], lines), "study")


def random_discovery(location: dict[str, Any]) -> str:
    entries = location.get("discoveries", [])
    return entries[0] if not entries else entries[hash(location["name"]) % len(entries)]


def contract_detail_lines(contract: dict[str, Any]) -> list[str]:
    status = "claimed" if contract.get("claimed") else "complete" if contract.get("completed") else f"{contract.get('progress', 0)}/{contract.get('count', 1)}"
    return [
        f"{contract['title']} // {contract['branch_name']}",
        contract["hook"],
        "",
        contract["objective"],
        f"Status: {status}",
        f"Issuer: {contract['issuer']}",
        f"Reward: {contract['reward_gold']} gold / {contract['reward_xp']} xp / {contract['reward_favor']} dragon favor",
    ]


async def npc_favor_action(session, location_id: str):
    player = session.data["player"]
    figure = VILLAGE_FIGURES[location_id]
    flag = f"npc:{location_id}:{figure['favor_name']}"
    if player.setdefault("daily_flags", {}).get(flag):
        return False, f"{figure['name']} has already done what they will for you today."
    note = None
    if location_id == "market_square":
        player["turns"] += 1
        note = "Pellar taps the square map with a dirty nail and sends you toward the right kind of trouble. You gain 1 turn."
    elif location_id == "lantern_inn":
        if player["gold"] < 4:
            return False, "Edda wants 4 gold for a hot bowl and a quiet corner."
        player["gold"] -= 4
        player["turns"] += 1
        healed = min(3, player["max_hp"] - player["hp"])
        player["hp"] += healed
        note = f"Edda feeds you road stew. You gain 1 turn and {healed} hp."
    elif location_id == "healers_lane":
        healed = min(4, player["max_hp"] - player["hp"])
        if healed <= 0:
            return False, "Mother Sable snorts. 'You are standing up well enough already.'"
        player["hp"] += healed
        note = f"Mother Sable straps on a poultice and sends you out with {healed} hp back under your skin."
    elif location_id == "armorers_row":
        player.setdefault("blessings", {})["next_attack_bonus"] = max(1, int(player.get("blessings", {}).get("next_attack_bonus", 0)))
        note = "Brannock drags a fresh edge across your steel. Your next clean strike will bite deeper."
    elif location_id == "criers_dais":
        player["forest_fights"] += 1
        contracts = contract_entries(player)
        active = next((entry for entry in contracts if not entry.get("completed")), None)
        if active:
            note = f"Old Tern points you at {active['branch_name']}. He also shouts you one extra forest fight for tonight."
        else:
            note = "Old Tern laughs that every road is bad, then grants you one extra forest fight for the trouble."
    elif location_id == "forest_gate":
        player.setdefault("blessings", {})["gate_charm"] = True
        note = "Gate-Mother Hesh knots a charm onto your belt. The next time you run for it in the wild, the road will help your feet."
    elif location_id == "dragon_shrine":
        player.setdefault("blessings", {})["dragon_ward"] = True
        player["dragon_favor"] += 1
        note = "Sister Ione draws a cinder mark over your heart. You gain 1 dragon favor and a ward against hotter blood."
    else:
        return False, "No favor waits here."
    player["daily_flags"][flag] = True
    await save_player(session, player)
    return True, note


def npc_screen(location_id: str):
    figure = VILLAGE_FIGURES[location_id]

    async def favor(session, _ctx):
        ok, note = await npc_favor_action(session, location_id)
        await screens.replace(session, npc_screen_with_note(location_id, note), f"npc-{location_id}-refresh")

    async def related(session, _ctx):
        if location_id == "market_square":
            await screens.push(session, contracts_board_screen(), "npc-market-contracts")
            return
        if location_id == "lantern_inn":
            await screens.push(session, rumor_board_screen(), "npc-rumors")
            return
        if location_id == "healers_lane":
            await screens.push(session, healer_screen(), "npc-healer")
            return
        if location_id == "armorers_row":
            await screens.push(session, armory_screen(), "npc-armory")
            return
        if location_id == "criers_dais":
            await screens.push(session, contracts_board_screen(), "npc-contracts")
            return
        if location_id == "forest_gate":
            await screens.push(session, contracts_board_screen(), "npc-gate-contracts")
            return
        if location_id == "dragon_shrine":
            await screens.push(session, dragon_chronicle_screen(), "npc-chronicle")
            return

    return npc_screen_with_note(location_id, None, favor, related)


def npc_screen_with_note(location_id: str, note: str | None, favor=None, related=None):
    figure = VILLAGE_FIGURES[location_id]
    favor = favor or (lambda s, _ctx: screens.replace(s, npc_screen(location_id), "npc-reopen"))
    related = related or (lambda s, _ctx: screens.replace(s, npc_screen(location_id), "npc-reopen"))
    related_label = {
        "market_square": "Read the contracts board",
        "lantern_inn": "Read the rumor board",
        "healers_lane": "Step into the healer's rooms",
        "armorers_row": "Browse the armory",
        "criers_dais": "Read the contracts board",
        "forest_gate": "Read the contracts board",
        "dragon_shrine": "Read the dragon chronicle",
    }[location_id]
    return create_option_screen(
        id=f"npc-{location_id}",
        prompt_label="talk:",
        options=[
            {"key": "1", "label": figure["favor_label"], "aliases": ["favor", figure["favor_name"]], "action": favor},
            {"key": "2", "label": related_label, "aliases": ["more", "board", "service"], "action": related},
            {"key": "Q", "label": "Back", "aliases": ["back"], "action": lambda s, _ctx: screens.pop(s, "back")},
        ],
        render=lambda _s, view: render_npc_scene(
            f"{figure['name'].upper()} // {figure['role'].upper()}",
            [*figure["greeting"], "", "[1] " + figure["favor_label"], "[2] " + related_label, "[Q] Back"],
            view.get("note") or note,
        ),
        on_escape=lambda s, _ctx: screens.pop(s, "escape"),
    )


def dragon_chronicle_screen():
    return create_option_screen(
        id="dragon-chronicle",
        prompt_label="chronicle:",
        options=[{"key": "Q", "label": "Back", "aliases": ["back"], "action": lambda s, _ctx: screens.pop(s, "back")}],
        render=lambda s, view: render_dragon_chronicle(s.data.get("dragon_state") or create_default_dragon_state(), view.get("note")),
        on_escape=lambda s, _ctx: screens.pop(s, "escape"),
    )


def contracts_board_screen():
    async def inspect_or_claim(session, _ctx, branch_id: str):
        player = session.data["player"]
        contracts = contract_entries(player)
        contract = next((entry for entry in contracts if entry["branch_id"] == branch_id), None)
        if contract is None:
            await screens.replace(session, contracts_board_screen_with_note("That notice has already come off the board."), "contracts-refresh")
            return
        if contract.get("completed") and not contract.get("claimed"):
            ok, note = claim_contract_reward(player, branch_id)
            if ok:
                await save_player(session, player)
                await append_rumor(session, f"{player['handle']} claimed the contract '{contract['title']}' and walked away smiling.")
            await screens.replace(session, contracts_board_screen_with_note(note), "contracts-claim")
            return
        await screens.push(session, static_text_screen("CONTRACT", contract_detail_lines(contract)), f"contract-{branch_id}")

    return contracts_board_screen_with_note(None, inspect_or_claim)


def contracts_board_screen_with_note(note: str | None, inspect_or_claim=None):
    inspect_or_claim = inspect_or_claim or (lambda s, _ctx, _branch_id=None: screens.replace(s, contracts_board_screen(), "contracts-reopen"))

    async def load_options(session, _ctx):
        entries = contract_entries(session.data["player"])
        options = []
        for idx, entry in enumerate(entries, start=1):
            options.append({
                "key": str(idx),
                "label": entry["title"],
                "aliases": [entry["branch_id"], entry["title"]],
                "entry": entry,
                "action": lambda s, ctx, branch_id=entry["branch_id"]: inspect_or_claim(s, ctx, branch_id),
            })
        options.append({"key": "Q", "label": "Back", "aliases": ["back"], "action": lambda s, _ctx: screens.pop(s, "back")})
        return options

    return create_option_screen(
        id="contracts-board",
        prompt_label="contracts:",
        load_options=load_options,
        loading={"message": lambda _s, ctx: f"old tern sorting road notices {ctx['frame']}"},
        render=lambda _s, view: render_contracts([opt["entry"] for opt in view["options"] if opt.get("entry")], view.get("note") or note),
        on_escape=lambda s, _ctx: screens.pop(s, "escape"),
    )


async def begin_dragon_capstone(session):
    ready, reason = dragon_capstone_ready(session)
    if not ready:
        await paint_location(session, reason)
        return
    player = session.data["player"]
    dragon_state = session.data.get("dragon_state") or await load_dragon_state(session)
    monster = {
        "id": "ashen_dragon",
        "name": dragon_state["name"],
        "family": "dragonkin",
        "attack": 9,
        "defense": 5,
        "max_hp": 36,
        "hp": 36,
        "xp": 65,
        "gold": 90,
        "intro": f"{dragon_state['name']} {dragon_state['title']} unfolds over Ember Peak and chooses you as the next sound it will end.",
    }
    session.data["combat"] = {
        "monster": monster,
        "transcript": [
            "You cross the final ash shelf and the whole mountain seems to inhale.",
            monster["intro"],
        ],
        "capstone": True,
    }
    await save_player(session, player)
    await screens.push(session, combat_screen(), "dragon-capstone")


def class_select_screen():
    options = []
    for idx, (class_id, klass) in enumerate(CLASSES.items(), start=1):
        options.append(
            {
                "key": str(idx),
                "label": klass["name"],
                "note": klass["blurb"],
                "aliases": [class_id, klass["name"].lower()],
                "action": make_class_pick_action(class_id),
            }
        )
    options.append({"key": "Q", "label": "Jack out", "aliases": ["quit"], "action": leave_action})
    return create_option_screen(
        id="class-select",
        prompt_label="class:",
        options=options,
        render=render_class_menu,
    )


def make_class_pick_action(class_id: str):
    async def action(session, _ctx):
        player = create_player(session.handle or "wanderer", class_id)
        logger.info("class-picked handle=%s class=%s", session.handle, class_id)
        player["last_day"] = current_day_key(session)
        session.data["player"] = player
        await save_player(session, player)
        await append_rumor(session, f"{player['handle']} arrives in town bearing the look of someone with unfinished business.")
        await screens.set_root(session, world_screen())
    return action


async def leave_action(session, _ctx):
    logger.info("jackout handle=%s", session.handle)
    await session.detach("jackout")


async def start_forest_run(session):
    player = session.data["player"]
    location = current_location(player)
    logger.info(
        "forest-run-start handle=%s location=%s turns=%s forest_fights=%s",
        player["handle"], player["location"], player["turns"], player["forest_fights"]
    )
    if player["turns"] <= 0 or player["forest_fights"] <= 0:
        await session.notify("You have no forest turns left today.", level="warn")
        await paint_location(session, "You have no forest turns left today.")
        return
    spend_forest_turn(player)
    special = await maybe_special_wild_scene(session, location)
    if special:
        pressure = 2 if location["name"] == "Ashen Pass" else 1
        await adjust_dragon_state(session, pressure_delta=pressure, source=f"{location['name']} scene")
        logger.info("forest-special-scene handle=%s event=%s", player["handle"], special["id"])
        if special.get("combat_monster"):
            monster = special["combat_monster"]
            await append_rumor(session, f"{player['handle']} vanished into {location['name']} after sighting {monster['name']}.")
            session.data["combat"] = {
                "monster": monster,
                "transcript": [special["text"], monster["intro"]],
                "elite": True,
            }
            await save_player(session, player)
            await screens.push(session, combat_screen(), "special-combat")
            return
        await append_rumor(session, compose_wild_rumor(location, player, event=True))
        await screens.push(session, event_screen(special), "special-event")
        return
    event = maybe_forest_event(player, location.get("event_line"), location.get("event_tags"))
    if event:
        pressure = 2 if "dragonkin" in event.get("tags", []) else 1
        await adjust_dragon_state(session, pressure_delta=pressure, source=location["name"])
        logger.info("forest-event handle=%s event=%s", player["handle"], event["id"])
        await save_player(session, player)
        await append_rumor(session, compose_wild_rumor(location, player, event=True))
        curio = await maybe_award_curio(session, "wild event", event["text"])
        if curio:
            event["bonus_lines"] = [
                f"You recover {curio['name']} and tuck it into your satchel.",
                curio["blurb"],
            ]
        if player["location"] == "ashen_pass":
            event["bonus_lines"] = [*(event.get("bonus_lines") or []), advance_dragon_approach(player)]
            await save_player(session, player)
        contract_notes = progress_contracts(player, branch_id=player["location"])
        if contract_notes:
            event["bonus_lines"] = [*(event.get("bonus_lines") or []), *contract_notes]
            await save_player(session, player)
        await screens.push(session, event_screen(event), "event")
        return
    monster = choose_monster(
        player,
        active_branch_contract(player, player["location"]).get("families") if active_branch_contract(player, player["location"]) and active_branch_contract(player, player["location"]).get("goal_kind") == "kill_family" else location.get("monster_families"),
        dragon_stage_index=(session.data.get("dragon_state") or {}).get("stage_index", 0),
    )
    logger.info("forest-combat-start handle=%s monster=%s", player["handle"], monster["id"])
    contract = active_branch_contract(player, player["location"])
    contract_lines = CONTRACT_SCENE_TEXT.get(contract["id"], {}).get("combat", []) if contract else []
    session.data["combat"] = {
        "monster": monster,
        "transcript": [location.get("event_line", "The wild closes around you."), *contract_lines, monster["intro"]],
    }
    await save_player(session, player)
    await screens.push(session, combat_screen(), "combat")


def event_screen(event: dict[str, Any]):
    options = [{"key": "Q", "label": "Back", "aliases": ["back", "continue"], "action": lambda s, _ctx: screens.pop(s, "event")}]
    return create_option_screen(
        id=f"event-{event['id']}",
        prompt_label="wilds:",
        options=options,
        render=lambda _s, view: render_simple_menu(
            "FOREST EVENT",
            [event["title"], "", event["text"], *([ "", *event.get("bonus_lines", []) ] if event.get("bonus_lines") else []), "", "[Q] Return"],
            view.get("note"),
        ),
        on_escape=lambda s, _ctx: screens.pop(s, "escape"),
    )


def character_sheet_screen():
    return create_option_screen(
        id="character-sheet",
        prompt_label="sheet:",
        options=[
            {"key": "1", "label": "Open relic satchel", "aliases": ["curios", "satchel"], "action": lambda s, _ctx: screens.push(s, curio_satchel_screen(), "sheet-satchel")},
            {"key": "Q", "label": "Back", "aliases": ["back"], "action": lambda s, _ctx: screens.pop(s, "back")},
        ],
        render=lambda s, view: render_sheet(s.data["player"], view.get("note")),
        on_escape=lambda s, _ctx: screens.pop(s, "escape"),
    )


def curio_satchel_screen():
    return create_option_screen(
        id="curio-satchel",
        prompt_label="satchel:",
        options=[{"key": "Q", "label": "Back", "aliases": ["back"], "action": lambda s, _ctx: screens.pop(s, "back")}],
        render=lambda s, view: render_curios(s.data["player"].get("curios", []), "RELIC SATCHEL", view.get("note"), show_values=True),
        on_escape=lambda s, _ctx: screens.pop(s, "escape"),
    )


def trade_curio_screen():
    async def trade_curio(session, _ctx, curio_id: str):
        player = session.data["player"]
        curios = player.get("curios", [])
        curio = next((entry for entry in curios if entry["id"] == curio_id), None)
        if curio is None:
            await screens.replace(session, trade_curio_screen_with_note("That relic is no longer in your satchel."), "trade-missing")
            return
        curios.remove(curio)
        player["gold"] += curio["sale_gold"]
        await save_player(session, player)
        await append_rumor(session, f"{player['handle']} traded away {curio['name']} in the inn yard for {curio['sale_gold']} gold.")
        note = f"The innkeeper takes {curio['name']} and counts out {curio['sale_gold']} gold."
        logger.info("trade-curio handle=%s curio=%s gold=%s", player["handle"], curio["template_id"], curio["sale_gold"])
        await screens.replace(session, trade_curio_screen_with_note(note), "trade-refresh")

    return trade_curio_screen_with_note(None, trade_curio)


def trade_curio_screen_with_note(note: str | None, trade_curio=None):
    trade_curio = trade_curio or (lambda s, _ctx, _curio_id=None: screens.replace(s, trade_curio_screen(), "trade-reopen"))

    def render(s, view):
        return render_curios(s.data["player"].get("curios", []), "INN CURIOS", view.get("note") or note, show_values=True)

    return create_option_screen(
        id="trade-curio",
        prompt_label="trade:",
        load_options=lambda s, _ctx: build_curio_choice_options(s, trade_curio, "trade") + [{"key": "Q", "label": "Back", "aliases": ["back"], "action": lambda sx, _cx: screens.pop(sx, "back")}],
        loading={"message": lambda _s, ctx: f"innkeeper weighing oddments {ctx['frame']}"},
        render=render,
        on_escape=lambda s, _ctx: screens.pop(s, "escape"),
    )


def offer_curio_screen():
    async def offer_curio(session, _ctx, curio_id: str):
        player = session.data["player"]
        curios = player.get("curios", [])
        curio = next((entry for entry in curios if entry["id"] == curio_id), None)
        if curio is None:
            await screens.replace(session, offer_curio_screen_with_note("That relic is no longer in your satchel."), "offer-missing")
            return
        curios.remove(curio)
        player["dragon_favor"] += curio["shrine_favor"]
        await save_player(session, player)
        await adjust_dragon_state(
            session,
            calm_delta=max(1, curio["shrine_favor"]),
            omen_delta=1,
            source=f"{player['handle']} offered {curio['name']}",
        )
        await append_rumor(session, f"{player['handle']} offered {curio['name']} at the shrine and left lighter for it.")
        note = f"The shrine accepts {curio['name']}. Your dragon favor rises by {curio['shrine_favor']}."
        logger.info("offer-curio handle=%s curio=%s favor=%s", player["handle"], curio["template_id"], curio["shrine_favor"])
        await screens.replace(session, offer_curio_screen_with_note(note), "offer-refresh")

    return offer_curio_screen_with_note(None, offer_curio)


def offer_curio_screen_with_note(note: str | None, offer_curio=None):
    offer_curio = offer_curio or (lambda s, _ctx, _curio_id=None: screens.replace(s, offer_curio_screen(), "offer-reopen"))

    def render(s, view):
        return render_curios(s.data["player"].get("curios", []), "SHRINE OFFERINGS", view.get("note") or note, show_values=True)

    return create_option_screen(
        id="offer-curio",
        prompt_label="offer:",
        load_options=lambda s, _ctx: build_curio_choice_options(s, offer_curio, "offer") + [{"key": "Q", "label": "Back", "aliases": ["back"], "action": lambda sx, _cx: screens.pop(sx, "back")}],
        loading={"message": lambda _s, ctx: f"altar glass reading relics {ctx['frame']}"},
        render=render,
        on_escape=lambda s, _ctx: screens.pop(s, "escape"),
    )


def build_curio_choice_options(session, handler, mode: str) -> list[dict[str, Any]]:
    curios = session.data["player"].get("curios", [])
    options = []
    for idx, curio in enumerate(curios[:9], start=1):
        verb = "Trade" if mode == "trade" else "Offer"
        options.append({
            "key": str(idx),
            "label": f"{verb} {curio['name']}",
            "aliases": [curio["id"]],
            "action": lambda s, ctx, curio_id=curio["id"]: handler(s, ctx, curio_id),
        })
    return options


def hall_of_fame_screen():
    return create_option_screen(
        id="hall",
        prompt_label="hall:",
        options=[{"key": "Q", "label": "Back", "aliases": ["back"], "action": lambda s, _ctx: screens.pop(s, "back")}],
        load_options=lambda s, _ctx: build_hall_options(s),
        loading={"message": lambda _s, ctx: f"town clerk polishing brass plaques {ctx['frame']}"},
        render=lambda _s, view: render_hall([option["entry"] for option in view["options"] if option.get("entry")], view.get("note")),
        on_escape=lambda s, _ctx: screens.pop(s, "escape"),
    )


async def build_hall_options(session):
    entries = await load_hall(session)
    return [{"key": "Q", "label": "Back", "aliases": ["back"], "action": lambda s, _ctx: screens.pop(s, "back")}] + [
        {"key": str(idx + 1), "label": entry["handle"], "entry": entry, "aliases": [] , "action": lambda _s, _ctx: None}
        for idx, entry in enumerate(entries)
    ]


def rumor_board_screen():
    return create_option_screen(
        id="rumors",
        prompt_label="rumors:",
        options=[{"key": "Q", "label": "Back", "aliases": ["back"], "action": lambda s, _ctx: screens.pop(s, "back")}],
        load_options=lambda s, _ctx: build_rumor_options(s),
        loading={"message": lambda _s, ctx: f"innkeeper scraping gossip into a ledger {ctx['frame']}"},
        render=lambda _s, view: render_rumors([option["entry"] for option in view["options"] if option.get("entry")], view.get("note")),
        on_escape=lambda s, _ctx: screens.pop(s, "escape"),
    )


async def build_rumor_options(session):
    rumors = await load_rumors(session)
    return [{"key": "Q", "label": "Back", "aliases": ["back"], "action": lambda s, _ctx: screens.pop(s, "back")}] + [
        {"key": str(idx + 1), "label": rumor[:40], "entry": rumor, "aliases": [], "action": lambda _s, _ctx: None}
        for idx, rumor in enumerate(rumors[:10])
    ]


def healer_screen():
    async def patch_wounds(session, _ctx):
        player = session.data["player"]
        ok, note = heal_player(player)
        logger.info("healer-heal handle=%s ok=%s note=%s", player["handle"], ok, note)
        if ok:
            await save_player(session, player)
        await screens.replace(session, healer_screen_with_note(note), "healer")

    async def buy_red_tonic(session, _ctx):
        player = session.data["player"]
        ok, note = buy_potion(player)
        logger.info("healer-potion handle=%s ok=%s note=%s", player["handle"], ok, note)
        if ok:
            await save_player(session, player)
        await screens.replace(session, healer_screen_with_note(note), "healer")

    return healer_screen_with_note(None, patch_wounds, buy_red_tonic)


def healer_screen_with_note(note: str | None, patch_wounds=None, buy_red_tonic=None):
    patch_wounds = patch_wounds or (lambda s, _ctx: screens.replace(s, healer_screen(), "refresh"))
    buy_red_tonic = buy_red_tonic or (lambda s, _ctx: screens.replace(s, healer_screen(), "refresh"))
    return create_option_screen(
        id="healer",
        prompt_label="healer:",
        options=[
            {"key": "1", "label": "Heal wounds", "aliases": ["heal"], "action": patch_wounds},
            {"key": "2", "label": "Buy tonic (18g)", "aliases": ["tonic", "potion"], "action": buy_red_tonic},
            {"key": "Q", "label": "Back", "aliases": ["back"], "action": lambda s, _ctx: screens.pop(s, "back")},
        ],
        render=lambda s, view: render_simple_menu("HEALER", [
            f"Gold {s.data['player']['gold']}  HP {s.data['player']['hp']}/{s.data['player']['max_hp']}  Potions {s.data['player']['potions']}",
            "",
            "[1] Heal wounds",
            "[2] Buy tonic (18g)",
            "[Q] Back",
        ], view.get("note") or note),
        on_escape=lambda s, _ctx: screens.pop(s, "escape"),
    )


def armory_screen():
    async def buy(session, _ctx, item_type: str, item_id: str):
        player = session.data["player"]
        ok, note = buy_weapon(player, item_id) if item_type == "weapon" else buy_armor(player, item_id)
        logger.info("armory-buy handle=%s item_type=%s item_id=%s ok=%s note=%s", player["handle"], item_type, item_id, ok, note)
        if ok:
            await save_player(session, player)
            await append_rumor(session, f"{player['handle']} leaves the armory carrying something meaner than before.")
        await screens.replace(session, armory_screen_with_note(note, build_armory_options(buy)), "armory")

    return armory_screen_with_note(None, build_armory_options(buy))


def build_armory_options(buy_action):
    options = []
    idx = 1
    for weapon in WEAPONS:
        options.append({
            "key": str(idx),
            "label": f"{weapon['name']} ({weapon['cost']}g)",
            "aliases": [weapon["id"]],
            "action": lambda s, ctx, item_id=weapon["id"]: buy_action(s, ctx, "weapon", item_id),
        })
        idx += 1
    for armor in ARMOR:
        options.append({
            "key": str(idx),
            "label": f"{armor['name']} ({armor['cost']}g)",
            "aliases": [armor["id"]],
            "action": lambda s, ctx, item_id=armor["id"]: buy_action(s, ctx, "armor", item_id),
        })
        idx += 1
    options.append({"key": "Q", "label": "Back", "aliases": ["back"], "action": lambda s, _ctx: screens.pop(s, "back")})
    return options


def armory_screen_with_note(note: str | None, options=None):
    return create_option_screen(
        id="armory",
        prompt_label="armory:",
        options=options,
        render=lambda s, view: render_simple_menu("ARMORY", [
            f"Gold {s.data['player']['gold']}  Weapon {s.data['player']['weapon_name']}  Armor {s.data['player']['armor_name']}",
            "",
            *[f"[{opt['key']}] {opt['label']}" for opt in view["options"] if opt["key"] != "Q"],
            "[Q] Back",
        ], view.get("note") or note),
        on_escape=lambda s, _ctx: screens.pop(s, "escape"),
    )


def dragon_shrine_screen():
    async def seek_omen(session, _ctx):
        player = session.data["player"]
        logger.info("dragon-shrine handle=%s action=seek-omen", player["handle"])
        state = await adjust_dragon_state(session, omen_delta=1, source=f"{player['handle']} sought an omen")
        fallback = [
            f"The shrine glass shivers with the name of {state['name']} {state['title']}.",
            f"The dragon is in {state['stage_name']}. {state['weather']}",
            "A bell inside the stone says your name like a promise and a warning.",
        ]
        lines = await oracle_lines(
            session,
            "You are a dark fantasy shrine oracle. Speak in 3 short lines.",
            f"Player {player['handle']} the {player['class_name']} seeks an omen about the dragon {state['name']} {state['title']}, currently {state['stage_name']} with weather '{state['weather']}' and {dragon_progress_summary(state)}.",
            fallback,
            wait_title="DRAGON SHRINE",
            wait_body=[
                f"{player['handle']} kneels before the altar glass.",
                "The oracle is listening for the dragon's mood in stone and candle smoke.",
            ],
        )
        player["dragon_favor"] += 1
        await save_player(session, player)
        await save_dragon_state(session, state)
        await screens.push(session, static_text_screen("DRAGON SHRINE", lines), "omen")

    return create_option_screen(
        id="dragon-shrine",
        prompt_label="shrine:",
        options=[
            {"key": "1", "label": "Seek omen", "aliases": ["omen", "pray"], "action": seek_omen},
            {"key": "Q", "label": "Back", "aliases": ["back"], "action": lambda s, _ctx: screens.pop(s, "back")},
        ],
        render=lambda s, view: render_simple_menu(
            "DRAGON SHRINE",
            [
                f"Dragon favor {s.data['player']['dragon_favor']}",
                f"{(s.data.get('dragon_state') or {'stage_name': 'Slumber', 'pressure': 0, 'stage_index': 0}).get('stage_name', 'Slumber')}  //  {dragon_progress_summary(s.data.get('dragon_state') or {'stage_name': 'Slumber', 'pressure': 0, 'stage_index': 0})}",
                "",
                "[1] Seek omen",
                "[Q] Back",
            ],
            view.get("note"),
        ),
        on_escape=lambda s, _ctx: screens.pop(s, "escape"),
    )


def static_text_screen(title: str, lines: list[str]):
    return create_option_screen(
        id=f"static-{title.lower().replace(' ', '-')}",
        prompt_label="story:",
        options=[{"key": "Q", "label": "Back", "aliases": ["back", "continue"], "action": lambda s, _ctx: screens.pop(s, "back")}],
        render=lambda _s, view: render_simple_menu(title, lines + ["", "[Q] Return"], view.get("note")),
        on_escape=lambda s, _ctx: screens.pop(s, "escape"),
    )


def town_crier_screen():
    async def open_screen(session, _ctx):
        day = current_day_key(session)
        logger.info("town-crier-open handle=%s day=%s", session.handle, day)
        dragon_state = session.data.get("dragon_state") or await load_dragon_state(session)
        cache_key = crier_cache_key(day, dragon_state)
        cached = await session.storage.shared.get(cache_key)
        if cached:
            logger.info("town-crier-cache handle=%s hit=true", session.handle)
            lines = cached["value"]
        else:
            logger.info("town-crier-cache handle=%s hit=false", session.handle)
            rumors = await load_rumors(session)
            fallback = fallback_crier(rumors, dragon_state)
            lines = await oracle_lines(
                session,
                "You are a town crier in a dark fantasy village. Summarize the day in 4 short lines.",
                f"Rumors: {rumors[:5]}. Dragon state: {dragon_state}. Dragon progress: {dragon_progress_summary(dragon_state)}. Last slayer: {dragon_state.get('last_slayer')}.",
                fallback,
                wait_title="TOWN CRIER",
                wait_body=[
                    "The crier is gathering yesterday's noise into something worth shouting.",
                    "The oracle is dressing rumor, weather, and danger in a public voice.",
                ],
            )
            await session.storage.shared.put(cache_key, lines)
        await screens.push(session, static_text_screen("TOWN CRIER", lines), "crier-lines")

    return create_option_screen(
        id="town-crier",
        prompt_label="crier:",
        options=[
            {"key": "1", "label": "Hear the crier", "aliases": ["hear", "listen"], "action": open_screen},
            {"key": "Q", "label": "Back", "aliases": ["back"], "action": lambda s, _ctx: screens.pop(s, "back")},
        ],
        render=lambda _s, view: render_simple_menu("TOWN CRIER", [
            "[1] Hear the crier",
            "[Q] Back",
        ], view.get("note")),
        on_escape=lambda s, _ctx: screens.pop(s, "escape"),
    )


def combat_screen():
    class CombatScreen:
        id = "combat"

        async def on_enter(self, session, *_args):
            await self.paint(session)

        async def on_resume(self, session, *_args):
            await self.paint(session)

        async def on_line(self, session, text, stack, *_args):
            choice = (text or "").strip().lower()
            mapping = {
                "1": "attack",
                "2": "defend",
                "3": "potion",
                "4": "flee",
                "attack": "attack",
                "hit": "attack",
                "strike": "attack",
                "kill": "attack",
                "defend": "defend",
                "block": "defend",
                "guard": "defend",
                "potion": "potion",
                "drink": "potion",
                "drink potion": "potion",
                "use potion": "potion",
                "flee": "flee",
                "run": "flee",
                "escape": "flee",
            }
            action = mapping.get(choice)
            if action is None:
                await self.paint(session, "Choose 1-4 or type attack, defend, potion, or flee.")
                return True
            player = session.data["player"]
            combat = session.data["combat"]
            logger.info(
                "combat-action handle=%s monster=%s action=%s player_hp=%s monster_hp=%s",
                player["handle"], combat["monster"]["id"], action, player["hp"], combat["monster"]["hp"]
            )
            result = combat_round(player, combat["monster"], action)
            combat["transcript"].extend(result["transcript"])
            await save_player(session, player)
            if result["reward"]:
                pressure = 3 if combat["monster"]["family"] == "dragonkin" else 2
                if combat.get("capstone"):
                    renewed, slain = reset_dragon_after_slay(
                        session.data.get("dragon_state") or await load_dragon_state(session),
                        slayer=player["handle"],
                        day_key=current_day_key(session),
                    )
                    player["dragon_slays"] = int(player.get("dragon_slays", 0)) + 1
                    player["dragon_favor"] += 3
                    player["gems"] += 2
                    await save_player(session, player)
                    await save_dragon_state(session, renewed)
                    await sync_dragon_state(session, renewed)
                    session.data["resume_note"] = (
                        f"You slew {slain['dragon']}. The board names you dragon-slayer, and a new weather begins."
                    )
                    await append_rumor(session, f"{player['handle']} slew {slain['dragon']} above the village and came back under a cleaner sky.")
                    logger.info("combat-result handle=%s outcome=dragon-slay dragon=%s", player["handle"], slain["dragon"])
                else:
                    await adjust_dragon_state(session, pressure_delta=pressure, source=f"{current_location(player)['name']} victory")
                logger.info("combat-result handle=%s outcome=victory reward=%s", player["handle"], result["reward"])
                if not combat.get("capstone"):
                    if combat.get("elite"):
                        await append_rumor(session, f"{player['handle']} brought down {combat['monster']['name']} in {current_location(player)['name']} and made the road remember it.")
                        player["dragon_favor"] += 1
                        player["gems"] += 1
                        session.data["resume_note"] = "You broke a named threat and drew 1 dragon favor and 1 gem from the telling of it."
                        await save_player(session, player)
                    else:
                        await append_rumor(session, compose_wild_rumor(current_location(player), player, event=False, monster_name=combat["monster"]["name"]))
                    contract_notes = progress_contracts(player, branch_id=player["location"], monster=combat["monster"])
                    if contract_notes:
                        session.data["resume_note"] = " ".join(
                            [session.data.get("resume_note", ""), *contract_notes]
                        ).strip()
                        await save_player(session, player)
                    if player["location"] == "ashen_pass":
                        approach_note = advance_dragon_approach(player)
                        session.data["resume_note"] = " ".join(
                            [session.data.get("resume_note", ""), approach_note]
                        ).strip()
                        await save_player(session, player)
                curio = await maybe_award_curio(
                    session,
                    "combat victory",
                    f"{player['handle']} slew {combat['monster']['name']} at {current_location(player)['name']}.",
                )
                if curio:
                    session.data["resume_note"] = f"You also recover {curio['name']} from the aftermath."
                await stack.pop(session, "combat-win")
                return True
            if result["escaped"]:
                logger.info("combat-result handle=%s outcome=escaped", player["handle"])
                await append_rumor(session, f"{player['handle']} slipped the jaws of a {combat['monster']['name']}.")
                await stack.pop(session, "combat-flee")
                return True
            if result["defeated"]:
                logger.info("combat-result handle=%s outcome=defeated", player["handle"])
                await append_rumor(session, f"{player['handle']} staggered back from the forest pale and broke.")
                await stack.pop(session, "combat-loss")
                return True
            await self.paint(session)
            return True

        async def on_key(self, session, key, _mods, stack, *_args):
            if str(key).lower() in {"escape", "esc"}:
                await stack.pop(session, "combat-back")
                return True
            return False

        async def paint(self, session, note: str | None = None):
            player = session.data["player"]
            combat = session.data["combat"]
            await session.paint(render_combat(player, combat["monster"], combat["transcript"], note))
            await session.prompt(label="combat:", max_length=20)

    return CombatScreen()


async def handle_attach(session, _payload, _client):
    logger.info("attach handle=%s session_id=%s", session.handle, session.id)
    await session.paint(render_simple_menu(
        "REVENGE OF THE DRAGON",
        [
            "The village bells stir and the old road opens.",
            "",
            "Awakening your story...",
        ],
    ))
    asyncio.create_task(bootstrap_session(session))


async def bootstrap_session(session):
    try:
        logger.info("bootstrap-session-start handle=%s session_id=%s", session.handle, session.id)
        await screens.set_root(session, world_screen())
        logger.info("bootstrap-session-ready handle=%s session_id=%s", session.handle, session.id)
    except Exception:
        logger.exception("bootstrap-session-failed handle=%s session_id=%s", session.handle, session.id)
        await session.paint(render_simple_menu(
            "REVENGE OF THE DRAGON",
            [
                "The road shutters itself against you.",
                "",
                "A startup fault hit the village logic.",
                "Check the sidecar logs, then try again.",
            ],
        ))
        await session.prompt(label="error:", max_length=1)


async def handle_line(session, text, payload, client):
    logger.info("line handle=%s session_id=%s text=%s", session.handle, session.id, text)
    await screens.dispatch_line(session, text, payload, client)


async def handle_key(session, key, modifiers, payload, client):
    logger.info("key handle=%s session_id=%s key=%s modifiers=%s", session.handle, session.id, key, modifiers)
    await screens.dispatch_key(session, key, modifiers, payload, client)


async def handle_welcome(payload, _client):
    logger.info("welcome protocol=%s capabilities=%s", payload.get("protocol_version"), payload.get("capabilities_granted"))


async def handle_detach(session, reason, _payload, _client):
    logger.info("detach handle=%s session_id=%s reason=%s", session.handle, session.id, reason)


async def handle_shutdown(payload, _client):
    logger.warning("shutdown reason=%s", payload.get("reason"))


async def handle_time(session, clock, _payload, _client):
    logger.debug("time-tick handle=%s session_id=%s unix_time=%s", session.handle, session.id, clock.get("unixTimeSec"))


def configure_logging():
    level_name = os.environ.get("ROTD_LOG_LEVEL", "INFO").upper()
    level = getattr(logging, level_name, logging.INFO)
    logging.basicConfig(
        level=level,
        format="%(asctime)s %(levelname)-7s [%(name)s] %(message)s",
        datefmt="%H:%M:%S",
    )


def print_startup_banner():
    banner = [
        "",
        "REVENGE OF THE DRAGON // PYTHON DOOR 0.1.0",
        "-------------------------------------------",
        f"door id    : {DOOR_ID}",
        f"door url   : {os.environ.get('BBS_DOOR_URL', 'ws://localhost:8080/ws/door')}",
        f"log level  : {os.environ.get('ROTD_LOG_LEVEL', 'INFO').upper()}",
        "runtime    : booting sidecar, shared kv, halls, rumors, and dragon omens",
        "",
    ]
    print("\n".join(banner), flush=True)


def build_client():
    return connect_door(
        url=os.environ.get("BBS_DOOR_URL"),
        manifest=manifest(),
        on_welcome=handle_welcome,
        on_attach=handle_attach,
        on_line=handle_line,
        on_key=handle_key,
        on_time=handle_time,
        on_detach=handle_detach,
        on_shutdown=handle_shutdown,
        logger=logger,
    )


async def run():
    client = build_client()
    await client.run()


def run_cli():
    configure_logging()
    print_startup_banner()
    logger.info("starting door client")
    asyncio.run(run())


if __name__ == "__main__":
    run_cli()
