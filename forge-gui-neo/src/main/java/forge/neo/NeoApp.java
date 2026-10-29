package forge.neo;

import java.util.ArrayList;
import java.util.List;

import forge.deck.Deck;
import forge.game.card.CardView;
import forge.item.PaperCard;
import forge.neo.card.CardImages;
import forge.neo.card.CardNode;
import forge.neo.match.NeoGame;
import forge.neo.match.NeoMatchUI;
import forge.neo.match.TableBinder;
import forge.neo.ui.DeckBuilderScreen;
import forge.neo.match.NeoFormat;

import forge.neo.ui.HomeScreen;
import forge.neo.ui.MainMenu;

import forge.neo.ui.PauseMenu;
import forge.neo.ui.SettingsPanel;
import forge.neo.ui.TableScreen;
import forge.neo.ui.UiScale;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

public class NeoApp extends Application implements SettingsPanel.Host {

    TableScreen table;
    HomeScreen home;
    Scene scene;
    Stage stage;

    TableBinder binder;

    private Deck lastDeck;
    private int lastOpponents;
    private String lastAi;
    private boolean lastWatch;
    List<Deck> lastOpponentDecks;
    
    int[] lastTeams;
    NeoFormat lastFormat = NeoFormat.COMMANDER;

    double cardWidth;
    double sideWidth;

