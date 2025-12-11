package net.runelite.client.plugins.aibrain;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;

import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;

import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.widgets.Widget;

import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

@Slf4j
@PluginDescriptor(
        name = "AI Brain",
        description = "Sends game state to a local AI brain server and executes its actions",
        tags = {"ai", "quest", "skill", "manual"}
)
public class AIBrainPlugin extends Plugin
{
    private static final Gson GSON = new Gson();
    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.parse("application/json; charset=utf-8");

    private static final int AUTO_DECISION_INTERVAL_TICKS = 12;
    private static final int MAX_CHAT_LOG = 50;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private AIBrainPluginConfig config;

    @Inject
    private ClientToolbar clientToolbar;

    private final OkHttpClient httpClient = new OkHttpClient();

    private WorldPoint queuedWalkTarget;
    private String queuedTalkNpcName;
    private WorldPoint queuedObjectTarget;
    private String queuedObjectName;
    private String queuedObjectOption;
    private String queuedUseItemName;
    private String queuedUseItemOnName;
    private String queuedUseItemOption;
    private Integer queuedUseItemSlot;
    private boolean queuedCameraAdjust;
    private boolean queuedDialogContinue;

    private boolean aiPaused;

    private final Deque<String> chatLog = new ArrayDeque<>();

    private AIBrainPanel panel;
    private NavigationButton navButton;

    private int autoTickCounter = 0;

    @Override
    protected void startUp() throws Exception
    {
        log.info("AI Brain plugin started");

        queuedWalkTarget = null;
        queuedTalkNpcName = null;
        queuedObjectTarget = null;
        queuedObjectName = null;
        queuedObjectOption = null;
        queuedUseItemName = null;
        queuedUseItemOnName = null;
        queuedUseItemOption = null;
        queuedUseItemSlot = null;
        queuedCameraAdjust = false;
        queuedDialogContinue = false;
        aiPaused = false;
        synchronized (chatLog)
        {
            chatLog.clear();
        }
        autoTickCounter = 0;

        panel = new AIBrainPanel((Runnable) this::executeOnceFromUI, (Runnable) this::stopExecutionFromUI);

        BufferedImage dummyIcon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);

        navButton = NavigationButton.builder()
                .tooltip("AI Brain")
                .icon(dummyIcon)
                .priority(5)
                .panel(panel)
                .build();

