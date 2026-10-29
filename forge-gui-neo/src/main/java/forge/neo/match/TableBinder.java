package forge.neo.match;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import forge.card.mana.ManaAtom;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.combat.CombatView;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.neo.ui.CombatOverlay;
import forge.neo.ui.TableScreen;
import forge.util.collect.FCollectionView;
import javafx.application.Platform;

public class TableBinder {

    private final TableScreen table;

    private final AtomicBoolean pending = new AtomicBoolean(false);

    private volatile GameView gameView;
    private volatile PlayerView self;

    private volatile NeoMatchUI matchUi;

    public void setMatchUi(final NeoMatchUI ui) {
        this.matchUi = ui;
    }

    public NeoMatchUI getMatchUi() {
        return matchUi;
    }

    public enum Abandon {
        
        CRASH,
        
        QUIT,
        
        BEFORE_START
    }

    private volatile boolean abandoned;
    private volatile java.util.function.Consumer<Abandon> onAbandoned;

    public void setOnAbandoned(final java.util.function.Consumer<Abandon> listener) {
        this.onAbandoned = listener;
    }

    public boolean isAbandoned() {
        return abandoned;
    }

    public void fireAbandoned(final Abandon why) {
        abandoned = true;
        final java.util.function.Consumer<Abandon> listener = onAbandoned;
        onAbandoned = null;
        if (listener != null) {
            listener.accept(why);
        }
    }

    private volatile PlayerView viewedOpponent;

    private volatile PlayerView lastTurnPlayer;

    public TableBinder(final TableScreen table) {
        this.table = table;
        table.setOnOpponentSelected(p -> {
            viewedOpponent = p;

            final NeoMatchUI ui = matchUi;
            if (ui != null) {
                ui.onOpponentFocused(p);
            }
            requestRefresh();
        });
    }

    public TableScreen getTable() {
        return table;
    }

    public void setSelf(final PlayerView player) {
        this.self = player;
        requestRefresh();
    }

    public void setGameView(final GameView view) {

        final boolean newGame = view != null && (gameView == null || gameView.getId() != view.getId());
        this.gameView = view;
        if (newGame) {
            viewedOpponent = null;
            shownOpponent = null;
            lastTurnPlayer = null;
        }
        requestRefresh();
    }

    public void setPrompt(final String message) {
        try {
            Platform.runLater(() -> table.setPrompt(message));
        } catch (final IllegalStateException ignored) {
            
        }
    }

    public void requestRefresh() {
        if (pending.compareAndSet(false, true)) {
            try {
                Platform.runLater(this::apply);
            } catch (final IllegalStateException e) {
                
                pending.set(false);
            }
        }
    }

    private static PlayerView liveOne(final List<PlayerView> players, final PlayerView wanted) {
        if (wanted == null) {
            return null;
        }
        for (final PlayerView p : players) {
            if (p != null && p.getId() == wanted.getId()) {
                return p;
            }
        }
        return null;
    }

