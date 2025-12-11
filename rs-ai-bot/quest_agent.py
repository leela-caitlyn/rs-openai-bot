import json
import math
from typing import Any, Dict, List, Optional

# Very simple mapping of quests -> important NPCs near the start.
# (This is enough to show behaviour; full F2P coverage would need more entries.)
QUEST_NPCS = {
    "tutorial island": [
        "Gielinor Guide",
        "Survival Expert",
        "Master Chef",
        "Quest Guide",
        "Mining Instructor",
        "Combat Instructor",
        "Brother Brace",
        "Magic Instructor",
    ],
    "cook's assistant": ["Cook"],
    "sheep shearer": ["Fred the Farmer"],
    "the restless ghost": ["Father Aereck", "Father Urhney"],
    "rune mysteries": ["Duke Horacio", "Sedridor"],
    "demon slayer": ["Gypsy Aris", "Sir Prysin"],
    "shield of arrav": ["Reldo", "Charlie the Tramp", "Curator Haig Halen"],
    "vampire slayer": ["Morgan", "Dr Harlow"],
    "goblin diplomacy": ["General Bentnoze", "General Wartface"],
    "imp catcher": ["Wizard Mizgog"],
    "doric's quest": ["Doric"],
    "the knight's sword": ["Sir Vyvin"],
    "pirate's treasure": ["Redbeard Frank"],
    "prince ali rescue": ["Hassan", "Osman"],
    "black knights' fortress": ["Sir Amik Varze"],
    "witch's potion": ["Hetty"],
    "the corsair curse": ["Captain Tock"],
    "below ice mountain": ["Willie"],
    "dragon slayer i": ["Guildmaster", "Oziach"],
}

QUEST_SEQUENCE = [
    "Tutorial Island",
    "Cook's Assistant",
    "Sheep Shearer",
    "The Restless Ghost",
    "Rune Mysteries",
    "Demon Slayer",
    "Shield of Arrav",
    "Vampire Slayer",
    "Goblin Diplomacy",
    "Imp Catcher",
    "Doric's Quest",
    "The Knight's Sword",
    "Pirate's Treasure",
    "Prince Ali Rescue",
    "Black Knights' Fortress",
    "Witch's Potion",
    "The Corsair Curse",
    "Below Ice Mountain",
    "Dragon Slayer I",
]


def _distance(p: Dict[str, Any], q: Dict[str, Any]) -> float:
    try:
        dx = (p.get("x", 0) or 0) - (q.get("x", 0) or 0)
        dy = (p.get("y", 0) or 0) - (q.get("y", 0) or 0)
        return math.hypot(dx, dy)
    except Exception:
        return 999999.0


def _find_best_npc(
    game_state: Dict[str, Any],
    quest_name: str,
) -> Optional[Dict[str, Any]]:
    """
    Return the best NPC to talk to:
    1) Prefer quest-specific NPCs from QUEST_NPCS, nearest first
    2) Otherwise any nearby NPC with a Talk-to action
    """
    npcs: List[Dict[str, Any]] = game_state.get("npcs") or []
    player = game_state.get("player") or {}

    quest_key = quest_name.lower().strip()
    quest_list = [q.lower() for q in QUEST_NPCS.get(quest_key, [])]

    best = None
    best_dist = 999999.0

    for npc in npcs:
        name = (npc.get("name") or "").strip()
        if not name:
            continue

        name_l = name.lower()
        actions = [a.lower() for a in (npc.get("actions") or []) if a]
        npc_pos = {
            "x": npc.get("x"),
            "y": npc.get("y"),
            "plane": npc.get("plane"),
        }
        dist = _distance(player, npc_pos)

        # Prefer quest-specific NPCs
        if name_l in quest_list:
            if dist < best_dist:
                best = npc
                best_dist = dist
            continue

        # Fallback: any NPC with a talk option, if we don't have a quest NPC yet
        if best is None and any("talk" in a for a in actions):
            if dist < best_dist:
                best = npc
                best_dist = dist

    return best


def _extract_text_state(game_state: Dict[str, Any], ctx: Dict[str, Any]) -> str:
    """Return a compact textual dump of the game state for prompting."""

    player = game_state.get("player") or {}
    npcs = game_state.get("npcs") or []
    objects = game_state.get("objects") or []
    dialog = game_state.get("dialog") or {}
    chat_log = game_state.get("chat_log") or []
    ui_text = game_state.get("ui_text") or []

    quest = ctx.get("current_quest") or ""
    do_all = ctx.get("do_all_quests") or False

    lines: List[str] = []
    lines.append(
        f"CONTEXT (QUEST): current_quest={quest!r}, do_all_quests={do_all}"\
        f", mode={ctx.get('mode')}"
    )

    if player:
        lines.append(
            f"PLAYER: name={player.get('name')}, pos=({player.get('x')},"
            f"{player.get('y')},{player.get('plane')})"
        )

    lines.append("NPCS (up to 15):")
    for npc in npcs[:15]:
        acts = npc.get("actions") or []
        lines.append(
            f"- {npc.get('name')} @ ({npc.get('x')},{npc.get('y')},{npc.get('plane')}) "
            f"actions={acts}"
        )

    if objects:
        lines.append("OBJECTS (up to 20):")
        for obj in objects[:20]:
            acts = obj.get("actions") or []
            lines.append(
                f"- {obj.get('name')} @ ({obj.get('x')},{obj.get('y')},{obj.get('plane')}) "
                f"actions={acts}"
            )

    npc_text = dialog.get("npc_text")
    player_text = dialog.get("player_text")
    can_continue = dialog.get("can_continue", False)
    lines.append(f"DIALOG.can_continue={can_continue}")
    if npc_text:
        lines.append(f"DIALOG.NPC: {npc_text}")
    if player_text:
        lines.append(f"DIALOG.PLAYER: {player_text}")

    if chat_log:
        lines.append("CHAT_LOG (latest first):")
        for msg in chat_log[-10:]:
            lines.append(f"- {msg}")

    if ui_text:
        lines.append("UI_TEXT (first 20 entries):")
        for w in ui_text[:20]:
            lines.append(f"- [{w.get('group')}:{w.get('id')}] {w.get('text')}")

    return "\n".join(lines)