    @Override
    public void start(final Stage stage) {
        final List<String> args = getParameters().getRaw();
        final String snapshotPath = optionOf(args, "--snapshot");
        final int waitSecs = Integer.parseInt(orDefault(optionOf(args, "--wait"), "8"));

        final var bounds = Screen.getPrimary().getVisualBounds();
        double winW = bounds.getWidth();
        double winH = bounds.getHeight();

        final String forced = System.getProperty("neo.win");
        if (forced != null) {
            try {
                final String[] wh = forced.split("x");
                winW = Double.parseDouble(wh[0]);
                winH = Double.parseDouble(wh[1]);
                System.out.printf("[arranque] ventana forzada a %.0fx%.0f%n", winW, winH);
            } catch (final RuntimeException e) {
                System.err.println("neo.win no valido (se espera ANCHOxALTO): " + forced);
            }
        }

        UiScale.setScreenSize(winW, winH);
        sideWidth = UiScale.sideWidth(winW);
        cardWidth = UiScale.cardWidth(winW, winH);
        measuredW = winW;
        measuredH = winH;

        UiScale.setOverride(NeoSettings.getScale());
        forge.neo.card.CardNode.setAnimationsEnabled(
                NeoSettings.getBool(NeoSettings.ANIMATIONS, true));
        forge.neo.card.CardNode.setFoilEffectEnabled(
                NeoSettings.getBool(NeoSettings.FOIL_EFFECT, true));
        forge.neo.ui.ImpactLayer.setEnabled(
                NeoSettings.getBool(NeoSettings.IMPACT_EFFECTS, true));
        forge.neo.ui.ImpactLayer.setEntranceEnabled(
                NeoSettings.getBool(NeoSettings.COMMANDER_ENTRANCE, true));
        forge.neo.card.CardNode.setKeywordBadgesEnabled(
                NeoSettings.getBool(NeoSettings.KEYWORD_BADGES, true));
        forge.neo.ui.HandFan.setFanned(NeoSettings.handFan());

        forge.neo.match.NeoMatchUI.setBlindSourceFallback(
                NeoSettings.getBool(NeoSettings.BLIND_MANA, false)
                        || Boolean.getBoolean("neo.blindMana"));

        FpsMeter.startIfAsked();
        
        forge.neo.platform.PrismGuard.install(s -> System.out.println("[neo] " + s));

        scene = new Scene(new StackPane(), winW, winH);
        final var css = NeoApp.class.getResource("/forge/neo/neo.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        forge.neo.platform.NeoFonts.apply(scene);

        forge.neo.ui.Rtl.applyTo(scene);

        scene.heightProperty().addListener((o, was, is) -> applyScale());

        scene.setOnKeyPressed(ev -> {
            final boolean inGame = table != null && table.getScene() != null;

            if (inGame && table.isCurtainUp()) {
                if (ev.getCode() == javafx.scene.input.KeyCode.ENTER
                        || ev.getCode() == javafx.scene.input.KeyCode.SPACE) {
                    table.hideCurtain();
                }
                ev.consume();
                return;
            }
            if (ev.getCode() == javafx.scene.input.KeyCode.ESCAPE) {

                if (inGame) {

                    if (table.isZoomShowing()) {
                        table.hideZoom();
                    } else if (table.getOverlay().isPeeking()) {

                        table.getOverlay().setPeeking(false);
                    } else {
                        togglePauseMenu();
                    }
                } else if (settingsOverlay != null) {
                    
                    hideMenuSettings();
                } else if (!forge.neo.ui.Overlay.dismissTopmost(scene.getRoot())) {

                    showMenuSettings();
                }
                ev.consume();
                return;
            }

            if (inGame && runShortcut(ev)) {
                ev.consume();
            }
        });

        forge.neo.ui.TableKeys.guardFocusedButtons(scene, () -> table,
                this::runShortcut, heldShortcuts::clear);
        
        scene.addEventHandler(javafx.scene.input.KeyEvent.KEY_RELEASED, ev -> heldShortcuts.clear());

        CardImages.addListener(() -> {
            if (table != null && table.getScene() != null) {
                table.refreshAll();
            }
            if (home != null && home.getScene() != null) {
                home.refreshArt();
            }
            if (builder != null && builder.getScene() != null) {
                builder.refreshArt();
            }

            if (scene != null && scene.getRoot() != null) {
                CardNode.refreshAllIn(scene.getRoot());
            }
        });

        forge.neo.platform.NeoGuiBase.setChooser(new forge.neo.platform.NeoGuiBase.Chooser() {
            @Override
            public <T> java.util.List<T> choose(final String message, final int min, final int max,
                                                final java.util.List<T> options,
                                                final java.util.List<T> preselected,
                                                final java.util.function.Function<T, String> display) {
                return askChoice(message, min, max, options, preselected, display);
            }
        });

        this.stage = stage;
        stage.setTitle("NeoForge");

        forge.neo.NeoLogo.applyTo(stage);
        stage.setScene(scene);

        final javafx.beans.value.ChangeListener<Number> rescale =
                (o, was, is) -> forge.neo.card.CardNode.refreshCachesIn(scene.getRoot());
        stage.renderScaleXProperty().addListener(rescale);
        stage.renderScaleYProperty().addListener(rescale);

        forge.neo.discord.DiscordRich.setEnabled(NeoSettings.discord());
        scene.rootProperty().addListener((obs, old, root) -> {
            try {
                forge.neo.discord.DiscordStatus.screen(root);
            } catch (final RuntimeException e) {
                System.err.println("[neo] Discord: " + e);
            }
        });

        

        forge.neo.platform.NeoOs.installMacMouse(scene);

        stage.setFullScreenExitKeyCombination(javafx.scene.input.KeyCombination.NO_MATCH);
        stage.setFullScreenExitHint("");
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.setMaximized(true);

        final forge.neo.ui.LoadingScreen loading = new forge.neo.ui.LoadingScreen();
        scene.setRoot(loading);
        applyScale();
        stage.show();
        watchScreen(stage);

        if (NeoSettings.getBool(NeoSettings.FULLSCREEN, false)
                || Boolean.getBoolean("neo.fullscreen")) {
            stage.setFullScreen(true);
        }

        if (Boolean.getBoolean("neo.onTop")) {
            stage.setAlwaysOnTop(true);
            stage.toFront();
        }

        System.out.println("[startup] window visible at "
                + forge.neo.NeoBoot.sinceStart() + " ms");

        forge.neo.NeoLock.watchForRaise(() -> Platform.runLater(() -> {
            stage.setIconified(false);
            stage.toFront();
        }));

        final long loadingSnapAt = Long.getLong("neo.loading.snapAt", -1L);
        if (loadingSnapAt >= 0 && snapshotPath != null) {
            final PauseTransition t = new PauseTransition(Duration.millis(loadingSnapAt));
            t.setOnFinished(e -> Snapshots.write(scene, snapshotPath));
            t.play();
        }

        final Thread cargador = new Thread(() -> {
            try {
                forge.neo.NeoBoot.loadEngine(loading);
            } catch (final Throwable e) {

                e.printStackTrace();
                Platform.runLater(() -> showError(stage,
                        NeoText.get("loading.failed") + "\n\n" + e));
                return;
            }
            Platform.runLater(() -> afterEngineReady(stage, args, snapshotPath, waitSecs));
        }, "neo-carga");

        cargador.setDaemon(true);
        cargador.start();
    }

    private void afterEngineReady(final Stage stage, final List<String> args,
            final String snapshotPath, final int waitSecs) {
        System.out.println("[startup] ready to play at " + NeoBoot.sinceStart() + " ms");
        applyEngineSettings();
        forge.neo.platform.NeoGuiBase.setOnEngineCrash(msg -> Platform.runLater(this::onEngineCrashed));
        if (args.contains("--library")) showLibrary();
        else if (args.contains("--look")) showLook();
        else if (args.contains("--home")) showHome(NeoFormat.COMMANDER);
        else if (args.contains("--live")) {
            final Deck deck = firstDeckOf(NeoFormat.COMMANDER);
            if (deck == null) { showError(stage, "No Commander decks found"); return; }
            lastDeck = deck;
            showTable();
            startLiveGame(deck, Integer.parseInt(orDefault(optionOf(args, "--opponents"), "3")),
                args.contains("--watch") ? NeoMatchUI.Mode.OBSERVE : NeoMatchUI.Mode.HUMAN,
                Integer.parseInt(orDefault(optionOf(args, "--timeout"), "86400")), false, null, true);
        } else showMainMenu();
        showImportResult();
        if (snapshotPath != null) {
            final PauseTransition timer = new PauseTransition(Duration.seconds(waitSecs));
            timer.setOnFinished(e -> { Snapshots.write(scene, snapshotPath); if (Boolean.getBoolean("neo.testExit")) Platform.exit(); });
            timer.play();
        }
    }

    private void applyEngineSettings() {
        NeoSettings.applyAudioToEngine();

        NeoSettings.applyCardArtToEngine();

        forge.neo.look.NeoMusic.applyToEngine();
        forge.neo.look.NeoMusic.playMenus();
    }

    private void togglePauseMenu() {
        if (table.getMenuOverlay().isShowing()) {
            table.getMenuOverlay().hide();
            return;
        }
        openPauseMenu(true);
    }

    private PauseMenu openPauseMenu(final boolean countGesture) {
        final PauseMenu menu = PauseMenu.forMatch(new PauseMenu.Actions() {
            @Override
            public void resume() {
                table.getMenuOverlay().hide();
            }

            @Override
            public void restart() {
                table.getMenuOverlay().hide();
                leaveMatch(forge.neo.match.NeoMatchUI.Exit.RESTART);
            }

            @Override
            public void quitToMenu() {
                table.getMenuOverlay().hide();
                leaveMatch(forge.neo.match.NeoMatchUI.Exit.MENU);
            }

            @Override
            public boolean hasSavedOrders() {
                final forge.neo.match.NeoMatchUI ui = binder == null ? null : binder.getMatchUi();
                return ui != null && ui.hasSavedOrders();
            }

            @Override
            public void forgetSavedOrders() {
                final forge.neo.match.NeoMatchUI ui = binder == null ? null : binder.getMatchUi();
                if (ui != null) {
                    ui.forgetSavedOrders();
                }
            }
        }, this, false, false, true);

        final forge.neo.match.NeoMatchUI shown = binder == null ? null : binder.getMatchUi();
        if (shown != null && shown.isReplay()) {
            menu.asReplay();
        }
        menu.setGestureSpy(table::gesture);
        table.getMenuOverlay().show(menu);
        if (countGesture) {
            table.gesture(forge.neo.tutorial.Gesture.PAUSE_OPEN);
        }
        return menu;
    }

    private static Thread engineThread() {

        Thread fallback = null;
        for (final Thread t : Thread.getAllStackTraces().keySet()) {
            final String n = t.getName();
            if (!n.startsWith("Game-") || !t.isAlive()) {
                continue;
            }
            if (n.length() > 5 && Character.isDigit(n.charAt(5))) {
                return t;
            }
            fallback = t;
        }
        return fallback;
    }

    private static Deck firstDeckOf(final NeoFormat format) {
        final List<Deck> decks = format.decks();
        return decks.isEmpty() ? null : decks.get(0);
    }

    private void undoLast() {
        final TableBinder b = binder;
        final forge.neo.match.NeoMatchUI ui = b == null ? null : b.getMatchUi();
        if (ui != null) {
            ui.undoLast();
        }
    }

    private final java.util.EnumSet<forge.neo.NeoShortcuts.Action> heldShortcuts =
            java.util.EnumSet.noneOf(forge.neo.NeoShortcuts.Action.class);

    private static boolean isInside(final javafx.scene.Node node, final javafx.scene.Node ancestor) {
        for (javafx.scene.Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }

    private boolean runShortcut(final javafx.scene.input.KeyEvent ev) {
        
        if (ev.getTarget() instanceof javafx.scene.control.TextInputControl
                || scene.getFocusOwner() instanceof javafx.scene.control.TextInputControl) {
            return false;
        }
        final forge.neo.NeoShortcuts.Action action = forge.neo.NeoShortcuts.actionFor(ev);
        if (action == null) {
            return false;
        }
        if (!action.repeats() && !heldShortcuts.add(action)) {
            
            return true;
        }
        final TableBinder b = binder;
        final forge.neo.match.NeoMatchUI ui = b == null ? null : b.getMatchUi();
        final boolean free = !table.isModalShowing();
        switch (action) {
            case PASS_PRIORITY:

                if (free && table.getPromptBanner().acknowledge()) {
                    return true;
                }
                
                return free && table.getActionBar().pressPrimary();
            case PASS_TURN:
                return free && ui != null && ui.passTurn();
            case ALPHA_STRIKE:
                return free && ui != null && ui.alphaStrike();
            case ATTACK_NON_TOKENS:
                return free && ui != null && ui.attackWithNonTokens();
            case UNDO:
                
                undoLast();
                return true;
            case ZOOM_CARD: {

                final forge.neo.card.CardNode hovered = forge.neo.ui.CardZoom.hoveredCardNode(table);
                if (hovered != null && hovered.getCard() != null) {
                    table.showZoom(hovered.getCard());
                    return true;
                }
                return false;
            }
            case GAME_LOG:

                table.getLogButton().fire();
                return true;
            case EXPAND_STACK:
                return table.toggleAllStackEntries();
            case STACK_MENU:
                return free && ui != null && ui.openTopStackMenu();
            case AUTO_YIELD_YES:
            case AUTO_YIELD_NO: {
                if (!free || ui == null) {
                    return false;
                }
                final String said = ui.autoYieldTop(action == forge.neo.NeoShortcuts.Action.AUTO_YIELD_YES);
                if (said == null) {
                    return false;
                }
                table.getActionBar().setWarning(said, false);
                return true;
            }
            case FULL_CONTROL: {
                if (ui == null) {
                    return false;
                }
                final Boolean on = ui.toggleFullControl();
                if (on == null) {
                    return false;
                }
                table.getActionBar().setWarning(forge.neo.NeoText.get(on
                        ? "shortcuts.fullControl.on" : "shortcuts.fullControl.off"), false);
                return true;
            }
            case CONCEDE:
                
                if (table.getMenuOverlay().isShowing()) {
                    return false;
                }
                openPauseMenu(false).askQuit();
                return true;
            case MACRO_RECORD:
                return free && ui != null && ui.pressMacroRecord();
            case MACRO_PLAY:
                return free && ui != null && ui.pressMacroPlay();
            case MACRO_NEXT:
                return free && ui != null && ui.nextMacroAction();
            case SHOW_SHORTCUTS:
                if (table.getMenuOverlay().isShowing()) {
                    return false;
                }
                table.getMenuOverlay().show(new forge.neo.ui.ShortcutsPanel(true,
                        forge.neo.NeoText.get("common.close"), () -> table.getMenuOverlay().hide()));
                return true;
            default:
                return false;
        }
    }

    private void leaveMatch(final forge.neo.match.NeoMatchUI.Exit action) {
        final TableBinder b = binder;
        final forge.neo.match.NeoMatchUI ui = b == null ? null : b.getMatchUi();
        if (ui != null) {
            table.setPrompt(forge.neo.NeoText.get("app.closing"));

            ui.setOnAbandon(() -> Platform.runLater(() -> abandonMatch(b, false)));
            ui.leaveMatch(action);
        } else {

            if (b != null) {
                b.fireAbandoned(TableBinder.Abandon.BEFORE_START);
            }
            showHome();
        }
    }

    private void onEngineCrashed() {
        if (table == null) {
            return;
        }
        crashDialogUp = true;
        table.setPrompt(forge.neo.NeoText.get("app.engineDead.short"));
        table.getMenuOverlay().setOnBackgroundClick(null);
        table.getMenuOverlay().show(crashDialog = new forge.neo.ui.ConfirmDialog(
                forge.neo.NeoText.get("app.engineDead.title"),
                forge.neo.NeoText.get("app.engineDead.text"),
                java.util.List.of(forge.neo.NeoText.get("app.engineDead.leave")),
                0,
                choice -> {
                    crashDialogUp = false;
                    table.getMenuOverlay().hide();

                    abandonMatch(binder, true);
                }));
    }

    private volatile boolean crashDialogUp;

    private forge.neo.ui.ConfirmDialog crashDialog;

    private boolean crashDialogShowing() {
        return crashDialogUp && table != null && crashDialog != null
                && table.getMenuOverlay().isShowing()
                && table.getMenuOverlay().getContent() == crashDialog;
    }

    private void abandonMatch(final TableBinder leaving, final boolean crashed) {
        if (crashDialogShowing()) {
            
            return;
        }

        crashDialogUp = false;
        if (leaving == null || binder != leaving) {
            
            return;
        }
        leaving.fireAbandoned(crashed ? TableBinder.Abandon.CRASH : TableBinder.Abandon.QUIT);
        abandonedBinder = leaving;
        binder = null;
        showHome();
    }

    volatile TableBinder abandonedBinder;

    @Override
    public void setAutoPayMana(final boolean on) {
        final TableBinder b = binder;
        final forge.neo.match.NeoMatchUI ui = b == null ? null : b.getMatchUi();
        if (ui != null) {
            ui.setAutoPayMana(on);
        }
    }

    @Override
    public void setAiSpeed(final int hundredths) {
        final TableBinder b = binder;
        final forge.neo.match.NeoMatchUI ui = b == null ? null : b.getMatchUi();
        if (ui != null) {
            ui.setAiSpeed(hundredths);
        }
    }

    @Override
    public void setPauseMode(final int mode) {
        final TableBinder b = binder;
        final forge.neo.match.NeoMatchUI ui = b == null ? null : b.getMatchUi();
        if (ui != null) {
            ui.setPauseMode(mode);
        }
    }

    @Override
    public boolean isInMatch() {
        final TableBinder b = binder;
        return b != null && b.getMatchUi() != null;
    }

    @Override
    public boolean restartToImport() {
        final String launcher = relauncher();
        if (launcher == null) {
            return false;
        }
        NeoLock.release();
        try {
            forge.neo.platform.NeoOs.relaunch(java.util.Collections.singletonList(launcher)).start();
        } catch (final java.io.IOException e) {
            System.err.println("[neo] no se ha podido reabrir para importar: " + e);
            return false;
        }
        Platform.exit();
        return true;
    }

    private void showImportResult() {
        final java.util.Properties r = forge.neo.data.NeoBackup.takeResult(
                forge.neo.data.DataPlaces.desktop().root);
        if (r == null) {
            return;
        }
        final String title;
        final String body;
        if (r.getProperty("error") != null) {
            title = NeoText.get("import.failed.title");
            body = NeoText.get("import.failed.body", r.getProperty("error"));
        } else {
            title = NeoText.get("import.done.title");
            final String from = r.getProperty("from", "");
            body = NeoText.get("REPLACE".equals(r.getProperty("mode")) ? "import.done.replace" : "import.done.add",
                    r.getProperty("written", "0"), r.getProperty("kept", "0"),
                    from.isEmpty() ? "?" : Character.toUpperCase(from.charAt(0)) + from.substring(1),
                    r.getProperty("version", "?"), r.getProperty("backup", ""));
        }
        final javafx.scene.Parent previous = scene.getRoot();
        final javafx.scene.layout.StackPane layer = new javafx.scene.layout.StackPane();
        layer.getStyleClass().add("overlay");
        layer.getChildren().add(new forge.neo.ui.ConfirmDialog(title, body,
                java.util.List.of(NeoText.get("common.accept")), 0,
                choice -> {
                    if (scene.getRoot() instanceof javafx.scene.layout.StackPane sp
                            && sp.getChildren().contains(layer)) {
                        sp.getChildren().remove(layer);
                        if (sp.getChildren().size() == 1) {
                            scene.setRoot((javafx.scene.Parent) sp.getChildren().get(0));
                        }
                    }
                }));
        final javafx.scene.layout.StackPane both = new javafx.scene.layout.StackPane(previous, layer);
        scene.setRoot(both);
        applyScale();
    }

    @Override
    public void refreshTable() {
        final TableBinder b = binder;
        if (b != null) {
            b.requestRefresh();
        }
    }

    @Override
    public boolean isFullScreen() {
        return stage != null && stage.isFullScreen();
    }

    @Override
    public void setFullScreen(final boolean on) {
        if (stage != null) {
            stage.setFullScreen(on);
        }
    }

    @Override
    public double getTextZoom() {
        return table == null ? NeoSettings.getDouble(NeoSettings.TEXT_ZOOM, 1.15)
                : table.getDetailPanel().getTextZoom();
    }

    @Override
    public void setTextZoom(final double zoom) {
        if (table != null) {
            table.getDetailPanel().setTextZoom(zoom);
        }
    }

    private double measuredW;
    private double measuredH;

    private void watchScreen(final Stage stage) {
        final String test = System.getProperty("neo.screenTest");
        if (test != null) {
            final javafx.animation.PauseTransition later =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(4));
            later.setOnFinished(e -> {
                try {
                    final String[] wh = test.split("x");
                    remeasure(Double.parseDouble(wh[0]), Double.parseDouble(wh[1]));
                } catch (final RuntimeException ex) {
                    System.err.println("neo.screenTest no valido (ANCHOxALTO): " + test);
                }
            });
            later.play();
        }
        if (System.getProperty("neo.win") != null) {
            return;
        }
        final javafx.animation.PauseTransition settle =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(400));
        settle.setOnFinished(e -> {
            final double cx = stage.getX() + stage.getWidth() / 2;
            final double cy = stage.getY() + stage.getHeight() / 2;
            final List<Screen> under = Screen.getScreensForRectangle(cx, cy, 1, 1);
            if (!under.isEmpty()) {
                final javafx.geometry.Rectangle2D vb = under.get(0).getVisualBounds();
                remeasure(vb.getWidth(), vb.getHeight());
            }
        });
        final javafx.beans.value.ChangeListener<Number> moved = (o, was, is) -> settle.playFromStart();
        stage.xProperty().addListener(moved);
        stage.yProperty().addListener(moved);
        stage.outputScaleXProperty().addListener(moved);
    }

