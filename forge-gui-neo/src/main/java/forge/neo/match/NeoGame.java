package forge.neo.match;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import forge.deck.Deck;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.interfaces.IGuiGame;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.gamemodes.puzzle.Puzzle;
import forge.gamemodes.puzzle.PuzzleIO;
import forge.localinstance.properties.ForgeConstants;
import forge.neo.NeoSettings;
import forge.neo.platform.NeoGuiBase;
import forge.player.GamePlayerUtil;

public final class NeoGame {

    private NeoGame() {
    }

    public static final class Result {
        public final boolean completed;
        public final int turns;
        public final int decisions;
        public final String winner;
        
        public final NeoMatchUI.Exit exit;
        
        public final int yourLife;
        
        public final String matchWinner;
        
        public final int games;

        Result(final boolean completed, final int turns, final int decisions, final String winner,
               final NeoMatchUI.Exit exit, final int yourLife) {
            this(completed, turns, decisions, winner, exit, yourLife, null);
        }

        Result(final boolean completed, final int turns, final int decisions, final String winner,
               final NeoMatchUI.Exit exit, final int yourLife, final String matchWinner) {
            this(completed, turns, decisions, winner, exit, yourLife, matchWinner, 1);
        }

        Result(final boolean completed, final int turns, final int decisions, final String winner,
               final NeoMatchUI.Exit exit, final int yourLife, final String matchWinner, final int games) {
            this.games = games;
            this.completed = completed;
            this.turns = turns;
            this.decisions = decisions;
            this.winner = winner;
            this.exit = exit;
            this.yourLife = yourLife;
            this.matchWinner = matchWinner;
        }
    }

    public static Result play(final Deck deck, final int opponents, final NeoMatchUI.Mode mode,
                              final int timeoutSecs, final boolean verbose) {
        return play(deck, opponents, mode, timeoutSecs, verbose, null);
    }

    public static Result play(final Deck deck, final int opponents, final NeoMatchUI.Mode mode,
                              final int timeoutSecs, final boolean verbose,
                              final TableBinder binder) {
        return play(deck, opponents, mode, timeoutSecs, verbose, binder, null);
    }

    public static Result play(final Deck deck, final int opponents, final NeoMatchUI.Mode mode,
                              final int timeoutSecs, final boolean verbose,
                              final TableBinder binder, final String aiProfile) {
        return play(deck, opponents, mode, timeoutSecs, verbose, binder, aiProfile, true);
    }

    public static Result play(final Deck deck, final int opponents, final NeoMatchUI.Mode mode,
                              final int timeoutSecs, final boolean verbose,
                              final TableBinder binder, final String aiProfile,
                              final boolean autoPayMana) {
        return play(deck, opponents, mode, timeoutSecs, verbose, binder, aiProfile,
                autoPayMana, NeoFormat.COMMANDER);
    }

    public static Result play(final Deck deck, final int opponents, final NeoMatchUI.Mode mode,
                              final int timeoutSecs, final boolean verbose,
                              final TableBinder binder, final String aiProfile,
                              final boolean autoPayMana, final NeoFormat format) {
        return play(deck, opponents, mode, timeoutSecs, verbose, binder, aiProfile,
                autoPayMana, format, null);
    }

    public static Result play(final Deck deck, final int opponents, final NeoMatchUI.Mode mode,
                              final int timeoutSecs, final boolean verbose,
                              final TableBinder binder, final String aiProfile,
                              final boolean autoPayMana, final NeoFormat format,
                              final List<Deck> opponentDecks) {
        return play(deck, opponents, mode, timeoutSecs, verbose, binder, aiProfile,
                autoPayMana, format, opponentDecks, 1);
    }

    public static Result play(final Deck deck, final int opponents, final NeoMatchUI.Mode mode,
                              final int timeoutSecs, final boolean verbose,
                              final TableBinder binder, final String aiProfile,
                              final boolean autoPayMana, final NeoFormat format,
                              final List<Deck> opponentDecks, final int gamesPerMatch) {
        return play(deck, opponents, mode, timeoutSecs, verbose, binder, aiProfile,
                autoPayMana, format, opponentDecks, gamesPerMatch, null);
    }

    static volatile java.util.function.Consumer<NeoMatchUI> onUiForTest;