    private void apply() {
        pending.set(false);
        final GameView gv = gameView;
        if (gv == null) {
            return;
        }

        final List<PlayerView> players = new ArrayList<>();
        for (final PlayerView p : gv.getPlayers()) {
            players.add(p);
        }
        if (players.isEmpty()) {
            return;
        }

        PlayerView bottom = liveOne(players, self);
        if (bottom == null) {
            bottom = players.get(0);
        }
        bottomNow = bottom;

        final List<PlayerView> opponents = new ArrayList<>();
        for (final PlayerView p : players) {
            if (!p.equals(bottom)) {
                opponents.add(p);
            }
        }

        final PlayerView turnPlayer = gv.getPlayerTurn();
        if (turnPlayer != null && !turnPlayer.equals(lastTurnPlayer)) {
            lastTurnPlayer = turnPlayer;
            if (opponents.contains(turnPlayer)) {
                viewedOpponent = turnPlayer;
            }
        }

        PlayerView top = viewedOpponent;
        if (top == null || !opponents.contains(top)) {
            top = opponents.isEmpty() ? null : opponents.get(0);
            viewedOpponent = top;
        }

        final boolean wanted = multiBoard() && !opponents.isEmpty();
        final int room = table.maxOpponentSeats();
        final boolean fits = wanted && room >= opponents.size();
        final int seats = fits ? opponents.size() : 1;
        table.setOpponentSeats(seats);

        if (wanted && !fits && !warnedNoRoom && !table.getMenuOverlay().isShowing()) {
            warnedNoRoom = true;
            table.showInfo(
                    forge.neo.NeoText.get("table.allBoards.tight.title"),
                    forge.neo.NeoText.get("table.allBoards.tight.body",
                            opponents.size(), room));
        }
        if (fits) {
            warnedNoRoom = false;
        }

        table.setOpponents(opponents, top, gv.getPlayerTurn(), bottom);
        
        table.noteAttachmentsBefore();

        if (seats > 1) {

            shownOpponent = null;
            applyPlayer(gv, bottom, -1);
            for (int i = 0; i < opponents.size(); i++) {
                applyPlayer(gv, opponents.get(i), i);
            }
        } else {

            if (top != null && !top.equals(shownOpponent)) {
                table.skipOpponentRemovalAnimation();
            }
            shownOpponent = top;

            applyPlayer(gv, bottom, -1);
            if (top != null) {
                applyPlayer(gv, top, 0);
            }
        }

        table.setPhase(gv.getPhase());
        table.getPhaseRail().setPlanes(activePlanes(gv));
        pushTurnOrder(gv);
        table.setStackGame(gv.getGame());
        table.setStack(gv.getStack(), self);
        table.setCombatLinks(links(gv));
        table.attachmentsMoved();

        if (matchUi != null) {
            table.setActionable(matchUi::actionableStrength);

            final NeoMatchUI forTabs = matchUi;
            table.getOpponentTabs().setHandReveal(p -> mockHandReveal() != null ? mockHandReveal() : new int[] {
                    RevealedHand.visible(p, forTabs::mayView),
                    RevealedHand.castable(p, forTabs::isPlayableOutside)});
        }
        
        table.reapplyHighlighted();
    }

    private void pushTurnOrder(final GameView gv) {
        final PlayerView turn = gv.getPlayerTurn();
        final List<PlayerView> seats = gv.getPlayers() == null ? List.of() : new ArrayList<>(gv.getPlayers());
        final int start = turn == null ? -1 : seats.indexOf(turn);
        final PlayerView me = matchUi == null ? null : matchUi.localPlayerView();
        final List<String> order = new ArrayList<>();
        int you = -1;
        int priority = -1;
        for (int i = 0; start >= 0 && i < seats.size(); i++) {
            final PlayerView p = seats.get((start + i) % seats.size());
            if (p.getHasLost()) {
                continue;
            }
            if (you < 0 && p.equals(me)) {
                you = order.size();
            }
            if (priority < 0 && p.getHasPriority()) {
                priority = order.size();
            }
            order.add(PlayerName.of(p));
        }
        table.getPhaseRail().setTurnOrder(order, you, priority);
    }

