# manual_agent.py

import json
from typing import Any, Dict


def _extract_text_state(game_state: Dict[str, Any], ctx: Dict[str, Any]) -> str:
    player = game_state.get("player") or {}
    skills = game_state.get("skills") or {}
    npcs = game_state.get("npcs") or []
    objects = game_state.get("objects") or []
    dialog = game_state.get("dialog") or {}
    chat_log = game_state.get("chat_log") or []
    ui_text = game_state.get("ui_text") or []

    lines = []

    lines.append(
        f"CONTEXT (MANUAL): manual_goal={ctx.get('manual_goal')!r}, mode={ctx.get('mode')}"
    )

    if player:
        lines.append(
            f"PLAYER: name={player.get('name')}, pos=({player.get('x')},"
            f"{player.get('y')},{player.get('plane')})"
        )

    if skills:
        skill_pairs = [f"{k}={v}" for k, v in skills.items()]
        lines.append("SKILLS: " + ", ".join(skill_pairs))

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
        lines.append("UI_TEXT (first 30 entries):")
        for w in ui_text[:30]:
            lines.append(f"- [{w.get('group')}:{w.get('id')}] {w.get('text')}")

    return "\n".join(lines)


def _call_llm_for_manual(client, game_state: Dict[str, Any], ctx: Dict[str, Any]) -> Dict[str, Any]:
    summary = _extract_text_state(game_state, ctx)
    manual_goal = ctx.get("manual_goal") or ""

    system_prompt = (
        "You control an Old School RuneScape character via a bot.\n"
        "Your role is MANUAL: follow the human's high-level instruction (manual_goal) as much as possible,\n"
        "while choosing a single safe low-level action.\n\n"
        "You can ONLY choose ONE of these actions:\n"
        "  - walk_to_tile\n"
        "  - talk_to_npc\n"
        "  - interact_object\n"
        "  - dialog_continue\n"
        "  - adjust_camera\n"
        "  - wait\n\n"
        "Action JSON schema (strict):\n"
        "{\n"
        '  \"action\": \"walk_to_tile\" | \"talk_to_npc\" | \"interact_object\" | \"dialog_continue\" | \"adjust_camera\" | \"wait\",\n'
        "  \"target\": null | {\n"
        "      \"x\": int,\n"
        "      \"y\": int,\n"
        "      \"plane\": int,\n"
        "      \"name\": string,\n"
        "      \"option\": string\n"
        "  },\n"
        "  \"meta\": { \"reason\": string }\n"
        "}\n\n"
        "Rules:\n"
        " - Use manual_goal as the high-level desire (e.g. 'walk to the bank', 'talk to the guide').\n"
        " - Never invent map coordinates: for walking, only use player tile or tiles of visible NPCs/objects.\n"
        " - Prefer talk_to_npc for direct interactions and interact_object for things like trees, doors, or fires.\n"
        " - Use dialog_continue if a dialogue is open.\n"
        " - If the instruction cannot be furthered right now, use wait or adjust_camera.\n"
    )

    user_prompt = (
        f"manual_goal = {manual_goal!r}\n\n"
        "Here is the current game state and context:\n\n"
        f"{summary}\n\n"
        "Decide the best SINGLE next action and return ONLY the JSON object."
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
        print("Failed to parse manual LLM JSON:", e, "raw:", text)
        return {
            "action": "adjust_camera",
            "target": None,
            "meta": {"reason": "LLM JSON parse failed, adjust camera as safe fallback."},
        }


def decide_manual_action(client, game_state: Dict[str, Any], ctx: Dict[str, Any]) -> Dict[str, Any]:
    dialog = game_state.get("dialog") or {}
    if dialog.get("can_continue"):
        return {
            "action": "dialog_continue",
            "target": None,
            "meta": {"reason": "Dialogue shows 'Click here to continue'."},
        }

    return _call_llm_for_manual(client, game_state, ctx)