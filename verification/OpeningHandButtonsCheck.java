import forge.deck.Deck;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.neo.*;
import forge.neo.match.*;
import forge.neo.platform.*;
import forge.neo.ui.TableScreen;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Disposable-profile check: human + three AIs, keep opening hand, then exit normally. */
public final class OpeningHandButtonsCheck {
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        System.out.println("PASS: " + message);
    }
    static boolean click(TableBinder binder, String field, String label) throws Exception {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                var bar = binder.getTable().getActionBar();
                var f = bar.getClass().getDeclaredField(field);
                f.setAccessible(true);
                var button = (javafx.scene.control.Button) f.get(bar);
                if (button.isDisabled() || !button.isVisible()
                        || !button.getText().equals(label)) { result.complete(false); return; }
                System.out.println("Clicking UI button: " + button.getText());
                button.fire();
                result.complete(true);
            } catch (Throwable e) { result.completeExceptionally(e); }
        });
        return result.get(5, TimeUnit.SECONDS);
    }
    public static void main(String[] args) throws Exception {
        NeoPortable.apply();
        GuiBase.setInterface(new NeoGuiBase(new FxUiDispatcher()));
        NeoBoot.loadEngine(null);
        FModel.getPreferences().setPref(FPref.PLAYER_NAME, "Commander Test");
        // Old installations may have enabled this; match startup must override it.
        FModel.getPreferences().setPref(FPref.UI_RECORD_REPLAYS, true);
        List<Deck> decks = NeoFormat.COMMANDER.decks();
        CompletableFuture<TableBinder> ready = new CompletableFuture<>();
        Platform.startup(() -> {
            TableScreen table = new TableScreen(160, 260);
            Stage stage = new Stage();
            Scene scene = new Scene(table, 1280, 800);
            scene.getStylesheets().add(OpeningHandButtonsCheck.class.getResource("/forge/neo/neo.css").toExternalForm());
            stage.setScene(scene);
            stage.show();
            ready.complete(new TableBinder(table));
        });
        TableBinder binder = ready.get(30, TimeUnit.SECONDS);
        CompletableFuture<Void> verified = new CompletableFuture<>();
        NeoGame.setOnUiForTest(ui -> {
            Thread probe = new Thread(() -> {
                try {
                    long deadline = System.currentTimeMillis() + 60000;
                    long lastShot = 0;
                    boolean kept = false;
                    Object mulliganInput = null;
                    boolean takeMulligan = args.length < 2 || !args[1].equals("keep");
                    while (System.currentTimeMillis() < deadline) {
                        Platform.runLater(() -> {
                            var content = binder.getTable().getOverlay().getContent();
                            if (content instanceof forge.neo.ui.ChoiceDialog<?> notice) {
                                if (!notice.acknowledge()) notice.pickNumber(1, false);
                            }
                        });
                        if (System.currentTimeMillis() - lastShot > 10000) {
                            lastShot = System.currentTimeMillis();
                            Platform.runLater(() -> {
                                System.out.println("Overlay: " + binder.getTable().getOverlay().getContent());
                                Snapshots.write(binder.getTable().getScene(), args[0]);
                            });
                        }
                        var view = ui.getGameView();
                        if (view != null && view.getPlayers() != null && view.getPlayers().size() == 4
                                && ui.getGameController() instanceof forge.player.PlayerControllerHuman human) {
                            var input = human.getInputQueue().getInput();
                            if (input != null && input.getClass().getSimpleName().equals("InputLondonMulligan")) {
                                // First Commander mulligan is free; the engine still asks to return zero cards.
                                if (click(binder, "ok", "OK")) check(true, "zero-card bottom prompt confirmed through UI");
                                Thread.sleep(200);
                                continue;
                            }
                            if (!kept && input != null && input.getClass().getSimpleName().contains("Mulligan")) {
                                if (takeMulligan && mulliganInput == null) {
                                    if (click(binder, "cancel", "Mulligan")) { mulliganInput = input; check(true, "Mulligan UI button enabled and clicked"); }
                                    Thread.sleep(200);
                                    continue;
                                }
                                if (mulliganInput == input) { Thread.sleep(100); continue; }
                                if (!click(binder, "ok", "Keep")) { Thread.sleep(100); continue; }
                                if (takeMulligan) check(true, "new keep prompt received after mulligan");
                                check(ui.getLocalPlayers().size() == 1, "four seats include one human and three AI opponents");
                                check(ui.localPlayerView().getLife() == 40, "human starts with 40 life");
                                check(!FModel.getPreferences().getPrefBoolean(FPref.UI_RECORD_REPLAYS), "old recording preference overridden");
                                kept = true;
                            } else if (kept && view.getTurn() >= 1 && input != null
                                    && !input.getClass().getSimpleName().contains("Mulligan")) {
                                check(true, "opening hand accepted and normal play reached");
                                CompletableFuture<Void> shot = new CompletableFuture<>();
                                Platform.runLater(() -> {
                                    try {
                                        Snapshots.write(binder.getTable().getScene(), args[0]);
                                        check(binder.getTable().lookupAll(".macro-button").isEmpty(), "no macro controls on table");
                                        shot.complete(null);
                                    } catch (Throwable e) { shot.completeExceptionally(e); }
                                });
                                shot.get(15, TimeUnit.SECONDS);
                                verified.complete(null);
                                ui.leaveMatch(NeoMatchUI.Exit.MENU);
                                return;
                            }
                        }
                        Thread.sleep(100);
                    }
                    throw new AssertionError("Human match did not reach normal play");
                } catch (Throwable error) { verified.completeExceptionally(error); error.printStackTrace(); System.exit(1); }
            }, "human-match-check");
            probe.setDaemon(true);
            probe.start();
        });
        try {
            var result = NeoGame.play(decks.get(0), 3, NeoMatchUI.Mode.HUMAN, 90, false,
                    binder, null, true, NeoFormat.COMMANDER, decks.subList(1, 4));
            verified.get(1, TimeUnit.SECONDS);
            check(result.completed && result.exit == NeoMatchUI.Exit.MENU, "concede/return-to-menu ends match cleanly");
            System.out.println("OPENING HAND BUTTONS CHECK PASSED");
            System.exit(0);
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}