        clientToolbar.addNavigation(navButton);
    }

    @Override
    protected void shutDown() throws Exception
    {
        log.info("AI Brain plugin stopped");

        queuedWalkTarget = null;
        queuedTalkNpcName = null;
        queuedObjectTarget = null;
        queuedObjectName = null;
        queuedObjectOption = null;
        queuedUseItemName = null;
        queuedUseItemOnName = null;
        queuedUseItemOption = null;
        queuedUseItemSlot = null;
        queuedCameraAdjust = false;
        queuedDialogContinue = false;
        aiPaused = false;
        synchronized (chatLog)
        {
            chatLog.clear();
        }

        if (clientToolbar != null && navButton != null)
        {
            clientToolbar.removeNavigation(navButton);
        }
        navButton = null;
        panel = null;
    }

    // ===== UI helpers =====

    AIBrainMode getCurrentMode()
    {
        if (panel != null)
        {
            return panel.getSelectedMode();
        }
        return AIBrainMode.QUEST;
    }

    String getCurrentQuest()
    {
        if (panel != null)
        {
            String q = panel.getQuestName();
            if (q != null && !q.trim().isEmpty())
            {
                return q.trim();
            }
        }
        return "Cook's Assistant";
    }

    boolean isDoAllQuests()
    {
        return panel != null && panel.isDoAllQuests();
    }

    String getSkillName()
    {
        if (panel != null)
        {
            String s = panel.getSkillName();
            if (s != null && !s.trim().isEmpty())
            {
                return s.trim();
            }
        }
        return "Mining";
    }

    int getSkillTargetLevel()
    {
        if (panel != null)
        {
            int v = panel.getTargetLevel();
            if (v < 1) v = 1;
            if (v > 99) v = 99;
            return v;
        }
        return 10;
    }

    int getSkillBudget()
    {
        if (panel != null)
        {
            int v = panel.getBudgetGp();
            if (v < 0) v = 0;
            return v;
        }
        return 5000;
    }

    String getSkillingGoal()
    {
        if (panel != null)
        {
            String s = panel.getSkillGoal();
            if (s != null && !s.trim().isEmpty())
            {
                return s.trim();
            }
        }
        return "Train the selected skill safely.";
    }

    String getManualGoal()
    {
        if (panel != null)
        {
            String s = panel.getManualGoal();
            if (s != null && !s.trim().isEmpty())
            {
                return s.trim();
            }
        }
        return "";
    }

    void setPanelStatus(String status, String details, boolean running)
    {
        if (panel != null)
        {
            panel.setStatus(status, details, running);
        }
    }

    // ===== Called from panel =====

    void executeOnceFromUI()
    {
        aiPaused = false;
        executeStep(false);
    }

    void stopExecutionFromUI()
    {
        aiPaused = true;
        clearQueuedActions();
        setPanelStatus("Status: Stopped", "Execution halted by user.", false);
    }

    private void executeStep(boolean fromAuto)
    {
        if (aiPaused)
        {
            if (!fromAuto)
            {
                aiPaused = false;
            }
            else
            {
                setPanelStatus("Status: Stopped", "Execution paused. Use Execute AI step to resume.", false);
                return;
            }
        }

        if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
        {
            setPanelStatus("Status: Not logged in",
                    "Log into the game before executing.", false);
            return;
        }

        AIBrainMode mode = getCurrentMode();
        String modeLabel = mode != null ? mode.getDisplayName() : "Quest";

        setPanelStatus(
                "Status: Executing...",
                (fromAuto ? "Auto " : "Manual ") + modeLabel + " step...",
                true
        );

        JsonObject gameState = buildGameState();
        sendControlUpdate();
        sendToBrainAsync(gameState);
    }

    // ===== Events =====

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        ChatMessageType type = event.getType();

        if (type == ChatMessageType.GAMEMESSAGE
                || type == ChatMessageType.ENGINE
                || type == ChatMessageType.SPAM
                || type == ChatMessageType.BROADCAST)
        {
            String msg = event.getMessage();
            if (msg == null || msg.isEmpty())
            {
                return;
            }

            synchronized (chatLog)
            {
                if (chatLog.size() >= MAX_CHAT_LOG)
                {
                    chatLog.removeFirst();
                }
                chatLog.addLast(msg);
            }
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        if (queuedWalkTarget != null)
        {
            issueWalk(queuedWalkTarget);
            queuedWalkTarget = null;
        }

        if (queuedTalkNpcName != null && !queuedTalkNpcName.isEmpty())
        {
            issueTalkToNpc(queuedTalkNpcName);
            queuedTalkNpcName = null;
        }

        if (queuedObjectName != null && queuedObjectTarget != null)
        {
            issueInteractWithObject(queuedObjectName, queuedObjectOption, queuedObjectTarget);
            queuedObjectName = null;
            queuedObjectTarget = null;
            queuedObjectOption = null;
        }

        if (queuedUseItemName != null && !queuedUseItemName.isEmpty())
        {
            if (queuedUseItemOnName != null && !queuedUseItemOnName.isEmpty())
            {
                issueUseItemOnItem(queuedUseItemName, queuedUseItemOnName, queuedUseItemOption, queuedUseItemSlot);
            }
            else
            {
                issueUseInventoryItem(queuedUseItemName, queuedUseItemOption, queuedUseItemSlot);
            }

            queuedUseItemName = null;
            queuedUseItemOnName = null;
            queuedUseItemOption = null;
            queuedUseItemSlot = null;
        }

        if (queuedCameraAdjust)
        {
            adjustCameraSlightly();
            queuedCameraAdjust = false;
        }

        if (queuedDialogContinue)
        {
            issueDialogContinue();
            queuedDialogContinue = false;
        }

        if (aiPaused)
        {
            return;
        }

        autoTickCounter++;

        AIBrainMode mode = getCurrentMode();
        if (mode == AIBrainMode.QUEST || mode == AIBrainMode.SKILL)
        {
            if (autoTickCounter % AUTO_DECISION_INTERVAL_TICKS == 0)
            {
                executeStep(true);
            }
        }
    }

    // ===== Build game_state JSON =====

    private JsonObject buildGameState()
    {
        JsonObject root = new JsonObject();

        // ---- Player ----
        JsonObject playerJson = new JsonObject();
        if (client.getLocalPlayer() != null)
        {
            WorldPoint wp = client.getLocalPlayer().getWorldLocation();
            playerJson.addProperty("x", wp.getX());
            playerJson.addProperty("y", wp.getY());
            playerJson.addProperty("plane", wp.getPlane());
            playerJson.addProperty("name", client.getLocalPlayer().getName());
        }
        root.add("player", playerJson);

        // ---- Nearby NPCs ----
        JsonArray npcsArr = new JsonArray();
        for (NPC npc : client.getNpcs())
        {
            if (npc == null || npc.getName() == null || npc.getName().isEmpty())
            {
                continue;
            }

            WorldPoint wp = npc.getWorldLocation();
            if (wp == null)
            {
                continue;
            }

            if (client.getLocalPlayer() != null)
            {
                WorldPoint pw = client.getLocalPlayer().getWorldLocation();
                if (pw != null && pw.distanceTo(wp) > 20)
                {
                    continue;
                }
            }

            JsonObject n = new JsonObject();
            n.addProperty("id", npc.getId());
            n.addProperty("name", npc.getName());
            n.addProperty("x", wp.getX());
            n.addProperty("y", wp.getY());
            n.addProperty("plane", wp.getPlane());

            NPCComposition comp = npc.getComposition();
            if (comp != null && comp.getActions() != null)
            {
                JsonArray acts = new JsonArray();
                for (String a : comp.getActions())
                {
                    if (a != null && !a.isEmpty())
                    {
                        acts.add(a);
                    }
                }
                n.add("actions", acts);
            }

            npcsArr.add(n);
        }
        root.add("npcs", npcsArr);

        // ---- Inventory ----
        JsonArray invArr = new JsonArray();
        ItemContainer inv = client.getItemContainer(InventoryID.INV);
        if (inv != null)
        {
            Item[] items = inv.getItems();
            for (int slot = 0; slot < items.length; slot++)
            {
                Item it = items[slot];
                if (it == null || it.getId() <= 0)
                {
                    continue;
                }

                JsonObject itm = new JsonObject();
                itm.addProperty("slot", slot);
                itm.addProperty("id", it.getId());
                itm.addProperty("quantity", it.getQuantity());

                ItemComposition comp = client.getItemDefinition(it.getId());
                if (comp != null)
                {
                    itm.addProperty("name", comp.getName());
                }
                invArr.add(itm);
            }
        }
        root.add("inventory", invArr);

        // ---- Nearby objects ----
        JsonArray objectsArr = new JsonArray();
        WorldPoint playerWp = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
        int plane = client.getTopLevelWorldView().getPlane();
        Tile[][][] tiles = client.getTopLevelWorldView().getScene().getTiles();

        if (tiles != null && plane >= 0 && plane < tiles.length)
        {
            for (Tile[] row : tiles[plane])
            {
                if (row == null)
                {
                    continue;
                }

                for (Tile tile : row)
                {
                    if (tile == null)
                    {
                        continue;
                    }

                    for (GameObject obj : tile.getGameObjects())
                    {
                        if (obj == null)
                        {
                            continue;
                        }

                        WorldPoint wp = obj.getWorldLocation();
                        if (wp == null || wp.getPlane() != plane)
                        {
                            continue;
                        }

                        if (playerWp != null && playerWp.distanceTo(wp) > 20)
                        {
                            continue;
                        }

                        ObjectComposition comp = client.getObjectDefinition(obj.getId());
                        if (comp == null || comp.getName() == null || comp.getName().isEmpty())
                        {
                            continue;
                        }

                        JsonObject o = new JsonObject();
                        o.addProperty("id", obj.getId());
                        o.addProperty("name", comp.getName());
                        o.addProperty("x", wp.getX());
                        o.addProperty("y", wp.getY());
                        o.addProperty("plane", wp.getPlane());

                        if (comp.getActions() != null)
                        {
                            JsonArray acts = new JsonArray();
                            for (String a : comp.getActions())
                            {
                                if (a != null && !a.isEmpty())
                                {
                                    acts.add(a);
                                }
                            }
                            o.add("actions", acts);
                        }

                        objectsArr.add(o);
                    }
                }
            }
        }

        root.add("objects", objectsArr);

        // ---- Skills ----
        JsonObject skills = new JsonObject();
        for (Skill skill : Skill.values())
        {
            int level = client.getRealSkillLevel(skill);
            skills.addProperty(skill.getName(), level);
        }
        root.add("skills", skills);

        // ---- Dialogue (fixed can_continue) ----
        JsonObject dialog = new JsonObject();

        Widget npcText = client.getWidget(WidgetInfo.DIALOG_NPC_TEXT);
        String npcStr = null;
        if (npcText != null)
        {
            npcStr = npcText.getText();
            dialog.addProperty("npc_text", npcStr);
        }

        Widget playerText = client.getWidget(WidgetInfo.DIALOG_PLAYER_TEXT);
        String playerStr = null;
        if (playerText != null)
        {
            playerStr = playerText.getText();
            dialog.addProperty("player_text", playerStr);
        }

        // Look for the dedicated "continue" widgets – this is the important part.
        Widget npcContinue = client.getWidget(InterfaceID.ChatLeft.CONTINUE);
        Widget playerContinue = client.getWidget(InterfaceID.ChatRight.CONTINUE);

        boolean hasContinueWidget =
                (npcContinue != null && !npcContinue.isHidden())
                        || (playerContinue != null && !playerContinue.isHidden());

        // Fallback: textual hint, just in case
        boolean textHints = false;
        if (npcStr != null && npcStr.toLowerCase().contains("click here to continue"))
        {
            textHints = true;
        }
        if (playerStr != null && playerStr.toLowerCase().contains("click here to continue"))
        {
            textHints = true;
        }

        boolean canContinue = hasContinueWidget || textHints;
        dialog.addProperty("can_continue", canContinue);

        root.add("dialog", dialog);

        // ---- Chat log ----
        JsonArray chatArr = new JsonArray();
        synchronized (chatLog)
        {
            for (String msg : chatLog)
            {
                chatArr.add(msg);
            }
        }
        root.add("chat_log", chatArr);

        // ---- Context from UI ----
        JsonObject ctx = new JsonObject();
        AIBrainMode mode = getCurrentMode();
        ctx.addProperty("mode", mode != null ? mode.getWireName() : "quest");
        ctx.addProperty("current_quest", getCurrentQuest());
        ctx.addProperty("do_all_quests", isDoAllQuests());
        ctx.addProperty("skill_target", getSkillName());
        ctx.addProperty("skill_target_level", getSkillTargetLevel());
        ctx.addProperty("skill_budget_gp", getSkillBudget());
        ctx.addProperty("skilling_goal", getSkillingGoal());
        ctx.addProperty("manual_goal", getManualGoal());
        root.add("context", ctx);

        // ---- UI text (for future smarter agents) ----
        JsonArray uiArr = new JsonArray();
        try
        {
            final int MAX_GROUP = 800;
            final int MAX_CHILD = 800;

            for (int group = 0; group < MAX_GROUP; group++)
            {
                for (int child = 0; child < MAX_CHILD; child++)
                {
                    Widget w = client.getWidget(group, child);
                    if (w == null || w.isHidden())
                    {
                        continue;
                    }

                    String text = w.getText();
                    if (text == null)
                    {
                        continue;
                    }

                    String trimmed = text.trim();
                    if (trimmed.isEmpty())
                    {
                        continue;
                    }

                    if (trimmed.length() > 200)
                    {
                        trimmed = trimmed.substring(0, 200);
                    }

                    JsonObject ui = new JsonObject();
                    ui.addProperty("group", group);
                    ui.addProperty("id", w.getId());
                    ui.addProperty("text", trimmed);

                    uiArr.add(ui);
                }
            }
        }
        catch (Exception e)
        {
            log.debug("Error while collecting UI text", e);
        }
        root.add("ui_text", uiArr);

        return root;
    }

    // ===== HTTP helpers =====

    private String normalizeBaseUrl(String baseUrl)
    {
        if (baseUrl == null || baseUrl.isEmpty())
        {
            baseUrl = "http://127.0.0.1:9420";
        }
        if (baseUrl.endsWith("/"))
        {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private void sendToBrainAsync(JsonObject gameState)
    {
        try
        {
            String baseUrl = normalizeBaseUrl(config.brainServerUrl());
            String url = baseUrl + "/decide";

            String json = GSON.toJson(gameState);
            RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, json);

            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            new Thread(() ->
            {
                try (Response response = httpClient.newCall(request).execute())
                {
                    if (!response.isSuccessful())
                    {
                        log.warn("AI brain /decide error: {}", response);
                        setPanelStatus("Status: Error",
                                "Brain /decide HTTP " + response.code(), false);
                        return;
                    }

                    if (response.body() == null)
                    {
                        log.warn("AI brain /decide returned no body");
                        setPanelStatus("Status: Error", "Empty body from /decide", false);
                        return;
                    }

                    String respBody = response.body().string();
                    if (respBody.isEmpty())
                    {
                        log.warn("AI brain /decide returned empty string");
                        setPanelStatus("Status: Error", "Empty string from /decide", false);
                        return;
                    }

                    JsonObject actionJson = GSON.fromJson(respBody, JsonObject.class);
                    if (actionJson == null)
                    {
                        log.warn("AI brain /decide returned invalid JSON: {}", respBody);
                        setPanelStatus("Status: Error", "Invalid JSON from /decide", false);
                        return;
                    }

                    if (aiPaused)
                    {
                        setPanelStatus("Status: Stopped", "Execution halted by user.", false);
                        clearQueuedActions();
                        return;
                    }

                    handleActionResponse(actionJson);

                    String actionName = actionJson.has("action")
                            ? actionJson.get("action").getAsString()
                            : "unknown";

                    String reason = null;
                    if (actionJson.has("meta") && actionJson.get("meta").isJsonObject())
                    {
                        JsonObject meta = actionJson.getAsJsonObject("meta");
                        if (meta.has("reason") && !meta.get("reason").isJsonNull())
                        {
                            reason = meta.get("reason").getAsString();
                        }
                    }

                    String desc = "Last action: " + actionName;
                    if (reason != null && !reason.isEmpty())
                    {
                        desc += " – " + reason;
                    }

                    setPanelStatus("Status: Idle", desc, false);
                }
                catch (Exception e)
                {
                    log.warn("Failed to call AI brain /decide", e);
                    setPanelStatus("Status: Error", "Exception in /decide: " + e.getMessage(), false);
                }
            }, "aibrain-decide-http").start();
        }
        catch (Exception e)
        {
            log.warn("Error preparing AI brain /decide request", e);
            setPanelStatus("Status: Error", "Failed to prepare /decide request", false);
        }
    }

    private void sendControlUpdate()
    {
        try
        {
            JsonObject payload = new JsonObject();

            AIBrainMode mode = getCurrentMode();
            if (mode != null)
            {
                payload.addProperty("mode", mode.getWireName());
            }

            payload.addProperty("current_quest", getCurrentQuest());
            payload.addProperty("do_all_quests", isDoAllQuests());

            String skillName = getSkillName();
            if (skillName != null && !skillName.isEmpty())
            {
                payload.addProperty("skill_target", skillName);
            }
            payload.addProperty("skill_target_level", getSkillTargetLevel());
            payload.addProperty("skill_budget_gp", getSkillBudget());

            String skGoal = getSkillingGoal();
            if (skGoal != null && !skGoal.isEmpty())
            {
                payload.addProperty("skilling_goal", skGoal);
            }

            String manual = getManualGoal();
            if (manual != null && !manual.isEmpty())
            {
                payload.addProperty("manual_goal", manual);
            }

            String baseUrl = normalizeBaseUrl(config.brainServerUrl());
            String url = baseUrl + "/control";

            String json = GSON.toJson(payload);
            RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, json);

            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute())
            {
                if (!response.isSuccessful())
                {
                    log.debug("AI brain /control error: {}", response);
                }
            }
        }
        catch (Exception e)
        {
            log.debug("Error sending AI brain /control request", e);
        }
    }

    // ===== Action handling =====

    private void handleActionResponse(JsonObject actionJson)
    {
        if (aiPaused)
        {
            clearQueuedActions();
            return;
        }

        String action = actionJson.has("action")
                ? actionJson.get("action").getAsString()
                : "wait";

        JsonObject target = actionJson.has("target") && actionJson.get("target").isJsonObject()
                ? actionJson.getAsJsonObject("target")
                : null;

        if ("walk_to_tile".equalsIgnoreCase(action) && target != null)
        {
            int tx = target.has("x") ? target.get("x").getAsInt() : 0;
            int ty = target.has("y") ? target.get("y").getAsInt() : 0;
            int plane = target.has("plane") ? target.get("plane").getAsInt() : 0;
            queuedWalkTarget = new WorldPoint(tx, ty, plane);
            return;
        }

        if ("talk_to_npc".equalsIgnoreCase(action) && target != null)
        {
            String npcName = target.has("name") ? target.get("name").getAsString() : "";
            if (!npcName.isEmpty())
            {
                queuedTalkNpcName = npcName;
            }
            return;
        }

        if ("adjust_camera".equalsIgnoreCase(action))
        {
            queuedCameraAdjust = true;
            return;
        }

        if ("dialog_continue".equalsIgnoreCase(action))
        {
            queuedDialogContinue = true;
        }

        if ("interact_object".equalsIgnoreCase(action) && target != null)
        {
            int tx = target.has("x") ? target.get("x").getAsInt() : 0;
            int ty = target.has("y") ? target.get("y").getAsInt() : 0;
            int plane = target.has("plane") ? target.get("plane").getAsInt() : 0;
            queuedObjectTarget = new WorldPoint(tx, ty, plane);
            queuedObjectName = target.has("name") ? target.get("name").getAsString() : null;
            queuedObjectOption = target.has("option") ? target.get("option").getAsString() : null;
            return;
        }

        if ("use_inventory_item".equalsIgnoreCase(action) && target != null)
        {
            queuedUseItemName = target.has("name") ? target.get("name").getAsString() : null;
            queuedUseItemOption = target.has("option") ? target.get("option").getAsString() : "Use";
            queuedUseItemOnName = target.has("use_on_name") ? target.get("use_on_name").getAsString() : null;
            queuedUseItemSlot = target.has("slot") ? target.get("slot").getAsInt() : null;
            return;
        }

        autoTickCounter = 0;
    }

    private void issueWalk(WorldPoint target)
    {
        clientThread.invoke(() ->
        {
            if (client.getLocalPlayer() == null || target == null)
            {
                return;
            }

            if (client.getTopLevelWorldView().getPlane() != target.getPlane())
            {
                return;
            }

            LocalPoint lp = LocalPoint.fromWorld(client, target);
            if (lp == null)
            {
                return;
            }

            int sceneX = lp.getSceneX();
            int sceneY = lp.getSceneY();

            client.menuAction(
                    sceneX,
                    sceneY,
                    MenuAction.WALK,
                    0,
                    0,
                    "Walk here",
                    ""
            );
        });
    }

    private void issueTalkToNpc(String npcName)
    {
        clientThread.invoke(() ->
        {
            if (client.getLocalPlayer() == null || npcName == null || npcName.isEmpty())
            {
                return;
            }

            NPC targetNpc = null;
            for (NPC npc : client.getNpcs())
            {
                if (npc != null && npc.getName() != null && npc.getName().equalsIgnoreCase(npcName))
                {
                    targetNpc = npc;
                    break;
                }
            }

            if (targetNpc == null)
            {
                return;
            }

            WorldPoint npcWp = targetNpc.getWorldLocation();
            if (npcWp == null || npcWp.getPlane() != client.getTopLevelWorldView().getPlane())
            {
                return;
            }

            LocalPoint lp = LocalPoint.fromWorld(client, npcWp);
            if (lp == null)
            {
                return;
            }

            int sceneX = lp.getSceneX();
            int sceneY = lp.getSceneY();

            client.menuAction(
                    sceneX,
                    sceneY,
                    MenuAction.NPC_FIRST_OPTION,
                    targetNpc.getIndex(),
                    0,
                    "Talk-to",
                    targetNpc.getName()
            );
        });
    }

    private void issueInteractWithObject(String objectName, String option, WorldPoint target)
    {
        clientThread.invoke(() ->
        {
            if (client.getLocalPlayer() == null || target == null)
            {
                return;
            }

            int plane = client.getTopLevelWorldView().getPlane();
            if (plane != target.getPlane())
            {
                return;
            }

            Tile[][][] tiles = client.getTopLevelWorldView().getScene().getTiles();
            if (tiles == null || plane < 0 || plane >= tiles.length)
            {
                return;
            }

            GameObject found = null;

            for (Tile[] row : tiles[plane])
            {
                if (row == null)
                {
                    continue;
                }

                for (Tile tile : row)
                {
                    if (tile == null)
                    {
                        continue;
                    }

                    for (GameObject obj : tile.getGameObjects())
                    {
                        if (obj == null)
                        {
                            continue;
                        }

                        WorldPoint wp = obj.getWorldLocation();
                        if (wp == null || wp.getPlane() != target.getPlane())
                        {
                            continue;
                        }

                        if (wp.getX() != target.getX() || wp.getY() != target.getY())
                        {
                            continue;
                        }

                        ObjectComposition comp = client.getObjectDefinition(obj.getId());
                        if (comp == null)
                        {
                            continue;
                        }

                        if (objectName != null && !objectName.isEmpty())
                        {
                            if (!comp.getName().equalsIgnoreCase(objectName))
                            {
                                continue;
                            }
                        }

                        found = obj;
                        break;
                    }

                    if (found != null)
                    {
                        break;
                    }
                }

                if (found != null)
                {
                    break;
                }
            }

            if (found == null)
            {
                return;
            }

            ObjectComposition comp = client.getObjectDefinition(found.getId());
            LocalPoint lp = LocalPoint.fromWorld(client, target);
            if (comp == null || lp == null)
            {
                return;
            }

            String[] actions = comp.getActions();
            MenuAction menuAction = MenuAction.GAME_OBJECT_FIRST_OPTION;

            if (option != null && actions != null)
            {
                for (int i = 0; i < actions.length; i++)
                {
                    String act = actions[i];
                    if (act != null && option.equalsIgnoreCase(act))
                    {
                        switch (i)
                        {
                            case 1:
                                menuAction = MenuAction.GAME_OBJECT_SECOND_OPTION;
                                break;
                            case 2:
                                menuAction = MenuAction.GAME_OBJECT_THIRD_OPTION;
                                break;
                            case 3:
                                menuAction = MenuAction.GAME_OBJECT_FOURTH_OPTION;
                                break;
                            case 4:
                                menuAction = MenuAction.GAME_OBJECT_FIFTH_OPTION;
                                break;
                            default:
                                menuAction = MenuAction.GAME_OBJECT_FIRST_OPTION;
                                break;
                        }
                        break;
                    }
                }
            }

            String optLabel = option != null ? option : "Interact";
            String targetLabel = comp.getName();

            client.menuAction(
                    lp.getSceneX(),
                    lp.getSceneY(),
                    menuAction,
                    found.getId(),
                    0,
                    optLabel,
                    targetLabel
            );
        });
    }

    private int findInventorySlotByName(String itemName, Integer preferredSlot, Item[] items)
    {
        if (itemName == null || itemName.isEmpty() || items == null)
        {
            return -1;
        }

        if (preferredSlot != null && preferredSlot >= 0 && preferredSlot < items.length)
        {
            Item preferred = items[preferredSlot];
            if (preferred != null && preferred.getId() > 0)
            {
                ItemComposition comp = client.getItemDefinition(preferred.getId());
                if (comp != null && itemName.equalsIgnoreCase(comp.getName()))
                {
                    return preferredSlot;
                }
            }
        }

        for (int i = 0; i < items.length; i++)
        {
            Item it = items[i];
            if (it == null || it.getId() <= 0)
            {
                continue;
            }

            ItemComposition comp = client.getItemDefinition(it.getId());
            if (comp != null && comp.getName() != null && comp.getName().equalsIgnoreCase(itemName))
            {
                return i;
            }
        }

        return -1;
    }

    private void issueUseInventoryItem(String itemName, String option, Integer preferredSlot)
    {
        clientThread.invoke(() ->
        {
            ItemContainer inv = client.getItemContainer(InventoryID.INV);
            if (inv == null)
            {
                return;
            }

            Item[] items = inv.getItems();
            int slot = findInventorySlotByName(itemName, preferredSlot, items);
            if (slot < 0)
            {
                return;
            }

            Item item = items[slot];
            ItemComposition comp = item != null ? client.getItemDefinition(item.getId()) : null;
            String itemLabel = comp != null ? comp.getName() : itemName;
            String actionLabel = (option != null && !option.isEmpty()) ? option : "Use";

            MenuAction menuAction = MenuAction.ITEM_USE;
            if (!"use".equalsIgnoreCase(actionLabel))
            {
                menuAction = MenuAction.ITEM_FIRST_OPTION;
            }

            client.menuAction(
                    slot,
                    WidgetInfo.INVENTORY.getId(),
                    menuAction,
                    item.getId(),
                    item.getId(),
                    actionLabel,
                    itemLabel
            );
        });
    }

    private void issueUseItemOnItem(String sourceName, String targetName, String option, Integer preferredSlot)
    {
        clientThread.invoke(() ->
        {
            ItemContainer inv = client.getItemContainer(InventoryID.INV);
            if (inv == null)
            {
                return;
            }

            Item[] items = inv.getItems();
            int sourceSlot = findInventorySlotByName(sourceName, preferredSlot, items);
            int targetSlot = findInventorySlotByName(targetName, null, items);

            if (sourceSlot < 0 || targetSlot < 0)
            {
                return;
            }

            Item source = items[sourceSlot];
            Item target = items[targetSlot];
            if (source == null || target == null)
            {
                return;
            }

            ItemComposition srcComp = client.getItemDefinition(source.getId());
            ItemComposition tgtComp = client.getItemDefinition(target.getId());
            String actionLabel = (option != null && !option.isEmpty()) ? option : "Use";

            client.menuAction(
                    sourceSlot,
                    WidgetInfo.INVENTORY.getId(),
                    MenuAction.ITEM_USE,
                    source.getId(),
                    source.getId(),
                    actionLabel,
                    srcComp != null ? srcComp.getName() : sourceName
            );

            client.menuAction(
                    targetSlot,
                    WidgetInfo.INVENTORY.getId(),
                    MenuAction.ITEM_USE,
                    target.getId(),
                    target.getId(),
                    actionLabel,
                    tgtComp != null ? tgtComp.getName() : targetName
            );
        });
    }

    private void adjustCameraSlightly()
    {
        clientThread.invoke(() ->
        {
            int currentYaw = client.getCameraYaw();
            int currentPitch = client.getCameraPitch();

            int newYaw = (currentYaw + 256) & 2047;
            int newPitch = currentPitch + 32;
            if (newPitch > 383)
            {
                newPitch = 383;
            }

            client.setCameraYawTarget(newYaw);
            client.setCameraPitchTarget(newPitch);
        });
    }

    private void issueDialogContinue()
    {
        clientThread.invoke(() ->
        {
            client.menuAction(
                    0,
                    0,
                    MenuAction.WIDGET_CONTINUE,
                    0,
                    0,
                    "Continue",
                    ""
            );
        });
    }

    private void clearQueuedActions()
    {
        queuedWalkTarget = null;
        queuedTalkNpcName = null;
        queuedObjectTarget = null;
        queuedObjectName = null;
        queuedObjectOption = null;
        queuedUseItemName = null;
        queuedUseItemOnName = null;
        queuedUseItemOption = null;
        queuedUseItemSlot = null;
        queuedCameraAdjust = false;
        queuedDialogContinue = false;
    }

    @Provides
    AIBrainPluginConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(AIBrainPluginConfig.class);
    }
}