    private void remeasure(final double w, final double h) {
        if (Math.abs(w - measuredW) < 1 && Math.abs(h - measuredH) < 1) {
            return;
        }
        measuredW = w;
        measuredH = h;
        UiScale.setScreenSize(w, h);
        sideWidth = UiScale.sideWidth(w);
        cardWidth = UiScale.cardWidth(w, h);
        System.out.printf(java.util.Locale.ROOT,
                "[pantalla] otra pantalla: %.0fx%.0f -> carta %.0f, columna %.0f%n", w, h, cardWidth, sideWidth);
        applyScale();
        if (scene != null && scene.getRoot() instanceof forge.neo.ui.MainMenu) {
            showMainMenu();
        } else if (scene != null && home != null && scene.getRoot() == home && lastFormat != null) {

            showHome(lastFormat);
        } else if (scene != null && scene.getRoot() != null) {
            scene.getRoot().requestLayout();
        }
    }

    @Override
    public void applyScale() {
        if (scene != null && scene.getRoot() != null) {
            final String style = UiScale.rootStyle(scene.getHeight());

            if (!style.equals(lastScaleStyle)) {
                System.out.printf(java.util.Locale.ROOT, "[escala] %s alto de la ventana %.0f%n",
                        style, scene.getHeight());
                lastScaleStyle = style;
            }
            scene.getRoot().setStyle(style);
        }
    }

