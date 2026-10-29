package forge.neo.ui;

import forge.neo.NeoText;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import forge.neo.NeoSettings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public class SettingsPanel extends VBox {

    public static void applyHoverZoom(final javafx.scene.Node root, final int pct) {
        NeoSettings.setInt(NeoSettings.HOVER_ZOOM, pct);
        NeoSettings.save();

        if (root != null) {
            relayoutHoverRoom(root);
        }
    }

    private static void relayoutHoverRoom(final javafx.scene.Node node) {
        if (node instanceof HandFan || node instanceof BattlefieldPane) {
            ((javafx.scene.Parent) node).requestLayout();
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (final javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                relayoutHoverRoom(child);
            }
        }
    }

    public interface Host {
        
        void setAutoPayMana(boolean on);

        void setPauseMode(int mode);

        void setAiSpeed(int hundredths);

        void applyScale();

        boolean isFullScreen();

        void setFullScreen(boolean on);

        double getTextZoom();

        void setTextZoom(double zoom);

        default boolean isInMatch() {
            return false;
        }

        default void refreshTable() {
        }

        default boolean restartToImport() {
            return false;
        }
    }

    private final Label title = new Label(NeoText.get("settings.title"));

    private final VBox content = new VBox(6);

    private final javafx.scene.control.ScrollPane scroll =
            new javafx.scene.control.ScrollPane(content);

    private final HBox footer = new HBox();

    private final boolean inMatch;

    public SettingsPanel(final Host host, final Runnable onClose) {
        this.inMatch = host.isInMatch();
        LayoutAudit.installIfAsked(this, "ajustes");
        getStyleClass().addAll("dialog", "settings");
        setSpacing(6);
        setPadding(new Insets(22, 26, 18, 26));
        setMaxWidth(Region.USE_PREF_SIZE);
        setMaxHeight(Region.USE_PREF_SIZE);

        title.getStyleClass().add("dialog-title");

        getChildren().add(title);
        tab(Tab.GENERAL);
        getChildren().add(section(NeoText.get("settings.language")));

        final java.util.List<forge.neo.NeoLanguage.Option> langs =
                forge.neo.NeoLanguage.available();
        final String[] labels = new String[langs.size()];
        for (int i = 0; i < langs.size(); i++) {
            labels[i] = langs.get(i).getLabel();
        }

        final Label note = new Label();
        note.getStyleClass().add("home-subtitle");
        note.setWrapText(true);
        note.setMaxWidth(UiScale.px(560));
        note.setMinHeight(Region.USE_PREF_SIZE);
        note.setText(noteFor(langs, forge.neo.NeoLanguage.current(), false));

        getChildren().add(choiceRow(NeoText.get("settings.language.row"), labels,
                labelFor(langs, forge.neo.NeoLanguage.current()),
                v -> {
                    for (final forge.neo.NeoLanguage.Option o : langs) {
                        if (v.equals(o.getLabel())) {
                            forge.neo.NeoLanguage.set(o.getId());
                            note.setText(noteFor(langs, o.getId(), true));
                            break;
                        }
                    }
                }));
        getChildren().add(note);

        tab(Tab.DISPLAY);

        final Double savedScale = NeoSettings.getScale();
        getChildren().add(choiceRow(NeoText.get("settings.scale"),
                new String[] {"Auto", "90%", "100%", "115%", "130%", "150%"},
                savedScale == null ? "Auto"
                        : String.format(java.util.Locale.ROOT, "%.0f%%", savedScale * 100),
                v -> {
                    final Double scale = "Auto".equals(v) ? null
                            : Double.valueOf(Integer.parseInt(v.replace("%", "")) / 100.0);
                    UiScale.setOverride(scale);
                    NeoSettings.setScale(scale);
                    NeoSettings.save();
                    host.applyScale();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.animations"), NeoSettings.getBool(NeoSettings.ANIMATIONS, true),
                on -> {
                    forge.neo.card.CardNode.setAnimationsEnabled(on);
                    NeoSettings.setBool(NeoSettings.ANIMATIONS, on);
                    NeoSettings.save();
                }));

        final int[] hoverPcts = {100, 108, 120, 135, 150};
        final String[] hoverLabels = new String[hoverPcts.length];
        for (int i = 0; i < hoverPcts.length; i++) {
            hoverLabels[i] = hoverPcts[i] == 100
                    ? NeoText.get("settings.hoverZoom.off") : hoverPcts[i] + "%";
        }
        final int hoverNow = (int) Math.round(NeoSettings.hoverZoom() * 100);
        getChildren().add(choiceRow(NeoText.get("settings.hoverZoom"), hoverLabels,
                hoverNow == 100 ? NeoText.get("settings.hoverZoom.off") : hoverNow + "%",
                v -> applyHoverZoom(getScene() == null ? null : getScene().getRoot(),
                        v.endsWith("%") ? Integer.parseInt(v.replace("%", "")) : 100)));

        getChildren().add(toggleRow(NeoText.get("settings.handFan"),
                NeoSettings.handFan(),
                on -> {
                    HandFan.setFanned(on);
                    NeoSettings.setBool(NeoSettings.HAND_FAN, on);
                    NeoSettings.save();

                    if (getScene() != null && getScene().getRoot() != null) {
                        HandFan.relayoutAllIn(getScene().getRoot());
                    }
                }));

        getChildren().add(toggleRow(NeoText.get("settings.orderHand"),
                NeoSettings.orderHand(),
                on -> {
                    NeoSettings.setBool(NeoSettings.ORDER_HAND, on);
                    NeoSettings.save();
                    if (getScene() != null && getScene().getRoot() != null) {
                        TableScreen.reorderHandsIn(getScene().getRoot());
                    }
                }));

        final String[] touchValues = {NeoSettings.TOUCH_HAND_OFF,
                NeoSettings.TOUCH_HAND_FINGER, NeoSettings.TOUCH_HAND_ALWAYS};
        final String[] touchLabels = {NeoText.get("settings.touchHand.off"),
                NeoText.get("settings.touchHand.finger"),
                NeoText.get("settings.touchHand.always")};
        final String touchNow = NeoSettings.touchHand();
        String touchCurrent = touchLabels[0];
        for (int i = 0; i < touchValues.length; i++) {
            if (touchValues[i].equals(touchNow)) {
                touchCurrent = touchLabels[i];
            }
        }
        getChildren().add(choiceRow(NeoText.get("settings.touchHand"), touchLabels, touchCurrent,
                v -> {
                    for (int i = 0; i < touchLabels.length; i++) {
                        if (touchLabels[i].equals(v)) {
                            NeoSettings.set(NeoSettings.TOUCH_HAND, touchValues[i]);
                        }
                    }
                    NeoSettings.save();
                }));
        final Label touchNote = new Label(NeoText.get("settings.touchHand.note"));
        touchNote.getStyleClass().add("settings-note");
        touchNote.setWrapText(true);
        touchNote.setMaxWidth(UiScale.px(560));
        touchNote.setMinHeight(Region.USE_PREF_SIZE);
        getChildren().add(touchNote);

        getChildren().add(toggleRow(NeoText.get("settings.keywordBadges"),
                forge.neo.card.CardNode.areKeywordBadgesEnabled(),
                on -> {
                    forge.neo.card.CardNode.setKeywordBadgesEnabled(on);
                    NeoSettings.setBool(NeoSettings.KEYWORD_BADGES, on);
                    NeoSettings.save();
                    if (getScene() != null && getScene().getRoot() != null) {
                        forge.neo.card.CardNode.refreshAllIn(getScene().getRoot());
                    }
                }));

        getChildren().add(toggleRow(NeoText.get("settings.foilEffect"),
                NeoSettings.getBool(NeoSettings.FOIL_EFFECT, true),
                on -> {
                    forge.neo.card.CardNode.setFoilEffectEnabled(on);
                    NeoSettings.setBool(NeoSettings.FOIL_EFFECT, on);
                    NeoSettings.save();
                    if (getScene() != null && getScene().getRoot() != null) {
                        forge.neo.card.CardNode.refreshAllIn(getScene().getRoot());
                    }
                }));

        getChildren().add(toggleRow(NeoText.get("settings.impactEffects"),
                NeoSettings.getBool(NeoSettings.IMPACT_EFFECTS, true),
                on -> {
                    ImpactLayer.setEnabled(on);
                    NeoSettings.setBool(NeoSettings.IMPACT_EFFECTS, on);
                    NeoSettings.save();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.commanderEntrance"),
                NeoSettings.getBool(NeoSettings.COMMANDER_ENTRANCE, true),
                on -> {
                    ImpactLayer.setEntranceEnabled(on);
                    NeoSettings.setBool(NeoSettings.COMMANDER_ENTRANCE, on);
                    NeoSettings.save();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.sharpArt"),
                forge.neo.card.CardImages.isSharp(),
                on -> {
                    NeoSettings.setBool(NeoSettings.SHARP_ART, on);
                    NeoSettings.save();
                    forge.neo.card.CardImages.setSharp(on);
                    if (getScene() != null && getScene().getRoot() != null) {
                        forge.neo.card.CardNode.refreshAllIn(getScene().getRoot());
                    }
                }));

        getChildren().add(toggleRow(NeoText.get("settings.cardArtLanguage"),
                NeoSettings.getBool(NeoSettings.CARD_ART_LANGUAGE, true),
                on -> {
                    NeoSettings.setBool(NeoSettings.CARD_ART_LANGUAGE, on);
                    NeoSettings.save();
                    forge.neo.card.CardArt.reload();

                    forge.neo.card.CardImages.clear();
                    if (getScene() != null && getScene().getRoot() != null) {
                        forge.neo.card.CardNode.refreshAllIn(getScene().getRoot());
                    }
                }));

        final String[] artLabels = {
                NeoText.get("settings.cardArt.latest"),
                NeoText.get("settings.cardArt.latestCore"),
                NeoText.get("settings.cardArt.original"),
                NeoText.get("settings.cardArt.originalCore")};
        final boolean[][] artValues = {{true, false}, {true, true}, {false, false}, {false, true}};
        final boolean artLatestNow =
                NeoSettings.getBool(NeoSettings.CARD_ART_LATEST, NeoSettings.CARD_ART_LATEST_DEFAULT);
        final boolean artCoreNow = NeoSettings.getBool(NeoSettings.CARD_ART_CORE_ONLY,
                NeoSettings.CARD_ART_CORE_ONLY_DEFAULT);
        String artCurrent = artLabels[0];
        for (int i = 0; i < artValues.length; i++) {
            if (artValues[i][0] == artLatestNow && artValues[i][1] == artCoreNow) {
                artCurrent = artLabels[i];
            }
        }
        getChildren().add(choiceRow(NeoText.get("settings.cardArt"), artLabels, artCurrent,
                v -> {
                    for (int i = 0; i < artLabels.length; i++) {
                        if (artLabels[i].equals(v)) {
                            NeoSettings.setBool(NeoSettings.CARD_ART_LATEST, artValues[i][0]);
                            NeoSettings.setBool(NeoSettings.CARD_ART_CORE_ONLY, artValues[i][1]);
                        }
                    }
                    NeoSettings.save();
                    NeoSettings.applyCardArtToEngine();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.randomArtInPools"),
                NeoSettings.getBool(NeoSettings.RANDOM_ART_IN_POOLS, true),
                on -> {
                    NeoSettings.setBool(NeoSettings.RANDOM_ART_IN_POOLS, on);
                    NeoSettings.save();
                    NeoSettings.applyCardArtToEngine();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.fullscreen"), host.isFullScreen(),
                on -> {
                    host.setFullScreen(on);
                    NeoSettings.setBool(NeoSettings.FULLSCREEN, on);
                    NeoSettings.save();
                }));

        tab(Tab.GAME);

        getChildren().add(toggleRow(NeoText.get("settings.autoMana"),
                NeoSettings.autoPayMana(),
                on -> {
                    host.setAutoPayMana(on);
                    NeoSettings.setBool(NeoSettings.AUTO_MANA, on);
                    NeoSettings.save();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.autoManaNoCreatures"),
                NeoSettings.autoManaNoCreatures(), on -> {
                    NeoSettings.setBool(NeoSettings.AUTO_MANA_NO_CREATURES, on);
                    NeoSettings.save();
                }));
        getChildren().add(toggleRow(NeoText.get("settings.autoManaQuick"),
                NeoSettings.autoManaQuick(), on -> {
                    NeoSettings.setBool(NeoSettings.AUTO_MANA_QUICK, on);
                    NeoSettings.save();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.blindMana"),
                NeoSettings.getBool(NeoSettings.BLIND_MANA, false),
                on -> {
                    forge.neo.match.NeoMatchUI.setBlindSourceFallback(on);
                    NeoSettings.setBool(NeoSettings.BLIND_MANA, on);
                    NeoSettings.save();
                }));

        final String[] mulliganRules = {"London", "Vancouver", "Paris", "Original", "Houston",
                forge.neo.match.FriendlyMulligan.RULE};
        final String[] mulliganLabels = {
                NeoText.get("settings.mulligan.london"),
                NeoText.get("settings.mulligan.vancouver"),
                NeoText.get("settings.mulligan.paris"),
                NeoText.get("settings.mulligan.original"),
                NeoText.get("settings.mulligan.houston"),
                NeoText.get("settings.mulligan.friendly")};
        final String mulliganNow =
                NeoSettings.get(NeoSettings.MULLIGAN_RULE, NeoSettings.MULLIGAN_RULE_DEFAULT);
        String mulliganCurrent = mulliganLabels[0];
        for (int i = 0; i < mulliganRules.length; i++) {
            if (mulliganRules[i].equals(mulliganNow)) {
                mulliganCurrent = mulliganLabels[i];
            }
        }

        final Label mulliganNote = nextGameNote();
        getChildren().add(choiceRow(NeoText.get("settings.mulligan"),
                mulliganLabels, mulliganCurrent,
                nextGame(v -> {
                    String rule = NeoSettings.MULLIGAN_RULE_DEFAULT;
                    for (int i = 0; i < mulliganLabels.length; i++) {
                        if (mulliganLabels[i].equals(v)) {
                            rule = mulliganRules[i];
                        }
                    }
                    NeoSettings.set(NeoSettings.MULLIGAN_RULE, rule);
                    NeoSettings.save();
                }, mulliganNote)));
        getChildren().add(mulliganNote);

        final Label anteNote = nextGameNote();
        final boolean anteNow = NeoSettings.getBool(NeoSettings.ANTE, false);
        final Region anteRarityRow = toggleRow(NeoText.get("settings.anteMatchRarity"),
                NeoSettings.getBool(NeoSettings.ANTE_MATCH_RARITY, false),
                nextGame(on -> {
                    NeoSettings.setBool(NeoSettings.ANTE_MATCH_RARITY, on);
                    NeoSettings.save();
                }, anteNote));
        final Region anteLandsRow = toggleRow(NeoText.get("settings.anteBasicLands"),
                NeoSettings.getBool(NeoSettings.ANTE_INCLUDE_BASIC_LANDS, false),
                nextGame(on -> {
                    NeoSettings.setBool(NeoSettings.ANTE_INCLUDE_BASIC_LANDS, on);
                    NeoSettings.save();
                }, anteNote));
        anteRarityRow.setVisible(anteNow);
        anteRarityRow.setManaged(anteNow);
        anteLandsRow.setVisible(anteNow);
        anteLandsRow.setManaged(anteNow);

        getChildren().add(toggleRow(NeoText.get("settings.ante"), anteNow,
                nextGame(on -> {
                    NeoSettings.setBool(NeoSettings.ANTE, on);
                    NeoSettings.save();
                    anteRarityRow.setVisible(on);
                    anteRarityRow.setManaged(on);
                    anteLandsRow.setVisible(on);
                    anteLandsRow.setManaged(on);
                }, anteNote)));
        getChildren().add(anteRarityRow);
        getChildren().add(anteLandsRow);

        getChildren().add(anteNote);

        final String[] confirmLabels = {
                NeoText.get("settings.confirmPhase.never"),
                NeoText.get("settings.confirmPhase.combat"),
                NeoText.get("settings.confirmPhase.both")};
        final int confirmNow = NeoSettings.confirmPhaseMode();
        getChildren().add(choiceRow(NeoText.get("settings.confirmPhase"),
                confirmLabels, confirmLabels[confirmNow],
                v -> {
                    int mode = NeoSettings.CONFIRM_PHASE_DEFAULT;
                    for (int i = 0; i < confirmLabels.length; i++) {
                        if (confirmLabels[i].equals(v)) {
                            mode = i;
                        }
                    }
                    NeoSettings.setInt(NeoSettings.CONFIRM_PHASE, mode);
                    NeoSettings.save();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.confirmBlock"),
                NeoSettings.confirmNoBlock(), on -> {
                    NeoSettings.setBool(NeoSettings.CONFIRM_NO_BLOCK, on);
                    NeoSettings.save();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.confirmPick"),
                NeoSettings.confirmLastPick(), on -> {
                    NeoSettings.setBool(NeoSettings.CONFIRM_LAST_PICK, on);
                    NeoSettings.save();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.confirmActivate"),
                NeoSettings.confirmActivate(), on -> {
                    NeoSettings.setBool(NeoSettings.CONFIRM_ACTIVATE, on);
                    NeoSettings.save();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.confirmHandAbility"),
                NeoSettings.confirmHandAbility(), on -> {
                    NeoSettings.setBool(NeoSettings.CONFIRM_HAND_ABILITY, on);
                    NeoSettings.save();
                }));

        final String[] smartLabels = {
                NeoText.get("settings.smartPass.low"),
                NeoText.get("settings.smartPass.normal"),
                NeoText.get("settings.smartPass.all")};
        final Region smartLevelRow = choiceRow(NeoText.get("settings.smartPass.level"),
                smartLabels, smartLabels[NeoSettings.smartPassLevel()],
                v -> {
                    int level = 0;
                    for (int i = 0; i < smartLabels.length; i++) {
                        if (smartLabels[i].equals(v)) {
                            level = i;
                        }
                    }
                    NeoSettings.setInt(NeoSettings.SMART_PASS_LEVEL, level);
                    NeoSettings.save();
                    forge.neo.match.NeoGame.refreshSmartPass();
                });
        final boolean autoPassNow = NeoSettings.getBool(NeoSettings.AUTO_PASS, true);
        final boolean[] smartState = {NeoSettings.getBool(NeoSettings.SMART_PASS, false)};
        smartLevelRow.setVisible(autoPassNow && smartState[0]);
        smartLevelRow.setManaged(autoPassNow && smartState[0]);

        final Region smartRow = toggleRow(NeoText.get("settings.smartPass"), smartState[0],
                on -> {
                    smartState[0] = on;
                    NeoSettings.setBool(NeoSettings.SMART_PASS, on);
                    NeoSettings.save();
                    forge.neo.match.NeoGame.refreshSmartPass();
                    smartLevelRow.setVisible(on);
                    smartLevelRow.setManaged(on);
                });
        smartRow.setVisible(autoPassNow);
        smartRow.setManaged(autoPassNow);

        getChildren().add(toggleRow(NeoText.get("settings.autoPass"), autoPassNow,
                on -> {
                    NeoSettings.setBool(NeoSettings.AUTO_PASS, on);
                    NeoSettings.save();
                    forge.neo.match.NeoGame.refreshAutoPass();
                    smartRow.setVisible(on);
                    smartRow.setManaged(on);
                    smartLevelRow.setVisible(on && smartState[0]);
                    smartLevelRow.setManaged(on && smartState[0]);
                }));
        getChildren().add(smartRow);
        getChildren().add(smartLevelRow);

        getChildren().add(toggleRow(NeoText.get("settings.endTurnInterrupts"),
                NeoSettings.endTurnInterrupts(), on -> {
                    NeoSettings.setBool(NeoSettings.END_TURN_INTERRUPTS, on);
                    NeoSettings.save();
                    forge.neo.match.NeoGame.refreshSmartPass();
                }));
        final Label endTurnNote = new Label(NeoText.get("settings.endTurnInterrupts.note"));
        endTurnNote.getStyleClass().add("settings-note");
        endTurnNote.setWrapText(true);
        getChildren().add(endTurnNote);

        

        final String[] pauseLabels = {
                NeoText.get("settings.pause.never"),
                NeoText.get("settings.pause.affects"),
                NeoText.get("settings.pause.stack"),
                NeoText.get("settings.pause.always")};
        final int pauseNow = Math.max(0, Math.min(3,
                NeoSettings.getInt(NeoSettings.PAUSE_MODE, 2)));

        final String[] newsLabels = {
                NeoText.get("settings.news.corner"),
                NeoText.get("settings.news.center")};

        final int[] autoValues = {0, 5, 7, 10};
        final String[] autoLabels = {
                NeoText.get("settings.newsAutoClose.never"),
                NeoText.get("settings.newsAutoClose.seconds", 5),
                NeoText.get("settings.newsAutoClose.seconds", 7),
                NeoText.get("settings.newsAutoClose.seconds", 10)};
        int autoNow = 0;
        for (int i = 0; i < autoValues.length; i++) {
            if (autoValues[i] == NeoSettings.getInt(NeoSettings.NEWS_AUTOCLOSE, 0)) {
                autoNow = i;
            }
        }
        final Region autoRow = choiceRow(NeoText.get("settings.newsAutoClose"),
                autoLabels, autoLabels[autoNow],
                v -> {
                    for (int i = 0; i < autoLabels.length; i++) {
                        if (autoLabels[i].equals(v)) {
                            NeoSettings.setInt(NeoSettings.NEWS_AUTOCLOSE, autoValues[i]);
                        }
                    }
                    NeoSettings.save();
                });
        final java.util.function.IntConsumer showAuto = pause -> {
            final boolean shown = pause > 0 && NeoSettings.getInt(NeoSettings.NEWS_STYLE, 0) == 1;
            autoRow.setVisible(shown);
            autoRow.setManaged(shown);
        };
        final Region newsRow = choiceRow(NeoText.get("settings.news"),
                newsLabels, newsLabels[Math.max(0, Math.min(1,
                        NeoSettings.getInt(NeoSettings.NEWS_STYLE, 0)))],
                v -> {
                    NeoSettings.setInt(NeoSettings.NEWS_STYLE, newsLabels[1].equals(v) ? 1 : 0);
                    NeoSettings.save();
                    showAuto.accept(NeoSettings.getInt(NeoSettings.PAUSE_MODE, 2));
                });
        newsRow.setVisible(pauseNow > 0);
        newsRow.setManaged(pauseNow > 0);
        showAuto.accept(pauseNow);
        getChildren().add(choiceRow(NeoText.get("settings.pause"),
                pauseLabels, pauseLabels[pauseNow],
                v -> {
                    int mode = 0;
                    for (int i = 0; i < pauseLabels.length; i++) {
                        if (pauseLabels[i].equals(v)) {
                            mode = i;
                        }
                    }
                    host.setPauseMode(mode);
                    NeoSettings.setInt(NeoSettings.PAUSE_MODE, mode);
                    NeoSettings.save();
                    newsRow.setVisible(mode > 0);
                    newsRow.setManaged(mode > 0);
                    showAuto.accept(mode);
                }));
        getChildren().add(newsRow);
        getChildren().add(autoRow);

        final String[] speedLabels = {
                NeoText.get("settings.aiSpeed.none"),
                "x2", "x1", "x0,5"};
        final int[] speedValues = {0, 200, 100, 50};
        final int speedNow = NeoSettings.getInt(NeoSettings.AI_SPEED, 100);
        String speedCurrent = speedLabels[2];
        for (int i = 0; i < speedValues.length; i++) {
            if (speedValues[i] == speedNow) {
                speedCurrent = speedLabels[i];
            }
        }
        getChildren().add(choiceRow(NeoText.get("settings.aiSpeed"),
                speedLabels, speedCurrent,
                v -> {
                    int value = 100;
                    for (int i = 0; i < speedLabels.length; i++) {
                        if (speedLabels[i].equals(v)) {
                            value = speedValues[i];
                        }
                    }
                    host.setAiSpeed(value);
                    NeoSettings.setInt(NeoSettings.AI_SPEED, value);
                    NeoSettings.save();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.pauseWhileReading"),
                NeoSettings.pauseWhileReading(),
                on -> {
                    NeoSettings.setBool(NeoSettings.PAUSE_WHILE_READING, on);
                    NeoSettings.save();
                }));

        final Label aiNote = nextGameNote();
        getChildren().add(toggleRow(NeoText.get("settings.aiCheatShuffle"),
                NeoSettings.getBool(NeoSettings.AI_CHEAT_SHUFFLE, false),
                nextGame(on -> {
                    NeoSettings.setBool(NeoSettings.AI_CHEAT_SHUFFLE, on);
                    NeoSettings.save();
                }, aiNote)));

        final String[] timeoutLabels = {"2 s", "5 s", "10 s", "20 s", "60 s"};
        final int[] timeoutValues = {2, 5, 10, 20, 60};
        final int timeoutNow = NeoSettings.getInt(NeoSettings.AI_TIMEOUT, NeoSettings.AI_TIMEOUT_DEFAULT);
        String timeoutCurrent = timeoutLabels[1];
        for (int i = 0; i < timeoutValues.length; i++) {
            if (timeoutValues[i] == timeoutNow) {
                timeoutCurrent = timeoutLabels[i];
            }
        }
        
        getChildren().add(choiceRow(NeoText.get("settings.aiTimeout"),
                timeoutLabels, timeoutCurrent,
                nextGame(v -> {
                    int value = NeoSettings.AI_TIMEOUT_DEFAULT;
                    for (int i = 0; i < timeoutLabels.length; i++) {
                        if (timeoutLabels[i].equals(v)) {
                            value = timeoutValues[i];
                        }
                    }
                    NeoSettings.setInt(NeoSettings.AI_TIMEOUT, value);
                    NeoSettings.save();
                }, aiNote)));

        final String[] friendlyLabels = {NeoText.get("settings.aiFriendly.normal"),
                NeoText.get("settings.aiFriendly.low"), NeoText.get("settings.aiFriendly.veryLow")};
        final int friendlyNow = Math.max(0, Math.min(2, NeoSettings.getInt(
                forge.neo.match.FriendlyAttacks.SETTING, forge.neo.match.FriendlyAttacks.NORMAL)));
        getChildren().add(choiceRow(NeoText.get("settings.aiFriendly"),
                friendlyLabels, friendlyLabels[friendlyNow],
                nextGame(v -> {
                    int value = forge.neo.match.FriendlyAttacks.NORMAL;
                    for (int i = 0; i < friendlyLabels.length; i++) {
                        if (friendlyLabels[i].equals(v)) {
                            value = i;
                        }
                    }
                    NeoSettings.setInt(forge.neo.match.FriendlyAttacks.SETTING, value);
                    NeoSettings.save();
                }, aiNote)));
        final Label friendlyHint = new Label(NeoText.get("settings.aiFriendly.note"));
        friendlyHint.getStyleClass().add("settings-note");
        friendlyHint.setWrapText(true);
        getChildren().add(friendlyHint);
        getChildren().add(aiNote);

        tab(Tab.TABLE);

        getChildren().add(toggleRow(NeoText.get("settings.showRound"),
                forge.neo.match.RoundCounter.enabled(),
                on -> {
                    NeoSettings.setBool(forge.neo.match.RoundCounter.SETTING, on);
                    NeoSettings.save();
                    host.refreshTable();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.allBoards"),
                NeoSettings.getBool(NeoSettings.ALL_BOARDS, false),
                on -> {
                    NeoSettings.setBool(NeoSettings.ALL_BOARDS, on);
                    NeoSettings.save();

                    host.refreshTable();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.stackCards"),
                NeoSettings.stackCards(),
                on -> {
                    NeoSettings.setBool(NeoSettings.STACK_CARDS, on);
                    NeoSettings.save();
                    host.refreshTable();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.groupStack"),
                NeoSettings.groupStack(),
                on -> {
                    NeoSettings.setBool(NeoSettings.GROUP_STACK, on);
                    NeoSettings.save();
                    host.refreshTable();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.sideFolded"),
                NeoSettings.getBool(NeoSettings.SIDE_FOLDED, false),
                on -> {
                    NeoSettings.setBool(NeoSettings.SIDE_FOLDED, on);
                    NeoSettings.save();
                    host.refreshTable();
                }));
        getChildren().add(toggleRow(NeoText.get("settings.slimBars"),
                NeoSettings.getBool(NeoSettings.SLIM_BARS, false),
                on -> {
                    NeoSettings.setBool(NeoSettings.SLIM_BARS, on);
                    NeoSettings.save();
                    host.refreshTable();
                }));
        
        getChildren().add(toggleRow(NeoText.get("settings.selfBarBesideHand"),
                NeoSettings.getBool(NeoSettings.SELF_BAR_BESIDE_HAND, false),
                on -> {
                    NeoSettings.setBool(NeoSettings.SELF_BAR_BESIDE_HAND, on);
                    NeoSettings.save();
                    host.refreshTable();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.stackBanner"),
                NeoSettings.stackBanner(),
                on -> {
                    NeoSettings.setBool(NeoSettings.STACK_BANNER, on);
                    NeoSettings.save();
                    PromptBanner.refreshLive();
                    host.refreshTable();
                }));
        
        getChildren().add(toggleRow(NeoText.get("settings.stackBanner.brief"),
                NeoSettings.stackBannerBrief(),
                on -> {
                    NeoSettings.setBool(NeoSettings.STACK_BANNER_BRIEF, on);
                    NeoSettings.save();
                    host.refreshTable();
                }));
        getChildren().add(toggleRow(NeoText.get("settings.stackBanner.edit"),
                NeoSettings.stackBannerEditable(),
                on -> {
                    NeoSettings.setBool(NeoSettings.STACK_BANNER_EDIT, on);
                    NeoSettings.save();
                    PromptBanner.refreshLive();
                }));

        final Label bannerNote = new Label(NeoText.get("settings.stackBanner.note"));
        bannerNote.getStyleClass().add("settings-note");
        bannerNote.setWrapText(true);
        bannerNote.setMaxWidth(UiScale.px(560));
        bannerNote.setMinHeight(Region.USE_PREF_SIZE);
        getChildren().add(bannerNote);
        final Button bannerReset = new Button(NeoText.get("settings.stackBanner.reset"));
        bannerReset.getStyleClass().add("segment");
        bannerReset.setMinWidth(Region.USE_PREF_SIZE);
        bannerReset.setOnAction(e -> {
            PromptBanner.resetPlacement();
            host.refreshTable();
        });
        getChildren().add(row(NeoText.get("settings.stackBanner.place"), bannerReset));

        getChildren().add(toggleRow(NeoText.get("settings.stackSame"),
                NeoSettings.stackSameCards(),
                on -> {
                    NeoSettings.setBool(NeoSettings.STACK_SAME, on);
                    NeoSettings.save();
                    host.refreshTable();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.attachStacked"),
                NeoSettings.attachmentsStacked(),
                on -> {
                    NeoSettings.setBool(NeoSettings.ATTACHMENTS_STACKED, on);
                    NeoSettings.save();
                    host.refreshTable();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.hoverDetail"),
                NeoSettings.getBool(NeoSettings.HOVER_DETAIL, false),
                on -> {
                    NeoSettings.setBool(NeoSettings.HOVER_DETAIL, on);
                    NeoSettings.save();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.builderHoverCard"),
                NeoSettings.getBool(NeoSettings.BUILDER_HOVER_CARD, true),
                on -> {
                    NeoSettings.setBool(NeoSettings.BUILDER_HOVER_CARD, on);
                    NeoSettings.save();
                }));

        getChildren().add(toggleRow(NeoText.get("settings.bigHoverCard"),
                NeoSettings.getBool(NeoSettings.BIG_HOVER_CARD, false),
                on -> {
                    NeoSettings.setBool(NeoSettings.BIG_HOVER_CARD, on);
                    NeoSettings.save();
                }));

        final String[] colorLabels = new String[NeoSettings.ACTIONABLE_COLOR_KEYS.length + 1];
        for (int i = 0; i < NeoSettings.ACTIONABLE_COLOR_KEYS.length; i++) {
            colorLabels[i] = NeoText.get("settings.actionableColor." + NeoSettings.ACTIONABLE_COLOR_KEYS[i]);
        }
        colorLabels[NeoSettings.ACTIONABLE_CUSTOM_INDEX] = NeoText.get("settings.actionableColor.custom");
        final HBox colorRow = choiceRow(NeoText.get("settings.actionableColor"), colorLabels,
                colorLabels[NeoSettings.actionableColorIndex()], v -> {
                    for (int i = 0; i < colorLabels.length; i++) {
                        if (colorLabels[i].equals(v)) {
                            NeoSettings.setInt(NeoSettings.ACTIONABLE_COLOR, i);
                        }
                    }
                    NeoSettings.save();
                    host.applyScale();
                });
        final javafx.scene.control.ColorPicker picker = new javafx.scene.control.ColorPicker(
                javafx.scene.paint.Color.web(NeoSettings.actionableCustom()));
        picker.getStyleClass().add("settings-color");
        for (final javafx.scene.Node n : colorRow.getChildren()) {
            if (n instanceof javafx.scene.layout.FlowPane buttons) {
                buttons.getChildren().add(picker);
                picker.setOnAction(e -> {
                    final javafx.scene.paint.Color c = picker.getValue();
                    NeoSettings.setActionableCustom(String.format(java.util.Locale.ROOT, "#%02X%02X%02X",
                            Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255),
                            Math.round(c.getBlue() * 255)));
                    NeoSettings.save();
                    
                    for (final javafx.scene.Node b : buttons.getChildren()) {
                        if (b instanceof Button button) {
                            button.pseudoClassStateChanged(SELECTED,
                                    colorLabels[NeoSettings.ACTIONABLE_CUSTOM_INDEX].equals(button.getText()));
                        }
                    }
                    host.applyScale();
                });
            }
        }
        getChildren().add(colorRow);

        final Label bigNote = new Label(NeoText.get("settings.bigHoverCard.note"));
        bigNote.getStyleClass().add("settings-note");
        bigNote.setWrapText(true);
        bigNote.setMaxWidth(UiScale.px(560));
        bigNote.setMinHeight(Region.USE_PREF_SIZE);
        getChildren().add(bigNote);

        tab(Tab.GAME);
        getChildren().add(section(NeoText.get("settings.rivalsSection")));

        final Label warnNote = nextGameNote();
        getChildren().add(toggleRow(NeoText.get("settings.hideAiWarning"),
                NeoSettings.getBool(NeoSettings.HIDE_AI_WARNING, false),
                nextGame(on -> {
                    NeoSettings.setBool(NeoSettings.HIDE_AI_WARNING, on);
                    NeoSettings.save();
                }, warnNote)));
        getChildren().add(warnNote);

        final Label rivalsNote = nextGameNote();
        getChildren().add(toggleRow(NeoText.get("settings.modernRivals"),
                NeoSettings.getBool(NeoSettings.MODERN_RIVALS, true),
                nextGame(on -> {
                    NeoSettings.setBool(NeoSettings.MODERN_RIVALS, on);
                    NeoSettings.save();
                }, rivalsNote)));
        getChildren().add(rivalsNote);

        tab(Tab.MODES);
        
        getChildren().add(section(NeoText.get("banlist.section")));
        getChildren().add(new BanListEditor());

        tab(Tab.GAME);
        getChildren().add(section(NeoText.get("settings.net")));
        final forge.localinstance.properties.ForgeNetPreferences netPrefs =
                forge.model.FModel.getNetPreferences();
        final String[] afkValues = {"0", "1", "2", "3", "5", "10", "15", "30", "60"};
        final String[] afkLabels = new String[afkValues.length];
        for (int i = 0; i < afkValues.length; i++) {
            afkLabels[i] = "0".equals(afkValues[i]) ? NeoText.get("common.no") : afkValues[i];
        }
        final int afkNow = netPrefs.getPrefInt(
                forge.localinstance.properties.ForgeNetPreferences.FNetPref.NET_AFK_TIMEOUT);
        String afkCurrent = afkNow <= 0 ? afkLabels[0] : String.valueOf(afkNow);
        getChildren().add(choiceRow(
                forge.util.Localizer.getInstance().getMessage("lblAfkTimeout"), afkLabels, afkCurrent,
                label -> {
                    final String minutes = label.equals(afkLabels[0]) ? "0" : label;
                    netPrefs.setPref(forge.localinstance.properties.ForgeNetPreferences.FNetPref
                            .NET_AFK_TIMEOUT, minutes);
                    netPrefs.save();
                }));
        final Label afkNote = new Label(NeoText.get("settings.net.afk.note"));
        afkNote.getStyleClass().add("home-subtitle");
        afkNote.setWrapText(true);
        afkNote.setMaxWidth(UiScale.px(560));
        afkNote.setMinHeight(Region.USE_PREF_SIZE);
        getChildren().add(afkNote);

        getChildren().add(section(NeoText.get("settings.detailLog")));
        getChildren().add(toggleRow(NeoText.get("settings.detailLog.on"),
                forge.neo.match.DetailLog.enabled(),
                on -> {
                    NeoSettings.setBool(forge.neo.match.DetailLog.SETTING, on);
                    NeoSettings.save();
                }));
        
        getChildren().add(toggleRow(NeoText.get("settings.handLog"),
                forge.neo.match.HandLog.enabled(),
                on -> {
                    NeoSettings.setBool(forge.neo.match.HandLog.SETTING, on);
                    NeoSettings.save();
                }));
        final Label handNote = new Label(NeoText.get("settings.handLog.note"));
        handNote.getStyleClass().add("home-subtitle");
        handNote.setWrapText(true);
        handNote.setMaxWidth(UiScale.px(560));
        handNote.setMinHeight(Region.USE_PREF_SIZE);
        getChildren().add(handNote);
        final Label detailNote = new Label(NeoText.get("settings.detailLog.note",
                forge.neo.match.DetailLog.file().getPath()));
        detailNote.getStyleClass().add("home-subtitle");
        detailNote.setWrapText(true);
        detailNote.setMaxWidth(UiScale.px(560));
        detailNote.setMinHeight(Region.USE_PREF_SIZE);
        getChildren().add(detailNote);
        final Button detailOpen = new Button(NeoText.get("settings.detailLog.open"));
        detailOpen.getStyleClass().add("segment");
        detailOpen.setMinWidth(Region.USE_PREF_SIZE);
        detailOpen.setOnAction(e -> forge.neo.match.DetailLog.openFolder());
        getChildren().add(detailOpen);

        tab(Tab.GENERAL);
        getChildren().add(section(NeoText.get("settings.discord")));
        getChildren().add(toggleRow(NeoText.get("settings.discord.on"),
                NeoSettings.discord(),
                on -> {
                    NeoSettings.setBool(NeoSettings.DISCORD, on);
                    NeoSettings.save();
                    forge.neo.discord.DiscordRich.setEnabled(on);
                }));

        getChildren().add(section(NeoText.get("settings.replays")));
        getChildren().add(toggleRow(NeoText.get("settings.replays.on"),
                NeoSettings.recordReplays(),
                on -> {
                    NeoSettings.setBool(NeoSettings.RECORD_REPLAYS, on);
                    NeoSettings.save();
                }));

        getChildren().add(section(NeoText.get("settings.update")));
        getChildren().add(toggleRow(NeoText.get("settings.update.on"),
                NeoSettings.updateCheck(),
                on -> {
                    NeoSettings.setBool(NeoSettings.UPDATE_CHECK, on);
                    NeoSettings.save();
                    forge.neo.update.NeoUpdate.enabledChanged(on);
                }));

        tab(Tab.SOUND);

        getChildren().add(sliderRow(NeoText.get("settings.sfx"),
                0, 100, NeoSettings.getInt(NeoSettings.SOUND_VOLUME, NeoSettings.SOUND_VOLUME_DEFAULT), 5,
                v -> {
                    NeoSettings.setInt(NeoSettings.SOUND_VOLUME, (int) Math.round(v));
                    NeoSettings.save();
                    NeoSettings.applyAudioToEngine();
                },
                v -> String.format(java.util.Locale.ROOT, "%.0f", v)));

        getChildren().add(sliderRow(NeoText.get("settings.music"),
                0, 100, NeoSettings.getInt(NeoSettings.MUSIC_VOLUME, NeoSettings.MUSIC_VOLUME_DEFAULT), 5,
                v -> {
                    NeoSettings.setInt(NeoSettings.MUSIC_VOLUME, (int) Math.round(v));
                    NeoSettings.save();
                    NeoSettings.applyAudioToEngine();
                },
                v -> String.format(java.util.Locale.ROOT, "%.0f", v)));

        getChildren().add(toggleRow(NeoText.get("settings.neoSounds"),
                NeoSettings.getBool(forge.neo.sound.NeoSounds.SETTING, forge.neo.sound.NeoSounds.DEFAULT),
                on -> {
                    NeoSettings.setBool(forge.neo.sound.NeoSounds.SETTING, on);
                    NeoSettings.save();
                }));

        tab(Tab.ART);

        final forge.neo.card.ImageQuality[] qualities = forge.neo.card.ImageQuality.values();
        final String[] qualityLabels = new String[qualities.length];
        for (int i = 0; i < qualities.length; i++) {
            qualityLabels[i] = NeoText.get("settings.art.quality." + qualities[i].scryfall);
        }
        final Label qualityNote = new Label(qualityNote(forge.neo.card.ImageQuality.current()));
        qualityNote.getStyleClass().add("home-subtitle");
        qualityNote.setWrapText(true);
        qualityNote.setMaxWidth(UiScale.px(560));
        qualityNote.setMinHeight(Region.USE_PREF_SIZE);
        getChildren().add(choiceRow(NeoText.get("settings.art.quality"), qualityLabels,
                NeoText.get("settings.art.quality." + forge.neo.card.ImageQuality.current().scryfall),
                v -> {
                    for (final forge.neo.card.ImageQuality q : qualities) {
                        if (NeoText.get("settings.art.quality." + q.scryfall).equals(v)) {
                            NeoSettings.set(NeoSettings.IMAGE_QUALITY, q.scryfall);

                            NeoSettings.save();
                            qualityNote.setText(qualityNote(q));

                            forge.neo.card.ArtDownload.afterDownload();
                        }
                    }
                }));
        getChildren().add(qualityNote);

        final Label artlessNote = new Label(artlessNote(false));
        artlessNote.getStyleClass().add("home-subtitle");
        artlessNote.setWrapText(true);
        artlessNote.setMaxWidth(UiScale.px(560));
        artlessNote.setMinHeight(Region.USE_PREF_SIZE);
        getChildren().add(toggleRow(NeoText.get("settings.art.hideArtless"),
                forge.neo.card.ArtlessCards.hidden(),
                on -> {
                    forge.neo.card.ArtlessCards.setHidden(on);
                    artlessNote.setText(artlessNote(true));
                }));
        getChildren().add(artlessNote);

        final Button artAll = new Button(NeoText.get("settings.art.all"));
        artAll.getStyleClass().add("segment");
        artAll.setMinWidth(Region.USE_PREF_SIZE);
        artAll.setOnAction(e -> showArtDownload(forge.neo.card.ArtDownload.Scope.ALL));
        getChildren().add(row(NeoText.get("settings.art.allRow"), artAll));

        final Button artTokens = new Button(NeoText.get("settings.art.tokens"));
        artTokens.setId("settings-art-tokens");
        artTokens.getStyleClass().add("segment");
        artTokens.setMinWidth(Region.USE_PREF_SIZE);
        artTokens.setOnAction(e -> showArtDownload(forge.neo.card.ArtDownload.Scope.TOKENS));
        getChildren().add(row(NeoText.get("settings.art.tokensRow"), artTokens));

        final Button artDecks = new Button(NeoText.get("settings.art.decks"));
        artDecks.getStyleClass().add("segment");
        artDecks.setMinWidth(Region.USE_PREF_SIZE);
        artDecks.setOnAction(e -> showArtDownload(forge.neo.card.ArtDownload.Scope.MY_DECKS));
        getChildren().add(row(NeoText.get("settings.art.decksRow"), artDecks));

        final Button artEvery = new Button(NeoText.get("settings.art.every"));
        artEvery.getStyleClass().add("segment");
        artEvery.setMinWidth(Region.USE_PREF_SIZE);
        artEvery.setOnAction(e -> showArtDownload(forge.neo.card.ArtDownload.Scope.EVERY_PRINTING));
        getChildren().add(row(NeoText.get("settings.art.everyRow"), artEvery));

        getChildren().add(row(NeoText.get("settings.art.setRow"), artSetPicker()));
        getChildren().add(row(NeoText.get("settings.art.formatRow"), artFormatPicker()));

        final Button artHd = new Button(NeoText.get("settings.art.hd"));
        artHd.setId("settings-art-hd");
        artHd.getStyleClass().add("segment");
        artHd.setMinWidth(Region.USE_PREF_SIZE);
        final Label artHdStatus = new Label();
        artHdStatus.getStyleClass().add("home-subtitle");
        artHdStatus.setWrapText(true);
        artHdStatus.setMaxWidth(UiScale.px(460));
        artHd.setOnAction(e -> {
            artHd.setDisable(true);
            artHdStatus.setText(NeoText.get("settings.art.hd.start"));
            final Thread t = new Thread(() -> {
                final forge.neo.card.ArtHdScan.Result r = forge.neo.card.ArtHdScan.run(
                        forge.neo.card.CardImages.HD_STORE,
                        new forge.neo.card.ArtHdScan.Progress() {
                            @Override
                            public void update(final int done, final int sets, final String name, final int updated) {
                                javafx.application.Platform.runLater(() ->
                                        artHdStatus.setText(NeoText.get("settings.art.hd.progress",
                                                Math.min(done + 1, Math.max(sets, 1)), sets, name, updated)));
                            }

                            @Override
                            public void waiting(final long seconds) {
                                javafx.application.Platform.runLater(() ->
                                        artHdStatus.setText(NeoText.get("settings.art.hd.waiting", seconds)));
                            }
                        },
                        null);
                javafx.application.Platform.runLater(() -> {
                    artHd.setDisable(false);
                    artHdStatus.setText(r.sets() == 0 ? NeoText.get("settings.art.hd.none")
                            : r.stoppedByNetwork() ? NeoText.get("settings.art.hd.offline", r.updated())
                            : NeoText.get("settings.art.hd.done", r.updated(), r.sets()));
                });
            }, "neo-art-hd");
            t.setDaemon(true);
            t.start();
        });
        final HBox artHdBox = new HBox(12, artHd, artHdStatus);
        artHdBox.setAlignment(Pos.CENTER_LEFT);
        getChildren().add(row(NeoText.get("settings.art.hdRow"), artHdBox));

        addDataSection(host);

        tab(Tab.GENERAL);
        getChildren().add(section(NeoText.get("settings.keyboard")));
        final Button shortcuts = new Button(NeoText.get("settings.shortcuts.open"));
        shortcuts.getStyleClass().add("segment");
        shortcuts.setMinWidth(Region.USE_PREF_SIZE);
        shortcuts.setOnAction(e -> showShortcuts());
        getChildren().add(row(NeoText.get("settings.shortcuts"), shortcuts));

        tab(Tab.SUPPORT);
        getChildren().add(supportCard());

        final Button close = new Button(NeoText.get("common.close"));
        close.getStyleClass().add("btn-primary");
        close.setOnAction(e -> onClose.run());

        final Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        footer.getChildren().addAll(gap, close);
        footer.setPadding(new Insets(12, 0, 0, 0));

        final List<javafx.scene.Node> middle =
                new ArrayList<>(getChildren().subList(1, getChildren().size()));
        Tab current = Tab.GENERAL;
        for (final javafx.scene.Node n : middle) {
            if (n.getProperties().get(TAB_MARK) instanceof Tab t) {
                current = t;
                continue;
            }
            pages.computeIfAbsent(current, k -> {
                final VBox page = new VBox(getSpacing());
                page.getStyleClass().add("settings-page");
                return page;
            }).getChildren().add(n);
        }

        final javafx.scene.control.ToggleGroup group = new javafx.scene.control.ToggleGroup();
        rail.getStyleClass().add("settings-tabs");
        rail.setMinWidth(Region.USE_PREF_SIZE);

        search.setPromptText(NeoText.get("settings.search"));
        search.getStyleClass().add("settings-search");
        search.setId("settings-search");
        search.textProperty().addListener((o, was, now) -> applySearch(now));
        rail.getChildren().add(search);
        for (final Tab t : Tab.values()) {
            if (!pages.containsKey(t)) {
                continue;
            }
            final javafx.scene.control.ToggleButton b =
                    new javafx.scene.control.ToggleButton(NeoText.get("settings.tab." + t.id));
            b.getStyleClass().add("settings-tab");
            if (t == Tab.SUPPORT) {
                b.getStyleClass().add("settings-tab-support");
            }
            b.setToggleGroup(group);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(e -> showTab(t));
            tabButtons.put(t, b);
            rail.getChildren().add(b);
        }
        
        group.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null && was != null && search.getText().trim().isEmpty()) {
                was.setSelected(true);
            }
        });
        showTab(Tab.byId(NeoSettings.get(LAST_TAB, Tab.GAME.id)), false);

        content.setSpacing(getSpacing());
        scroll.getStyleClass().add("dialog-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);

        final HBox body = new HBox(UiScale.px(18), rail, scroll);
        HBox.setHgrow(scroll, Priority.ALWAYS);
        VBox.setVgrow(body, Priority.ALWAYS);

        title.setMinHeight(Region.USE_PREF_SIZE);
        footer.setMinHeight(Region.USE_PREF_SIZE);

        getChildren().setAll(title, body, footer);
    }

    public enum Tab {
        GENERAL("general"), GAME("game"), TABLE("table"), DISPLAY("display"),
        SOUND("sound"), ART("art"), MODES("modes"), DATA("data"), SUPPORT("support");

        public final String id;

        Tab(final String id) {
            this.id = id;
        }

        static Tab byId(final String id) {
            for (final Tab t : values()) {
                if (t.id.equalsIgnoreCase(id)) {
                    return t;
                }
            }
            return GAME;
        }
    }

    private static final String LAST_TAB = "settingsTab";

    private static final String TAB_MARK = "neo.settings.tab";

    private final java.util.Map<Tab, VBox> pages = new java.util.EnumMap<>(Tab.class);
    private final java.util.Map<Tab, javafx.scene.control.ToggleButton> tabButtons =
            new java.util.EnumMap<>(Tab.class);
    private final VBox rail = new VBox(4);

    private final javafx.scene.control.TextField search = new javafx.scene.control.TextField();
    
    private Tab beforeSearch;
    private Tab shown = Tab.GAME;

    public void searchForTest(final String text) {
        search.setText(text);
    }

    private void applySearch(final String raw) {
        final String q = fold(raw == null ? "" : raw.trim());
        if (q.isEmpty()) {
            for (final VBox page : pages.values()) {
                for (final javafx.scene.Node n : page.getChildren()) {
                    n.setVisible(true);
                    n.setManaged(true);
                }
            }
            if (beforeSearch != null) {
                final Tab back = beforeSearch;
                beforeSearch = null;
                showTab(back, false);
            }
            return;
        }
        if (beforeSearch == null) {
            beforeSearch = shown;
        }
        final List<javafx.scene.Node> visiblePages = new ArrayList<>();
        for (final Tab t : Tab.values()) {
            final VBox page = pages.get(t);
            
            if (page == null || t == Tab.SUPPORT) {
                continue;
            }
            boolean anyInPage = false;
            Label sectionHead = null;
            boolean sectionHit = false;
            for (final javafx.scene.Node n : page.getChildren()) {
                if (n instanceof Label l && l.getStyleClass().contains("settings-section")) {
                    if (sectionHead != null) {
                        sectionHead.setVisible(sectionHit);
                        sectionHead.setManaged(sectionHit);
                    }
                    sectionHead = l;
                    sectionHit = false;
                    continue;
                }
                final boolean hit = fold(textOf(n)).contains(q);
                n.setVisible(hit);
                n.setManaged(hit);
                sectionHit |= hit;
                anyInPage |= hit;
            }
            if (sectionHead != null) {
                sectionHead.setVisible(sectionHit);
                sectionHead.setManaged(sectionHit);
            }
            if (anyInPage) {
                visiblePages.add(page);
            }
        }
        if (visiblePages.isEmpty()) {
            final Label none = new Label(NeoText.get("settings.searchNone", raw.trim()));
            none.getStyleClass().add("settings-note");
            content.getChildren().setAll(none);
        } else {
            content.getChildren().setAll(visiblePages);
        }
        for (final javafx.scene.control.ToggleButton b : tabButtons.values()) {
            b.setSelected(false);
        }
        scroll.setVvalue(0);
    }

    private static String textOf(final javafx.scene.Node n) {
        final StringBuilder sb = new StringBuilder();
        if (n instanceof javafx.scene.control.Labeled l && l.getText() != null) {
            sb.append(l.getText()).append(' ');
        }
        if (n instanceof javafx.scene.Parent p) {
            for (final javafx.scene.Node c : p.lookupAll(".label")) {
                if (c instanceof javafx.scene.control.Labeled l && l.getText() != null) {
                    sb.append(l.getText()).append(' ');
                }
            }
            for (final javafx.scene.Node c : p.lookupAll(".button")) {
                if (c instanceof javafx.scene.control.Labeled l && l.getText() != null) {
                    sb.append(l.getText()).append(' ');
                }
            }
        }
        return sb.toString();
    }

    private static String fold(final String s) {
        return java.text.Normalizer.normalize(s.toLowerCase(java.util.Locale.ROOT),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private void tab(final Tab t) {
        final Region mark = new Region();
        mark.getProperties().put(TAB_MARK, t);
        getChildren().add(mark);
    }

    public java.util.Map<String, List<String>> rowsByTab() {
        final java.util.Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        for (final Tab t : Tab.values()) {
            final VBox page = pages.get(t);
            if (page == null) {
                continue;
            }
            final List<String> labels = new ArrayList<>();
            for (final javafx.scene.Node n : page.lookupAll(".settings-label")) {
                if (n instanceof Label l) {
                    labels.add(l.getText());
                }
            }
            out.put(t.id, labels);
        }
        return out;
    }

    public boolean pressForTest(final String caption, final String button) {
        for (final VBox page : pages.values()) {
            for (final javafx.scene.Node n : page.lookupAll(".settings-label")) {
                if (!(n instanceof Label l) || !caption.equals(l.getText()) || l.getParent() == null) {
                    continue;
                }
                for (final javafx.scene.Node b : l.getParent().lookupAll(".button")) {
                    if (b instanceof javafx.scene.control.ButtonBase bb && button.equals(bb.getText())) {
                        bb.fire();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void showTab(final String id) {
        showTab(Tab.byId(id));
    }

    public void showTab(final Tab t) {
        showTab(t, true);
    }

    private void showTab(final Tab wanted, final boolean remember) {
        final Tab t = pages.containsKey(wanted) ? wanted : Tab.GAME;
        final VBox page = pages.get(t);
        if (page == null) {
            return;
        }
        
        if (!search.getText().isEmpty()) {
            beforeSearch = null;
            search.setText("");
        }
        shown = t;
        content.getChildren().setAll(page);
        scroll.setVvalue(0);
        final javafx.scene.control.ToggleButton b = tabButtons.get(t);
        if (b != null) {
            b.setSelected(true);
        }
        if (remember) {
            NeoSettings.set(LAST_TAB, t.id);
            NeoSettings.save();
        }
    }

    private double tallest;
    private double widest;

    @Override
    protected void layoutChildren() {
        final javafx.scene.Scene sc = getScene();
        if (sc != null && sc.getHeight() > 0) {
            final double chrome = title.prefHeight(-1) + footer.prefHeight(-1)
                    + getPadding().getTop() + getPadding().getBottom()
                    + getSpacing() * 2 + 8;
            final double room = sc.getHeight() * 0.90 - chrome;
            final double wanted = Math.max(rail.prefHeight(-1), content.prefHeight(
                    content.getWidth() > 0 ? content.getWidth() : content.prefWidth(-1)));

            tallest = Math.max(tallest, wanted);
            final double h = Math.max(160, Math.min(tallest + 2, room));

            final double roomW = sc.getWidth() * 0.94 - rail.prefWidth(-1) - UiScale.px(18)
                    - getPadding().getLeft() - getPadding().getRight() - 24;
            final double w = Math.max(UiScale.px(320), Math.min(content.prefWidth(-1), roomW));
            if (w > widest + 1 || w < widest - 1 && w < content.prefWidth(-1)) {
                widest = w;
                scroll.setMinViewportWidth(w);
            }
            if (Math.abs(h - scroll.getPrefViewportHeight()) > 1) {
                scroll.setPrefViewportHeight(h);
            }
        }
        super.layoutChildren();
    }

    public void showShortcuts() {
        if (shortcutsOpen) {
            return;
        }
        shortcutsOpen = true;
        final List<javafx.scene.Node> main = new ArrayList<>(getChildren());
        getChildren().setAll(new ShortcutsPanel(false, NeoText.get("common.back"), () -> {
            shortcutsOpen = false;
            getChildren().setAll(main);
        }));
    }

    private boolean shortcutsOpen;

    public void showArtDownload(final forge.neo.card.ArtDownload.Scope scope) {
        showArtDownload(scope, null);
    }

    public void showArtDownload(final forge.neo.card.ArtDownload.Scope scope, final String target) {
        if (shortcutsOpen) {
            return;
        }
        shortcutsOpen = true;
        final List<javafx.scene.Node> main = new ArrayList<>(getChildren());
        getChildren().setAll(new ArtDownloadPanel(scope, target, () -> {
            shortcutsOpen = false;
            getChildren().setAll(main);
        }));
    }

    private static String labelFor(final java.util.List<forge.neo.NeoLanguage.Option> langs,
                                   final String id) {
        for (final forge.neo.NeoLanguage.Option o : langs) {
            if (o.getId().equals(id)) {
                return o.getLabel();
            }
        }
        return langs.isEmpty() ? "" : langs.get(0).getLabel();
    }

    private static String artlessNote(final boolean justChanged) {
        final boolean out = forge.neo.card.ArtlessCards.hiddenThisSession();
        String text = out ? NeoText.get("settings.art.hideArtless.hidden")
                : NeoText.get("settings.art.hideArtless.note", forge.neo.card.ArtlessCards.present().size());
        if (justChanged && forge.neo.card.ArtlessCards.hidden() != out) {
            text += "  " + NeoText.get("settings.language.restart");
        }
        return text;
    }

    private static String noteFor(final java.util.List<forge.neo.NeoLanguage.Option> langs,
                                  final String id, final boolean justChanged) {
        String cards = "";
        for (final forge.neo.NeoLanguage.Option o : langs) {
            if (o.getId().equals(id)) {
                cards = o.hasCardNames()
                        ? NeoText.get("settings.language.cards")
                        : NeoText.get("settings.language.noCards");
                break;
            }
        }
        return justChanged ? cards + "  " + NeoText.get("settings.language.restart") : cards;
    }

    private static Label nextGameNote() {
        final Label note = new Label(NeoText.get("settings.nextGame"));
        note.getStyleClass().add("settings-note");
        note.setWrapText(true);
        note.setMaxWidth(UiScale.px(560));
        note.setMinHeight(Region.USE_PREF_SIZE);

        final boolean forced = Boolean.getBoolean("neo.settings.notes");
        note.setVisible(forced);
        note.setManaged(forced);
        return note;
    }

    private <T> Consumer<T> nextGame(final Consumer<T> action, final Label note) {
        return v -> {
            action.accept(v);
            if (inMatch) {
                note.setVisible(true);
                note.setManaged(true);
            }
        };
    }

    private void addDataSection(final Host host) {
        tab(Tab.DATA);
        final Label help = new Label(NeoText.get("settings.data.help"));
        help.getStyleClass().add("home-subtitle");
        help.setWrapText(true);
        help.setMaxWidth(560);
        getChildren().add(help);

        final forge.neo.data.NeoBackup.Places places = forge.neo.data.DataPlaces.desktop();
        final long musicMb = (sizeOf(new java.io.File(places.root, "neo/music"))
                + sizeOf(new java.io.File(places.root, "custom"))) >> 20;
        final javafx.scene.control.CheckBox music = new javafx.scene.control.CheckBox(
                NeoText.get("settings.data.music", musicMb));
        music.setSelected(false);

        final Button export = new Button(NeoText.get("settings.data.export"));
        final Button importB = new Button(NeoText.get("settings.data.import"));
        for (final Button b : new Button[] {export, importB}) {
            b.getStyleClass().add("segment");
            b.setMinWidth(Region.USE_PREF_SIZE);
        }
        export.setId("settings-data-export");
        importB.setId("settings-data-import");
        final Label status = new Label();
        status.getStyleClass().add("home-subtitle");
        status.setWrapText(true);
        status.setMaxWidth(560);
        final VBox choice = new VBox(6);

        if (inMatch) {
            export.setDisable(true);
            importB.setDisable(true);
            status.setText(NeoText.get("settings.data.inMatch"));
        }

        export.setOnAction(e -> {
            final javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setInitialFileName("NeoForge-datos-"
                    + new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date())
                    + ".zip");
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("zip", "*.zip"));
            final java.io.File to = fc.showSaveDialog(getScene() == null ? null : getScene().getWindow());
            if (to == null) {
                return;
            }
            export.setDisable(true);
            status.setText(NeoText.get("settings.data.exporting"));
            final boolean withMusic = music.isSelected();
            final Thread t = new Thread(() -> {
                String msg;
                try {
                    final forge.neo.data.NeoBackup.Summary s = forge.neo.data.NeoBackup.export(
                            places, to, withMusic, forge.neo.NeoVersion.neoVersion(), forge.neo.data.DataPlaces.platform());
                    msg = NeoText.get("settings.data.exported", to.getName(), s.decks, s.total());
                } catch (final java.io.IOException | RuntimeException ex) {
                    msg = NeoText.get("settings.data.failed", ex.getMessage());
                }
                final String m = msg;
                javafx.application.Platform.runLater(() -> {
                    status.setText(m);
                    export.setDisable(false);
                });
            }, "neo-export");
            t.setDaemon(true);
            t.start();
        });

        importB.setOnAction(e -> {
            final javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("zip", "*.zip"));
            final java.io.File from = fc.showOpenDialog(getScene() == null ? null : getScene().getWindow());
            if (from == null) {
                return;
            }
            final forge.neo.data.NeoBackup.Summary s = forge.neo.data.NeoBackup.inspect(from);
            choice.getChildren().clear();
            if (!s.valid) {
                status.setText(NeoText.get("settings.data.notBackup"));
                return;
            }
            status.setText(NeoText.get("settings.data.found", platformName(s.platform),
                    s.version.isEmpty() ? "?" : s.version, s.date, s.decks, s.total()));
            final Button add = new Button(NeoText.get("settings.data.add"));
            final Button replace = new Button(NeoText.get("settings.data.replace"));
            final Button cancel = new Button(NeoText.get("common.cancel"));
            add.getStyleClass().add("btn-primary");
            replace.getStyleClass().add("btn-secondary");
            cancel.getStyleClass().add("btn-secondary");
            final Label addHelp = new Label(NeoText.get("settings.data.addHelp"));
            final Label replaceHelp = new Label(NeoText.get("settings.data.replaceHelp"));
            addHelp.getStyleClass().add("home-subtitle");
            replaceHelp.getStyleClass().add("home-subtitle");
            cancel.setOnAction(x -> {
                choice.getChildren().clear();
                status.setText("");
            });
            final java.util.function.Consumer<forge.neo.data.NeoBackup.Mode> go = mode -> {
                try {
                    forge.neo.data.NeoBackup.stage(places.root, from, mode);
                } catch (final java.io.IOException ex) {
                    status.setText(NeoText.get("settings.data.failed", ex.getMessage()));
                    return;
                }
                choice.getChildren().clear();
                if (!host.restartToImport()) {
                    status.setText(NeoText.get("settings.data.restartManual"));
                }
            };
            add.setOnAction(x -> go.accept(forge.neo.data.NeoBackup.Mode.ADD));
            replace.setOnAction(x -> go.accept(forge.neo.data.NeoBackup.Mode.REPLACE));
            final HBox addRow = new HBox(10, add, addHelp);
            final HBox replaceRow = new HBox(10, replace, replaceHelp);
            addRow.setAlignment(Pos.CENTER_LEFT);
            replaceRow.setAlignment(Pos.CENTER_LEFT);
            choice.getChildren().addAll(addRow, replaceRow, cancel);
        });

        final HBox buttons = new HBox(12, export, importB, music);
        buttons.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(buttons, status, choice);
    }

    private static String platformName(final String p) {
        if (p == null || p.isEmpty()) {
            return "?";
        }
        return Character.toUpperCase(p.charAt(0)) + p.substring(1);
    }

    private static long sizeOf(final java.io.File f) {
        if (f.isFile()) {
            return f.length();
        }
        long n = 0;
        final java.io.File[] kids = f.listFiles();
        if (kids != null) {
            for (final java.io.File k : kids) {
                n += sizeOf(k);
            }
        }
        return n;
    }

    private static Region supportCard() {
        final Label title = new Label(NeoText.get("support.title"));
        title.getStyleClass().add("support-title");
        title.setWrapText(true);

        final VBox card = new VBox(UiScale.px(12), title);
        for (final String key : new String[] {"support.text1", "support.text2"}) {
            final Label p = new Label(NeoText.get(key));
            p.getStyleClass().add("support-text");
            p.setWrapText(true);
            p.setMinHeight(Region.USE_PREF_SIZE);
            card.getChildren().add(p);
        }

        final Button kofi = MainMenu.kofiButton();
        kofi.setText(NeoText.get("support.kofi"));
        kofi.getStyleClass().add("support-link");
        final Button itch = MainMenu.itchButton();
        itch.setText(NeoText.get("support.itch"));
        itch.getStyleClass().add("support-link");
        final javafx.scene.layout.FlowPane links =
                new javafx.scene.layout.FlowPane(UiScale.px(12), UiScale.px(10), kofi, itch);
        links.setPadding(new Insets(UiScale.px(6), 0, UiScale.px(2), 0));
        card.getChildren().add(links);

        final Label thanks = new Label(NeoText.get("support.thanks"));
        thanks.getStyleClass().add("support-thanks");
        thanks.setWrapText(true);
        card.getChildren().add(thanks);

        final Button rate = new Button(NeoText.get("support.rate"));
        rate.getStyleClass().add("support-rate");
        rate.setMinWidth(Region.USE_PREF_SIZE);
        rate.setOnAction(e -> {
            try {
                forge.gui.GuiBase.getInterface().browseToUrl(forge.neo.update.ItchVersion.RATE_URL);
            } catch (final Exception ex) {
                System.err.println("[neo] no se ha podido abrir itch.io: " + ex);
            }
        });
        card.getChildren().add(rate);

        card.getStyleClass().add("support-card");
        card.setMaxWidth(UiScale.px(640));
        return card;
    }

    private static Label section(final String text) {
        final Label l = new Label(text);
        l.getStyleClass().add("settings-section");
        return l;
    }

    private Region artSetPicker() {
        final javafx.scene.control.ComboBox<forge.card.CardEdition> sets = new javafx.scene.control.ComboBox<>();
        sets.getItems().addAll(forge.neo.card.ArtDownload.editions());
        
        sets.getStyleClass().add("team-combo");
        sets.setVisibleRowCount(16);
        sets.setPrefWidth(UiScale.px(300));
        sets.setPromptText(NeoText.get("settings.art.setPick"));
        sets.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(final forge.card.CardEdition ed) {
                return ed == null ? "" : ed.getName() + " (" + ed.getCode() + ")";
            }

            @Override
            public forge.card.CardEdition fromString(final String s) {
                return null;
            }
        });
        final Button go = new Button(NeoText.get("settings.art.set"));
        go.getStyleClass().add("segment");
        go.setMinWidth(Region.USE_PREF_SIZE);
        go.disableProperty().bind(sets.valueProperty().isNull());
        go.setOnAction(e -> showArtDownload(forge.neo.card.ArtDownload.Scope.SET, sets.getValue().getCode()));
        final HBox box = new HBox(8, sets, go);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Region artFormatPicker() {
        final javafx.scene.control.ComboBox<forge.game.GameFormat> formats = new javafx.scene.control.ComboBox<>();
        formats.getItems().addAll(forge.neo.card.ArtDownload.formats());
        
        formats.getStyleClass().add("team-combo");
        formats.setVisibleRowCount(16);
        formats.setPrefWidth(UiScale.px(300));
        formats.setPromptText(NeoText.get("settings.art.formatPick"));
        formats.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(final forge.game.GameFormat f) {
                return f == null ? "" : f.getName();
            }

            @Override
            public forge.game.GameFormat fromString(final String s) {
                return null;
            }
        });
        final Button go = new Button(NeoText.get("settings.art.format"));
        go.getStyleClass().add("segment");
        go.setMinWidth(Region.USE_PREF_SIZE);
        go.disableProperty().bind(formats.valueProperty().isNull());
        go.setOnAction(e -> showArtDownload(forge.neo.card.ArtDownload.Scope.FORMAT, formats.getValue().getName()));
        final HBox box = new HBox(8, formats, go);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static HBox row(final String caption, final Region control) {
        final Label label = new Label(caption);
        label.getStyleClass().add("settings-label");
        label.setMinWidth(UiScale.px(190));

        label.setWrapText(true);
        final HBox box = new HBox(14, label, control);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(4, 0, 4, 0));
        return box;
    }

    private static String qualityNote(final forge.neo.card.ImageQuality q) {
        return NeoText.get("settings.art.quality.note." + q.scryfall) + " "
                + NeoText.get("settings.art.quality.applies");
    }

    private static HBox choiceRow(final String caption, final String[] values,
                                  final String current, final Consumer<String> onPick) {
        final javafx.scene.layout.FlowPane buttons = new javafx.scene.layout.FlowPane(4, 4);
        final List<Button> all = new ArrayList<>();
        for (final String value : values) {
            final Button b = new Button(value);
            b.getStyleClass().add("segment");

            b.setMinWidth(Region.USE_PREF_SIZE);
            b.pseudoClassStateChanged(SELECTED, value.equals(current));
            b.setOnAction(e -> {
                for (final Button other : all) {
                    other.pseudoClassStateChanged(SELECTED, other == b);
                }
                onPick.accept(value);
            });
            all.add(b);
            buttons.getChildren().add(b);
        }
        return row(caption, buttons);
    }

    private static HBox sliderRow(final String caption, final double min, final double max,
                                  final double value, final double step,
                                  final Consumer<Double> onChange,
                                  final java.util.function.Function<Double, String> format) {
        final Slider slider = new Slider(min, max, clamp(value, min, max));
        slider.setBlockIncrement(step);
        slider.setPrefWidth(UiScale.px(240));
        slider.getStyleClass().add("settings-slider");

        final Label readout = new Label(format.apply(slider.getValue()));
        readout.getStyleClass().add("settings-value");
        readout.setMinWidth(UiScale.px(52));

        slider.valueProperty().addListener((o, was, is) -> {
            final double v = Math.round(is.doubleValue() / step) * step;
            readout.setText(format.apply(v));
            onChange.accept(v);
        });

        final HBox control = new HBox(10, slider, readout);
        control.setAlignment(Pos.CENTER_LEFT);
        return row(caption, control);
    }

    private static HBox toggleRow(final String caption, final boolean on,
                                  final Consumer<Boolean> onChange) {
        final String yes = NeoText.get("common.yes");
        final String no = NeoText.get("common.no");
        return choiceRow(caption, new String[] {yes, no}, on ? yes : no,
                v -> onChange.accept(yes.equals(v)));
    }

    private static double clamp(final double v, final double lo, final double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static final javafx.css.PseudoClass SELECTED =
            javafx.css.PseudoClass.getPseudoClass("selected");
}
