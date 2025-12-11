package net.runelite.client.plugins.aibrain;

import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.border.BorderFactory;
import javax.swing.border.EmptyBorder;
import java.awt.*;

class AIBrainPanel extends PluginPanel
{
    private final Runnable executeCallback;
    private Runnable stopCallback;

    private final JComboBox<AIBrainMode> modeCombo;
    private final JComboBox<String> questCombo;
    private final JCheckBox doAllQuestsCheck;

    private final JComboBox<String> skillCombo;
    private final JSpinner targetLevelSpinner;
    private final JSpinner budgetSpinner;

    private final JTextField skillGoalField;
    private final JTextField manualGoalField;

    private final JButton executeButton;
    private final JButton stopButton;
    private final JLabel statusLabel;
    private final JTextArea actionLabel;

    private final CardLayout cardLayout;
    private final JPanel cardPanel;

    private static final String[] F2P_QUESTS = new String[]{
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
            "Dragon Slayer I"
    };

    AIBrainPanel(Runnable executeCallback)
    {
        this(executeCallback, null);
    }

    AIBrainPanel(Runnable executeCallback, Runnable stopCallback)
    {
        this.executeCallback = executeCallback;
        this.stopCallback = stopCallback;

        setLayout(new BorderLayout());

        JPanel main = new JPanel();
        main.setLayout(new BoxLayout(main, BoxLayout.Y_AXIS));
        main.setBorder(new EmptyBorder(8, 8, 8, 8));

        JScrollPane scrollPane = new JScrollPane(main);
        scrollPane.setBorder(null);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scrollPane, BorderLayout.CENTER);