    private String lastScaleStyle;

    void showMainMenu() {
        home = null;
        scene.setRoot(new MainMenu(new MainMenu.Actions() {
            public void play(final NeoFormat format) { showHome(NeoFormat.COMMANDER); }
            public void library() { showLibrary(); }
            public void look() { showLook(); }
            public void replays() { showReplays(); }
            public void settings() { showMenuSettings(); }
            public void quit() { Platform.exit(); }
        }));
        applyScale();
    }

    private void showLibrary() {
        scene.setRoot(new forge.neo.ui.CardLibraryScreen(cardWidth, this::showMainMenu));
        applyScale();
    }

    void showReplays() {
        scene.setRoot(new forge.neo.ui.ReplaysScreen(this::playReplay, this::showMainMenu));
        applyScale();
    }

    private void playReplay(final java.io.File file) {
        if (forge.gamemodes.net.client.ReplayPlayer.isPlaying()) {
            return;
        }
        final TableScreen oldTable = table;
        final TableBinder oldBinder = binder;
        table = new TableScreen(cardWidth, sideWidth);
        table.getDetailPanel().setTextZoom(NeoSettings.getDouble(NeoSettings.TEXT_ZOOM, 1.15));
        final TableBinder b = new TableBinder(table);
        binder = b;

        final NeoMatchUI gui = new NeoMatchUI(NeoMatchUI.Mode.HUMAN, false);
        gui.setBinder(b);
        gui.setTable(table);
        gui.setEnding(NeoMatchUI.Ending.REPLAY);
        b.setMatchUi(gui);
        gui.setOnOpened(() -> {
            Platform.runLater(this::showTable);

            if (Boolean.getBoolean("neo.replay.fast")) {
                final Thread t = new Thread(() -> {
                    try {
                        Thread.sleep(1500);
                    } catch (final InterruptedException e) {
                        return;
                    }
                    for (int i = 0; i < 3; i++) {
                        Platform.runLater(() -> gui.getGameController().selectButtonCancel());
                    }

                    for (int i = 0; i < 150; i++) {
                        try {
                            Thread.sleep(1000);
                        } catch (final InterruptedException e) {
                            return;
                        }
                        final boolean[] done = {false};
                        final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
                        Platform.runLater(() -> {
                            final javafx.scene.Node over = scene.getRoot().lookup(".game-over .btn-primary");
                            if (over instanceof javafx.scene.control.Button b2) {
                                System.out.println("[neo] replay test: pressing " + b2.getText());
                                b2.fire();
                                done[0] = true;
                            }
                            l.countDown();
                        });
                        try {
                            l.await();
                        } catch (final InterruptedException e) {
                            return;
                        }
                        if (done[0]) {
                            return;
                        }
                    }
                }, "neo-replay-fast");
                t.setDaemon(true);
                t.start();
            }
        });
        gui.setOnClosed(() -> Platform.runLater(this::showReplays));
        try {
            System.out.println("[neo] Replay: " + file.getName());
            forge.gamemodes.net.client.ReplayPlayer.play(file, gui);
        } catch (final java.io.IOException | RuntimeException e) {
            System.out.println("[neo] Could not open the replay " + file.getName() + ": " + e);
            
            table = oldTable;
            binder = oldBinder;
            forge.neo.ui.Toast.show(NeoText.get("replays.broken"));
        }
    }