    public static void setOnUiForTest(final java.util.function.Consumer<NeoMatchUI> hook) {
        onUiForTest = hook;
    }

    public interface Seating {
        
        EnumSet<GameType> variants();

        RegisteredPlayer human(Deck deck, int seats);

        RegisteredPlayer opponent(int i, Deck deck, int seats);

        default forge.LobbyPlayer player(final int i, final String aiProfile) {
            return null;
        }
    }

    public static Result play(final Deck deck, final int opponents, final NeoMatchUI.Mode mode,
                              final int timeoutSecs, final boolean verbose,
                              final TableBinder binder, final String aiProfile,
                              final boolean autoPayMana, final NeoFormat format,
                              final List<Deck> opponentDecks, final int gamesPerMatch,
                              final Seating seating) {
        return play(deck, opponents, mode, timeoutSecs, verbose, binder, aiProfile,
                autoPayMana, format, opponentDecks, gamesPerMatch, seating,
                NeoMatchUI.Ending.NORMAL);
    }

    public static Result play(final Deck deck, final int opponents, final NeoMatchUI.Mode mode,
                              final int timeoutSecs, final boolean verbose,
                              final TableBinder binder, final String aiProfile,
                              final boolean autoPayMana, final NeoFormat format,
                              final List<Deck> opponentDecks, final int gamesPerMatch,
                              final Seating seating, final NeoMatchUI.Ending ending) {

        if (mode == NeoMatchUI.Mode.HUMAN) {
            applyEnginePrefs();
        } else if (mode == NeoMatchUI.Mode.OBSERVE && binder == null) {

            FModel.getPreferences().setPref(FPref.MATCH_AI_TIMEOUT, "2");
        }

        final NeoMatchUI gui = new NeoMatchUI(mode, verbose);
        gui.setAutoPayMana(autoPayMana);
        gui.setEnding(ending);
        
        final java.util.function.Consumer<NeoMatchUI> forTest = onUiForTest;
        if (forTest != null) {
            forTest.accept(gui);
        }
        if (binder != null) {
            gui.setBinder(binder);
            gui.setTable(binder.getTable());
            
            binder.setMatchUi(gui);
        }
        final HostedMatch match = new HostedMatch();
        final CountDownLatch over = new CountDownLatch(1);

        final List<RegisteredPlayer> players = new ArrayList<>();
        RegisteredPlayer humanSeat = null;

        final int seats = opponents + 1;

        forge.neo.look.NeoLook.setDeckInPlay(
                mode == NeoMatchUI.Mode.OBSERVE ? null : deck);
        if (mode != NeoMatchUI.Mode.OBSERVE) {
            humanSeat = seating == null ? format.register(deck, seats)
                    : seating.human(deck, seats);
            humanSeat.setPlayer(forge.neo.look.NeoPlayers.human());
            players.add(humanSeat);
        }

        final int aiCount = mode == NeoMatchUI.Mode.OBSERVE ? opponents + 1 : opponents;
        
        forge.neo.look.NeoPlayers.newMatch();

        final List<RegisteredPlayer> hotSeats = new ArrayList<>();
        for (int i = 0; i < aiCount; i++) {
            final Deck aiDeck = deckForOpponent(opponentDecks, i, deck);
            final RegisteredPlayer ai = seating == null ? format.register(aiDeck, seats)
                    : seating.opponent(i, aiDeck, seats);
            if (mode == NeoMatchUI.Mode.HUMAN && forge.neo.look.RivalSetup.isPerRival(aiProfile)
                    && forge.neo.look.RivalSetup.isHuman(i)) {
                ai.setPlayer(forge.neo.look.NeoPlayers.hotSeat(i));
                hotSeats.add(ai);
                players.add(ai);
                continue;
            }

            final forge.LobbyPlayer passing = seating == null ? null : seating.player(i, aiProfile);
            ai.setPlayer(passing != null ? passing : forge.neo.look.NeoPlayers.ai(i, aiProfile));
            
            players.add(ai);
        }

        System.out.printf(Locale.ROOT, "  Format: %s | mode: %s | seats: %d | deck: %s | AI: %s%n",
                format, mode, players.size(), deck.getName(),
                aiProfile == null || aiProfile.isBlank() ? "(default)"
                        : aiProfile.replace("neo:perRival:", "per opponent, overall "));
        for (int i = 0; i < aiCount; i++) {
            System.out.printf(Locale.ROOT, "    %s plays: %s%n",
                    forge.neo.look.NeoPlayers.aiName(i),
                    deckForOpponent(opponentDecks, i, deck).getName());
        }

        match.setOnMatchOver(over::countDown);

        final Runnable rig = devExileCommander ? () -> exileCommander(match.getGame())
                : devCommanderCasts > 0 ? () -> commanderCasts(match.getGame())
                : devPlayFromGraveyard != null ? () -> putInGraveyard(match.getGame())
                : devSpeed > 0 ? () -> riggedSpeed(match.getGame())
                : devFilterLand ? () -> riggedFilterLand(match.getGame())
                : devRig != null ? () -> riggedCards(match.getGame()) : null;
        match.setStartGameHook(SafeAi.hook(match, rig));

        final GameRules rules = new GameRules(format.getGameType());
        rules.setGamesPerMatch(gamesPerMatch);

        rules.setAppliedVariants(seating == null
                ? EnumSet.of(format.getGameType()) : seating.variants());

        applyNeoRules(rules);

        if (mode == NeoMatchUI.Mode.OBSERVE) {

            NeoGuiBase.setGuiGameFactory(() -> gui);
            
            match.startMatch(rules, null, players, (RegisteredPlayer) null, (IGuiGame) null);
        } else if (!hotSeats.isEmpty()) {

            final java.util.Map<RegisteredPlayer, IGuiGame> guis = new java.util.LinkedHashMap<>();
            guis.put(humanSeat, gui);
            for (final RegisteredPlayer seat : hotSeats) {
                guis.put(seat, gui);
            }
            match.startMatch(rules, null, players, guis, null);
        } else {
            match.startMatch(rules, null, players, humanSeat, gui);
        }

        boolean completed = false;
        try {
            completed = over.await(timeoutSecs, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        final int turns = gui.getGameView() == null ? 0 : gui.getGameView().getTurn();
        final String winner = gui.getGameView() == null ? null : gui.getGameView().getWinningPlayerName();

        if (!completed) {
            System.out.printf(Locale.ROOT,
                    "%n  TIMEOUT: the game did not finish in %ds (turn %d).%n",
                    timeoutSecs, turns);
        }

        final NeoMatchUI.Exit exit = gui.getExitAction();

        final forge.game.player.PlayerView me = gui.localPlayerView();
        final int yourLife = me == null ? -1 : me.getLife();
        String matchWinner = null;
        int games = 1;
        try {
            final forge.game.Match m = match.getMatch();
            games = m == null ? 1 : Math.max(1, com.google.common.collect.Iterables.size(m.getOutcomes()));
            final RegisteredPlayer champ = m == null ? null : m.getWinner();
            matchWinner = champ == null || champ.getPlayer() == null ? null : champ.getPlayer().getName();
        } catch (final RuntimeException e) {
            
        }
        gui.shutdown();

        forge.neo.look.NeoLook.setDeckInPlay(null);
        return new Result(completed, turns, gui.getDecisionCount(), winner, exit, yourLife, matchWinner, games);
    }

    private static Deck deckForOpponent(final List<Deck> decks, final int i, final Deck fallback) {
        if (decks == null || i >= decks.size() || decks.get(i) == null) {
            return fallback;
        }
        return decks.get(i);
    }

    public static List<Puzzle> puzzles() {
        final List<Puzzle> out = new ArrayList<>(
                PuzzleIO.loadPuzzles(ForgeConstants.PUZZLE_DIR));

        final String extra = System.getProperty("neo.puzzleDir");
        if (extra != null && !extra.isBlank()) {
            out.addAll(PuzzleIO.loadPuzzles(extra.endsWith("/") ? extra : extra + "/"));
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static volatile boolean devExileCommander;

    public static void setDevExileCommander(final boolean on) {
        devExileCommander = on;
    }

    private static volatile int devCommanderCasts;

    public static void setDevCommanderCasts(final int n) {
        devCommanderCasts = n;
    }

    private static void commanderCasts(final forge.game.Game game) {
        if (game == null) {
            return;
        }
        for (final forge.game.player.Player p : game.getPlayers()) {
            if (p.isAI()) {
                continue;
            }
            for (final forge.game.card.Card c : p.getCommanders()) {
                System.out.println("  [dev] " + c + " ya lanzado " + devCommanderCasts + " veces");
                for (int i = 0; i < devCommanderCasts; i++) {
                    p.incCommanderCast(c);
                }
            }
        }
    }

    private static volatile int devSpeed;

    public static void setDevSpeed(final int n) {
        devSpeed = n;
    }

    private static volatile boolean devFilterLand;

    public static void setDevFilterLand(final boolean on) {
        devFilterLand = on;
    }

    private static volatile String devRig;

    public static void setDevRig(final String names) {
        devRig = names;
    }

    private static void riggedCards(final forge.game.Game game) {
        if (game == null || devRig == null) {
            return;
        }
        for (final forge.game.player.Player p : game.getPlayers()) {
            if (p.isAI()) {
                continue;
            }
            for (final String name : devRig.split(";")) {
                put(game, p, name.trim(), forge.game.zone.ZoneType.Battlefield);
                System.out.println("  [dev] en tu campo: " + name.trim());
            }
            return;
        }
    }

    private static void riggedFilterLand(final forge.game.Game game) {
        if (game == null) {
            return;
        }
        for (final forge.game.player.Player p : game.getPlayers()) {
            if (p.isAI()) {
                continue;
            }

            put(game, p, "Forest", forge.game.zone.ZoneType.Battlefield);
            put(game, p, "Sungrass Prairie", forge.game.zone.ZoneType.Battlefield);
            put(game, p, "Watchwolf", forge.game.zone.ZoneType.Hand);
            System.out.println("  [dev] mesa de tierra de filtro puesta");
            return;
        }
    }

    private static void put(final forge.game.Game game, final forge.game.player.Player p,
                            final String name, final forge.game.zone.ZoneType zone) {
        final forge.item.PaperCard paper = FModel.getMagicDb().getCommonCards().getCard(name);
        if (paper == null) {
            System.out.println("  [dev] no existe la carta: " + name);
            return;
        }
        final forge.game.card.Card c = forge.game.card.Card.fromPaperCard(paper, p);
        game.getAction().moveTo(zone, c, null, forge.game.ability.AbilityKey.newMap());
        c.setSickness(false);
    }

    private static void riggedSpeed(final forge.game.Game game) {
        if (game == null) {
            return;
        }
        for (final forge.game.player.Player p : game.getPlayers()) {
            final int target = p.isAI() ? 4 : Math.min(devSpeed, 4);
            for (int i = 0; i < target; i++) {
                p.increaseSpeed();
            }
            System.out.println("  [dev] velocidad de " + p.getName() + ": " + p.getSpeed());
        }
    }

    private static volatile String devPlayFromGraveyard;

    public static void setDevPlayFromGraveyard(final String cardName) {
        devPlayFromGraveyard = cardName == null || cardName.isBlank() ? null : cardName.trim();
    }

    private static volatile forge.game.card.Card devGraveCard;

    public static String devGraveCardZone() {
        final forge.game.card.Card c = devGraveCard;

        final forge.game.zone.Zone z = c == null ? null : c.getZone();
        return z == null ? null : z.getZoneType().name();
    }

    private static void putInGraveyard(final forge.game.Game game) {
        final String name = devPlayFromGraveyard;
        if (game == null || name == null) {
            return;
        }
        for (final forge.game.player.Player p : game.getPlayers()) {
            if (p.isAI()) {
                continue;
            }
            final forge.item.PaperCard paper = FModel.getMagicDb().getCommonCards().getCard(name);
            if (paper == null) {
                System.out.println("  [dev] no existe la carta: " + name);
                return;
            }
            final forge.game.card.Card c = forge.game.card.Card.fromPaperCard(paper, p);
            game.getAction().moveTo(forge.game.zone.ZoneType.Graveyard, c, null,
                    forge.game.ability.AbilityKey.newMap());
            System.out.println("  [dev] al cementerio: " + c);
            devGraveCard = c;

            final forge.item.PaperCard land =
                    FModel.getMagicDb().getCommonCards().getCard("City of Brass");
            for (int i = 0; land != null && i < 3; i++) {
                final forge.game.card.Card l = forge.game.card.Card.fromPaperCard(land, p);
                game.getAction().moveTo(forge.game.zone.ZoneType.Battlefield, l, null,
                        forge.game.ability.AbilityKey.newMap());
                l.setSickness(false);
            }
            System.out.println("  [dev] tres City of Brass en tu campo");

            p.updateFlashbackForView();
            return;
        }
    }

    private static void exileCommander(final forge.game.Game game) {
        if (game == null) {
            return;
        }
        for (final forge.game.player.Player p : game.getPlayers()) {
            if (p.isAI()) {
                continue;
            }
            for (final forge.game.card.Card c : new ArrayList<>(p.getCommanders())) {
                System.out.println("  [dev] exiliando tu comandante: " + c);
                game.getAction().moveTo(forge.game.zone.ZoneType.Exile, c, null,
                        forge.game.ability.AbilityKey.newMap());
            }
        }
    }

    public static void applyNeoRules(final GameRules rules) {
        rules.setAllowCheatShuffle(NeoSettings.getBool(NeoSettings.AI_CHEAT_SHUFFLE, false));
        rules.setPlayForAnte(NeoSettings.getBool(NeoSettings.ANTE, false));
        rules.setMatchAnteRarity(NeoSettings.getBool(NeoSettings.ANTE_MATCH_RARITY, false));
        rules.setAnteIncludeBasicLands(NeoSettings.getBool(NeoSettings.ANTE_INCLUDE_BASIC_LANDS, false));
    }

    public static void applyEnginePrefs() {
        final ForgePreferences prefs = FModel.getPreferences();
        prefs.setPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, NeoSettings.getBool(NeoSettings.AUTO_PASS, true));

        prefs.setPref(FPref.UI_RECORD_REPLAYS, NeoSettings.recordReplays());

        NeoSettings.applyAudioToEngine();
        prefs.setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, true);

        prefs.setPref(FPref.UI_SHOW_AUTOTAP_PREVIEW, true);

        prefs.setPref(FPref.UI_MANA_LOST_PROMPT, true);

        prefs.setPref(FPref.UI_SELECT_FROM_CARD_DISPLAYS, false);

        forge.StaticData.instance().setMulliganRule(FriendlyMulligan.apply(
                NeoSettings.get(NeoSettings.MULLIGAN_RULE, NeoSettings.MULLIGAN_RULE_DEFAULT)));

        prefs.setPref(FPref.MATCH_AI_TIMEOUT,
                String.valueOf(NeoSettings.getInt(NeoSettings.AI_TIMEOUT, NeoSettings.AI_TIMEOUT_DEFAULT)));

        applySmartPass(prefs);
        
    }

    private static void applySmartPass(final ForgePreferences prefs) {
        final boolean on = NeoSettings.getBool(NeoSettings.SMART_PASS, false);
        final int level = NeoSettings.smartPassLevel();

        prefs.setPref(FPref.YIELD_AUTO_PASS_RESPECTS_INTERRUPTS, on);

        final boolean cut = NeoSettings.endTurnInterrupts();
        prefs.setPref(FPref.YIELD_INTERRUPT_ON_ATTACKERS, cut);
        prefs.setPref(FPref.YIELD_INTERRUPT_ON_OPPONENT_SPELL, cut);
        
        prefs.setPref(FPref.YIELD_INTERRUPT_ON_TARGETING, on && level >= 1);
        prefs.setPref(FPref.YIELD_INTERRUPT_ON_MASS_REMOVAL, on && level >= 1);

        prefs.setPref(FPref.YIELD_INTERRUPT_ON_TRIGGERS, on && level >= 2);
        prefs.setPref(FPref.YIELD_INTERRUPT_ON_REVEAL, on && level >= 2);
    }

    public static void refreshSmartPass() {
        applySmartPass(FModel.getPreferences());
    }

    public static void refreshAutoPass() {
        FModel.getPreferences().setPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS,
                NeoSettings.getBool(NeoSettings.AUTO_PASS, true));
    }

    public static Deck firstCommanderDeck() {
        for (final Deck d : FModel.getDecks().getCommander()) {
            return d;
        }
        return null;
    }

    public static Deck commanderDeck(final String name) {
        for (final Deck d : FModel.getDecks().getCommander()) {
            if (d.getName().equalsIgnoreCase(name)) {
                return d;
            }
        }
        return null;
    }
}