        // ---- Mode row ----
        JPanel modeRow = new JPanel(new BorderLayout(6, 0));
        JLabel modeLabel = new JLabel("Mode:");
        modeCombo = new JComboBox<>(AIBrainMode.values());
        modeCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, modeCombo.getPreferredSize().height));
        modeCombo.addActionListener(e -> updateModeCard());
        modeRow.add(modeLabel, BorderLayout.WEST);
        modeRow.add(modeCombo, BorderLayout.CENTER);
        modeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        main.add(modeRow);

        main.add(Box.createVerticalStrut(6));

        // ---- Card layout ----
        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);

        // QUEST card
        JPanel questCard = new JPanel();
        questCard.setLayout(new BoxLayout(questCard, BoxLayout.Y_AXIS));
        questCard.setAlignmentX(Component.LEFT_ALIGNMENT);

        questCard.add(new JLabel("Quest:"));
        questCombo = new JComboBox<>(F2P_QUESTS);
        questCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, questCombo.getPreferredSize().height));
        questCard.add(questCombo);
        questCard.add(Box.createVerticalStrut(4));
        doAllQuestsCheck = new JCheckBox("Do all F2P quests sequentially");
        questCard.add(doAllQuestsCheck);

        // SKILL card
        JPanel skillCard = new JPanel();
        skillCard.setLayout(new BoxLayout(skillCard, BoxLayout.Y_AXIS));
        skillCard.setAlignmentX(Component.LEFT_ALIGNMENT);

        skillCard.add(new JLabel("Skill:"));
        skillCombo = new JComboBox<>(new String[]{
                "Attack", "Strength", "Defence", "Hitpoints", "Ranged", "Magic", "Prayer",
                "Mining", "Smithing", "Woodcutting", "Fishing", "Cooking", "Firemaking",
                "Crafting", "Runecraft"
        });
        skillCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, skillCombo.getPreferredSize().height));
        skillCard.add(skillCombo);
        skillCard.add(Box.createVerticalStrut(4));

        skillCard.add(new JLabel("Target level:"));
        targetLevelSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 99, 1));
        targetLevelSpinner.setMaximumSize(new Dimension(Integer.MAX_VALUE, targetLevelSpinner.getPreferredSize().height));
        skillCard.add(targetLevelSpinner);
        skillCard.add(Box.createVerticalStrut(4));

        skillCard.add(new JLabel("Max GP per run:"));
        budgetSpinner = new JSpinner(new SpinnerNumberModel(5000, 0, 10_000_000, 1000));
        budgetSpinner.setMaximumSize(new Dimension(Integer.MAX_VALUE, budgetSpinner.getPreferredSize().height));
        skillCard.add(budgetSpinner);
        skillCard.add(Box.createVerticalStrut(4));

        skillCard.add(new JLabel("Skilling description (optional):"));
        skillGoalField = new JTextField();
        skillGoalField.setMaximumSize(new Dimension(Integer.MAX_VALUE, skillGoalField.getPreferredSize().height));
        skillCard.add(skillGoalField);

        // MANUAL card
        JPanel manualCard = new JPanel();
        manualCard.setLayout(new BoxLayout(manualCard, BoxLayout.Y_AXIS));
        manualCard.setAlignmentX(Component.LEFT_ALIGNMENT);

        manualCard.add(new JLabel("Manual instruction:"));
        manualGoalField = new JTextField();
        manualGoalField.setMaximumSize(new Dimension(Integer.MAX_VALUE, manualGoalField.getPreferredSize().height));
        manualCard.add(manualGoalField);

        cardPanel.add(questCard, AIBrainMode.QUEST.name());
        cardPanel.add(skillCard, AIBrainMode.SKILL.name());
        cardPanel.add(manualCard, AIBrainMode.MANUAL.name());

        JPanel cardWrapper = wrapSection("Mode details", cardPanel);
        main.add(cardWrapper);

        main.add(Box.createVerticalStrut(8));

        // ---- Execute controls ----
        JPanel controlRow = new JPanel();
        controlRow.setLayout(new BoxLayout(controlRow, BoxLayout.X_AXIS));

        executeButton = new JButton("Execute AI step");
        executeButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        executeButton.addActionListener(e ->
        {
            if (executeCallback != null)
            {
                executeCallback.run();
            }
        });

        stopButton = new JButton("Stop execution");
        stopButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        stopButton.addActionListener(e ->
        {
            if (stopCallback != null)
            {
                stopCallback.run();
            }
        });

        controlRow.add(executeButton);
        controlRow.add(Box.createHorizontalStrut(6));
        controlRow.add(stopButton);

        controlRow.setAlignmentX(Component.CENTER_ALIGNMENT);
        main.add(controlRow);

        main.add(Box.createVerticalStrut(8));

        // ---- Status / last action ----
        statusLabel = new JLabel("Status: Idle");
        actionLabel = new JTextArea("Last action: (none)");
        actionLabel.setLineWrap(true);
        actionLabel.setWrapStyleWord(true);
        actionLabel.setEditable(false);
        actionLabel.setOpaque(false);
        actionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel statusBody = new JPanel();
        statusBody.setLayout(new BoxLayout(statusBody, BoxLayout.Y_AXIS));
        statusBody.add(statusLabel);
        statusBody.add(actionLabel);

        JPanel statusPanel = wrapSection("Run status", statusBody);
        main.add(statusPanel);

        updateModeCard();
    }

    void setStopCallback(Runnable stopCallback)
    {
        this.stopCallback = stopCallback;
    }

    private JPanel wrapSection(String title, JComponent body)
    {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(BorderFactory.createTitledBorder(title));
        wrapper.add(body, BorderLayout.CENTER);
        wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        return wrapper;
    }

    private void updateModeCard()
    {
        AIBrainMode mode = getSelectedMode();
        if (mode == null)
        {
            mode = AIBrainMode.QUEST;
        }
        cardLayout.show(cardPanel, mode.name());
    }

    AIBrainMode getSelectedMode()
    {
        Object sel = modeCombo.getSelectedItem();
        if (sel instanceof AIBrainMode)
        {
            return (AIBrainMode) sel;
        }
        return AIBrainMode.QUEST;
    }

    String getQuestName()
    {
        Object v = questCombo.getSelectedItem();
        return v != null ? v.toString().trim() : "";
    }

    boolean isDoAllQuests()
    {
        return doAllQuestsCheck.isSelected();
    }

    String getSkillName()
    {
        Object v = skillCombo.getSelectedItem();
        return v != null ? v.toString() : "";
    }

    int getTargetLevel()
    {
        Object v = targetLevelSpinner.getValue();
        return (v instanceof Integer) ? (Integer) v : 1;
    }

    int getBudgetGp()
    {
        Object v = budgetSpinner.getValue();
        return (v instanceof Integer) ? (Integer) v : 0;
    }

    String getSkillGoal()
    {
        return skillGoalField.getText().trim();
    }

    String getManualGoal()
    {
        return manualGoalField.getText().trim();
    }

    void setStatus(String status, String details, boolean running)
    {
        SwingUtilities.invokeLater(() ->
        {
            statusLabel.setText(status != null ? status : "");
            actionLabel.setText(details != null ? details : "");
            executeButton.setEnabled(!running);
            stopButton.setEnabled(true);
        });
    }
}