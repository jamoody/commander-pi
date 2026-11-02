import forge.deck.Deck;
import forge.gui.GuiBase;
import forge.neo.NeoBoot;
import forge.neo.NeoPortable;
import forge.neo.NeoSettings;
import forge.neo.Snapshots;
import forge.neo.match.NeoFormat;
import forge.neo.platform.ConsoleUiDispatcher;
import forge.neo.platform.NeoGuiBase;
import forge.neo.ui.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

/** Run against the packaged app with a disposable neo.dataDir and a screenshot path. */
public final class NetDeckPickerCheck {
    private static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        System.out.println("PASS: " + message);
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        NeoPortable.apply();
        GuiBase.setInterface(new NeoGuiBase(new ConsoleUiDispatcher()));
        NeoBoot.loadEngine(null);
        List<Deck> stock = NeoFormat.COMMANDER.decks();
        Platform.startup(() -> {
            try {
                HomeScreen home = new HomeScreen(NeoFormat.COMMANDER, new ArrayList<>(stock), 180,
                        (deck, opponents, ai, watch, rivals, teams) -> {}, () -> {}, (deck, where) -> {}, () -> {});
                // Simulate an already downloaded cache without network or personal deck data.
                List<Deck> net = (List<Deck>) field(home, "net");
                net.clear();
                for (int i = 0; i < 45; i++) net.add((Deck) stock.get(i).copyTo("Net fixture " + i));
                Stage stage = new Stage();
                Scene scene = new Scene(home, 1280, 800);
                scene.getStylesheets().add(HomeScreen.class.getResource("/forge/neo/neo.css").toExternalForm());
                stage.setScene(scene);
                stage.show();
                home.openOpponentPicker(0, false);
                DeckPickerDialog picker = (DeckPickerDialog) field(home, "picker");
                Button tab = (Button) picker.lookup("#picker-net-decks");
                check(tab != null, "HomeScreen wires downloaded decks into opponent picker");
                tab.fire();
                List<DeckTile> tiles = (List<DeckTile>) field(picker, "tiles");
                check(tiles.size() == 40, "first net-deck page contains 40 tiles");
                Pager pager = (Pager) field(picker, "pager");
                pager.goToItem(40);
                check(tiles.size() == 5, "remaining net decks are reachable on page two");
                TextField search = (TextField) field(picker, "search");
                search.setText("Net fixture 44");
                check(tiles.size() == 1 && pager.from() == 0, "search resets page and finds a specific net deck");
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                Snapshots.write(scene, args[0]);
                tiles.get(0).getOnMouseClicked().handle(null);
                List<Deck> selected = (List<Deck>) field(home, "opponentDecks");
                check(selected.get(0) == net.get(44), "click assigns the exact net deck to opponent one");
                check(NeoSettings.rivalDeck(NeoFormat.COMMANDER, 0).equals("deck:Net fixture 44"),
                        "selected net deck is saved for the opponent");
                var resolve = HomeScreen.class.getDeclaredMethod("resolvedOpponentDecks");
                resolve.setAccessible(true);
                List<Deck> resolved = (List<Deck>) resolve.invoke(home);
                check(resolved.get(0).getName().equals("Net fixture 44"), "match receives the selected net deck");
                home.openOpponentPicker(1, false);
                DeckPickerDialog second = (DeckPickerDialog) field(home, "picker");
                second.showStock();
                check(!((List<?>) field(second, "tiles")).isEmpty(), "Forge deck selection remains available");
                DeckPickerDialog empty = new DeckPickerDialog("Empty cache", List.of(), List.of(), 100, d -> {}, () -> {});
                empty.setNetDecks(List.of());
                empty.setNetDecks(null);
                check(empty.lookup("#picker-net-decks") == null, "empty or missing cache adds no dead tab");
                stage.close();
                System.exit(0);
            } catch (Throwable error) {
                error.printStackTrace();
                System.exit(1);
            }
        });
    }
}
