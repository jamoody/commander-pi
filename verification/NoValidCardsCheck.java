import forge.gui.GuiBase;
import forge.neo.*;
import forge.neo.match.*;
import forge.neo.platform.*;
import forge.neo.ui.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import java.util.concurrent.*;

/** Verifies notification acknowledgement through the packaged JavaFX UI. */
public class NoValidCardsCheck {
    static TableScreen table;
    static NeoMatchUI ui;
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        System.out.println("PASS: " + message);
    }
    static void fx(Runnable r) throws Exception {
        FutureTask<Void> t = new FutureTask<>(r, null);
        Platform.runLater(t); t.get(10, TimeUnit.SECONDS);
    }
    static void notice(boolean onFx, String screenshot) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Runnable send = () -> {
            try { ui.message("Winota, Joiner of Forces effect's value is No valid cards", "Winota, Joiner of Forces"); done.complete(null); }
            catch (Throwable e) { done.completeExceptionally(e); }
        };
        if (onFx) Platform.runLater(send); else new Thread(send, "engine-notification-test").start();
        boolean[] shown = {false};
        for (int n=0;n<50 && !shown[0];n++) {
            fx(() -> shown[0] = table.getOverlay().getContent() instanceof CardPromptDialog);
            if (!shown[0]) Thread.sleep(100);
        }
        check(shown[0], "result dialog displayed" + (onFx ? " on FX thread" : " from engine thread"));
        fx(() -> {
            table.setPrompt("Next priority prompt");
            table.getActionBar().setButtons("OK", "Cancel", true, true);
        });
        Thread.sleep(1500);
        check(!done.isDone(), "resolution waits for acknowledgement");
        fx(() -> {
            var dialog = table.getOverlay().getContent();
            check(dialog instanceof CardPromptDialog, "dialog survives prompt updates");
            check(dialog.lookupAll(".label").stream().anyMatch(n -> n instanceof Label l && l.getText().equals("Winota, Joiner of Forces")), "source card identified");
            check(dialog.lookupAll(".label").stream().anyMatch(n -> n instanceof Label l && l.getText().contains("No valid cards")), "result text preserved");
            Snapshots.write(table.getScene(), screenshot);
            ((Button)dialog.lookup(".card-prompt-option")).fire();
        });
        done.get(5, TimeUnit.SECONDS);
        fx(() -> check(table.getOverlay().getContent() == null, "OK dismisses result and resumes caller"));
    }
    public static void main(String[] args) throws Exception {
        NeoPortable.apply(); GuiBase.setInterface(new NeoGuiBase(new FxUiDispatcher())); NeoBoot.loadEngine(null);
        Platform.startup(() -> {});
        try {
            fx(() -> {
                table = new TableScreen(160,260); ui = new NeoMatchUI(NeoMatchUI.Mode.HUMAN,false); ui.setTable(table);
                Stage stage=new Stage(); Scene scene=new Scene(table,1280,800);
                scene.getStylesheets().add(NoValidCardsCheck.class.getResource("/forge/neo/neo.css").toExternalForm());
                stage.setScene(scene); stage.show();
            });
            notice(false,args[0]); notice(true,args[0]);
            ui.message("Routine informational result", "Info");
            fx(() -> check(table.getOverlay().getContent()==null,"routine information remains nonmodal"));
            new NeoMatchUI(NeoMatchUI.Mode.AUTO_PLAY,false).message("No valid cards", "Winota");
            System.out.println("NO VALID CARDS CHECK PASSED");
            System.exit(0);
        } catch(Throwable e) {e.printStackTrace();System.exit(1);}
    }
}