    private void showMenuSettings() {
        if (settingsOverlay != null) {
            hideMenuSettings();
            return;
        }
        final javafx.scene.Parent previous = scene.getRoot();
        final javafx.scene.layout.StackPane layer = new javafx.scene.layout.StackPane();
        layer.getStyleClass().add("overlay");
        layer.getChildren().add(new SettingsPanel(this, this::hideMenuSettings));

        final javafx.scene.layout.StackPane root =
                new javafx.scene.layout.StackPane(previous, layer);
        settingsUnder = previous;
        settingsOverlay = root;
        scene.setRoot(root);
        applyScale();
    }

    private void hideMenuSettings() {
        final javafx.scene.Parent under = settingsUnder;
        final javafx.scene.layout.StackPane holder = settingsOverlay;
        settingsOverlay = null;
        settingsUnder = null;
        if (under == null || holder == null) {
            return;
        }
        holder.getChildren().remove(under);
        scene.setRoot(under);
        applyScale();

        under.requestLayout();
        Platform.runLater(under::requestLayout);
    }

    private javafx.scene.layout.StackPane settingsOverlay;
    private javafx.scene.Parent settingsUnder;

    private <T> java.util.List<T> askChoice(final String message, final int min, final int max,
                                            final java.util.List<T> options,
                                            final java.util.List<T> preselected,
                                            final java.util.function.Function<T, String> display) {
        if (Platform.isFxApplicationThread() || scene == null) {
            return null;
        }
        final java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.List<T> answer = new java.util.ArrayList<>();
        Platform.runLater(() -> {
            final javafx.scene.layout.StackPane layer = new javafx.scene.layout.StackPane();
            layer.getStyleClass().add("overlay");
            final javafx.scene.Parent previous = scene.getRoot();
            layer.getChildren().add(new forge.neo.ui.ChoiceDialog<>(
                    message, options, min, max, display, cardWidth * 1.4, preselected,
                    picked -> {

                        try {
                            answer.addAll(picked);

                            if (scene.getRoot() instanceof javafx.scene.layout.Pane pane) {
                                pane.getChildren().remove(previous);
                            }
                            scene.setRoot(previous);
                            applyScale();
                        } finally {
                            done.countDown();
                        }
                    }));
            scene.setRoot(new javafx.scene.layout.StackPane(previous, layer));
            applyScale();
        });
        try {
            done.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return answer;
    }

    private void showFirstRunLanguage() {
        final String loaded = forge.neo.NeoLanguage.current();
        scene.setRoot(new forge.neo.ui.LanguageScreen(picked -> {
            forge.neo.NeoLanguage.set(picked);
            if (picked.equals(loaded)) {
                showMainMenu();
                return;
            }
            reopenIn(picked);
        }));
        applyScale();
    }

    private void reopenIn(final String language) {
        String label = language;
        for (final forge.neo.NeoLanguage.Option o : forge.neo.NeoLanguage.available()) {
            if (o.getId().equals(language)) {
                label = o.getLabel();
                break;
            }
        }
        final String launcher = relauncher();
        final String detail = launcher != null
                ? NeoText.get("lang.reopen.auto", label)
                : NeoText.get("lang.reopen.manual", label);

        final javafx.scene.layout.StackPane layer = new javafx.scene.layout.StackPane();
        layer.getStyleClass().add("overlay");
        final javafx.scene.Parent previous = scene.getRoot();
        
        scene.setRoot(new javafx.scene.layout.StackPane(previous, layer));
        applyScale();
    }

    private static String relauncher() {
        final String command = ProcessHandle.current().info().command().orElse(null);
        if (command == null) {
            return null;
        }
        final String name = new java.io.File(command).getName().toLowerCase(java.util.Locale.ROOT);
        if (name.equals("java.exe") || name.equals("javaw.exe") || name.equals("java")) {
            return null;
        }
        return command;
    }

    private void showHome(final NeoFormat format) {

        lastFormat = format;

        home = new HomeScreen(format, format.decks(), cardWidth * 1.7,
                this::startFromHome, this::showMainMenu, this::showDeckBuilder,
                () -> showNetDecks(format));
        scene.setRoot(home);
        applyScale();
        
        final List<String> broken = forge.neo.deck.BrokenDecks.takePending();
        if (!broken.isEmpty()) {
            home.showBrokenDecks(broken);
        }
    }

    private void showNetDecks(final NeoFormat format) {
        scene.setRoot(new forge.neo.ui.NetDecksScreen(format, () -> showHome(format)));
        applyScale();
    }

    private void showHome() { showHome(NeoFormat.COMMANDER); }

    private void showDeckBuilder(final Deck deck) {
        showDeckBuilder(deck, lastFormat);
    }

    private void showDeckBuilder(final Deck deck, final forge.neo.deck.DeckContext where) {
        final NeoFormat format = lastFormat;
        final forge.neo.deck.DeckEditor editor = deck == null
                ? forge.neo.deck.DeckEditor.createNew(where, nextDeckName(where))
                : forge.neo.deck.DeckEditor.copyOf(where, deck);

        builder = new DeckBuilderScreen(editor, cardWidth, () -> {
            builder = null;
            showHome(format);
        });

        if (deck == null && format == NeoFormat.COMMANDER) {
            builder.startByPickingCommander();
        }
        scene.setRoot(builder);
        applyScale();
    }

    DeckBuilderScreen builder;

    private forge.neo.ui.LookScreen lookScreen;

    private void showLook() {
        lookScreen = new forge.neo.ui.LookScreen(this::showMainMenu, () -> {
            if (table != null) {
                table.applyPlaymat();
            }
        });
        scene.setRoot(lookScreen);
        applyScale();
    }

    static String nextDeckName(final forge.neo.deck.DeckContext format) {
        final String base = forge.neo.NeoText.get("deck.newDeck");
        if (!format.storage().contains(base)) {
            return base;
        }
        for (int i = 2; i < 500; i++) {
            if (!format.storage().contains(base + " " + i)) {
                return base + " " + i;
            }
        }
        return base;
    }

    void showTable() {
        if (table == null) {
            table = new TableScreen(cardWidth, sideWidth);
        }
        scene.setRoot(table);
        applyScale();
    }

    private void startFromHome(final Deck deck, final int opponents,
                               final String aiProfile, final boolean watch,
                               final List<Deck> opponentDecks, final int[] teams) {
        lastDeck = deck;
        lastOpponents = opponents;
        lastAi = aiProfile;
        lastWatch = watch;
        lastOpponentDecks = opponentDecks;
        lastTeams = teams;

        table = new TableScreen(cardWidth, sideWidth);
        table.getDetailPanel().setTextZoom(NeoSettings.getDouble(NeoSettings.TEXT_ZOOM, 1.15));
        showTable();
        startLiveGame(deck, opponents,
                watch ? NeoMatchUI.Mode.OBSERVE : NeoMatchUI.Mode.HUMAN,
                (int) java.util.concurrent.TimeUnit.DAYS.toSeconds(1),

                false, forge.neo.look.RivalSetup.perRival(aiProfile), true);
    }

    private void startLiveGame(final Deck deck, final int opponents,
                               final NeoMatchUI.Mode mode, final int timeout,
                               final boolean verbose, final String aiProfile,
                               final boolean backToHome) {
        final TableBinder liveBinder = new TableBinder(table);
        this.binder = liveBinder;
        final boolean autoMana = NeoSettings.autoPayMana() || Boolean.getBoolean("neo.autoPay");
        final NeoFormat format = lastFormat;
        table.setPrompt(forge.neo.NeoText.get("app.preparing"));

        final Thread engine = new Thread(() -> {
            NeoMatchUI.Exit exit = NeoMatchUI.Exit.MENU;

            if (format == forge.neo.match.NeoFormat.COMMANDER && mode == NeoMatchUI.Mode.HUMAN) {
                forge.neo.match.FriendlyAttacks.arm(NeoSettings.getInt(
                        forge.neo.match.FriendlyAttacks.SETTING, forge.neo.match.FriendlyAttacks.NORMAL));
            }
            try {

                final NeoGame.Result r = NeoGame.play(deck, opponents, mode, timeout, verbose,

                        liveBinder, aiProfile, autoMana, format, lastOpponentDecks,
                        mode == NeoMatchUI.Mode.HUMAN ? NeoSettings.bestOf(format) : 1,

                        forge.neo.match.Planechase.wrap(format,
                                forge.neo.match.Archenemy.wrap(format,
                                        forge.neo.match.NeoTeams.seating(format, lastTeams),
                                        
                                        NeoSettings.archenemy()
                                                && opponents >= forge.neo.match.Archenemy.MIN_OPPONENTS,
                                        NeoSettings.archenemyWho()),
                                NeoSettings.planechase(), NeoSettings.planechaseShared()));
                if (r.exit != null) {
                    exit = r.exit;
                }
            } catch (final Exception e) {
                System.err.println("[neo] la partida ha fallado: " + e);
                e.printStackTrace();
            } finally {
                forge.neo.match.FriendlyAttacks.disarm();

                if (backToHome) {
                    final NeoMatchUI.Exit decided = exit;
                    Platform.runLater(() -> {

                        if (abandonedBinder == liveBinder) {
                            abandonedBinder = null;
                            return;
                        }
                        binder = null;
                        if (decided == NeoMatchUI.Exit.RESTART && lastDeck != null) {

                            startFromHome(lastDeck, lastOpponents, lastAi, lastWatch,
                                    lastOpponentDecks, lastTeams);
                        } else {
                            showHome();
                        }
                    });
                }
            }
        }, "Game-neo-launcher");
        engine.setDaemon(true);
        engine.start();
    }

    private void showError(final Stage stage, final String msg) {
        final Label l = new Label(msg);
        l.getStyleClass().add("hud-title");
        final StackPane p = new StackPane(l);
        p.getStyleClass().add("table-root");
        p.setPadding(new Insets(40));
        final Scene s = new Scene(p, 900, 200);
        final var css = NeoApp.class.getResource("/forge/neo/neo.css");
        if (css != null) {
            s.getStylesheets().add(css.toExternalForm());
        }
        forge.neo.platform.NeoFonts.apply(s);
        stage.setScene(s);
        stage.show();
    }

    static String optionOf(final List<String> args, final String key) {
        for (final String a : args) {
            if (a.startsWith(key + "=")) {
                return a.substring(key.length() + 1);
            }
        }
        return null;
    }

    static String orDefault(final String v, final String def) {
        return v == null ? def : v;
    }
}
