import forge.neo.NeoBoot;
import forge.neo.NeoPortable;
import forge.neo.deck.CardLibrary;
import forge.neo.deck.DeckEditor;
import forge.neo.match.NeoFormat;
import forge.neo.match.NeoGame;
import forge.neo.match.NeoMatchUI;
import forge.neo.platform.ConsoleUiDispatcher;
import forge.neo.platform.NeoGuiBase;
import forge.gui.GuiBase;
import forge.deck.Deck;
import forge.game.player.RegisteredPlayer;
import java.util.*;

/** Integration checks against the actual stripped distribution and assets. */
public final class CommanderSmoke {
    static final java.util.concurrent.atomic.AtomicBoolean fourSeats = new java.util.concurrent.atomic.AtomicBoolean();
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        System.out.println("PASS: " + message);
    }
    public static void main(String[] args) {
        try {
            NeoPortable.apply();
            GuiBase.setInterface(new NeoGuiBase(new ConsoleUiDispatcher()));
            NeoBoot.loadEngine(null);
            check(NeoFormat.values().length == 1, "only Commander is registered");
            List<Deck> decks = NeoFormat.COMMANDER.decks();
            check(decks.size() > 100, "Commander precons retained: " + decks.size());
            Deck deck = decks.stream().filter(d -> !d.getCommanders().isEmpty()).findFirst().orElseThrow();
            RegisteredPlayer player = NeoFormat.COMMANDER.register(deck, 4);
            check(player.getStartingLife() == 40, "Commander starts at 40 life");
            check(player.getCommanders().equals(deck.getCommanders()), "command zone registration preserved");
            check(CardLibrary.get().size() > 30000, "encyclopedia includes full card catalog");
            Deck copy = (Deck) deck.copyTo("Commander smoke test");
            new DeckEditor(NeoFormat.COMMANDER, copy).save();
            check(NeoFormat.COMMANDER.storage().contains(copy.getName()), "Commander deck saves to Commander storage");
            // This profile is disposable and never points to the user's installation.
            NeoFormat.COMMANDER.storage().delete(copy.getName());
            List<Deck> opponents = new ArrayList<>();
            for (Deck d : decks) {
                if (!d.getCommanders().isEmpty() && d != deck) opponents.add(d);
                if (opponents.size() == 4) break;
            }
            NeoGame.setOnUiForTest(ui -> {
                Thread probe = new Thread(() -> {
                    long until = System.currentTimeMillis() + 60000;
                    while (System.currentTimeMillis() < until) {
                        var gv = ui.getGameView();
                        if (gv != null && gv.getPlayers() != null && gv.getPlayers().size() == 4
                                && ui.getGameController() instanceof forge.player.PlayerControllerHuman human
                                && human.getInputQueue().getInput() instanceof forge.gamemodes.match.input.InputPlaybackControl) {
                            fourSeats.set(true);
                            forge.gui.FThreads.invokeInEdtNowOrLater(() -> ui.getGameController().selectButtonCancel());
                            System.out.println("PASS: live Commander game has four seats");
                            return;
                        }
                        try { Thread.sleep(100); } catch (InterruptedException e) { return; }
                    }
                    System.err.println("FAIL: four seats not observed");
                }, "commander-smoke-probe");
                probe.setDaemon(true);probe.start();
            });
            var result = NeoGame.play(deck, 3, NeoMatchUI.Mode.OBSERVE, 180, false,
                    null, null, true, NeoFormat.COMMANDER, opponents);
            check(fourSeats.get(), "four seats observed in live game state");
            check(result.turns > 1, "four-player game advances turns: " + result.turns);
            System.out.println("MATCH completed=" + result.completed + " winner=" + result.winner);
            check(result.completed, "four-player Commander match completes");
            System.out.println("COMMANDER SMOKE PASSED");
            System.exit(0);
        } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
    }
}