def _call_llm_for_quest(client, game_state: Dict[str, Any], ctx: Dict[str, Any]) -> Dict[str, Any]:
    summary = _extract_text_state(game_state, ctx)

    quest_name = ctx.get("current_quest") or ""

    system_prompt = (
        "You control an Old School RuneScape character via a bot.\n"
        "Your role is QUESTING: keep progressing the active quest and keep the flow moving.\n\n"
        "You can ONLY choose ONE of these actions:\n"
        "  - walk_to_tile\n"
        "  - talk_to_npc\n"
        "  - interact_object\n"
        "  - dialog_continue\n"
        "  - adjust_camera\n"
        "  - wait\n\n"
        "Action JSON schema (strict):\n"
        "{\n"
        '  "action": "walk_to_tile" | "talk_to_npc" | "interact_object" | "dialog_continue" | "adjust_camera" | "wait",\n'
        '  "target": null | {\n'
        '      "x": int,\n'
        '      "y": int,\n'
        '      "plane": int,\n'
        '      "name": string,\n'
        '      "option": string\n'
        "  },\n"
        '  "meta": { "reason": string }\n'
        "}\n\n"
        "Rules:\n"
        " - Never invent map coordinates. For walking, only use the player's tile or a visible NPC tile.\n"
        " - Use interact_object for visible quest-relevant objects (e.g., trees, stoves, ladders).\n"
        " - Prefer dialog_continue when 'Click here to continue' is visible.\n"
        " - Prefer talk_to_npc to engage the next relevant quest NPC.\n"
        " - If do_all_quests is true, stay focused on the current quest without idling.\n"
        " - Use wait only if absolutely nothing safe is available.\n"
    )

    user_prompt = (
        f"Active quest: {quest_name or 'unknown'}\n\n"
        "Here is the current game state:\n\n"
        f"{summary}\n\n"
        "Decide the best SINGLE next action to keep the quest moving and return ONLY the JSON object."
    )

    resp = client.chat.completions.create(
        model="gpt-5-mini",
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
    )

    text = resp.choices[0].message.content.strip()

    try:
        start = text.find("{")
        end = text.rfind("}")
        if start != -1 and end != -1 and end > start:
            text = text[start : end + 1]
        return json.loads(text)
    except Exception as e:
        print("Failed to parse quest LLM JSON:", e, "raw:", text)
        return {}


def _default_quest(ctx: Dict[str, Any]) -> str:
    if ctx.get("current_quest"):
        return ctx["current_quest"]

    if ctx.get("do_all_quests"):
        return QUEST_SEQUENCE[0]

    return "Cook's Assistant"


def decide_quest_action(client, game_state: Dict[str, Any], ctx: Dict[str, Any]) -> Dict[str, Any]:
    # 1) Auto-continue dialogue when possible
    dialog = game_state.get("dialog") or {}
    if dialog.get("can_continue"):
        return {
            "action": "dialog_continue",
            "target": None,
            "meta": {"reason": "Dialogue has 'Click here to continue'."},
        }

    # 2) Ask the LLM for the best next step, but keep a deterministic fallback
    ctx = dict(ctx or {})
    ctx.setdefault("current_quest", _default_quest(ctx))

    llm_action = _call_llm_for_quest(client, game_state, ctx) if client is not None else {}

    if llm_action and isinstance(llm_action, dict) and llm_action.get("action"):
        # Ensure required keys exist
        llm_action.setdefault("target", None)
        llm_action.setdefault("meta", {"reason": "LLM provided action"})
        return llm_action

    # 3) Fallback deterministic behaviour to keep moving
    quest_name = ctx.get("current_quest") or ""
    npc = _find_best_npc(game_state, quest_name)

    if npc is not None:
        return {
            "action": "talk_to_npc",
            "target": {
                "name": npc.get("name"),
                # coords are not strictly needed for talk_to_npc on the Java side
                "x": npc.get("x"),
                "y": npc.get("y"),
                "plane": npc.get("plane"),
            },
            "meta": {
                "reason": f"Talking to quest NPC '{npc.get('name')}' for quest '{quest_name}'."
            },
        }

    return {
        "action": "wait",
        "target": None,
        "meta": {
            "reason": "No relevant quest NPC or dialogue – waiting safely instead of moving camera."
        },
    }