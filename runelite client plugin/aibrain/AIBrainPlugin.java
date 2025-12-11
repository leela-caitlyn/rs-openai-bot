package net.runelite.client.plugins.aibrain;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;

import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;

import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.ComponentID;
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
    private boolean queuedCameraAdjust;
    private boolean queuedDialogContinue;

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
        queuedCameraAdjust = false;
        queuedDialogContinue = false;
        synchronized (chatLog)
        {
            chatLog.clear();
        }
        autoTickCounter = 0;

        panel = new AIBrainPanel(this::executeOnceFromUI);

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
        queuedCameraAdjust = false;
        queuedDialogContinue = false;
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
        executeStep(false);
    }

    private void executeStep(boolean fromAuto)
    {
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
        for (NPC npc : client.getCachedNPCs())
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
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
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
                invArr.add(itm);
            }
        }
        root.add("inventory", invArr);

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

        Widget npcText = client.getWidget(ComponentID.DIALOG_NPC_TEXT);
        String npcStr = null;
        if (npcText != null)
        {
            npcStr = npcText.getText();
            dialog.addProperty("npc_text", npcStr);
        }

        Widget playerText = client.getWidget(ComponentID.DIALOG_PLAYER_TEXT);
        String playerStr = null;
        if (playerText != null)
        {
            playerStr = playerText.getText();
            dialog.addProperty("player_text", playerStr);
        }

        // Look for the dedicated "continue" widgets – this is the important part.
        Widget npcContinue = client.getWidget(ComponentID.DIALOG_NPC_CONTINUE);
        Widget playerContinue = client.getWidget(ComponentID.DIALOG_PLAYER_CONTINUE);

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
    }

    private void issueWalk(WorldPoint target)
    {
        clientThread.invoke(() ->
        {
            if (client.getLocalPlayer() == null || target == null)
            {
                return;
            }

            if (client.getPlane() != target.getPlane())
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
            for (NPC npc : client.getCachedNPCs())
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
            if (npcWp == null || npcWp.getPlane() != client.getPlane())
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

    @Provides
    AIBrainPluginConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(AIBrainPluginConfig.class);
    }
}