    static List<CardView> activePlanes(final GameView gv) {
        final List<CardView> out = new ArrayList<>();
        if (gv == null || gv.getPlayers() == null) {
            return out;
        }
        for (final PlayerView p : gv.getPlayers()) {
            final Iterable<CardView> cmd = p.getCards(ZoneType.Command);
            if (cmd == null) {
                continue;
            }
            for (final CardView c : cmd) {
                final forge.card.CardTypeView t = c == null || c.getCurrentState() == null
                        ? null : c.getCurrentState().getType();
                if (t != null && (t.isPlane() || t.isPhenomenon())) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    private boolean warnedNoRoom;

    private PlayerView bottomNow;

    private PlayerView shownOpponent;

    private static boolean multiBoard() {
        try {
            return forge.neo.NeoSettings.allBoards();
        } catch (final RuntimeException e) {
            return false;
        }
    }

    private static List<Object[]> links(final GameView gv) {
        final List<Object[]> out = new ArrayList<>();

        final CombatView combat = gv.getCombat();
        if (combat != null && combat.getAttackers() != null) {
            for (final CardView attacker : combat.getAttackers()) {
                final GameEntityView defender = combat.getDefender(attacker);
                if (defender != null) {
                    out.add(new Object[] {attacker, defender, CombatOverlay.Kind.ATTACK});
                }

                final Set<CardView> seen = new HashSet<>();
                addBlockers(out, attacker, combat.getBlockers(attacker), seen);
                addBlockers(out, attacker, combat.getPlannedBlockers(attacker), seen);
            }
        }

        if (gv.getStack() != null) {
            for (final StackItemView item : gv.getStack()) {
                final CardView source = item.getSourceCard();
                if (source == null) {
                    continue;
                }
                if (item.getTargetCards() != null) {
                    for (final CardView target : item.getTargetCards()) {
                        out.add(new Object[] {source, target, CombatOverlay.Kind.TARGET});
                    }
                }
                if (item.getTargetPlayers() != null) {
                    for (final PlayerView target : item.getTargetPlayers()) {
                        out.add(new Object[] {source, target, CombatOverlay.Kind.TARGET});
                    }
                }
            }
        }
        return out;
    }

    private static void addBlockers(final List<Object[]> out, final CardView attacker,
                                    final Iterable<CardView> blockers, final Set<CardView> seen) {
        if (blockers == null) {
            return;
        }
        for (final CardView blocker : blockers) {
            if (blocker != null && seen.add(blocker)) {
                out.add(new Object[] {blocker, attacker, CombatOverlay.Kind.BLOCK});
            }
        }
    }

    private javafx.scene.image.Image faceOf(final PlayerView p, final boolean opponent) {
        try {
            if (!opponent) {
                return forge.neo.look.NeoLook.currentAvatar().image();
            }

            final String custom = forge.neo.look.NeoPlayers.customFaceOf(p.getName());
            if (custom != null) {
                final javafx.scene.image.Image image = customFace(custom);
                if (image != null) {
                    return image;
                }
            }
            return forge.neo.look.NeoLook.builtInAvatar(p.getAvatarIndex()).image();
        } catch (final RuntimeException e) {
            
            return null;
        }
    }

    private final java.util.Map<String, java.util.Optional<javafx.scene.image.Image>> customFaces =
            new java.util.HashMap<>();

    private javafx.scene.image.Image customFace(final String id) {
        return customFaces.computeIfAbsent(id, k -> {
            final forge.neo.look.LookItem item = forge.neo.look.NeoLook.avatarById(k);
            return java.util.Optional.ofNullable(item == null ? null : item.image());
        }).orElse(null);
    }

    private javafx.scene.image.Image sleeveOf(final PlayerView p, final boolean opponent) {
        try {
            if (!opponent) {

                return forge.neo.look.NeoLook.sleeveInPlay().image();
            }
            return forge.neo.look.NeoLook.builtInSleeve(p.getSleeveIndex()).image();
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static int worstCommanderDamage(final GameView gv, final PlayerView p) {
        int worst = 0;
        for (final forge.neo.ui.PlayerBar.CommanderHit hit : commanderDamage(gv, p)) {
            worst = Math.max(worst, hit.damage());
        }
        return worst;
    }

    static List<forge.neo.ui.PlayerBar.CommanderHit> commanderDamage(
            final GameView gv, final PlayerView p) {
        final List<forge.neo.ui.PlayerBar.CommanderHit> out = new ArrayList<>();
        if (gv == null || p == null || gv.getPlayers() == null) {
            return out;
        }
        try {
            int commanders = 0;
            for (final PlayerView other : gv.getPlayers()) {
                final List<CardView> theirs = other.getCommanders();
                if (theirs == null) {
                    continue;
                }
                commanders += theirs.size();
                for (final CardView commander : theirs) {
                    if (commander == null) {
                        continue;
                    }
                    final int dmg = p.getCommanderDamage(commander);
                    if (dmg > 0) {
                        out.add(new forge.neo.ui.PlayerBar.CommanderHit(
                                shortName(commander), dmg));
                    }
                }
            }

            if (commanders <= 1 && out.size() == 1) {
                return List.of(new forge.neo.ui.PlayerBar.CommanderHit(
                        null, out.get(0).damage()));
            }
        } catch (final RuntimeException e) {
            return List.of();
        }
        return out;
    }

    private static String shortName(final CardView commander) {
        String name = forge.neo.card.CardText.nameOf(commander);
        if (name == null || name.isBlank()) {
            name = commander.getName();
        }
        if (name == null) {
            return "?";
        }
        final int comma = name.indexOf(',');
        return comma > 0 ? name.substring(0, comma) : name;
    }

    private static String detailsOf(final PlayerView p) {
        if (p == null) {
            return "";
        }
        try {
            final String base = p.getDetails();
            return base == null ? "" : base.trim();
        } catch (final RuntimeException e) {
            return "";
        }
    }

    private static int drawnThisTurn(final PlayerView p) {
        try {
            return p == null ? 0 : p.getNumDrawnThisTurn();
        } catch (final RuntimeException e) {
            
            return 0;
        }
    }

    private static com.google.common.collect.Multiset<forge.game.card.CounterType>
            countersOf(final PlayerView p) {
        try {
            return p == null ? null : p.getCounters();
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static int shardsOf(final PlayerView p) {
        try {
            return p == null ? 0 : p.getNumManaShards();
        } catch (final RuntimeException e) {
            return 0;
        }
    }

    private static String controllerOf(final PlayerView p) {
        try {
            final PlayerView master = p == null ? null : p.getMindSlaveMaster();
            return master == null || master.equals(p) ? null : PlayerName.of(master);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static int poisonToLose(final GameView gv) {
        try {
            return gv == null ? 10 : gv.getPoisonCountersToLose();
        } catch (final RuntimeException e) {
            return 10;
        }
    }

    private static int[] mockHandReveal() {
        final String v = System.getProperty("neo.mock.handRevealed");
        if (v == null || !v.contains(":")) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(v.substring(0, v.indexOf(':'))),
                    Integer.parseInt(v.substring(v.indexOf(':') + 1))};
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private final java.util.Set<Integer> thinkTested = new java.util.HashSet<>();

    private void applyPlayer(final GameView gv, final PlayerView p, final int seat) {
        final boolean opponent = seat >= 0;
        final var bar = opponent ? table.opponentBar(seat) : table.getSelfBar();

        bar.setPlayer(p);
        bar.setGameId(gv.getId());

        if (opponent && Boolean.getBoolean("neo.aiThinkTest") && thinkTested.add(p.getId())) {
            forge.util.AITimeoutTracker.begin(forge.util.AITimeoutTracker.key(gv.getId(), p.getId()),
                    java.util.concurrent.TimeUnit.SECONDS.toNanos(30));
        }
        bar.setAvatarImage(faceOf(p, opponent));
        (opponent ? table.opponentField(seat) : table.getSelfField())
                .setSleeveImage(sleeveOf(p, opponent));

        bar.setCommanderDamage(commanderDamage(gv, p));
        bar.setDetails(detailsOf(p));
        bar.setPlayerName(PlayerName.of(p));
        bar.setLife(p.getLife());
        bar.setActiveTurn(p.equals(gv.getPlayerTurn()));

        if (opponent) {
            bar.setAlly(NeoTeams.isAlly(bottomNow, p));
        }

        if (opponent) {
            final NeoMatchUI ui = matchUi;
            final int[] mock = mockHandReveal();
            bar.setHandRevealed(
                    mock != null ? mock[0] : ui == null ? 0 : RevealedHand.visible(p, ui::mayView),
                    mock != null ? mock[1] : ui == null ? 0 : RevealedHand.castable(p, ui::isPlayableOutside));
        }
        bar.setZones(
                p.getZoneSize(ZoneType.Hand),
                p.getZoneSize(ZoneType.Library),
                p.getZoneSize(ZoneType.Graveyard),
                p.getZoneSize(ZoneType.Exile),
                p.getZoneSize(ZoneType.Command));

        bar.setCounters(countersOf(p), shardsOf(p), poisonToLose(gv), controllerOf(p),
                forge.neo.match.PlayerSpeed.of(p), forge.neo.match.PlayerSpeed.rawText(p));

        bar.setTitles(PlayerTitles.of(p));

        bar.setDrawnThisTurn(drawnThisTurn(p));

        bar.setSpellsThisTurn(SpellsThisTurn.of(matchUi, p));

        bar.setAttached(AttachedToPlayer.enchanting(gv, p), AttachedToPlayer.protecting(gv, p));

        if (opponent) {
            
        }

        bar.setMana(
                p.getMana((byte) ManaAtom.WHITE),
                p.getMana((byte) ManaAtom.BLUE),
                p.getMana((byte) ManaAtom.BLACK),
                p.getMana((byte) ManaAtom.RED),
                p.getMana((byte) ManaAtom.GREEN),
                p.getMana((byte) ManaAtom.COLORLESS));

        final List<CardView> battlefield = toList(p.getBattlefield());
        if (opponent) {
            table.setOpponentBattlefield(seat, battlefield);
            table.setOpponentZonePiles(seat, p.getZoneSize(ZoneType.Graveyard),
                    p.getZoneSize(ZoneType.Exile),
                    hasPlayable(p, ZoneType.Graveyard), hasPlayable(p, ZoneType.Exile));
            table.setOpponentCommand(seat, toList(p.getCommand()));
        } else {
            table.setSelfBattlefield(battlefield);

            table.setSelfLibraryPile(p.getZoneSize(ZoneType.Library), topOfLibrary(p));
            table.setSelfZonePiles(p.getZoneSize(ZoneType.Graveyard),
                    p.getZoneSize(ZoneType.Exile),
                    hasPlayable(p, ZoneType.Graveyard), hasPlayable(p, ZoneType.Exile));

            table.setHand(toList(p.getHand()));
            table.setCommandZone(toList(p.getCommand()), p);
        }
    }

    private static CardView topOfLibrary(final PlayerView p) {
        final var library = p == null ? null : p.getCards(ZoneType.Library);
        if (library == null) {
            return null;
        }
        for (final CardView cv : library) {
            return cv;
        }
        return null;
    }

    private boolean hasPlayable(final PlayerView owner, final ZoneType zone) {
        for (final CardView cv : playableOutside()) {

            if (cv != null && cv.getZone() == zone && owner.equals(cv.getOwner())
                    && (matchUi == null || matchUi.isPlayableOutside(cv))) {
                return true;
            }
        }

        final NeoMatchUI ui = matchUi;
        final var cards = ui == null || (zone != ZoneType.Exile && zone != ZoneType.Graveyard)
                ? null : owner.getCards(zone);
        if (cards != null) {
            for (final CardView cv : cards) {
                if (cv != null && ui.isPlayableOutside(cv)) {
                    return true;
                }
            }
        }
        return false;
    }

    private FCollectionView<CardView> playableOutside() {
        final PlayerView me = matchUi == null ? null : matchUi.localPlayerView();
        final FCollectionView<CardView> flash = me == null ? null : me.getFlashback();
        return flash == null ? forge.util.collect.FCollection.getEmpty() : flash;
    }

    private static List<CardView> toList(final FCollectionView<CardView> view) {
        final List<CardView> out = new ArrayList<>();
        if (view != null) {
            for (final CardView cv : view) {
                out.add(cv);
            }
        }
        return out;
    }
}
