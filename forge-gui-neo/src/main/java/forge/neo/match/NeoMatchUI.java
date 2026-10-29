package forge.neo.match;

import forge.neo.NeoText;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.Game;
import forge.game.GameEntityView;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.spellability.StackItemView;
import forge.interfaces.IGameController;
import forge.player.AutoYieldStore;
import forge.gamemodes.match.YieldController;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.game.event.GameEvent;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnPhase;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventCardChangeZone;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.gamemodes.net.NetworkGuiGame;
import forge.gamemodes.match.NextGameDecision;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.neo.platform.NeoGuiBase;
import forge.neo.ui.AbilityMenu;
import forge.neo.ui.AmountDialog;
import forge.neo.ui.CardPromptDialog;
import forge.neo.ui.ChoiceDialog;
import forge.neo.ui.ConfirmDialog;
import forge.neo.ui.DamageDialog;
import forge.neo.ui.GameOverScreen;
import forge.neo.ui.TableScreen;
import forge.neo.platform.UiDispatcher;
import forge.neo.ui.PromptBanner;
import forge.trackable.TrackableCollection;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

public class NeoMatchUI extends NetworkGuiGame implements OverlookedSink {

    public enum Mode {
        
        OBSERVE,
        
        AUTO_PLAY,
        
        HUMAN
    }

    private final Mode mode;
    private final boolean verbose;

    private final ExecutorService responder =
            Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "neo-responder");
                t.setDaemon(true);
                return t;
            });

    public enum Exit {
        
        MENU,
        
        RESTART
    }

    private volatile Exit exitAction;

    public enum Ending {
        
        NORMAL,
        
        QUEST,
        
        ASCENT,
        
        NET,
        
        REPLAY
    }

    private volatile Ending ending = Ending.NORMAL;

    public void setEnding(final Ending ending) {
        this.ending = ending == null ? Ending.NORMAL : ending;
    }

    private volatile Runnable onAbandon;

    public void setOnAbandon(final Runnable action) {
        this.onAbandon = action;
    }

    private static final long LEAVE_TIMEOUT_MS = 6_000;

    private void watchdogForLeaving() {
        final Thread t = new Thread(() -> {
            try {
                Thread.sleep(LEAVE_TIMEOUT_MS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (closeNotified.get() || gameOverShowing) {
                return;
            }
            System.out.println("[neo] el motor no ha cerrado la partida en "
                    + (LEAVE_TIMEOUT_MS / 1000) + " s: se sale igual");
            final Runnable out = onAbandon;
            if (out != null && closeNotified.compareAndSet(false, true)) {
                out.run();
            }
        }, "neo-leave-watchdog");
        t.setDaemon(true);
        t.start();
    }

    public void leaveMatch(final Exit action) {
        this.exitAction = action;
        if (isReplay()) {

            forge.gamemodes.net.client.ReplayPlayer.stopFor(this, true);
            return;
        }

        uiRunLater(() -> table.forgetEngineDialogs());
        watchdogForLeaving();
        respondLater(() -> {

            autoConfirm = true;
            try {

                if (!QuietConcede.tryConcede(this)) {
                    concede();
                }
            } catch (final RuntimeException e) {

                System.out.println("[neo] el motor ha fallado al conceder: " + e);
            } finally {
                autoConfirm = false;
            }
            endTheGameForReal();
        });
    }

    private void endTheGameForReal() {
        if (isNetGame()) {

            return;
        }
        final GameView gv = getGameView();
        if (gv == null || gv.isGameOver()) {
            
            return;
        }
        final Game game = gv.getGame();
        if (game == null) {
            return;
        }
        game.getAction().invoke(() -> {
            try {
                game.setGameOver(forge.game.GameEndReason.AllHumansLost);
            } catch (final RuntimeException e) {
                System.out.println("[neo] no se ha podido cerrar la partida: " + e);
            }
        });
    }

    public Exit getExitAction() {
        return exitAction;
    }

    private volatile boolean autoConfirm;

    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicInteger decisions = new AtomicInteger();
    private PhaseType lastPhase;

    private volatile RoundCounter rounds = new RoundCounter();

    private int shownRound(final GameView gv) {
        final int r = gv == null ? 0 : rounds.round(gv);
        return RoundCounter.enabled() ? r : 0;
    }

    private volatile DetailLog detail;
    private int lastTurn = -1;

    private int discordSeats;

    private final AtomicInteger attackRetries = new AtomicInteger();
    private volatile int attackRetryTurn = -1;
    private final AtomicBoolean attackGaveUp = new AtomicBoolean(false);
    private static final int MAX_ATTACK_RETRIES = 6;
    
    private static final boolean ATTACK_GUARD =
            !"false".equals(System.getProperty("neo.autoplay.attackGuard"));

    private final AtomicBoolean blockGaveUp = new AtomicBoolean(false);
    
    private static final boolean BLOCK_GUARD =
            !"false".equals(System.getProperty("neo.autoplay.blockGuard"));

    private void autoPressOk() {
        final IGameController controller = getGameController();
        if (ATTACK_GUARD && controller instanceof forge.player.PlayerControllerHuman human
                && human.getInputQueue().getInput()
                        instanceof forge.gamemodes.match.input.InputAttack) {
            final Game game = human.getGame();
            final forge.game.combat.Combat combat = game == null ? null : game.getCombat();
            if (combat != null && !forge.game.combat.CombatUtil.validateAttackers(combat)) {
                answerInvalidAttack(human, combat);
                return;
            }
            attackRetries.set(0);
        }
        if (BLOCK_GUARD && controller instanceof forge.player.PlayerControllerHuman human
                && human.getInputQueue().getInput()
                        instanceof forge.gamemodes.match.input.InputBlock
                && !answerInvalidBlock(human)) {
            return;
        }
        controller.selectButtonOk();
    }

    private boolean answerInvalidBlock(final forge.player.PlayerControllerHuman human) {
        final Game game = human.getGame();
        final forge.game.combat.Combat combat = game == null ? null : game.getCombat();
        if (combat == null) {
            return true;
        }
        final forge.game.player.Player defender = blockingDefender(human, combat);
        if (defender == null) {
            return true;
        }
        final String problem = forge.game.combat.CombatUtil.validateBlocks(combat, defender);
        if (problem == null) {
            return true;
        }
        if (blockGaveUp.get()) {
            return false;
        }
        trace("bloqueo obligado: %s | poniendo a bloquear a los obligados", problem);
        assignRequiredBlocks(combat, defender);
        String left = forge.game.combat.CombatUtil.validateBlocks(combat, defender);
        if (left != null) {
            trace("bloqueo obligado: %s | pidiendo el bloqueo a la IA", left);
            try {
                new forge.ai.AiBlockController(defender, false).assignBlockersForCombat(combat);
            } catch (final RuntimeException e) {
                trace("bloqueo obligado: la IA ha fallado al bloquear: %s", e);
            }
            left = forge.game.combat.CombatUtil.validateBlocks(combat, defender);
        }
        if (left == null) {
            game.fireEvent(new forge.game.event.GameEventCombatChanged());
            return true;
        }
        if (!blockGaveUp.compareAndSet(false, true)) {
            return false;
        }
        System.out.println("  FALLO - piloto automatico: el motor rechaza el bloqueo (" + left
                + "); se termina la partida para no colgar la bateria");
        try {
            human.concede();
        } catch (final RuntimeException e) {
            System.out.println("[neo] el motor ha fallado al conceder: " + e);
        }
        endTheGameForReal();
        return false;
    }

    private static forge.game.player.Player blockingDefender(
            final forge.player.PlayerControllerHuman human, final forge.game.combat.Combat combat) {
        final forge.game.player.Player me = human.getPlayer();
        forge.game.player.Player other = null;
        for (final forge.game.player.Player p : combat.getDefendingPlayers()) {
            final forge.game.player.Player declares =
                    java.util.Objects.requireNonNullElse(p.getDeclaresBlockers(), p);
            if (declares != me || !combat.isPlayerAttacked(p)) {
                continue;
            }
            if (p == me) {
                return p;
            }
            if (other == null) {
                other = p;
            }
        }
        return other;
    }

    private static void assignRequiredBlocks(final forge.game.combat.Combat combat,
                                             final forge.game.player.Player defender) {
        for (final forge.game.card.Card blocker : defender.getCreaturesInPlay()) {
            if (!owesABlock(blocker, combat)) {
                continue;
            }
            for (final forge.game.card.Card attacker : combat.getAttackers()) {
                if (combat.isBlocking(blocker, attacker)
                        || !forge.game.combat.CombatUtil.canBlock(attacker, blocker, combat)) {
                    continue;
                }
                combat.addBlocker(attacker, blocker);
                if (!owesABlock(blocker, combat)) {
                    break;
                }
                combat.removeBlockAssignment(attacker, blocker);
            }
        }
    }

    private static boolean owesABlock(final forge.game.card.Card blocker,
                                      final forge.game.combat.Combat combat) {
        return forge.game.combat.CombatUtil.mustBlockAnAttacker(blocker, combat, null)
                || (forge.game.staticability.StaticAbilityMustBlock.blocksEachCombatIfAble(blocker)
                        && !combat.isBlocking(blocker));
    }

    private void answerInvalidAttack(final forge.player.PlayerControllerHuman human,
                                     final forge.game.combat.Combat combat) {
        if (attackGaveUp.get()) {
            return;
        }
        final GameView gv = getGameView();
        final int turn = gv == null ? -1 : gv.getTurn();
        if (attackRetryTurn != turn) {
            attackRetryTurn = turn;
            attackRetries.set(0);
        }
        final int tries = attackRetries.incrementAndGet();
        if (tries <= MAX_ATTACK_RETRIES && combat.getAttackers().isEmpty()) {
            trace("ataque obligado: el vacio no vale, atacando con todo");

            human.alphaStrike();
            return;
        }

        if (!attackGaveUp.compareAndSet(false, true)) {
            return;
        }
        System.out.println("  FALLO - piloto automatico: el motor rechaza el ataque "
                + tries + " veces seguidas (atacantes: " + combat.getAttackers()
                + "); se termina la partida para no colgar la bateria");
        try {
            human.concede();
        } catch (final RuntimeException e) {
            System.out.println("[neo] el motor ha fallado al conceder: " + e);
        }
        endTheGameForReal();
    }

    public NeoMatchUI(final Mode mode, final boolean verbose) {
        this.mode = mode;
        this.verbose = verbose;
    }

    public int getDecisionCount() {
        return decisions.get();
    }

    public boolean isFinished() {
        return finished.get();
    }

    public void shutdown() {
        responder.shutdownNow();
    }

    private volatile TableBinder binder;
    private volatile TableScreen table;

    public void setBinder(final TableBinder binder) {
        this.binder = binder;
        pushToTable();
    }

    public void setTable(final TableScreen table) {
        this.table = table;
        if (table == null || mode != Mode.HUMAN) {
            return;
        }

        final UiDispatcher ui = uiDispatcher();
        final Runnable wire = () -> {
            wireGameLogButton();
            table.setOnCardClicked(this::onCardClicked);
            table.setOnStackCountClicked(this::onStackCountClicked);
            table.setOnPlayerClicked(this::onPlayerClicked);

            table.setPlayableOutside(this::isPlayableOutside);

            table.setCardVisibility(this::mayView);
            
            table.setEquipAction(this::equipLabel, this::equipFromZoom);
            table.setOnCardDropped(this::onCardDropped);

            table.setOnHandReorder((card, index) -> {
                if (interactive() && !finished.get()) {
                    respondLater(() -> getGameController().reorderHand(card, index));
                }
            });

            table.setOnManaClicked(color ->
                    respondLater(() -> getGameController().useMana(color)));

            table.setOnStackMenu(this::showStackMenu);

            table.setPhaseStops(p -> hasStop(true, p), p -> hasStop(false, p));
            table.setOnPhaseToggled((mine, phase) -> {
                toggleStop(mine, phase);
                table.setPhaseStops(p -> hasStop(true, p), p -> hasStop(false, p));
            });
        };
        if (ui != null) {
            ui.runLater(wire);
        } else {
            wire.run();
        }
    }

    private boolean interactive() {
        return mode == Mode.HUMAN && table != null;
    }

    private volatile String lastTriggerDetail;

    String lastTriggerDetail() {
        return lastTriggerDetail;
    }

    private volatile String lastWiredDetail;
    private volatile String lastWiredTitle;

    String lastWiredDetail() {
        return lastWiredDetail;
    }

    String lastWiredTitle() {
        return lastWiredTitle;
    }

    private PlayerView chosenAttackPlayer;
    private int chosenAttackGame = -1;
    private int chosenAttackTurn = -1;

    private void rememberAttackPlayer(final PlayerView player) {
        final GameView gv = getGameView();
        if (gv == null || player == null || isMe(player) || payingMana || isSelecting() || getSelectionMax() > 0
                || !isMe(gv.getPlayerTurn()) || !player.isOpponentOf(gv.getPlayerTurn())) return;
        final PhaseType phase = gv.getPhase();
        if (phase != PhaseType.MAIN1 && phase != PhaseType.COMBAT_BEGIN
                && phase != PhaseType.COMBAT_DECLARE_ATTACKERS) return;
        chosenAttackPlayer = player;
        chosenAttackGame = gv.getId();
        chosenAttackTurn = gv.getTurn();
    }

    private PlayerView attackPlayerFor(final CardView card) {
        final GameView gv = getGameView();
        if (!isDeclaringAttackers() || chosenAttackPlayer == null || gv == null
                || gv.getId() != chosenAttackGame || gv.getTurn() != chosenAttackTurn
                || card.getZone() != ZoneType.Battlefield || !isMe(card.getController())
                || card.getCurrentState() == null || !card.getCurrentState().isCreature()) return null;
        for (final PlayerView player : gv.getPlayers()) {
            if (player.equals(chosenAttackPlayer) && !player.getHasLost()) return player;
        }
        return null;
    }

    public boolean dropForTest(final String sourceName, final String targetName) {
        final GameView gv = getGameView();
        if (gv == null) {
            return false;
        }
        CardView source = null;
        CardView target = null;
        for (final PlayerView p : gv.getPlayers()) {
            final Iterable<CardView> field = p.getCards(ZoneType.Battlefield);
            if (field == null) {
                continue;
            }
            for (final CardView c : field) {
                if (source == null && c.getName().equalsIgnoreCase(sourceName)) {
                    source = c;
                } else if (target == null && c.getName().equalsIgnoreCase(targetName)) {
                    target = c;
                }
            }
        }

        boolean fromHand = false;
        if (source == null) {
            final PlayerView me = localPlayerView();
            final Iterable<CardView> hand = me == null ? null : live(me).getCards(ZoneType.Hand);
            if (hand != null) {
                for (final CardView c : hand) {
                    if (c.getName().equalsIgnoreCase(sourceName)) {
                        source = c;
                        fromHand = true;
                        break;
                    }
                }
            }
        }
        if (source == null || target == null) {
            return false;
        }
        onCardDropped(source, fromHand, target);
        return true;
    }

    public boolean clickPlayerNamed(final String name) {
        final GameView gv = getGameView();
        if (gv == null) {
            return false;
        }
        final PlayerView me = localPlayer();
        for (final PlayerView p : gv.getPlayers()) {
            
            if ("@rival".equals(name) ? !p.equals(me) : p.getName().equalsIgnoreCase(name)) {
                onPlayerClicked(p);
                return true;
            }
        }
        return false;
    }

    private void onPlayerClicked(final PlayerView player) {
        if (!interactive() || player == null || finished.get()) {
            return;
        }
        rememberAttackPlayer(player);
        didDetail("clicked player " + DetailLog.name(player));
        respondLater(() -> getGameController().selectPlayer(player, null));
    }

    private void didDetail(final String what) {
        final DetailLog d = detail;
        if (d != null) {
            d.did(what);
        }
    }

    public void onOpponentFocused(final PlayerView player) {
        if (!interactive() || player == null || finished.get()) {
            return;
        }
        rememberAttackPlayer(player);
        if (!isDeclaringAttackers() || isHighlighted(player)) {
            return;
        }

        if (NeoTeams.isAlly(localPlayerView(), player)) {
            return;
        }
        if (CLICK_DEBUG) {
            System.out.printf("[click] pestanya %s -> nuevo defensor%n",
                    PlayerName.of(player));
        }
        respondLater(() -> getGameController().selectPlayer(player, null));
    }

    private boolean isDeclaringAttackers() {
        final GameView gv = getGameView();
        if (gv == null || gv.getPhase() != PhaseType.COMBAT_DECLARE_ATTACKERS) {
            return false;
        }
        return !payingMana && !isSelecting() && getSelectionMax() <= 0 && isMe(gv.getPlayerTurn());
    }

    private void onStackCountClicked(final List<CardView> cards) {
        if (cards == null || cards.isEmpty()) {
            return;
        }
        if (isSelecting() && interactive() && !finished.get()) {
            pickFromStack(cards);
            return;
        }
        if (!isDeclaringAttackers()) {
            onCardClicked(cards.get(0));
            return;
        }
        for (final CardView card : cards) {
            if (!card.isAttacking()) {
                onCardClicked(card);
            }
        }
    }

    private void pickFromStack(final List<CardView> cards) {
        final List<CardView> picked = new ArrayList<>();
        final List<CardView> free = new ArrayList<>();
        for (final CardView c : cards) {
            if (!isSelectable(c)) {
                continue;
            }
            (isHighlighted(c) ? picked : free).add(c);
        }
        if (free.isEmpty()) {
            if (picked.isEmpty()) {
                onCardClicked(cards.get(0));
            } else {
                picked.forEach(this::onCardClicked);
            }
            return;
        }
        final int max = getSelectionMax();
        final int room = max > 0 ? Math.max(0, max - countPickedSelectables()) : free.size();
        final int most = Math.min(free.size(), room);
        if (most <= 1 || table == null) {
            onCardClicked(free.get(0));
            return;
        }
        table.askPileCount(free.get(0), most, n -> {
            for (final CardView c : free.subList(0, Math.min(n, free.size()))) {
                onCardClicked(c);
            }
        });
    }

    private void onCardClicked(final CardView card) {
        if (!interactive() || card == null || finished.get()) {
            return;
        }
        didDetail("clicked card " + DetailLog.card(card));

        if (isDeclaringAttackers() && card.getCurrentState() != null
                && (card.getCurrentState().isBattle() || (card.getCurrentState().isPlaneswalker()
                && !isMe(card.getController())))) chosenAttackPlayer = null;
        final PlayerView chosen = attackPlayerFor(card);
        if (chosen != null) {
            lastClicked = card;
            respondLater(() -> {
                
                if (!chosen.equals(attackPlayerFor(card))) return;
                getGameController().selectPlayer(chosen, null);
                if (!isHighlighted(chosen)) return; 
                getGameController().selectCard(card, null, null);
            });
            return;
        }

        if (canUndoClick(card) && !isSelecting()) {
            clickTrace(card, "deshacer");
            lastManaSource = null;
            respondLater(() -> getGameController().undoLastAction());
            return;
        }

        final CardView blocked = attackerToSelectFirst(card);
        if (blocked != null) {
            clickTrace(card, "quitar bloqueo");
            respondLater(() -> getGameController().selectCard(blocked, null, null));
            lastClicked = card;
            respondLater(() -> getGameController().selectCard(card, null, null));
            return;
        }

        final CardView prepared = preparedSpellToCast(card);
        if (prepared != null) {
            clickTrace(card, "lanzar su hechizo preparado");
            lastClicked = card;
            respondLater(() -> getGameController().selectCard(prepared, null, null));
            return;
        }

        if (landPaysTheRest(card)) {
            return;
        }

        if (askBeforeLastPick(card)) {
            clickTrace(card, "preguntar antes de cerrar la eleccion");
            return;
        }

        if (askBlockOrUse(card)) {
            clickTrace(card, "preguntar bloquear o usar");
            return;
        }

        if (askBeforeActivate(card)) {
            clickTrace(card, "preguntar antes de activar");
            return;
        }

        clickTrace(card, "activar");
        lastClicked = card;
        respondLater(() -> {

            if (!getGameController().selectCard(card, null, null)) {
                final String why = CantPlay.reason(getGameController(), card);
                if (why != null) {
                    showWhy(why);
                }
            }
        });
    }

    private boolean askBeforeLastPick(final CardView card) {
        if (table == null || !interactive() || finished.get()
                || table.getOverlay().isShowing()
                || !forge.neo.match.PickGuard.shouldAsk(getGameController(), this, card)) {
            return false;
        }
        final StringBuilder names = new StringBuilder();
        for (final CardView c : forge.neo.match.PickGuard.picked(this, card)) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(forge.neo.card.CardText.nameOf(c));
        }
        final String prompt = lastPrompt;
        final AtomicBoolean answered = new AtomicBoolean();
        final ConfirmDialog dialog = new ConfirmDialog(
                prompt == null || prompt.isBlank() ? NeoText.get("pick.ask.title") : prompt,
                NeoText.get("pick.ask.detail", names.toString()),
                List.of(NeoText.get("pick.ask.change"), NeoText.get("pick.ask.confirm")),
                0,
                index -> {
                    if (!answered.compareAndSet(false, true)) {
                        return;
                    }
                    table.getOverlay().hide();
                    if (index != null && index == 1) {
                        lastClicked = card;
                        respondLater(() -> getGameController().selectCard(card, null, null));
                    }
                });
        dialog.getStyleClass().add("phase-ask");
        
        if (dialog.option(1) != null) {
            dialog.option(1).getStyleClass().add("phase-ask-go");
        }
        table.getOverlay().show(dialog);
        table.requestLayout();
        return true;
    }

    private boolean askBlockOrUse(final CardView card) {
        if (table == null || !interactive() || finished.get()
                || table.getOverlay().isShowing()
                || !forge.neo.match.BlockIntent.wouldMeanBlock(getGameController(), card)) {
            return false;
        }
        final String name = forge.neo.card.CardText.nameOf(card);
        final AtomicBoolean answered = new AtomicBoolean();
        final ConfirmDialog dialog = new ConfirmDialog(
                NeoText.get("blockIntent.title", name),
                NeoText.get("blockIntent.text"),
                List.of(NeoText.get("blockIntent.block"), NeoText.get("blockIntent.use")),
                0,
                index -> {
                    if (!answered.compareAndSet(false, true)) {
                        return;
                    }
                    table.getOverlay().hide();
                    if (index == null) {
                        return;
                    }
                    lastClicked = card;
                    if (index == 0) {
                        pendingBlocker = card;
                        pendingBlockAttacker = null;
                        respondLater(() -> getGameController().selectButtonOk());
                    } else {
                        respondLater(() -> getGameController().selectCard(card, null, null));
                    }
                });
        dialog.getStyleClass().add("phase-ask");
        if (dialog.option(0) != null) {
            dialog.option(0).getStyleClass().add("phase-ask-go");
        }
        table.getOverlay().show(dialog);
        table.requestLayout();
        return true;
    }

    private CardView pendingBlocker;

    private volatile CardView pendingAuraTarget;
    private volatile long pendingAuraUntil;
    private static final long AURA_DROP_WINDOW_MS = 15_000;

    private CardView pendingBlockAttacker;

    private boolean askBeforeActivate(final CardView card) {
        if (table == null || !interactive() || finished.get()
                || table.getOverlay().isShowing()
                || !forge.neo.match.ActivateGuard.shouldAsk(getGameController(), card)) {
            return false;
        }
        final String what = forge.neo.match.ActivateGuard.describe(getGameController(), card);

        final boolean fromHand = forge.neo.match.ActivateGuard.fromHand(getGameController(), card);
        final String name = forge.neo.card.CardText.nameOf(card);
        final AtomicBoolean answered = new AtomicBoolean();
        final ConfirmDialog dialog = new ConfirmDialog(
                fromHand ? NeoText.get("activate.hand.title", name) : NeoText.get("activate.ask.title", name),
                fromHand ? NeoText.get("activate.hand.text", what) : what,
                List.of(NeoText.get("activate.ask.cancel"),
                        NeoText.get(fromHand ? "activate.hand.go" : "activate.ask.go")),
                0,
                index -> {
                    if (!answered.compareAndSet(false, true)) {
                        return;
                    }
                    table.getOverlay().hide();
                    if (index != null && index == 1) {
                        lastClicked = card;
                        respondLater(() -> getGameController().selectCard(card, null, null));
                    }
                });
        dialog.getStyleClass().add("phase-ask");
        
        if (dialog.option(1) != null) {
            dialog.option(1).getStyleClass().add("phase-ask-go");
        }
        table.getOverlay().show(dialog);
        table.requestLayout();
        return true;
    }

    private CardView preparedSpellToCast(final CardView card) {
        final CardView spell = castablePreparedSpell(card);
        if (spell == null) {
            return null;
        }
        return getGameController().getActivateDescription(card) == null ? spell : null;
    }

    private CardView castablePreparedSpell(final CardView card) {
        if (card == null || isSelecting() || payingMana) {
            return null;
        }
        final CardView spell = card.getPreparedSpell();
        return spell != null && isPlayableOutside(spell) ? spell : null;
    }

    private CardView attackerToSelectFirst(final CardView card) {
        final GameView gv = getGameView();
        if (gv == null || gv.getPhase() != PhaseType.COMBAT_DECLARE_BLOCKERS
                || card == null || !isMe(card.getController()) || !card.isBlocking()
                || isSelecting() || getSelectionMax() > 0 || payingMana) {
            return null;
        }
        final forge.game.combat.CombatView combat = gv.getCombat();
        if (combat == null || combat.getAttackers() == null) {
            return null;
        }
        CardView blocked = null;
        boolean anyHighlighted = false;
        for (final CardView attacker : combat.getAttackers()) {
            if (attacker == null) {
                continue;
            }
            if (isHighlighted(attacker)) {
                anyHighlighted = true;
                if (blocks(combat, attacker, card)) {
                    return null;   
                }
            } else if (blocked == null && blocks(combat, attacker, card)) {
                blocked = attacker;
            }
        }
        if (!anyHighlighted || blocked == null) {
            return null;
        }
        return getGameController().getActivateDescription(card) == null ? blocked : null;
    }

    private static boolean blocks(final forge.game.combat.CombatView combat,
                                  final CardView attacker, final CardView blocker) {
        return contains(combat.getBlockers(attacker), blocker)
                || contains(combat.getPlannedBlockers(attacker), blocker);
    }

    private static boolean contains(final Iterable<CardView> cards, final CardView card) {
        if (cards == null) {
            return false;
        }
        for (final CardView cv : cards) {
            if (cv != null && cv.getId() == card.getId()) {
                return true;
            }
        }
        return false;
    }

    public void undoLast() {
        if (!interactive() || finished.get()) {
            return;
        }
        lastManaSource = null;
        respondLater(() -> getGameController().undoLastAction());
    }

    public boolean resolveStack() {
        if (!interactive() || finished.get()) {
            return false;
        }
        final IGameController gc = getGameController();
        final PlayerView me = getCurrentPlayer();
        if (gc == null || me == null) {
            return false;
        }
        respondLater(() -> ResolveAll.arm(gc, me));
        return true;
    }

    @Override
    public void applyYieldUpdate(final forge.gamemodes.match.YieldUpdate update) {
        super.applyYieldUpdate(update);
        ResolveAll.mirrorOff(getGameController(), update);
    }

    @Override
    public void applyMacroState(final forge.interfaces.IMacroSystem.State state) {
        super.applyMacroState(state);
        runOnUi(() -> {
            if (table != null) {
                table.setMacroState(isMacroRecording(), hasMacro());
            }
        });
    }

    public static boolean resolvingStack(final IGameController gc) {
        return ResolveAll.isOn(gc);
    }

    private int resolveAllCount(final boolean okEnabled) {
        return ResolveAll.count(getGameView(), getGameController(), okEnabled && !payingMana
                && !isSelecting() && getSelectionMax() <= 0 && isRoutinePriorityPrompt(lastPrompt));
    }

    private int yesToAllCount(final boolean okEnabled) {
        if (!okEnabled || payingMana || isSelecting() || lastPrompt == null) {
            return 0;
        }
        final StackItemView top = topOfStack();
        if (top == null || !top.isOptionalTrigger() || !isMe(top.getActivatingPlayer())
                || top.getKey() == null) {
            return 0;
        }
        final String ask = forge.util.Localizer.getInstance().getMessage("lblUseTriggeredAbilityOf").trim();
        if (ask.isEmpty() || !lastPrompt.contains(ask)) {
            return 0;
        }
        int same = 0;
        final GameView gv = getGameView();
        if (gv != null && gv.getStack() != null) {
            for (final StackItemView si : gv.getStack()) {
                if (si != null && top.getKey().equals(si.getKey())) {
                    same++;
                }
            }
        }
        return Math.max(1, same);
    }

    private void yesToAll() {
        if (!interactive() || finished.get()) {
            return;
        }
        didDetail("pressed yes to all");
        final String said = autoYieldTop(true);
        if (said != null && table != null) {
            table.getActionBar().setWarning(said, false);
        }
        respondLater(() -> getGameController().selectButtonOk());
    }

    public boolean passTurn() {
        if (!interactive() || finished.get()) {
            return false;
        }
        didDetail("pass turn (shortcut)");
        final IGameController gc = getGameController();
        final PlayerView me = getCurrentPlayer();
        if (gc == null || me == null) {
            return false;
        }
        final String prompt = lastPrompt;
        final boolean passNow = !payingMana && !isSelecting() && getSelectionMax() <= 0
                && prompt != null && isRoutinePriorityPrompt(prompt);
        
        if (passNow && confirmEndingTurn(() -> respondLater(() -> armPassTurn(gc, me, true)))) {
            return true;
        }
        respondLater(() -> armPassTurn(gc, me, passNow));
        return true;
    }

    void armPassTurn(final IGameController gc, final PlayerView me, final boolean passNow) {
        YieldController.endTurn(seated(gc), me);
        if (passNow) {
            gc.selectButtonOk();
        }
    }

    public Boolean toggleMacroRecording() {
        if (!interactive() || finished.get()) {
            return null;
        }
        final IGameController gc = getGameController();
        if (gc == null || gc.macros() == null) {
            return null;
        }
        final boolean willRecord = !gc.macros().isRecording();
        if (willRecord) {
            macroChoices.clear();
        }
        respondLater(() -> {
            if (willRecord) {
                holdAutoPassForMacro(gc);
            }
            gc.macros().setRememberedActions();
            syncMacroAutoPass(gc);
        });
        return willRecord;
    }

    public boolean playMacro() {
        if (!interactive() || finished.get()) {
            return false;
        }
        final IGameController gc = getGameController();
        if (gc == null || gc.macros() == null) {
            return false;
        }
        macroChoices.rewind();
        respondLater(() -> {
            holdAutoPassForMacro(gc);
            gc.macros().repeatRememberedActions();
            syncMacroAutoPass(gc);
        });
        return true;
    }

    private final MacroChoices macroChoices = new MacroChoices();

    private int macroMode() {
        try {
            final IGameController gc = getGameController();
            if (gc == null || gc.macros() == null) {
                return 0;
            }
            if (gc.macros().isReplaying()) {
                return 2;
            }
            return gc.macros().isRecording() ? 1 : 0;
        } catch (final RuntimeException e) {
            return 0;
        }
    }

    public boolean nextMacroAction() {
        if (!interactive() || finished.get()) {
            return false;
        }
        final IGameController gc = getGameController();
        if (gc == null || gc.macros() == null) {
            return false;
        }
        respondLater(() -> {
            holdAutoPassForMacro(gc);
            gc.macros().nextRememberedAction();
            syncMacroAutoPass(gc);
        });
        return true;
    }

    void holdAutoPassForMacro(final IGameController ui) {
        final IGameController gc = seated(ui);
        final YieldController y = gc.getYieldController();
        if (y == null || macroAutoPassSaved != null) {
            return;
        }

        for (final FPref scope : MACRO_SCOPES) {
            macroScopesSaved.put(scope, y.getStringPref(scope));
            y.setPref(scope, "NEVER");
        }
        macroAutoPassSaved = y.getStringPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS);
        y.setPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, "false");
        gc.setYieldPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, "false");
    }

    private static final FPref[] MACRO_SCOPES = {
            FPref.YIELD_DECLINE_SCOPE_STACK_YIELD, FPref.YIELD_DECLINE_SCOPE_NO_ACTIONS};
    private final java.util.Map<FPref, String> macroScopesSaved =
            new java.util.concurrent.ConcurrentHashMap<>();

    void syncMacroAutoPass(final IGameController gc) {
        if (gc == null || gc.macros() == null) {
            return;
        }
        if (gc.macros().isRecording() || gc.macros().isReplaying()) {
            
            holdAutoPassForMacro(gc);
            return;
        }
        final String saved = macroAutoPassSaved;
        if (saved == null) {
            return;
        }
        macroAutoPassSaved = null;
        final IGameController engine = seated(gc);
        final YieldController y = engine.getYieldController();
        if (y == null) {
            macroScopesSaved.clear();
            return;
        }
        macroScopesSaved.forEach(y::setPref);
        macroScopesSaved.clear();
        y.setPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, saved);

        engine.setYieldPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, saved);
    }

    static IGameController seated(final IGameController gc) {
        if (gc instanceof forge.player.PlayerControllerHuman human && human.getPlayer() != null
                && human.getPlayer().getController() instanceof forge.player.PlayerControllerHuman sat
                && sat.getInputQueue() == human.getInputQueue()) {
            return sat;
        }
        return gc;
    }

    private volatile String macroAutoPassSaved;

    public boolean isMacroRecording() {
        final IGameController gc = getGameController();
        return gc != null && gc.macros() != null && gc.macros().isRecording();
    }

    private boolean macroButtonsOverride;

    private volatile java.util.function.BooleanSupplier autoPlayHold;

    public void setAutoPlayHold(final java.util.function.BooleanSupplier hold) {
        this.autoPlayHold = hold;
    }

    public void nudgeAutoPlay() {
        pilotRespond(this::autoPressOk);
    }

    public boolean alphaStrike() {
        if (!interactive() || finished.get()) {
            return false;
        }
        final IGameController gc = getGameController();
        if (gc == null) {
            return false;
        }
        respondLater(gc::alphaStrike);
        return true;
    }

    public boolean attackWithNonTokens() {
        if (!interactive() || finished.get()) {
            return false;
        }
        final IGameController gc = getGameController();
        if (!(gc instanceof forge.player.PlayerControllerHuman human)) {
            return false;
        }
        respondLater(() -> {
            human.alphaStrike();
            final Game game = human.getGame();
            final forge.game.combat.Combat combat = game == null ? null : game.getCombat();
            if (combat == null) {
                return;
            }
            boolean removed = false;
            for (final forge.game.card.Card c : new java.util.ArrayList<>(combat.getAttackers())) {
                if (c.isToken()) {
                    combat.removeFromCombat(c);
                    removed = true;
                }
            }
            if (removed) {
                game.fireEvent(new forge.game.event.GameEventCombatChanged());
            }
        });
        return true;
    }

    public boolean openTopStackMenu() {
        final StackItemView top = topOfStack();
        if (!interactive() || finished.get() || top == null) {
            return false;
        }
        showStackMenu(top);
        return true;
    }

    public String autoYieldTop(final boolean yes) {
        if (!interactive() || finished.get()) {
            return null;
        }
        final IGameController gc = getGameController();
        final StackItemView top = topOfStack();
        final String key = top == null ? null : top.getKey();
        if (gc == null || top == null || !top.isAbility() || key == null || key.isEmpty()) {
            return NeoText.get("shortcuts.autoYield.none");
        }
        final boolean decides = top.isOptionalTrigger() && isMe(top.getActivatingPlayer());
        if (decides) {
            gc.setTriggerDecision(key, yes ? AutoYieldStore.TriggerDecision.ACCEPT
                    : AutoYieldStore.TriggerDecision.DECLINE, abilityScope(gc));
        }
        gc.setShouldAutoYield(key, true, abilityScope(gc));
        refreshYieldUi(getCurrentPlayer());
        final String name = top.getSourceCard() == null ? ""
                : forge.neo.card.CardText.nameOf(top.getSourceCard());
        if (!decides) {
            return NeoText.get("shortcuts.autoYield.pass", name);
        }
        return NeoText.get(yes ? "shortcuts.autoYield.yes" : "shortcuts.autoYield.no", name);
    }

    public Boolean toggleFullControl() {
        if (!interactive() || finished.get()) {
            return null;
        }
        return flipFullControl(getGameController());
    }

    Boolean flipFullControl(final IGameController ui) {
        final IGameController gc = seated(ui);
        if (gc == null || gc.getYieldController() == null) {
            return null;
        }
        final FPref pref = FPref.YIELD_AUTO_PASS_NO_ACTIONS;

        final String saved = macroAutoPassSaved;
        if (saved != null) {
            final boolean wasOn = Boolean.parseBoolean(saved);
            macroAutoPassSaved = String.valueOf(!wasOn);
            return wasOn;
        }

        final boolean on = gc.getYieldController().getBoolPref(pref);
        final String autoPass = String.valueOf(!on);
        respondLater(() -> {
            gc.getYieldController().setPref(pref, autoPass);
            if (on) {
                gc.getYieldController().clearActiveYieldAndDispatch();
            }
            gc.setYieldPref(pref, autoPass);
        });
        return on;
    }

    private volatile CardView lastClicked;

    private volatile CardView lastManaSource;
    private volatile int lastPoolSize;

    private void clickTrace(final CardView card, final String action) {
        if (!CLICK_DEBUG) {
            return;
        }
        System.out.printf("[click] %s -> %s | girada=%s elegible=%s reserva=%d fuente=%s%n",
                card, action, card.isTapped(), isSelectable(card), localPoolSize(),
                lastManaSource);
    }

    private static final boolean CLICK_DEBUG = Boolean.getBoolean("neo.click.debug");

    private boolean canUndoClick(final CardView card) {

        return card.equals(lastManaSource) && card.isTapped() && localPoolSize() > 0
                && actionableStrength(card) <= 0;
    }

    private PlayerView live(final PlayerView p) {
        final GameView gv = getGameView();
        if (p == null || gv == null || gv.getPlayers() == null) {
            return p;
        }
        for (final PlayerView candidate : gv.getPlayers()) {
            if (candidate != null && candidate.getId() == p.getId()) {
                return candidate;
            }
        }
        return p;
    }

    private int localPoolSize() {
        if (!hasLocalPlayers()) {
            return 0;
        }
        int total = 0;
        for (final PlayerView seat : getLocalPlayers()) {
            final PlayerView p = live(seat);

            for (final byte type : forge.card.mana.ManaAtom.MANATYPES) {
                total += p.getMana(type);
            }
        }
        return total;
    }

    private void onCardDropped(final CardView source, final boolean fromHand,
                               final Object target) {
        if (!interactive() || source == null || finished.get()) {
            return;
        }
        if (fromHand) {

            pendingAuraTarget = AuraDrop.creatureTarget(source, target);
            pendingAuraUntil = System.currentTimeMillis() + AURA_DROP_WINDOW_MS;
            respondLater(() -> getGameController().selectCard(source, null, null));
            return;
        }

        final boolean onBattlefield = source.getZone() == ZoneType.Battlefield;
        if (target == null) {
            if (!onBattlefield) {
                onCardClicked(source);
            }
            return;
        }
        final GameView gv = getGameView();
        final boolean ownCreature = source.getZone() == ZoneType.Battlefield
                && isMe(source.getController()) && source.getCurrentState() != null
                && source.getCurrentState().isCreature();
        final boolean attacking = ownCreature && isDeclaringAttackers();

        if (ownCreature && target instanceof CardView attacker && gv != null && gv.getCombat() != null
                && contains(gv.getCombat().getAttackers(), attacker)
                && forge.neo.match.BlockIntent.wouldMeanBlock(getGameController(), source)) {
            pendingBlocker = source;
            pendingBlockAttacker = attacker;
            respondLater(() -> getGameController().selectButtonOk());
            return;
        }
        final boolean blocking = ownCreature && gv != null
                && gv.getPhase() == PhaseType.COMBAT_DECLARE_BLOCKERS
                && !payingMana && !isSelecting() && getSelectionMax() <= 0;

        if (!attacking && !blocking) {
            if (!onBattlefield) {
                onCardClicked(source);
            } else {
                trace("arrastre de %s fuera de combate: no se hace nada", source.getName());
            }
            return;
        }
        if (attacking) {
            final boolean playerTarget = target instanceof PlayerView p && !p.getHasLost()
                    && p.isOpponentOf(source.getController());
            final boolean cardTarget = target instanceof CardView c && c.getZone() == ZoneType.Battlefield
                    && c.getCurrentState() != null && (c.getCurrentState().isBattle()
                    || (c.getCurrentState().isPlaneswalker() && c.getController() != null
                    && c.getController().isOpponentOf(source.getController())));
            if (!playerTarget && !cardTarget) return;
        } else if (!(target instanceof CardView c) || gv.getCombat() == null
                || !contains(gv.getCombat().getAttackers(), c)) {
            return;
        }
        if (target instanceof PlayerView player) {
            rememberAttackPlayer(player);
            respondLater(() -> {
                getGameController().selectPlayer(player, null);
                if (attacking && !isHighlighted(player)) return;
                getGameController().selectCard(source, null, null);
            });
        } else if (target instanceof CardView card && card.getId() != source.getId()) {
            if (isDeclaringAttackers()) chosenAttackPlayer = null;
            respondLater(() -> {
                getGameController().selectCard(card, null, null);
                if (attacking && !isHighlighted(card)) return;
                getGameController().selectCard(source, null, null);
            });
        }
    }

    private void pushToTable() {
        final TableBinder b = binder;
        if (b != null) {
            b.setGameView(getGameView());
            b.requestRefresh();
        }
    }

    @Override
    protected void afterDeltaApplied() {
        pushToTable();
    }

    private void log(final String fmt, final Object... args) {
        System.out.println("  " + String.format(fmt, args));
    }

    private void trace(final String fmt, final Object... args) {
        if (verbose) {
            System.out.println("    . " + String.format(fmt, args));
        }
    }

    private static String nameOf(final PlayerView p) {
        return PlayerName.of(p);
    }

    @Override
    public void updateTurn(final PlayerView player) {
        super.updateTurn(player);
        dumpTable();
        pushToTable();
        announceTurn(player);
    }

    private void announceTurn(final PlayerView player) {
        final forge.neo.ui.TableScreen t = table;
        if (t == null || player == null) {
            return;
        }

        final boolean yours = isLocalPlayer(player);
        final String who = nameOf(player);
        final int turn = getGameView() == null ? 0 : getGameView().getTurn();
        final int round = shownRound(getGameView());
        runOnUi(() -> {
            t.announceTurn(yours, who, turn, round);
            t.getPhaseRail().setTurnOwner(yours, who);
        });
    }

    @Override
    public void showCoinFlip(final boolean heads, final String caption, final boolean waitForTap) {
        final forge.neo.ui.TableScreen t = table;
        log("Coin: %s (%s)", caption, heads ? "heads" : "tails");
        if (!interactive() || t == null || !forge.neo.card.CardNode.areAnimationsEnabled()) {
            return;
        }
        final forge.util.Localizer words = forge.util.Localizer.getInstance();
        final String headsWord = words.getMessage("lblHeads");
        final String tailsWord = words.getMessage("lblTails");
        final java.util.concurrent.CountDownLatch landed = new java.util.concurrent.CountDownLatch(1);
        final UiDispatcher ui = uiDispatcher();
        final boolean onUi = javafx.application.Platform.isFxApplicationThread()
                || (ui != null && ui.isUiThread());
        runOnUi(() -> {
            try {
                t.flipCoin(heads, caption, headsWord, tailsWord, landed::countDown);
            } catch (final RuntimeException e) {
                landed.countDown();
            }
        });
        if (onUi) {
            return;
        }
        try {
            landed.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void showDiceRoll(final int sides, final List<Integer> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        final StackItemView top = topOfStack();
        final CardView source = top == null ? null : top.getSourceCard();
        final String caption = forge.neo.match.DiceCaption.of(whoRolled(),
                source == null ? null : forge.neo.card.CardText.nameOf(source), sides, results);
        log("Dice: %s -> %s", caption, results);
        animateDice(sides, results, caption);
    }

    private void showPlanarRoll(final forge.game.PlanarDice result) {
        final GameView gv = getGameView();
        final Game game = gv == null ? null : gv.getGame();
        final forge.game.player.Player roller = game == null ? null : game.getPhaseHandler().getPlayerTurn();
        final int face;
        switch (result) {
            case Chaos:
                face = 1;
                break;
            case Planeswalk:
                face = 6;
                break;
            default:
                face = 2 + forge.util.MyRandom.getRandom().nextInt(4);
                break;
        }
        final String caption = forge.neo.match.DiceCaption.planar(roller == null ? null : roller.getName(),
                forge.neo.NeoText.get("dice.planar." + result.name().toLowerCase(java.util.Locale.ROOT)));
        log("Planar die: %s", caption);
        animateDice(6, List.of(face), caption);
    }

    private void animateDice(final int sides, final List<Integer> results, final String caption) {
        final forge.neo.ui.TableScreen t = table;
        if (!interactive() || t == null || !forge.neo.card.CardNode.areAnimationsEnabled()) {
            return;
        }
        final List<Integer> rolled = new ArrayList<>(results);
        final java.util.concurrent.CountDownLatch landed = new java.util.concurrent.CountDownLatch(1);
        final UiDispatcher ui = uiDispatcher();
        final boolean onUi = javafx.application.Platform.isFxApplicationThread()
                || (ui != null && ui.isUiThread());
        runOnUi(() -> {
            try {
                t.rollDice(sides, rolled, caption, landed::countDown);
            } catch (final RuntimeException e) {
                landed.countDown();
            }
        });
        final GameView gv = getGameView();
        if (onUi || gv == null || gv.getGame() == null) {
            return;
        }
        try {
            landed.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private volatile java.util.Map<Integer, Integer> rollsBefore;

    private void noteRollsBefore() {
        final GameView gv = getGameView();
        final Game game = gv == null ? null : gv.getGame();
        if (game == null || rollsBefore != null) {
            return;
        }
        final java.util.Map<Integer, Integer> now = new java.util.HashMap<>();
        for (final forge.game.player.Player p : game.getPlayers()) {
            now.put(p.getId(), p.getNumRollsThisTurn());
        }
        rollsBefore = now;
    }

    private String whoRolled() {
        final java.util.Map<Integer, Integer> before = rollsBefore;
        rollsBefore = null;
        final GameView gv = getGameView();
        final Game game = gv == null ? null : gv.getGame();
        if (before == null || game == null) {
            return null;
        }
        for (final forge.game.player.Player p : game.getPlayers()) {
            if (p.getNumRollsThisTurn() > before.getOrDefault(p.getId(), 0)) {
                return p.getName();
            }
        }
        return null;
    }

    @Override
    public void updatePhase(final boolean afterPhase) {
        super.updatePhase(afterPhase);
        final GameView gv = getGameView();
        if (gv == null) {
            return;
        }
        final PhaseType phase = gv.getPhase();
        if (phase != lastPhase) {
            lastPhase = phase;
            trace("fase: %s", phase);
            final DetailLog d = detail;
            if (d != null) {
                
                final PlayerView turnOf = gv.getPlayerTurn();
                d.phase(gv, interactive() && phase != null
                        ? (turnOf == null || isLocalPlayer(turnOf) ? stops : theirStops).contains(phase)
                        : null);
            }

            updateDiscord();

            lastManaSource = null;
            lastClicked = null;
            lastPoolSize = localPoolSize();
        }
        pushToTable();
    }

    private void updateDiscord() {
        try {
            forge.neo.discord.DiscordStatus.game(getGameView(), discordSeats, isNetGame());
        } catch (final RuntimeException e) {
            trace("Discord: %s", e);
        }
    }

    private void dumpTable() {
        final GameView gv = getGameView();
        if (gv == null) {
            return;
        }
        final int turn = gv.getTurn();
        if (turn == lastTurn) {
            return;
        }
        lastTurn = turn;

        final StringBuilder sb = new StringBuilder();
        sb.append(String.format("%n-- Turn %d . round %d . %s to play", turn, rounds.round(gv),
                nameOf(gv.getPlayerTurn())));
        for (final PlayerView p : gv.getPlayers()) {
            sb.append(String.format("%n     %-14s %3d life | hand %2d | field %2d | graveyard %2d | library %2d",
                    nameOf(p), p.getLife(),
                    p.getZoneSize(ZoneType.Hand),
                    p.getZoneSize(ZoneType.Battlefield),
                    p.getZoneSize(ZoneType.Graveyard),
                    p.getZoneSize(ZoneType.Library)));
        }
        System.out.println(sb);
    }

    @Override
    public void handleGameEvent(final GameEvent event) {
        if (event == null) {
            return;
        }
        
        if (probing == Thread.currentThread()) {
            return;
        }

        tellTheSpy(event);
        feedGuestLog(event);
        playGuestSound(event);
        rememberPlotted(event);

        holdWhileReading();

        pushToTable();
        final DetailLog detailLog = detail;
        if (detailLog != null) {
            detailLog.sync(getGameView());
        }
        refreshCentralPrompt();
        flushStrandedViewChanges();
        final boolean stopped = watchForThingsThatAffectMe(event);

        if (!stopped) {
            slowTheAiDown(event);
        }
        animateEvent(event);
        if (event instanceof forge.game.event.GameEventRollDie die && die.planar() != null) {
            showPlanarRoll(die.planar());
        } else if (event instanceof forge.game.event.GameEventRollDie die && die.planar() == null) {
            noteRollsBefore();
        } else if (event instanceof forge.game.event.GameEventRollDice roll) {
            showDiceRoll(roll.sides(), roll.results());
        }
        trace("evento: %s", event.getClass().getSimpleName());
        finishIfGuest(event);
    }

    private void feedGuestLog(final GameEvent event) {
        final GameView gv = getGameView();
        if (gv == null || gv.getGame() != null) {
            return;
        }
        final forge.game.GameLog log = gv.getGameLog();
        if (log == null) {
            return;
        }
        try {
            log.getEventVisitor().recieve(event);
        } catch (final RuntimeException e) {
            trace("registro del invitado: %s", e);
        }
    }

    private void playGuestSound(final GameEvent event) {
        final GameView gv = getGameView();
        if (gv == null || gv.getGame() != null) {
            return;
        }
        try {
            forge.sound.SoundSystem.instance.receiveEvent(event);
        } catch (final RuntimeException e) {
            trace("sonido del invitado: %s", e);
        }
    }

    private void cancelStaleAwaitIfGuest() {
        final GameView gv = getGameView();
        if (gv != null && gv.getGame() == null) {
            cancelAwaitNextInput();
        }
    }

    private final Object awaitLock = new Object();

    @Override
    public void awaitNextInput() {
        synchronized (awaitLock) {
            super.awaitNextInput();
        }
    }

    private void finishIfGuest(final GameEvent event) {
        if (!(event instanceof forge.game.event.GameEventGameFinished)) {
            return;
        }
        final GameView gv = getGameView();
        if (gv == null || gv.getGame() != null || finished.get()) {
            return;
        }
        log("game over received from the network");
        forge.gui.GuiBase.getInterface().invokeInEdtLater(this::finishGame);
    }

    private static final long AI_STEP_MS = 3_000;

    private volatile int aiSpeed =
            forge.neo.NeoSettings.getInt(forge.neo.NeoSettings.AI_SPEED, 100);

    public void setAiSpeed(final int hundredths) {
        this.aiSpeed = hundredths;
    }

    private void slowTheAiDown(final GameEvent event) {
        final int speed = aiSpeed;
        if (speed <= 0 || !interactive()) {
            return;
        }
        if (isNetGame() && !runsTheEngine()) {

            return;
        }
        
        final UiDispatcher ui = uiDispatcher();
        if (ui != null && ui.isUiThread()) {
            return;
        }
        if (!isSomebodyElsePlayingACard(event)) {
            return;
        }
        try {
            Thread.sleep(AI_STEP_MS * 100 / speed);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final long READING_CAP_DEFAULT_MS = 5 * 60 * 1000L;

    private static long readingCapMs() {
        return Long.getLong("neo.readingCapMs", READING_CAP_DEFAULT_MS);
    }

    private volatile java.util.function.BooleanSupplier readingProbe;

    public void setReadingProbe(final java.util.function.BooleanSupplier probe) {
        this.readingProbe = probe;
    }

    private boolean playerIsReading() {
        final java.util.function.BooleanSupplier probe = readingProbe;
        if (probe != null) {
            return probe.getAsBoolean();
        }
        final forge.neo.ui.TableScreen t = table;
        return t != null && t.isPlayerReading();
    }

    private void holdWhileReading() {
        if (isNetGame() || !playerIsReading()) {
            return;
        }
        if (!forge.neo.NeoSettings.pauseWhileReading()) {
            return;
        }
        
        final UiDispatcher ui = uiDispatcher();
        if (ui != null && ui.isUiThread()) {
            return;
        }
        final long cap = readingCapMs();
        final long until = System.currentTimeMillis() + cap;
        while (playerIsReading() && exitAction == null && !finished.get()) {
            if (System.currentTimeMillis() > until) {
                System.out.println("[neo] la pausa por lectura lleva " + cap
                        + " ms: se sigue igual");
                return;
            }
            try {

                Thread.sleep(50L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean isSomebodyElsePlayingACard(final GameEvent event) {
        final PlayerView me = localPlayer();
        if (me == null) {
            return false;
        }
        PlayerView who = null;
        if (event instanceof GameEventSpellAbilityCast e) {
            final StackItemView si = e.si();
            who = si == null ? null : si.getActivatingPlayer();
        } else if (event instanceof GameEventLandPlayed e) {
            who = e.player();
        }
        if (who == null || me.equals(who)) {
            return false;
        }

        return !isNetGame() || who.isAI();
    }

    private boolean runsTheEngine() {
        final GameView gv = getGameView();
        return gv != null && gv.getGame() != null;
    }

    private volatile java.util.function.Consumer<GameEvent> eventSpy;

    public void setEventSpy(final java.util.function.Consumer<GameEvent> spy) {
        this.eventSpy = spy;
    }

    private void tellTheSpy(final GameEvent event) {
        final java.util.function.Consumer<GameEvent> spy = eventSpy;
        if (spy == null) {
            return;
        }

        runOnUi(() -> {
            try {
                spy.accept(event);
            } catch (final RuntimeException e) {

                System.out.println("[neo] el tutorial ha fallado al mirar un evento: " + e);
            }
        });
    }

    private void animateEvent(final GameEvent event) {
        final forge.neo.ui.TableScreen t = table;
        if (t == null || !forge.neo.card.CardNode.areAnimationsEnabled()) {
            return;
        }

        if (event instanceof GameEventCardChangeZone move && move.card() != null && move.from() != null
                && move.to() != null && move.to().zoneType() == ZoneType.Battlefield
                && (move.from().zoneType() == ZoneType.Hand || move.from().zoneType() == ZoneType.Stack)) {
            final CardView card = move.card();
            final boolean fromStack = move.from().zoneType() == ZoneType.Stack;
            final boolean mine = isMine(card);
            final PlayerView owner = card.getController() != null ? card.getController() : card.getOwner();
            runOnUi(() -> t.flyIn(card, fromStack, mine, owner));
        }

        if (event instanceof forge.game.event.GameEventCardDamaged e && e.amount() > 0) {
            
            final boolean combat = e.source() != null && (e.source().isAttacking() || e.source().isBlocking());
            
            if (combat && forge.neo.ui.ImpactLayer.isEnabled()) {
                final CardView src = e.source();
                final CardView dst = e.card();
                runOnUi(() -> t.strike(src, dst));
            }
            if (forge.neo.ui.ImpactLayer.isEnabled()) {
                
                final CardView dst = e.card();
                final int amount = e.amount();
                final boolean poison = e.type() == forge.game.event.GameEventCardDamaged.DamageType.M1M1Counters;
                runOnUi(() -> t.damagePop(dst, amount, combat, poison));
            } else {
                pulsesFor(t).hit(e.card());
            }
        } else if (event instanceof forge.game.event.GameEventPlayerDamaged e && e.amount() > 0) {
            final PlayerView dst = e.target();
            if (e.combat() && e.source() != null && forge.neo.ui.ImpactLayer.isEnabled()) {
                final CardView src = e.source();
                runOnUi(() -> t.strike(src, dst));
            }
            if (forge.neo.ui.ImpactLayer.isEnabled()) {
                final int amount = e.amount();
                final boolean combat = e.combat();
                final boolean poison = e.infect();
                runOnUi(() -> t.damagePop(dst, amount, combat, poison));
            }
        } else if (event instanceof forge.game.event.GameEventCardCounters e
                && e.oldValue() != e.newValue()) {
            pulsesFor(t).bump(e.card());
        } else if (event instanceof forge.game.event.GameEventSpellResolved e && !e.hasFizzled()
                && forge.neo.ui.ImpactLayer.isEnabled()) {
            noteImpacts(t, e);
            noteSpellBlast(t, e);
        } else if (event instanceof forge.game.event.GameEventCardChangeZone e
                && forge.neo.ui.ImpactLayer.isEntranceEnabled()) {
            noteCommanderEntrance(t, e);
        }
    }

    private void noteCommanderEntrance(final forge.neo.ui.TableScreen t,
            final forge.game.event.GameEventCardChangeZone e) {
        final CardView card = e.card();

        if (card == null || e.to() == null || e.to().zoneType() != forge.game.zone.ZoneType.Battlefield
                || (e.from() != null && e.from().zoneType() == forge.game.zone.ZoneType.Battlefield)
                || !card.isCommander()) {
            return;
        }
        final PlayerView owner = card.getController() != null ? card.getController() : card.getOwner();
        final String subtitle = isMine(card) || owner == null
                ? NeoText.get("entrance.commander")
                : NeoText.get("entrance.commanderOf", nameOf(owner));
        final CardView.CardStateView state = card.getCurrentState();
        final List<javafx.scene.paint.Color> colors = forge.neo.ui.ImpactLayer.colorsOf(
                state == null ? null : state.getColors());
        
        final CommanderSignature signature = CommanderSignature.of(card, isMine(card));
        if (Boolean.getBoolean("neo.impact.log")) {
            System.out.println("[entrada] " + card + (signature == CommanderSignature.NONE ? "" : " firma " + signature));
        }
        runOnUi(() -> t.commanderEntrance(card, colors, subtitle, signature));
    }

    private void noteImpacts(final forge.neo.ui.TableScreen t,
            final forge.game.event.GameEventSpellResolved e) {
        final GameView view = getGameView();
        final SpellImpact impact = SpellImpact.read(view == null ? null : view.getGame(), e);
        if (impact == null) {
            return;
        }
        
        if (Boolean.getBoolean("neo.impact.log")) {
            System.out.println("[destellos] " + e.spell().getHostCard() + ": " + impact);
        }

        if (pendingImpacts.size() < 24) {
            pendingImpacts.add(new Object[] {t, impact});
        }
        if (impactsScheduled.compareAndSet(false, true)) {
            runOnUi(this::flushImpacts);
        }
    }

    private void noteSpellBlast(final forge.neo.ui.TableScreen t,
            final forge.game.event.GameEventSpellResolved e) {
        try {
            final GameView view = getGameView();
            final forge.game.Game game = view == null ? null : view.getGame();
            final String resolving = SpellImpact.resolvingSpellName(game, e);
            if (Boolean.getBoolean("neo.impact.log")) {
                System.out.println("[hechizo] resuelve: " + resolving);
            }
            final SpellSignature sig = SpellSignature.of(resolving);
            if (sig == SpellSignature.NONE) {
                return;
            }
            Object target = null;
            if (sig.atTarget) {
                final SpellImpact impact = SpellImpact.read(game, e);
                if (impact == null || impact.targets.isEmpty()) {
                    return;
                }
                target = impact.targets.get(0);
            }
            if (Boolean.getBoolean("neo.impact.log")) {
                System.out.println("[hechizo] " + sig + (target == null ? "" : " sobre " + target));
            }
            final Object on = target;
            runOnUi(() -> t.spellBlast(sig, on));
        } catch (final RuntimeException ex) {
            
        }
    }

    private final java.util.concurrent.ConcurrentLinkedQueue<Object[]> pendingImpacts =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicBoolean impactsScheduled =
            new java.util.concurrent.atomic.AtomicBoolean();

    private void flushImpacts() {
        
        impactsScheduled.set(false);
        Object[] next;
        while ((next = pendingImpacts.poll()) != null) {
            final forge.neo.ui.TableScreen t = (forge.neo.ui.TableScreen) next[0];
            final SpellImpact impact = (SpellImpact) next[1];
            final List<javafx.scene.paint.Color> colors = forge.neo.ui.ImpactLayer.colorsOf(impact.colors);
            for (final Object o : impact.targets) {
                t.impactHit(o, colors, impact.source);
            }
            
            if (impact.arrived != null && !(impact.arrived.isCommander()
                    && forge.neo.ui.ImpactLayer.isEntranceEnabled())) {
                t.impactArrive(impact.arrived, colors);
            }
        }
    }

    private volatile CardPulses pulses;

    private CardPulses pulsesFor(final forge.neo.ui.TableScreen t) {
        CardPulses p = pulses;
        if (p == null || !p.isFor(t)) {
            p = new CardPulses(t, this::runOnUi);
            pulses = p;
        }
        return p;
    }

    private void runOnUi(final Runnable task) {
        if (javafx.application.Platform.isFxApplicationThread()) {
            task.run();
            return;
        }
        final UiDispatcher ui = uiDispatcher();
        if (ui != null) {
            ui.runLater(task);
        } else {
            task.run();
        }
    }

    private final List<String> pendingNews = new ArrayList<>();

    private volatile int pauseMode =
            forge.neo.NeoSettings.getInt(forge.neo.NeoSettings.PAUSE_MODE, 2);

    public void setPauseMode(final int mode) {
        this.pauseMode = mode;
    }

    public int getPauseMode() {
        return pauseMode;
    }

    private boolean watchForThingsThatAffectMe(final GameEvent event) {
        if (!interactive() || pauseMode <= 0 || localPlayer() == null) {
            return false;
        }

        final String note = describeIfItAffectsMe(event);
        if (note != null) {
            synchronized (pendingNews) {
                if (!pendingNews.contains(note)) {
                    pendingNews.add(note);
                }
            }
            uiRunLater(() -> table.markLogUnread());

            if (pauseMode >= 2) {
                return flushNews();
            }
        }

        if (event instanceof GameEventTurnPhase) {
            return flushNews();
        }
        return false;
    }

    private boolean flushNews() {
        final List<String> news;
        synchronized (pendingNews) {
            if (pendingNews.isEmpty()) {
                return false;
            }
            news = new ArrayList<>(pendingNews);
            pendingNews.clear();
        }
        if (table == null) {
            return false;
        }

        final String text = String.join(System.lineSeparator(), news);

        if (forge.neo.NeoSettings.getInt(forge.neo.NeoSettings.NEWS_STYLE, 0) == 0 || isReplay()) {
            final forge.neo.ui.TableScreen t = table;
            uiRunLater(() -> t.showNotice(text));
            return false;
        }

        askUser(done -> {
            table.getPromptBanner().showAlert(text, NeoText.get("banner.understood"), () -> {
                table.getPromptBanner().hide();
                done.accept(Boolean.TRUE);
            }, forge.neo.NeoSettings.getInt(forge.neo.NeoSettings.NEWS_AUTOCLOSE, 0));
            table.requestLayout();
        }, Boolean.TRUE);
        return true;
    }

    private String describeIfItAffectsMe(final GameEvent event) {
        final PlayerView me = localPlayer();

        if (pauseMode >= 2 && event instanceof GameEventSpellAbilityCast e) {
            final StackItemView si = e.si();
            if (si != null && si.getActivatingPlayer() != null
                    && !me.equals(si.getActivatingPlayer())) {
                return NeoText.get("alert.casts",
                        PlayerName.of(si.getActivatingPlayer()),
                        si.getText() == null || si.getText().isBlank()
                            ? NeoText.get("alert.aSpell") : si.getText());
            }
            return null;
        }

        if (pauseMode >= 3 && event instanceof GameEventLandPlayed e) {
            if (e.player() != null && !me.equals(e.player())) {
                return NeoText.get("alert.playsLand",
                        PlayerName.of(e.player()), nameOfCard(e.land()));
            }
            return null;
        }

        if (event instanceof GameEventCardChangeZone e) {
            final CardView card = e.card();
            if (card == null || e.from() == null || e.to() == null || !isMine(card)) {
                return null;
            }
            if (e.from().zoneType() != ZoneType.Battlefield) {
                return null;
            }
            switch (e.to().zoneType()) {
                case Graveyard:
                    return NeoText.get("alert.died", nameOfCard(card));
                case Exile:
                    return NeoText.get("alert.exiled", nameOfCard(card));
                case Hand:
                    return NeoText.get("alert.toHand", nameOfCard(card));
                case Library:
                    return NeoText.get("alert.toLibrary", nameOfCard(card));
                default:
                    return null;
            }
        }

        if (event instanceof GameEventPlayerDamaged e) {
            if (!me.equals(e.target()) || e.amount() <= 0) {
                return null;
            }
            return e.source() == null
                    ? NeoText.get("alert.damaged", e.amount())
                    : NeoText.get("alert.damagedBy", e.amount(), nameOfCard(e.source()));
        }

        return null;
    }

    private boolean isMine(final CardView card) {
        final PlayerView me = localPlayer();
        return me != null
                && (me.equals(card.getController()) || me.equals(card.getOwner()));
    }

    private static String nameOfCard(final CardView card) {
        if (card == null) {
            return NeoText.get("alert.aCard");
        }
        final String n = forge.neo.card.CardText.nameOf(card);
        return n == null || n.isBlank() ? NeoText.get("alert.aCard") : n;
    }

    private void showCentralPrompt(final String okLabel, final String cancelLabel,
                                   final boolean okEnabled, final boolean cancelEnabled) {
        final PromptBanner banner = table.getPromptBanner();

        if (banner.isAlerting()) {
            return;
        }

        if (!forge.neo.NeoSettings.stackBanner()) {
            banner.hide();
            return;
        }

        final StackItemView top = topOfStack();

        if (top == null || isSelecting() || table.isModalShowing()) {
            banner.hide();
            return;
        }

        final String key = String.valueOf(top.getKey());
        if (key.equals(dismissedStackKey)) {
            banner.hide();
            return;
        }

        banner.setOnDismiss(() -> dismissedStackKey = key);
        banner.showInfo(top.getSourceCard(), stackText(top));
        table.requestLayout();

        if (forge.neo.NeoSettings.stackBannerBrief() && !key.equals(briefKey)) {
            briefKey = key;
            if (briefTimer != null) {
                briefTimer.stop();
            }
            final javafx.animation.PauseTransition wait =
                    new javafx.animation.PauseTransition(javafx.util.Duration.millis(BRIEF_MS));
            wait.setOnFinished(e -> {
                final StackItemView now = topOfStack();
                if (now != null && key.equals(String.valueOf(now.getKey())) && !banner.isAlerting()
                        && banner.isVisible()) {
                    dismissedStackKey = key;
                    table.flyBannerToStack();
                }
            });
            briefTimer = wait;
            wait.play();
        }
    }

    private static final double BRIEF_MS = Double.parseDouble(System.getProperty("neo.stackBannerBriefMs", "1300"));
    private String briefKey;
    private javafx.animation.PauseTransition briefTimer;

    private volatile String dismissedStackKey;

    private final java.util.concurrent.atomic.AtomicBoolean promptPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void refreshCentralPrompt() {
        final UiDispatcher ui = uiDispatcher();
        if (ui == null || table == null || !interactive()) {
            return;
        }

        if (!promptPending.compareAndSet(false, true)) {
            return;
        }
        ui.runLater(() -> {
            promptPending.set(false);
            showCentralPrompt(null, null, false, false);
        });
    }

    private StackItemView topOfStack() {
        final GameView gv = getGameView();
        if (gv == null || gv.getStack() == null) {
            return null;
        }
        for (final StackItemView item : gv.getStack()) {
            return item;
        }
        return null;
    }

    private String stackText(final StackItemView item) {

        int count = 1;
        final GameView gv = getGameView();
        final StackItemView top = topOfStack();
        if (item != null && top != null && top.getId() == item.getId() && gv != null
                && forge.neo.NeoSettings.groupStack()) {
            count = StackGroups.topRun(gv.getStack());
        }
        return TableScreen.bannerText(item, localPlayerView(), count, gv == null ? null : gv.getGame());
    }

    private static boolean isSilencedAiDeckWarning(final String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (!forge.neo.NeoSettings.getBool(forge.neo.NeoSettings.HIDE_AI_WARNING, false)) {
            return false;
        }
        return forge.neo.EnginePhrase.contains(message, "lblAICantPlayCards");
    }

    private static boolean isRoutinePriorityPrompt(final String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return true;
        }
        return forge.neo.EnginePhrase.startsWith(prompt, "lblPriority", ": ");
    }

    public void wireGameLogButton() {
        if (table == null) {
            return;
        }
        table.getLogButton().setOnAction(e -> {
            final GameView gv = getGameView();
            if (gv != null) {
                table.showGameLog(gv.getGameLog(), localPlayer(), LogCardLinks.lookupIn(gv));
            }
        });

        if (!isReplay()) {
            table.enableTurnStats(() -> TurnStats.read(this, localPlayer()));
        }
        
        table.getMacroRecordButton().setOnAction(e -> pressMacroRecord());
        
        table.getMacroRecordButton().setVisible(!isReplay());
        table.getMacroPlayButton().setOnAction(e -> pressMacroPlay());
    }

    public boolean pressMacroRecord() {
        if (table == null || isReplay() || table.isModalShowing()) {
            return false;
        }
        final Boolean recording = toggleMacroRecording();
        if (recording == null) {
            return false;
        }

        table.setMacroState(recording, !recording || hasMacro());
        table.getActionBar().setWarning(forge.neo.NeoText.get(recording
                ? "macro.recording" : "macro.stopped"), false);
        return true;
    }

    public boolean pressMacroPlay() {
        if (table == null || isReplay() || table.isModalShowing() || !playMacro()) {
            return false;
        }
        table.setMacroRecording(false);
        return true;
    }

    public boolean hasMacro() {
        final IGameController gc = getGameController();
        return gc != null && gc.macros() != null && gc.macros().hasRememberedActions();
    }

    private PlayerView localPlayer() {
        
        final PlayerView f = front;
        if (f != null && hotSeat()) {
            return live(f);
        }
        for (final PlayerView p : getLocalPlayers()) {
            return live(p);
        }
        return null;
    }

    private volatile PlayerView front;

    private final java.util.concurrent.atomic.AtomicInteger frontAsk =
            new java.util.concurrent.atomic.AtomicInteger();

    boolean hotSeat() {
        return mode == Mode.HUMAN && getLocalPlayers().size() > 1;
    }

    private boolean manyPeople() {
        return mode == Mode.HUMAN && getOriginalGameControllers().size() > 1;
    }

    @Override
    public void setGameController(final PlayerView player, final IGameController gameController) {
        super.setGameController(player, gameController);
        final PlayerView f = front;
        if (gameController == null && f != null && !isLocalPlayer(f)) {
            PlayerView back = getCurrentPlayer();
            if (back == null || !isLocalPlayer(back)) {
                back = null;
                for (final PlayerView p : getLocalPlayers()) {
                    back = p;
                    break;
                }
            }
            if (back != null) {
                trace("control de %s acabado: la mesa vuelve a %s", nameOf(f), nameOf(back));
                frontAsk.incrementAndGet();
                switchFront(back);
            }
        }
    }

    private boolean isMe(final PlayerView p) {
        if (p == null) {
            return false;
        }
        if (!hotSeat()) {
            return isLocalPlayer(p);
        }
        final PlayerView f = front;
        return f != null && f.getId() == p.getId();
    }

    void bringToFront(final PlayerView seat) {
        if (seat == null || !hotSeat()) {
            return;
        }
        final PlayerView f = front;
        if (f != null && f.getId() == seat.getId()) {
            return;
        }
        frontAsk.incrementAndGet();
        switchFront(seat);
    }

    private void switchFront(final PlayerView seat) {
        front = seat;
        final PlayerView who = live(seat);
        trace("hot seat: delante %s", nameOf(who));
        forge.neo.ui.CardZoom.setLocalViewers(java.util.Collections.singletonList(who));
        final TableBinder b = binder;
        if (b != null) {
            b.setSelf(who);
        }

        if (interactive() && manyPeople()) {
            final String name = nameOf(who);
            uiRunLater(() -> table.showCurtain(name));
        }
        pushToTable();

        refreshHighlights();
    }

    boolean isUiSetToSkipPhaseFor(final PlayerView asking, final PlayerView turn,
                                  final PhaseType phase) {
        if (phase == null) {
            return false;
        }
        if (!hotSeat() || asking == null) {
            return isUiSetToSkipPhase(turn, phase);
        }
        final boolean mine = turn == null || turn.getId() == asking.getId();
        return !(mine ? stops : theirStops).contains(phase);
    }

    public static NeoMatchUI of(final Object gui) {
        if (gui instanceof NeoMatchUI ui) {
            return ui;
        }
        return HotSeatGui.unwrap(gui) instanceof NeoMatchUI ui ? ui : null;
    }

    private final HotSeatGui.Host hotSeatHost = new HotSeatGui.Host() {
        @Override
        public void bringToFront(final PlayerView seat) {
            NeoMatchUI.this.bringToFront(seat);
        }

        @Override
        public boolean isUiSetToSkipPhaseFor(final PlayerView asking, final PlayerView turn,
                                             final PhaseType phase) {
            return NeoMatchUI.this.isUiSetToSkipPhaseFor(asking, turn, phase);
        }
    };

    @Override
    public boolean mayView(final CardView c) {
        final PlayerView f = front;

        if (c != null && c.getZone() == forge.game.zone.ZoneType.Hand) {
            final PlayerView me = f != null && hotSeat() ? live(f) : localPlayerView();
            if (NeoTeams.isAlly(me, c.getController())) {
                return true;
            }
        }
        if (f == null || !hotSeat() || c == null) {
            return super.mayView(c);
        }
        final IGameController gc = getGameController();
        if (gc != null && gc.mayLookAtAllCards()) {
            return true;
        }
        return c.canBeShownToAny(java.util.Collections.singletonList(live(f)));
    }

    @Override
    public boolean mayFlip(final CardView cv) {
        final boolean any = super.mayFlip(cv);
        final PlayerView f = front;
        if (!any || f == null || !hotSeat() || cv == null || !cv.isFaceDown()) {
            return any;
        }
        return cv.canFaceDownBeShownToAny(java.util.Collections.singletonList(live(f)));
    }

    public PlayerView localPlayerView() {
        return localPlayer();
    }

    public boolean isPlayableOutside(final CardView card) {
        if (card == null || !interactive() || finished.get()) {
            return false;
        }
        final PlayerView me = localPlayer();
        final var flash = me == null ? null : me.getFlashback();
        if (flash != null) {
            for (final CardView cv : flash) {

                if (cv != null && cv.getId() == card.getId()) {
                    return castableNow(card);
                }
            }
        }

        return isPlottedCastable(card) || isMayhemCastable(card);
    }

    private boolean castableNow(final CardView card) {
        final GameView gv = getGameView();
        final Game game = gv == null ? null : gv.getGame();
        if (game == null) {
            return true;
        }
        if (!(getGameController() instanceof forge.player.PlayerControllerHuman human)
                || !(human.getInputQueue().getInput()
                        instanceof forge.gamemodes.match.input.InputPassPriority)) {
            return false;
        }
        try {
            final forge.game.player.Player me = game.getPlayer(localPlayer());
            final forge.game.player.Player owner = game.getPlayer(card.getOwner());
            if (me == null || owner == null || card.getZone() == null) {
                return true;
            }
            for (final forge.game.card.Card c : owner.getCardsIn(card.getZone())) {
                if (c.getId() == card.getId()) {
                    final Thread was = probing;
                    probing = Thread.currentThread();
                    try {
                        return !c.getAllPossibleAbilities(me, true).isEmpty();
                    } finally {
                        probing = was;
                    }
                }
            }
            return true;
        } catch (final RuntimeException e) {

            return true;
        }
    }

    private volatile Thread probing;

    boolean isMayhemCastable(final CardView card) {
        final GameView gv = getGameView();
        if (gv == null || card.getZone() != ZoneType.Graveyard
                || !isMe(card.getOwner()) || isSelecting() || payingMana) {
            return false;
        }
        final Game game = gv.getGame();
        if (game == null) {
            return false;
        }
        try {
            final forge.game.player.Player owner = game.getPlayer(card.getOwner());
            if (owner == null) {
                return false;
            }
            forge.game.card.Card c = null;
            for (final forge.game.card.Card g : owner.getCardsIn(ZoneType.Graveyard)) {
                if (g.getId() == card.getId()) {
                    c = g;
                    break;
                }
            }
            if (!OutsideCasts.fromGraveyard(c)) {
                return false;
            }

            if (c.isInstant() || c.hasKeyword(forge.game.keyword.Keyword.FLASH)) {
                return true;
            }
            final PhaseType phase = gv.getPhase();
            return isMe(gv.getPlayerTurn()) && phase != null && phase.isMain()
                    && (gv.getStack() == null || gv.getStack().isEmpty());
        } catch (final RuntimeException e) {

            return false;
        }
    }

    private final Map<Integer, Integer> plotted = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile int plottedGame = Integer.MIN_VALUE;

    private void rememberPlotted(final GameEvent event) {

        if (event instanceof GameEventCardChangeZone move && move.card() != null
                && move.from() != null && move.from().zoneType() == ZoneType.Exile) {
            plotted.remove(move.card().getId());
            return;
        }
        if (!(event instanceof forge.game.event.GameEventCardPlotted ev) || ev.card() == null) {
            return;
        }
        final GameView gv = getGameView();
        if (gv == null) {
            return;
        }
        if (gv.getId() != plottedGame) {
            plotted.clear();
            plottedGame = gv.getId();
        }
        plotted.put(ev.card().getId(), gv.getTurn());
    }

    boolean isPlottedCastable(final CardView card) {
        final GameView gv = getGameView();
        if (gv == null || gv.getId() != plottedGame || plotted.isEmpty()
                || card.getZone() != ZoneType.Exile || !isMe(card.getOwner())
                || isSelecting() || payingMana) {
            return false;
        }
        final Integer turn = plotted.get(card.getId());
        final PhaseType phase = gv.getPhase();
        return turn != null && gv.getTurn() > turn
                && isMe(gv.getPlayerTurn())
                && phase != null && phase.isMain()
                && (gv.getStack() == null || gv.getStack().isEmpty());
    }

    private void uiRunLater(final Runnable r) {
        final UiDispatcher ui = uiDispatcher();
        if (ui != null && table != null) {
            ui.runLater(r);
        }
    }

    private static final boolean MANA_DEBUG = Boolean.getBoolean("neo.mana.debug");

    @Override
    public void updateManaPool(final Iterable<PlayerView> players) {
        if (MANA_DEBUG && players != null) {
            for (final PlayerView p : players) {
                System.out.printf(
                        "[mana] %s  W=%d U=%d B=%d R=%d G=%d | incoloro: ManaAtom(32)=%d "
                                + "MagicColor(0)=%d%n",
                        PlayerName.of(p),
                        p.getMana((byte) forge.card.mana.ManaAtom.WHITE),
                        p.getMana((byte) forge.card.mana.ManaAtom.BLUE),
                        p.getMana((byte) forge.card.mana.ManaAtom.BLACK),
                        p.getMana((byte) forge.card.mana.ManaAtom.RED),
                        p.getMana((byte) forge.card.mana.ManaAtom.GREEN),
                        p.getMana((byte) forge.card.mana.ManaAtom.COLORLESS),
                        p.getMana(forge.card.MagicColor.COLORLESS));
            }
        }
        final int now = localPoolSize();
        if (now > lastPoolSize) {
            lastManaSource = lastClicked;
        } else if (now == 0) {
            lastManaSource = null;
        }
        lastPoolSize = now;
        pushToTable();
    }

    @Override
    public void updateRevealedCards(final forge.trackable.TrackableCollection<CardView> collection) {
        final GameView gv = getGameView();
        if (gv != null && collection != null) {
            RevealLog.note(gv.getGameLog(), new java.util.ArrayList<>(collection));
        }
        super.updateRevealedCards(collection);
    }

    @Override
    public void showRevealedCards(final Iterable<CardView> cards) {
        if (!interactive() || cards == null) {
            return;
        }
        final List<CardView> list = new ArrayList<>();
        for (final CardView c : cards) {
            list.add(c);
        }
        if (list.isEmpty()) {
            return;
        }
        final PlayerView owner = list.get(0).getOwner();
        trace("revelado sin dialogo: %d cartas de %s", list.size(), nameOf(owner));
        uiRunLater(() -> table.showRevealed(owner, list));
    }

    @Override
    public void hideRevealedCards() {
        if (interactive()) {
            uiRunLater(() -> table.hideRevealed());
        }
    }

    @Override
    public void updateCurrentPlayer(final PlayerView player) {
        trace("jugador actual: %s", nameOf(player));

        final PlayerView f = front;
        if (player != null && hotSeat() && (f == null || f.getId() != player.getId())) {
            final int ask = frontAsk.incrementAndGet();
            uiRunLater(() -> {
                final javafx.animation.PauseTransition wait =
                        new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
                wait.setOnFinished(e -> {
                    final PlayerView now = getCurrentPlayer();
                    final PlayerView before = front;
                    if (ask == frontAsk.get() && now != null && now.getId() == player.getId()
                            && (before == null || before.getId() != player.getId())) {
                        switchFront(player);
                    }
                });
                wait.play();
            });
        }
        pushToTable();
    }

    private static final java.util.concurrent.atomic.AtomicInteger OPENED_VIEWS =
            new java.util.concurrent.atomic.AtomicInteger();

    public static int openedViewsForTest() {
        return OPENED_VIEWS.get();
    }

    @Override
    public void openView(final TrackableCollection<PlayerView> myPlayers) {
        OPENED_VIEWS.incrementAndGet();

        if (ending != Ending.REPLAY && getGameView() != null) {
            GameStats.attach(getGameView().getGame());
            
            HandLog.attach(getGameView().getGame());
        }

        forge.neo.card.AffectedBy.setGame(getGameView() == null ? null : getGameView().getGame());

        if (ending != Ending.REPLAY && getGameView() != null) {
            CircledColors.watch(getGameView().getGame());
        }

        if (ending == Ending.ASCENT && getGameView() != null) {
            
        }
        if (mode == Mode.HUMAN) {

            if (ending != Ending.REPLAY) {
                NeoGame.applyEnginePrefs();
                detail = DetailLog.start(getGameView());
            }
            rounds = new RoundCounter();

            if (ending == Ending.ASCENT) {
                forge.StaticData.instance().setMulliganRule(
                        FriendlyMulligan.apply(FriendlyMulligan.ASCENT_RULE));
                log("Ascent: mulligan %s (the setting says %s)", FriendlyMulligan.ASCENT_RULE,
                        forge.neo.NeoSettings.get(forge.neo.NeoSettings.MULLIGAN_RULE,
                                forge.neo.NeoSettings.MULLIGAN_RULE_DEFAULT));
            }

            actionableScans.set(true);
        }

        finished.set(false);
        closeNotified.set(false);

        macroAutoPassSaved = null;
        macroScopesSaved.clear();

        uiRunLater(() -> table.forgetEngineDialogs());
        gameOverShowing = false;
        closeDeferred = false;

        forge.neo.platform.NeoGuiBase.clearEngineCrash();

        if (myPlayers != null) {
            for (final PlayerView pv : myPlayers) {
                if (getGameController(pv) instanceof forge.player.PlayerControllerHuman human) {
                    ForwarderGuard.guard(human.getInputQueue());
                }
            }
        }
        lastPhase = null;
        lastTurn = -1;
        log("Game opened. Local seats: %s",
                myPlayers == null ? "(none, watching)" : myPlayers.toString());
        final TableBinder b = binder;
        if (b != null && myPlayers != null && !myPlayers.isEmpty()) {
            
            b.setSelf(myPlayers.iterator().next());
        }
        
        front = null;
        if (myPlayers != null && myPlayers.size() > 1 && mode == Mode.HUMAN) {
            front = myPlayers.iterator().next();
            log("Hot seat: %d people on this device", myPlayers.size());
        }

        forge.neo.ui.CardZoom.setLocalViewers(front != null
                ? java.util.Collections.singletonList(front) : getLocalPlayers());

        if (table != null) {
            table.setEnginePausable(!isNetGame());
        }
        pushToTable();

        if (myPlayers != null) {
            for (final PlayerView seat : myPlayers) {
                if (ManaColor.install(getGameController(seat))) {
                    log("Dual lands: will ask for the color (%s)",
                            PlayerName.of(seat));
                }
            }

            if (front != null) {
                for (final PlayerView seat : myPlayers) {
                    if (getGameController(seat) instanceof forge.player.PlayerControllerHuman pch
                            && pch.getPlayer() != null
                            && pch.getPlayer().getController() instanceof forge.player.PlayerControllerHuman seated) {
                        seated.setGui(HotSeatGui.wrap(this, hotSeatHost, seat));
                    }
                }
            }
        }

        if (isNetGame()) {
            installForRemoteSeats();
        }

        listenToTheEngine();

        discordSeats = myPlayers == null ? 0 : myPlayers.size();
        updateDiscord();

        if (isNetGame() && getGameView() != null) {
            try {
                seedYieldStateOnHost();
            } catch (final Exception e) {

                System.out.println("[neo] no se han podido enviar mis ajustes al anfitrion: " + e);
            }
        }

        final Runnable opened = onOpened;
        if (opened != null) {
            opened.run();
        }
    }

    private void installForRemoteSeats() {
        final GameView gv = getGameView();
        final Game game = gv == null ? null : gv.getGame();
        if (game == null) {
            return;
        }
        for (final forge.game.player.Player p : game.getPlayers()) {
            if (p.getController() instanceof forge.player.PlayerControllerHuman pch
                    && pch.getGui() instanceof forge.gamemodes.net.server.RemoteClientGuiGame
                    && ManaColor.install(pch)) {
                log("Dual lands: will ask for the color (%s, online)", p.getName());
            }
        }
    }

    private void listenToTheEngine() {
        final GameView view = getGameView();
        final Game game = view == null ? null : view.getGame();

        if (game == null || game == subscribedTo) {
            return;
        }
        subscribedTo = game;
        game.subscribeToEvents(new EngineEars(this));
        trace("enganchado al bus de la partida");
    }

    private volatile Game subscribedTo;

    private static final class EngineEars {
        private final NeoMatchUI ui;

        EngineEars(final NeoMatchUI ui) {
            this.ui = ui;
        }

        @com.google.common.eventbus.Subscribe
        public void receive(final GameEvent event) {
            try {
                ui.handleGameEvent(event);
            } catch (final RuntimeException e) {
                System.out.println("[neo] fallo atendiendo un evento del motor: " + e);
                e.printStackTrace();
            }
        }
    }

    private volatile Runnable onOpened;

    public void setOnOpened(final Runnable action) {
        this.onOpened = action;
    }

    private volatile Runnable onClosed;

    public void setOnClosed(final Runnable action) {
        this.onClosed = action;
    }

    @Override
    public void afterGameEnd() {
        super.afterGameEnd();
        finished.set(true);

        final boolean first = closeNotified.compareAndSet(false, true);
        if (Boolean.getBoolean("neo.trace.choices")) {
            System.out.println("[red] afterGameEnd: primero=" + first + " pantalla final=" + gameOverShowing
                    + " finishGame=" + finishGameCalled);
        }
        if (first && (ending == Ending.NET || ending == Ending.REPLAY) && gameOverShowing) {

            closeDeferred = true;
            return;
        }
        final Runnable closed = onClosed;
        if (closed != null && first) {
            closed.run();
        }
    }

    private volatile boolean gameOverShowing;

    private volatile boolean closeDeferred;

    private final AtomicBoolean closeNotified = new AtomicBoolean(false);

    private volatile boolean finishGameCalled;

    public boolean finishGameCalled() {
        return finishGameCalled;
    }

    @Override
    public void finishGame() {
        finishGameCalled = true;
        finished.set(true);
        final DetailLog detailLog = detail;
        detail = null;
        if (detailLog != null) {
            detailLog.end(getGameView());
        }

        uiRunLater(() -> table.forgetEngineDialogs());
        final GameView gv = getGameView();
        System.out.println();
        if (gv != null && gv.isGameOver()) {
            log("GAME OVER - winner: %s", gv.getWinningPlayerName());
        } else {
            log("GAME OVER");
        }

        if (gv != null && hasLocalPlayers()) {
            final StringBuilder seats = new StringBuilder();
            for (final PlayerView p : getLocalPlayers()) {
                final PlayerView now = live(p);
                seats.append(' ').append(p.getId()).append('=').append(nameOf(now))
                        .append(now.getHasLost() ? " (lost)" : "").append(now == p ? "" : " [live]");
            }
            log("local seats:%s | local won: %s (by the game: %s) | game %d", seats, localPlayerWon(),
                    localWonFromGame(), gv.getNumPlayedGamesInMatch() + 1);
        }

        if (exitAction != null) {
            if (ending == Ending.QUEST && exitAction == Exit.MENU && showGameOverScreen(false)) {
                return;
            }
            respondLater(() -> getGameController().nextGameDecision(NextGameDecision.QUIT));
            return;
        }

        if (showGameOverScreen(hotSeat() ? anyLocalPlayerWon() : localPlayerWon())) {
            return;
        }

        if (mode != Mode.OBSERVE) {

            final GameView now = getGameView();

            if ((Boolean.getBoolean("neo.adventure.auto") && ending == Ending.QUEST
                    || Boolean.getBoolean("neo.bo3.auto"))
                    && now != null && !now.isMatchOver()) {
                respondLater(() -> getGameController().nextGameDecision(NextGameDecision.CONTINUE));
                return;
            }
            respondLater(() -> getGameController().nextGameDecision(NextGameDecision.QUIT));
        }
    }

    private boolean showGameOverScreen(final boolean won) {
        if (mode != Mode.HUMAN || table == null) {
            return false;
        }
        final UiDispatcher ui = uiDispatcher();
        if (ui == null) {
            return false;
        }
        final GameView gv = getGameView();

        final int winningTeam = NeoTeams.winningTeam(gv);
        final String winner = gv == null ? null
                : winningTeam >= 0
                        ? forge.util.Localizer.getInstance().getMessage("lblTeam")
                                + " " + (winningTeam + 1)
                        : gv.getWinningPlayerName();
        final int turns = gv == null ? 0 : gv.getTurn();

        final boolean matchOver = ending == Ending.NET || ending == Ending.REPLAY
                || gv == null || gv.isMatchOver();
        final int totalGames = gv == null ? 0 : gv.getNumGamesInMatch();

        final int gameNumber = gv == null ? 0 : gv.getNumPlayedGamesInMatch() + 1;
        gameOverShowing = true;
        final boolean hotSeatOver = hotSeat();

        ui.runLater(() -> later(GAME_OVER_BREATH_MS, () -> showFaded(lookButtons(gv, new GameOverScreen(
                won, winner, turns, ending, matchOver, gameNumber, totalGames, hotSeatOver,
                decision -> {
                    table.getOverlay().hide();
                    gameOverShowing = false;

                    log("End of game %d of %d (%s): the player chooses %s", gameNumber, totalGames,
                            won ? "won" : "lost", decision);
                    if (closeDeferred) {

                        closeDeferred = false;
                        final Runnable closed = onClosed;
                        if (closed != null) {
                            closed.run();
                        }
                        return;
                    }
                    if (decision == NextGameDecision.CONTINUE) {

                        respondLater(() ->
                                getGameController().nextGameDecision(NextGameDecision.CONTINUE));
                        return;
                    }

                    exitAction = decision == NextGameDecision.NEW
                            ? Exit.RESTART : Exit.MENU;
                    respondLater(() ->
                            getGameController().nextGameDecision(NextGameDecision.QUIT));
                })))));
        return true;
    }

    private static final long GAME_OVER_BREATH_MS = Long.getLong("neo.gameOver.breathMs", 1500L);

    private static void later(final long ms, final Runnable what) {
        if (ms <= 0) {
            what.run();
            return;
        }
        final javafx.animation.PauseTransition wait =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(ms));
        wait.setOnFinished(e -> what.run());
        wait.play();
    }

    private void showFaded(final GameOverScreen screen) {
        table.getOverlay().show(screen);
        screen.setOpacity(0);
        final javafx.animation.FadeTransition fade =
                new javafx.animation.FadeTransition(javafx.util.Duration.millis(250), screen);
        fade.setToValue(1);
        fade.play();
    }

    private GameOverScreen lookButtons(final GameView gv, final GameOverScreen screen) {
        
        screen.withScore(BestOf.score(gv));

        screen.withReason(gv == null ? null : EndReason.text(gv.getGame()));
        screen.addLookButtons(
                gv == null || gv.getGameLog() == null ? null
                        : () -> table.showGameLog(gv.getGameLog(), localPlayer(), LogCardLinks.lookupIn(gv)),
                () -> table.getOverlay().setPeeking(true));
        
        final GameStats stats = gv == null ? null : GameStats.of(gv.getGame());
        final Runnable openStats = stats == null ? null : () -> {
            final forge.neo.ui.Overlay menu = table.getMenuOverlay();
            menu.setOnBackgroundClick(menu::hide);
            menu.show(new forge.neo.ui.GameStatsView(stats.summary(localPlayer()), menu::hide));
        };
        screen.addStatsButton(openStats);

        if (openStats != null && Boolean.getBoolean("neo.stats.open")) {
            stats.summary(localPlayer()).forEach(l -> System.out.println("[stats] " + l));
            openStats.run();
        }
        return screen;
    }

    private Boolean localWonFromGame() {
        final GameView gv = getGameView();
        final forge.game.Game game = gv == null ? null : gv.getGame();
        if (game == null) {
            return null;
        }
        boolean any = false;
        for (final forge.game.player.Player p : game.getRegisteredPlayers()) {
            if (p.getController() instanceof forge.player.PlayerControllerHuman h && h.getGui() == this) {
                any = true;
                if (!p.hasLost()) {
                    return Boolean.TRUE;
                }
            }
        }
        return any ? Boolean.FALSE : null;
    }

    private boolean anyLocalPlayerWon() {
        final GameView gv = getGameView();
        final String winner = gv == null ? null : gv.getWinningPlayerName();
        if (winner == null) {
            return false;
        }
        for (final PlayerView p : getLocalPlayers()) {
            if (winner.equals(live(p).getName())) {
                return true;
            }
        }
        return localPlayerWon();
    }

    private boolean localPlayerWon() {

        final Boolean fromGame = localWonFromGame();
        if (fromGame != null && (fromGame || NeoTeams.winningTeam(getGameView()) < 0)) {
            return fromGame;
        }
        if (!hasLocalPlayers()) {
            return false;
        }

        final GameView over = getGameView();
        final String winnerName = over == null ? null : over.getWinningPlayerName();
        if (fromGame == null && winnerName != null && NeoTeams.winningTeam(over) < 0) {
            for (final PlayerView p : getLocalPlayers()) {
                if (winnerName.equals(live(p).getName())) {
                    return true;
                }
            }
            return false;
        }
        for (final PlayerView p : getLocalPlayers()) {
            if (!live(p).getHasLost()) {
                return true;
            }
        }

        final GameView gv = getGameView();
        if (NeoTeams.winningTeam(gv) >= 0) {
            for (final PlayerView p : getLocalPlayers()) {
                for (final PlayerView other : gv.getPlayers()) {
                    if (NeoTeams.isAlly(live(p), other) && !other.getHasLost()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void replayButtons(final String okLabel, final String cancelLabel,
                               final boolean okEnabled, final boolean cancelEnabled) {
        final UiDispatcher ui = uiDispatcher();
        if (ui == null) {
            return;
        }
        final boolean showCancel = cancelLabel != null && !cancelLabel.isBlank();
        ui.runLater(() -> {
            table.getActionBar().setButtons(ReplayText.label(okLabel),
                    showCancel ? ReplayText.label(cancelLabel) : " ", okEnabled, cancelEnabled && showCancel);
            table.getActionBar().setPlayerChoices(null);
            table.getActionBar().setResolveAll(null, null, null);
            table.getMacroRecordButton().setVisible(false);
            table.getActionBar().setOnOk(() -> respondLater(() -> getGameController().selectButtonOk()));
            table.getActionBar().setOnCancel(() -> respondLater(() -> getGameController().selectButtonCancel()));
        });
    }

    @Override
    public void showPromptMessage(final PlayerView playerView, final String message, final CardView card) {
        trace("prompt[%s]: %s", nameOf(playerView), message);
        if (isReplay()) {

            final TableBinder rb = binder;
            if (rb != null) {
                rb.setPrompt(ReplayText.prompt(message));
            }
            return;
        }
        cancelStaleAwaitIfGuest();

        if (isStaleAttackPrompt(message)) {
            trace("prompt descartado (el 'null' de declarar atacantes)");
            return;
        }
        lastPrompt = message == null ? "" : message;
        lastPreparedPrompt = null;
        final DetailLog detailLog = detail;
        if (detailLog != null) {
            detailLog.sync(getGameView());
            detailLog.prompt(playerView, message, card);
        }
        tapToPayStep = TapToPayStep.parse(message);

        if (interactive() && offerCommanderZone(message)) {
            final TableBinder cb = binder;
            if (cb != null) {
                cb.setPrompt(NeoText.get("commander.leftGame"));
            }
            return;
        }
        
        if (interactive() && offerCommanderReturn(message)) {
            return;
        }

        if (interactive() && offerTopOrBottom(message, card)) {
            return;
        }
        final TableBinder b = binder;
        if (b != null) {

            final String asked = PlayerPick.explainChooser(message, NeoText.get(
                    getGameController() instanceof forge.player.PlayerControllerHuman
                            ? "pick.chooser" : "pick.chooser.portrait"));
            final String tapToPay = tapToPayPrompt(tapToPayStep);
            final String prepared = forge.neo.EngineText.prompt(
                    isRoutinePriorityPrompt(message) ? routinePrompt() : frontLoadInstruction(asked))
                    + proliferateHint(message);
            lastPreparedPrompt = isRoutinePriorityPrompt(message) ? null : prepared;
            final String shown = tapToPay != null ? tapToPay : prepared + pickedSuffix();
            if (!shown.equals(message)) {
                trace("prompt reordenado: %s", shown.replace(System.lineSeparator(), " | "));
            }
            b.setPrompt(shown);
        }
        refreshCentralPrompt();
    }

    private static String proliferateHint(final String message) {
        return isProliferatePrompt(message) ? System.lineSeparator() + NeoText.get("prolif.hint") : "";
    }

    public static boolean isProliferatePrompt(final String message) {
        if (message == null) {
            return false;
        }
        final String asked = forge.util.Localizer.getInstance().getMessage("lblChooseProliferateTarget");
        return asked != null && !asked.isEmpty() && message.contains(asked);
    }

    private String routinePrompt() {
        final forge.game.GameView gv = getGameView();
        if (gv == null) {
            return "";
        }
        final PlayerView active = gv.getPlayerTurn();
        final boolean mine = active != null && isMe(active);

        final StringBuilder sb = new StringBuilder();
        sb.append(mine ? NeoText.get("turn.yours")
                : NeoText.get("prompt.plays", nameOf(active)));
        sb.append(System.lineSeparator());
        sb.append(forge.neo.ui.TurnBanner.turnText(gv.getTurn(), shownRound(gv)));
        final String phase = forge.neo.ui.PhaseRail.nameOf(gv.getPhase());
        if (!phase.isEmpty()) {
            sb.append("  ·  ").append(phase);
        }
        final int onStack = gv.getStack() == null ? 0 : gv.getStack().size();
        if (onStack > 0) {
            sb.append(System.lineSeparator())
              .append(NeoText.get("prompt.onStack", onStack));
        }
        return sb.toString();
    }

    private static final String COMMANDER_ZONE_MARK = ": If a commander is in a graveyard or in exile";

    private boolean offerCommanderZone(final String message) {
        if (message == null || table == null) {
            return false;
        }
        final int at = message.indexOf(COMMANDER_ZONE_MARK);
        if (at < 0) {
            return false;
        }
        final String name = message.substring(0, at).trim();
        final CardView commander = findCommanderAway(name);
        trace("comandante fuera de la partida: %s -> %s", name,
                commander == null ? "(NO ENCONTRADO)"
                        : commander.getName() + " en " + commander.getZone());
        final ZoneType zone = commander == null ? null : commander.getZone();
        final String where = NeoText.get(zone == ZoneType.Graveyard
                ? "commander.inGraveyard" : "commander.inExile");

        final UiDispatcher ui = uiDispatcher();
        if (ui == null) {
            return false;
        }
        final AtomicBoolean answered = new AtomicBoolean();
        ui.runLater(() -> {
            final CardPromptDialog dialog = new CardPromptDialog(
                    commander,
                    name.isEmpty() ? NeoText.get("commander.yours") : name,
                    NeoText.get("commander.ask", where),
                    List.of(NeoText.get("commander.toZone"),
                            NeoText.get("commander.leaveIt", where)),
                    0,
                    table.zoomCardWidth() * 0.62,
                    index -> {
                        
                        if (!answered.compareAndSet(false, true)) {
                            return;
                        }
                        table.getOverlay().hide();
                        final boolean toCommand = index != null && index == 0;
                        respondLater(() -> {
                            if (toCommand) {
                                getGameController().selectButtonOk();
                            } else {
                                getGameController().selectButtonCancel();
                            }
                        });
                    });
            
            table.getPromptBanner().hide();
            table.getOverlay().show(dialog);
            table.requestLayout();
        });
        return true;
    }

    private static final String COMMANDER_RETURN_MARK = "would be put into its owner's hand or library";

    private final AtomicBoolean commanderReturnUp = new AtomicBoolean();

    private boolean offerCommanderReturn(final String message) {
        if (message == null || table == null || !message.contains(COMMANDER_RETURN_MARK)) {
            return false;
        }
        if (!commanderReturnUp.compareAndSet(false, true)) {
            return true;
        }
        final CardView commander = commanderNamedIn(message);
        final UiDispatcher ui = uiDispatcher();
        if (ui == null) {
            commanderReturnUp.set(false);
            return false;
        }
        final TableBinder b = binder;
        if (b != null) {
            b.setPrompt(NeoText.get("commander.returnPrompt"));
        }
        final AtomicBoolean answered = new AtomicBoolean();
        ui.runLater(() -> {
            final CardPromptDialog dialog = new CardPromptDialog(
                    commander,
                    commander == null ? NeoText.get("commander.yours") : null,
                    NeoText.get("commander.returnAsk"),
                    List.of(NeoText.get("commander.toZone"), NeoText.get("commander.letItGo")),
                    0,
                    table.zoomCardWidth() * 0.62,
                    index -> {
                        if (!answered.compareAndSet(false, true)) {
                            return;
                        }
                        table.getOverlay().hide();
                        commanderReturnUp.set(false);
                        final boolean toCommand = index != null && index == 0;
                        respondLater(() -> {
                            if (toCommand) {
                                getGameController().selectButtonOk();
                            } else {
                                getGameController().selectButtonCancel();
                            }
                        });
                    });
            table.getPromptBanner().hide();
            table.getOverlay().show(dialog);
            table.requestLayout();
        });
        return true;
    }

    private CardView commanderNamedIn(final String message) {
        final GameView gv = getGameView();
        if (gv == null || gv.getPlayers() == null) {
            return null;
        }
        CardView away = null;
        for (final PlayerView p : gv.getPlayers()) {
            if (p.getCommanders() == null) {
                continue;
            }
            for (final CardView c : p.getCommanders()) {
                if (c.getName() != null && !c.getName().isEmpty() && message.contains(c.getName())) {
                    return c;
                }
                if (away == null && isMe(p) && c.getZone() != ZoneType.Command) {
                    away = c;
                }
            }
        }
        return away;
    }

    private volatile String lastPrompt = "";
    
    private volatile String lastPreparedPrompt;

    private volatile TapToPayStep.Step tapToPayStep;

    private volatile CardView pendingManaClick;

    private static String tapToPayPrompt(final TapToPayStep.Step step) {
        if (step == null) {
            return null;
        }

        final StringBuilder sb = new StringBuilder(NeoText.get("taptopay." + step.kind))
                .append(' ').append(NeoText.get("taptopay.rest", step.left));
        if (!step.head.isEmpty()) {
            sb.append(System.lineSeparator()).append(forge.neo.EngineText.prompt(step.head));
        }
        return sb.toString();
    }

    private boolean tapToPayPending() {
        final TapToPayStep.Step step = tapToPayStep;
        return step != null && step.somethingLeft() && TapToPayStep.isActive(getGameController());
    }

    private boolean landPaysTheRest(final CardView card) {
        if (!TapToPayStep.isLandThatPays(card, isMe(card.getController())) || !tapToPayPending()) {
            return false;
        }
        clickTrace(card, "pagar el resto con mana");
        pendingManaClick = card;
        respondLater(() -> getGameController().selectButtonOk());
        return true;
    }

    private final AtomicBoolean noPickWarned = new AtomicBoolean();

    private boolean warnNothingPicked() {
        if (getSelectionMax() <= 0 || getSelectionMin() != 0) {
            return false;
        }
        if (pickedSoFar() > 0 || lastPrompt.contains(TARGETED_MARK)) {
            return false;
        }
        if (table == null || !noPickWarned.compareAndSet(false, true)) {
            return false;
        }
        final AtomicBoolean answered = new AtomicBoolean();
        table.getOverlay().show(new ConfirmDialog(
                NeoText.get("pick.noneTitle"),
                NeoText.get("pick.noneAsk"),
                List.of(NeoText.get("pick.noneBack"), NeoText.get("pick.noneAnyway")),
                0,
                index -> {
                    if (!answered.compareAndSet(false, true)) {
                        return;
                    }
                    table.getOverlay().hide();
                    if (index != null && index == 1) {
                        respondLater(() -> getGameController().selectButtonOk());
                    }
                }));
        table.requestLayout();
        return true;
    }

    private final AtomicInteger askGen = new AtomicInteger();
    private volatile int okSentAtGen = -1;
    private volatile long okSentAt;
    private static final long OK_WAIT_MS = 3000;

    private boolean okAwaitingEngine() {
        return okSentAtGen == askGen.get()
                && System.currentTimeMillis() - okSentAt < OK_WAIT_MS;
    }

    private static boolean isEndTurnLabel(final String label) {
        return label != null && label.equals(forge.util.Localizer.getInstance().getMessage("lblEndTurn"));
    }

    private boolean confirmEndingTurn(final Runnable go) {
        if (table == null || !interactive() || finished.get()) {
            return false;
        }
        final int mode = forge.neo.NeoSettings.confirmPhaseMode();
        final GameView gv = getGameView();
        if (mode <= 0 || gv == null || !isMe(gv.getPlayerTurn())) {
            return false;
        }
        final PhaseType phase = gv.getPhase();
        if (phase == null || !phase.isBefore(PhaseType.END_OF_TURN)
                || (phase == PhaseType.MAIN2 && mode < 2)) {
            return false;
        }
        if (payingMana || isSelecting() || getSelectionMax() > 0 || topOfStack() != null) {
            return false;
        }
        final String prompt = lastPrompt;
        if (prompt == null || prompt.isBlank() || !isRoutinePriorityPrompt(prompt)
                || table.getOverlay().isShowing()) {
            return false;
        }

        String next = null;
        boolean mine = false;
        try {
            final forge.game.Game game = gv.getGame();
            final forge.game.player.Player p = game == null ? null : game.getPhaseHandler().getNextTurn();
            if (p != null) {
                mine = isMe(p.getView());
                next = p.getName();
            }
        } catch (final RuntimeException e) {
            next = null;
        }
        final String detail = mine ? NeoText.get("phase.ask.endTurnDetailYou")
                : next == null ? NeoText.get("phase.ask.endTurnDetailNoName")
                : NeoText.get("phase.ask.endTurnDetail", next);
        final AtomicBoolean answered = new AtomicBoolean();
        final ConfirmDialog dialog = new ConfirmDialog(
                NeoText.get("phase.ask.endTurnTitle"), detail,
                List.of(NeoText.get("phase.ask.stay"), NeoText.get("phase.ask.endGo")),
                0,
                index -> {
                    if (!answered.compareAndSet(false, true)) {
                        return;
                    }
                    table.getOverlay().hide();
                    if (index != null && index == 1) {
                        go.run();
                    }
                });
        dialog.getStyleClass().add("phase-ask");
        
        if (dialog.option(1) != null) {
            dialog.option(1).getStyleClass().add("phase-ask-go");
        }
        table.getOverlay().show(dialog);
        table.requestLayout();
        return true;
    }

    private boolean confirmLeavingMain() {
        if (table == null || !interactive() || finished.get()) {
            return false;
        }
        final int mode = forge.neo.NeoSettings.confirmPhaseMode();
        if (mode <= 0) {
            return false;
        }
        final GameView gv = getGameView();
        if (gv == null) {
            return false;
        }
        final PhaseType phase = gv.getPhase();
        final boolean toCombat = phase == PhaseType.MAIN1;
        if (!toCombat && (phase != PhaseType.MAIN2 || mode < 2)) {
            return false;
        }
        if (!isMe(gv.getPlayerTurn())) {
            return false;
        }
        if (payingMana || isSelecting() || getSelectionMax() > 0 || topOfStack() != null) {
            return false;
        }

        if (getGameController() instanceof forge.player.PlayerControllerHuman human
                && MainPhaseActivity.playedSomething(human.getPlayer())) {
            return false;
        }
        final String prompt = lastPrompt;
        if (prompt == null || prompt.isBlank() || !isRoutinePriorityPrompt(prompt)) {
            return false;
        }

        if (table.getOverlay().isShowing()) {
            return false;
        }
        final AtomicBoolean answered = new AtomicBoolean();
        final ConfirmDialog dialog = new ConfirmDialog(
                NeoText.get(toCombat ? "phase.ask.combatTitle" : "phase.ask.endTitle"),
                NeoText.get(toCombat ? "phase.ask.combatDetail" : "phase.ask.endDetail"),
                List.of(NeoText.get("phase.ask.stay"),
                        NeoText.get(toCombat ? "phase.ask.combatGo" : "phase.ask.endGo")),
                0,
                index -> {
                    if (!answered.compareAndSet(false, true)) {
                        return;
                    }
                    table.getOverlay().hide();
                    if (index != null && index == 1) {
                        respondLater(() -> getGameController().selectButtonOk());
                    }
                });
        dialog.getStyleClass().add("phase-ask");

        if (dialog.option(1) != null) {
            dialog.option(1).getStyleClass().add("phase-ask-go");
        }
        table.getOverlay().show(dialog);
        table.requestLayout();
        return true;
    }

    private boolean confirmNoBlockers() {
        if (table == null || !interactive() || finished.get()) {
            return false;
        }
        if (table.getOverlay().isShowing() || !BlockGuard.shouldAsk(getGameController())) {
            return false;
        }
        final AtomicBoolean answered = new AtomicBoolean();
        final ConfirmDialog dialog = new ConfirmDialog(
                NeoText.get("block.ask.title"),
                NeoText.get("block.ask.detail"),
                List.of(NeoText.get("block.ask.stay"), NeoText.get("block.ask.go")),
                0,
                index -> {
                    if (!answered.compareAndSet(false, true)) {
                        return;
                    }
                    table.getOverlay().hide();
                    if (index != null && index == 1) {
                        respondLater(() -> getGameController().selectButtonOk());
                    }
                });
        dialog.getStyleClass().add("phase-ask");
        if (dialog.option(1) != null) {
            dialog.option(1).getStyleClass().add("phase-ask-go");
        }
        table.getOverlay().show(dialog);
        table.requestLayout();
        return true;
    }

    private static final String TARGETED_MARK = "Targeted:";

    private boolean offerTopOrBottom(final String message, final CardView card) {
        if (message == null || card == null || table == null) {
            return false;
        }
        final boolean scry =
                forge.neo.EnginePhrase.contains(message, "lblPutCardOnTopOrBottomLibrary");
        final boolean surveil = !scry
                && forge.neo.EnginePhrase.contains(message, "lblPutCardsOnTheTopLibraryOrGraveyard");
        if (!scry && !surveil) {
            return false;
        }

        if (!topOrBottomUp.compareAndSet(false, true)) {
            return true;
        }
        final String ask = NeoText.get(scry ? "scry.ask" : "surveil.ask");
        final TableBinder b = binder;
        if (b != null) {
            b.setPrompt(ask);
        }

        final UiDispatcher ui = uiDispatcher();
        if (ui == null) {
            topOrBottomUp.set(false);
            return false;
        }
        final AtomicBoolean answered = new AtomicBoolean();
        ui.runLater(() -> {
            final CardPromptDialog dialog = new CardPromptDialog(
                    card,
                    null,
                    ask,
                    List.of(NeoText.get("scry.top"),
                            NeoText.get(scry ? "scry.bottom" : "surveil.grave")),
                    0,
                    table.zoomCardWidth() * 0.62,
                    index -> {
                        if (!answered.compareAndSet(false, true)) {
                            return;
                        }
                        table.getOverlay().hide();
                        topOrBottomUp.set(false);
                        final boolean top = index != null && index == 0;
                        respondLater(() -> {
                            if (top) {
                                getGameController().selectButtonOk();
                            } else {
                                getGameController().selectButtonCancel();
                            }
                        });
                    });
            
            table.getPromptBanner().hide();
            table.getOverlay().show(dialog);
            table.requestLayout();
        });
        return true;
    }

    private final AtomicBoolean topOrBottomUp = new AtomicBoolean();

    private CardView findCommanderAway(final String name) {
        final GameView gv = getGameView();
        if (gv == null || gv.getPlayers() == null) {
            return null;
        }

        for (final PlayerView p : gv.getPlayers()) {
            for (final ZoneType zone : AWAY_ZONES) {
                final CardView hit = byName(p.getCards(zone), name);
                if (hit != null) {
                    return hit;
                }
            }
        }

        for (final PlayerView p : gv.getPlayers()) {
            final CardView hit = byName(p.getCommanders(), name);
            if (hit != null) {
                return hit;
            }
        }

        System.err.println("[neo] no encuentro el comandante \"" + name
                + "\" fuera de la partida; el dialogo saldra sin carta");
        return null;
    }

    private static final ZoneType[] AWAY_ZONES = {
        ZoneType.Exile, ZoneType.Graveyard, ZoneType.Command,
    };

    private static CardView byName(final Iterable<CardView> cards, final String name) {
        if (cards == null || name == null || name.isEmpty()) {
            return null;
        }
        for (final CardView c : cards) {
            if (c == null) {
                continue;
            }

            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }

    private volatile Object selectionFor;

    private volatile Object[] earlyButtons;

    private static final int EARLY_BUTTONS_WAIT_MS = 300;

    private static final class GuestAsk {
    }

    private static Object newGuestAsk() {
        return new GuestAsk();
    }

    private static final ThreadLocal<Boolean> FLUSHING_EARLY = ThreadLocal.withInitial(() -> false);

    private void flushEarlyButtons(final Object asked) {
        final Object[] b;
        synchronized (this) {
            b = earlyButtons;
            if (b == null || b[0] != asked) {
                return;
            }
            earlyButtons = null;
        }
        FLUSHING_EARLY.set(true);
        try {
            updateButtons((PlayerView) b[1], (String) b[2], (String) b[3],
                    (Boolean) b[4], (Boolean) b[5], (Boolean) b[6]);
        } finally {
            FLUSHING_EARLY.set(false);
        }
    }

    private boolean isNetGuest() {
        final GameView gv = getGameView();
        return gv != null && gv.getGame() == null;
    }

    @Override
    public void updateButtons(final PlayerView owner, final String okLabel, final String cancelLabel,
                              final boolean okEnabled, final boolean cancelEnabled, final boolean focusOk) {
        if (isReplay()) {
            replayButtons(okLabel, cancelLabel, okEnabled, cancelEnabled);
            return;
        }
        if (Boolean.getBoolean("neo.trace.choices")) {
            System.out.println("[botones] " + okLabel + "/" + cancelLabel + " " + okEnabled + "/" + cancelEnabled
                    + " de " + (owner == null ? "?" : owner.getName() + "#" + owner.getId()));
        }

        final Object pending = pendingInput(getGameController());
        final Object asked = pending == null && isNetGuest() ? newGuestAsk() : pending;
        if (!FLUSHING_EARLY.get() && (asked instanceof GuestAsk
                || asked instanceof forge.gamemodes.match.input.InputBase && asked != selectionFor)) {
            synchronized (this) {
                earlyButtons = new Object[] {asked, owner, okLabel, cancelLabel, okEnabled, cancelEnabled, focusOk};
            }
            forge.util.ThreadUtil.delay(EARLY_BUTTONS_WAIT_MS, () -> flushEarlyButtons(asked));
            return;
        }
        cancelStaleAwaitIfGuest();
        final DetailLog detailLog = detail;
        if (detailLog != null) {
            detailLog.buttons(okLabel, cancelLabel, okEnabled, cancelEnabled);
        }

        final GameView pruneView = getGameView();
        if (pruneView != null) {
            LkiPruner.maybePrune(pruneView.getGame(), this);
        }

        final CardView blockWith = pendingBlocker;
        if (blockWith != null) {
            if (forge.neo.match.BlockIntent.isDeclaringBlocks(getGameController())) {
                final CardView against = pendingBlockAttacker;
                pendingBlocker = null;
                pendingBlockAttacker = null;
                lastClicked = blockWith;
                respondLater(() -> {

                    if (against != null) {
                        getGameController().selectCard(against, null, null);
                    }
                    getGameController().selectCard(blockWith, null, null);
                });
            } else if (pruneView != null && pruneView.getPhase() != PhaseType.COMBAT_DECLARE_ATTACKERS
                    && pruneView.getPhase() != PhaseType.COMBAT_DECLARE_BLOCKERS) {
                pendingBlocker = null;
                pendingBlockAttacker = null;
            }
        }

        final CardView auraOn = pendingAuraTarget;
        if (auraOn != null) {
            final Object in = pendingInput(getGameController());

            if (System.currentTimeMillis() > pendingAuraUntil
                    || in instanceof forge.gamemodes.match.input.InputPassPriority
                    && System.currentTimeMillis() > pendingAuraUntil - AURA_DROP_WINDOW_MS + 1000) {
                pendingAuraTarget = null;
            } else if (in instanceof forge.gamemodes.match.input.InputSelectTargets
                    && AuraDrop.contains(selectables, auraOn)) {
                pendingAuraTarget = null;
                trace("aura soltada: objetivo %s", auraOn.getName());
                respondLater(() -> getGameController().selectCard(auraOn, null, null));
            }
        }

        final IGameController macroGc = getGameController();
        
        if (macroAutoPassSaved != null && macroGc != null && macroGc.macros() != null
                && !macroGc.macros().isRecording() && !macroGc.macros().isReplaying()) {
            respondLater(() -> syncMacroAutoPass(macroGc));
        }
        if (!macroButtonsOverride && macroGc != null && macroGc.macros() != null
                && macroGc.macros().isReplaying()) {
            macroButtonsOverride = true;
            try {
                updateButtons(owner, "", forge.util.Localizer.getInstance().getMessage("lblCancel"),
                        false, true, false);
            } finally {
                macroButtonsOverride = false;
            }
            return;
        }
        
        askGen.incrementAndGet();

        pushToTable();

        setPayingMana(isAutoPayLabel(okLabel));

        final CardView manaClick = pendingManaClick;
        if (manaClick != null && (payingMana || !TapToPayStep.isActive(getGameController()))) {
            pendingManaClick = null;
            if (payingMana && interactive()) {
                trace("pago: giro la tierra clicada al elegir que girar: %s", manaClick);
                respondLater(() -> getGameController().selectCard(manaClick, null, null));
                return;
            }
        }
        
        final String shownOk = tapToPayPending()
                ? NeoText.get("taptopay.ok") : okLabel;
        if (interactive()) {

            final boolean optionalPayment = payingMana && isOptionalPayment();
            if (optionalPayment) {
                noteOptionalPayment();
            }
            final Object payInput = payingMana ? pendingInput(getGameController()) : null;
            if (payingMana && okEnabled && autoPayMana && !optionalPayment
                    && (payInput == null || payInput != autoPayHeldFor)
                    && autoPayPresses.incrementAndGet() <= MAX_AUTO_PAY_PRESSES) {
                
                heldButtons = new Object[] {owner, okLabel, cancelLabel, okEnabled, cancelEnabled, focusOk};

                setActionableScans(false);
                pressAutoInAMoment();
                return;
            }

            if (payingMana && !okEnabled && autoPayMana && !optionalPayment && tryBlindSource()) {
                return;
            }

            setActionableScans(true);

            noteAutoPayState(okEnabled);

            if (okEnabled && !payingMana) {
                
            }

            final List<forge.neo.ui.ActionBar.PlayerChoice> playerChoices = playerChoices();
            
            final int resolveAll = resolveAllCount(okEnabled);
            
            final int yesToAll = yesToAllCount(okEnabled);

            final UiDispatcher ui = uiDispatcher();
            if (ui != null) {
                ui.runLater(() -> {
                    table.getActionBar().setButtons(shownOk, cancelLabel, okEnabled, cancelEnabled);
                    table.getActionBar().setPlayerChoices(playerChoices);
                    table.getActionBar().setResolveAll(resolveAll > 0
                                    ? NeoText.get("stack.resolveAll", resolveAll) : null,
                            NeoText.get("stack.resolveAll.tip"), this::resolveStack);
                    table.getActionBar().setYesToAll(yesToAll > 0
                                    ? NeoText.get("trigger.yesToAll", yesToAll) : null,
                            NeoText.get("trigger.yesToAll.tip"), this::yesToAll);
                    table.getActionBar().setOnOk(() -> {

                        if (okAwaitingEngine()) {

                            if (Boolean.getBoolean("neo.okhold.debug")) {
                                System.out.println("[okhold] OK ignorado, el motor aun no ha vuelto"
                                        + " a preguntar | stack vacio en la vista: " + (topOfStack() == null));
                            }
                            return;
                        }
                        
                        if (warnNothingPicked()) {
                            return;
                        }
                        
                        if (confirmLeavingMain()) {
                            return;
                        }
                        
                        if (confirmNoBlockers()) {
                            return;
                        }
                        
                        if (confirmAutoSacrifice()) {
                            return;
                        }

                        if (!payingMana && !isSelecting() && getSelectionMax() <= 0
                                && isRoutinePriorityPrompt(lastPrompt)) {
                            okSentAtGen = askGen.get();
                            okSentAt = System.currentTimeMillis();
                        }
                        didDetail("pressed OK (" + shownOk + ")");
                        respondLater(() -> getGameController().selectButtonOk());
                    });
                    table.getActionBar().setOnCancel(() -> {
                        
                        if (isEndTurnLabel(cancelLabel) && confirmEndingTurn(() -> {
                            didDetail("pressed Cancel (" + cancelLabel + "), confirmed");
                            respondLater(() -> getGameController().selectButtonCancel());
                        })) {
                            return;
                        }
                        didDetail("pressed Cancel (" + cancelLabel + ")");
                        respondLater(() -> getGameController().selectButtonCancel());
                    });

                    showCentralPrompt(shownOk, cancelLabel, okEnabled, cancelEnabled);
                    table.requestLayout();

                    table.setGroupingEnabled(!isSelecting());
                    table.setSelectable(this::isSelectable);

                    table.setPlayersSelectable(true);

                    table.setMacroState(isMacroRecording(), hasMacro());
                });
            }
            return;
        }

        if (mode != Mode.AUTO_PLAY || finished.get()) {
            return;
        }

        

        final java.util.function.BooleanSupplier hold = autoPlayHold;
        if (hold != null && hold.getAsBoolean()) {
            return;
        }

        if (okEnabled) {

            pilotRespond(this::autoPressOk);
            return;
        }

        if (payingMana && autoPayMana && tryBlindSource()) {
            return;
        }

        if (isSelecting()) {

            if (pilotClickInFlight()) {
                trace("eligiendo carta: espero a que el motor marque la anterior");
                return;
            }
            final CardView next = nextUnpickedSelectable();
            if (next != null) {
                trace("eligiendo carta: %s", next);
                pilotClicks.put(next.getId(), System.currentTimeMillis());
                pilotRespond(() -> {
                    final IGameController gc = getGameController();
                    try {
                        gc.selectCard(next, null, null);
                    } finally {

                        if (gc instanceof forge.player.PlayerControllerHuman) {
                            pilotClicks.remove(next.getId());
                        }
                    }
                });
                return;
            }
        }

        if (getSelectionMax() > 0 && countPickedSelectables() < getSelectionMin()
                && selectables.isEmpty()) {

            final List<PlayerPick.Choice> valid = PlayerPick.pending(getGameController());
            PlayerView who = localPlayer();
            if (valid.isEmpty() && who != null && who.getOpponents() != null) {

                for (final PlayerView o : who.getOpponents()) {
                    if (!o.getHasLost()) {
                        who = o;
                        break;
                    }
                }
            }
            if (!valid.isEmpty()) {
                PlayerView first = null;
                boolean localValid = false;
                for (final PlayerPick.Choice c : valid) {
                    if (first == null) {
                        first = c.player();
                    }
                    localValid |= c.player().equals(who);
                }
                if (!localValid) {
                    who = first;
                }
            }
            if (who != null) {
                trace("eligiendo jugador: %s", nameOf(who));
                final PlayerView chosen = who;
                pilotRespond(() -> getGameController().selectPlayer(chosen, null));
                return;
            }
        }

        if (cancelEnabled) {
            pilotRespond(() -> getGameController().selectButtonCancel());
        }
    }

    private static final int MAX_AUTO_PAY_PRESSES = 4;

    private volatile boolean payingMana;

    private final AtomicInteger autoPayPresses = new AtomicInteger();

    private static boolean isAutoPayLabel(final String okLabel) {
        return forge.neo.EnginePhrase.equalsAny(okLabel, "lblAuto");
    }

    private void setPayingMana(final boolean on) {

        autoPayGen.incrementAndGet();
        if (on && !payingMana) {
            
            autoPayPresses.set(0);
            blindTries.set(0);
            blindTried.clear();
        }
        if (!on) {
            
            autoPayHint.incrementAndGet();
            
            setActionableScans(true);

            needTrackerFlush.set(true);
        }
        payingMana = on;
    }

    private final AtomicBoolean actionableScans = new AtomicBoolean(true);

    private void flushStrandedViewChanges() {
        if (!needTrackerFlush.compareAndSet(true, false)) {
            return;
        }
        final GameView gv = getGameView();
        final Game game = gv == null ? null : gv.getGame();
        if (game == null || game.getTracker() == null) {
            return;
        }
        try {
            game.getTracker().freeze();
            game.getTracker().unfreeze();
        } catch (final RuntimeException e) {
            System.out.println("[neo] no se han podido volcar los cambios pendientes: " + e);
        }
    }

    private final AtomicBoolean needTrackerFlush = new AtomicBoolean();

    private void setActionableScans(final boolean on) {
        if (actionableScans.get() == on) {
            return;
        }
        actionableScans.set(on);
        try {
            forge.model.FModel.getPreferences().setPref(
                    forge.localinstance.properties.ForgePreferences.FPref
                            .UI_SHOW_ACTIONABLE_HIGHLIGHTS, on);
        } catch (final RuntimeException e) {
            
            actionableScans.set(true);
        }
        if (!on) {

            setSelection(getSelection().withActionable(java.util.List.of()));
        } else {
            republishActionables();
        }
    }

    private void republishActionables() {
        final IGameController gc = getGameController();
        if (!(gc instanceof forge.player.PlayerControllerHuman human)) {
            return;
        }
        try {
            if (human.getInputQueue().getInput() instanceof forge.gamemodes.match.input.InputPassPriority) {
                
                setSelection(getSelection().withActionable(human.getActionableCards(false, null)));
                trace("resaltados: vuelven tras el pago");
            }
        } catch (final RuntimeException e) {
            log("Could not request the highlights again: %s", e);
        }
    }

    boolean isOptionalPayment() {
        return getGameController() instanceof forge.player.PlayerControllerHuman human
                && forge.gamemodes.match.input.NeoPaymentPeek.isEffectPayment(
                        human.getInputQueue().getInput());
    }

    private void noteOptionalPayment() {
        if (Boolean.getBoolean("neo.pay.debug")) {
            System.out.println("[pago] opcional (lo pide un efecto): no se paga solo");
        }
    }

    private void noteAutoPayState(final boolean okEnabled) {
        if (!payingMana || !autoPayMana) {
            return;
        }
        final int mine = autoPayHint.incrementAndGet();
        if (okEnabled) {
            return;
        }
        final UiDispatcher ui = uiDispatcher();
        if (ui == null || table == null) {
            return;
        }

        final String culprit = blindSourceName();
        forge.util.ThreadUtil.delay(500, () -> ui.runLater(() -> {
            if (autoPayHint.get() == mine && payingMana) {
                table.getActionBar().setWarning(culprit == null
                        ? NeoText.get("mana.autoStuck")
                        : NeoText.get("mana.autoStuckSource", culprit), false);
            }
        }));
    }

    private final AtomicInteger autoPayHint = new AtomicInteger();

    private static volatile boolean blindSourceFallback;

    public static void setBlindSourceFallback(final boolean on) {
        blindSourceFallback = on;
    }

    public static boolean isBlindSourceFallback() {
        return blindSourceFallback;
    }

    private final AtomicInteger blindTries = new AtomicInteger();

    private static final int MAX_BLIND_TRIES = 4;

    private final java.util.Set<Integer> blindTried =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private String blindSourceName() {
        final forge.game.card.Card c = firstBlindSource();
        return c == null ? null : forge.neo.card.CardText.nameOf(c.getView());
    }

    private forge.game.card.Card firstBlindSource() {
        final GameView gv = getGameView();
        final Game game = gv == null ? null : gv.getGame();
        if (game == null) {
            return null;
        }
        forge.game.player.Player me = null;
        for (final PlayerView seat : getLocalPlayers()) {
            for (final forge.game.player.Player p : game.getPlayers()) {
                if (p.getView() == seat) {
                    me = p;
                }
            }
        }
        for (final forge.game.card.Card c : FilterSources.of(game, me)) {
            if (!blindTried.contains(c.getId())) {
                return c;
            }
        }
        return null;
    }

    private boolean tryBlindSource() {
        if (!blindSourceFallback || blindTries.get() >= MAX_BLIND_TRIES) {
            return false;
        }
        final forge.game.card.Card c = firstBlindSource();
        if (c == null) {
            return false;
        }
        blindTries.incrementAndGet();
        blindTried.add(c.getId());
        trace("pago: probando una fuente que el motor no ve: %s", c);
        final CardView view = c.getView();
        respondLater(() -> getGameController().selectCard(view, null, null));
        return true;
    }

    private static final long AUTO_PAY_DELAY_MS =
            Long.getLong("neo.autoPay.delayMs", 250);

    

    

    private void pressAutoInAMoment() {
        final int mine = autoPayGen.get();
        if (AUTO_PAY_DELAY_MS <= 0) {
            respondLater(this::autoPressOrHold);
            return;
        }

        forge.util.ThreadUtil.delay((int) AUTO_PAY_DELAY_MS, () -> {
            if (autoPayGen.get() != mine || !payingMana || !autoPayMana) {
                return;
            }
            respondLater(this::autoPressOrHold);
        });
    }

    private void autoPressOrHold() {
        final Object input = pendingInput(getGameController());
        final forge.game.card.Card culprit = AutoPaySacrifice.culprit(input);

        final boolean floating = culprit == null && FloatingManaChoice.applies(input);
        
        final forge.game.card.Card creature = culprit == null && !floating
                ? AutoPayCreatures.culprit(input) : null;
        if (culprit == null && !floating && creature == null) {
            getGameController().selectButtonOk();
            return;
        }
        trace(floating ? "pago automatico parado: hay mana flotante"
                : creature != null ? "pago automatico parado: giraria " + creature.getName()
                : "pago automatico parado: " + culprit.getName() + " sacrificaria una carta");

        autoPayHeldFor = pendingInput(getGameController());
        final Object[] b = heldButtons;
        if (b != null) {
            updateButtons((PlayerView) b[0], (String) b[1], (String) b[2],
                    (Boolean) b[3], (Boolean) b[4], (Boolean) b[5]);
        }
        setActionableScans(true);
        if (table != null) {
            table.getActionBar().setWarning(floating ? NeoText.get("mana.floating.held")
                    : creature != null ? NeoText.get("mana.creatures.held",
                            forge.neo.card.CardText.nameOf(creature.getView()))
                    : NeoText.get("mana.autoSac.held",
                            forge.neo.card.CardText.nameOf(culprit.getView())), false);
        }
    }

    private boolean confirmAutoSacrifice() {
        if (!payingMana || table == null) {
            return false;
        }
        final forge.game.card.Card culprit =
                AutoPaySacrifice.culprit(pendingInput(getGameController()));
        if (culprit == null) {
            return false;
        }
        final String name = forge.neo.card.CardText.nameOf(culprit.getView());
        final AtomicBoolean answered = new AtomicBoolean();
        final ConfirmDialog dialog = new ConfirmDialog(
                NeoText.get("mana.autoSac.title"),
                NeoText.get("mana.autoSac.detail", name),
                List.of(NeoText.get("mana.autoSac.manual"), NeoText.get("mana.autoSac.go")),
                0,
                index -> {
                    if (!answered.compareAndSet(false, true)) {
                        return;
                    }
                    table.getOverlay().hide();
                    if (index != null && index == 1) {
                        didDetail("pressed Auto, may sacrifice with " + name);
                        respondLater(() -> getGameController().selectButtonOk());
                    }
                });
        dialog.getStyleClass().add("phase-ask");
        table.getOverlay().show(dialog);
        table.requestLayout();
        return true;
    }

    private volatile Object autoPayHeldFor;
    
    private volatile Object[] heldButtons;

    private final AtomicInteger autoPayGen = new AtomicInteger();

    private volatile boolean autoPayMana = true;

    public void setAutoPayMana(final boolean on) {
        this.autoPayMana = on;
        if (!on) {

            setActionableScans(true);
        }
    }

    private volatile List<CardView> selectables = List.of();

    @Override
    public void setSelection(final forge.gamemodes.match.Selection selection) {
        final forge.gamemodes.match.Selection prev = getSelection();
        super.setSelection(selection);
        actionableChanged();
        if (!prev.cards().equals(selection.cards())) {
            if (selection.cards().isEmpty()) {
                selectionEnded();
            } else {
                selectionStarted(selection.cards(), selection.min(), selection.max());
            }
        }
        
        if (!pilotClicks.isEmpty()) {
            for (final forge.trackable.TrackableObject e : com.google.common.collect.Sets.symmetricDifference(
                    new java.util.HashSet<>(prev.chosen()), new java.util.HashSet<>(selection.chosen()))) {
                if (e instanceof CardView cv) {
                    pilotClicks.remove(cv.getId());
                }
            }
        }

        if (!prev.equals(selection)) {
            refreshHighlights();
        }

        if (interactive() && selection.offersStackItems() && !prev.offersStackItems()) {
            askStackTargets(selection);
        }
        
        final Object asked = pendingInput(getGameController());
        selectionFor = asked;
        if (asked != null) {
            flushEarlyButtons(asked);
        } else if (isNetGuest()) {
            final Object[] parked = earlyButtons;
            if (parked != null && parked[0] instanceof GuestAsk) {
                flushEarlyButtons(parked[0]);
            }
        }
    }

    private void askStackTargets(final forge.gamemodes.match.Selection sel) {
        final List<StackItemView> offered = new ArrayList<>();
        for (final forge.trackable.TrackableObject t : sel.targets()) {
            if (t instanceof StackItemView si && !sel.chosen().contains(si)) {
                offered.add(si);
            }
        }
        if (offered.isEmpty()) {
            return;
        }
        final int max = Math.max(1, Math.min(sel.max() > 0 ? sel.max() : 1, offered.size()));
        final String title = lastPrompt == null || lastPrompt.isBlank() ? NeoText.get("pick.ask.title") : lastPrompt;
        final Thread t = new Thread(() -> {
            final List<StackItemView> picked = askChoice(title, offered, 0, max, null);
            if (picked.isEmpty()) {

                if (!sel.cards().isEmpty()) {
                    return;
                }
                respondLater(() -> getGameController().selectButtonCancel());

                forge.util.ThreadUtil.delay(600, () -> {
                    final forge.gamemodes.match.Selection now = getSelection();
                    if (now.offersStackItems() && now.targets().equals(sel.targets())) {
                        askStackTargets(now);
                    }
                });
                return;
            }
            for (final StackItemView si : picked) {
                respondLater(() -> pickStackItem(si));
            }
            if (picked.size() < max) {
                respondLater(() -> getGameController().selectButtonOk());
            }
        }, "neo-stack-targets");
        t.setDaemon(true);
        t.start();
    }

    private void selectionStarted(final List<CardView> cards, final int min, final int max) {
        final List<CardView> list = new ArrayList<>(cards);
        selectables = list;
        noPickWarned.set(false);
        pilotClicks.clear();
        trace("setSelection: %d elegibles (min %d, max %d)", list.size(), min, max);
        
        if (max > 0 && showSelectionZone(list)) {
            selectionZoneShown.set(true);
        }
    }

    private void selectionEnded() {
        selectables = List.of();
        noPickWarned.set(false);
        pilotClicks.clear();

        if (selectionZoneShown.getAndSet(false) && interactive()) {
            final UiDispatcher ui = uiDispatcher();
            if (ui != null) {
                ui.runLater(() -> table.hideZoneViewer());
            }
        }
    }

    private void refreshHighlights() {
        if (!interactive()) {
            return;
        }
        final UiDispatcher ui = uiDispatcher();
        if (ui != null) {
            ui.runLater(() -> {
                table.setHighlighted(this::isHighlightedEntity);

                final TableBinder b = binder;
                if (b != null) {

                    final String prepared = lastPreparedPrompt;
                    b.setPrompt((isRoutinePriorityPrompt(lastPrompt) ? routinePrompt()
                            : prepared != null ? prepared : lastPrompt) + pickedSuffix());
                }
            });
        }
    }

    private boolean isStaleAttackPrompt(final String message) {
        return message != null
                && message.contains(" null ")
                && forge.neo.EnginePhrase.contains(message, "lblSelectAttackCreatures");
    }

    static String frontLoadInstruction(final String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        final java.util.regex.Matcher m =
                BLANK_LINE.matcher(message);
        if (!m.find()) {
            return message;
        }
        final String recap = message.substring(0, m.start()).trim();
        final String instruction = message.substring(m.end()).trim();
        if (recap.isEmpty() || instruction.isEmpty()) {
            return message;
        }
        return instruction + System.lineSeparator() + recap;
    }

    private static final java.util.regex.Pattern BLANK_LINE =
            java.util.regex.Pattern.compile("\\R[ \\t]*\\R");

    private String pickedSuffix() {
        if (!isSelecting()) {
            return "";
        }
        final int need = getSelectionMin() > 0 ? getSelectionMin() : getSelectionMax();
        if (need <= 1) {
            return "";
        }
        return System.lineSeparator()
                + NeoText.get("prompt.picked", pickedSoFar(), need);
    }

    private int pickedSoFar() {
        int n = countPickedSelectables();
        final GameView game = getGameView();
        if (game != null && game.getPlayers() != null && getSelectionMax() > 0) {
            for (final PlayerView p : game.getPlayers()) {
                if (isHighlighted(p)) {
                    n++;
                }
            }
        }
        return n;
    }

    private boolean isHighlightedEntity(final Object entity) {
        return entity instanceof GameEntityView view && isHighlighted(view);
    }

    private List<forge.neo.ui.ActionBar.PlayerChoice> playerChoices() {
        final List<forge.neo.ui.ActionBar.PlayerChoice> out = new ArrayList<>();
        for (final PlayerPick.Choice c : PlayerPick.pending(getGameController())) {
            final PlayerView pv = c.player();
            out.add(new forge.neo.ui.ActionBar.PlayerChoice(PlayerName.of(pv), c.picked(),
                    () -> pickPlayer(pv)));
        }
        return out;
    }

    private void pickPlayer(final PlayerView player) {
        if (!interactive() || player == null || finished.get()) {
            return;
        }
        respondLater(() -> {
            for (final PlayerPick.Choice c : PlayerPick.pending(getGameController())) {
                if (c.player().equals(player)) {
                    getGameController().selectPlayer(player, null);
                    return;
                }
            }
        });
    }

    public boolean isWaitingForPlayerPick() {
        return getSelectionMax() > 0
                && countPickedSelectables() < getSelectionMin()
                && selectables.isEmpty();
    }

    private final Map<Integer, Long> pilotClicks = new java.util.concurrent.ConcurrentHashMap<>();

    private static final long PILOT_CLICK_WAIT_MS = 5000;

    private boolean pilotClickInFlight() {
        final long now = System.currentTimeMillis();
        pilotClicks.values().removeIf(t -> now - t >= PILOT_CLICK_WAIT_MS);
        return !pilotClicks.isEmpty();
    }

    private CardView nextUnpickedSelectable() {
        if (countPickedSelectables() >= getSelectionMin()) {
            return null;
        }
        for (final CardView cv : selectables) {
            if (!isHighlighted(cv)) {
                return cv;
            }
        }
        return null;
    }

    private final AtomicInteger droppedResponses = new AtomicInteger();

    private void respondLater(final Runnable action) {
        decisions.incrementAndGet();
        final Runnable safe = () -> {
            try {
                action.run();
            } catch (final Exception e) {
                
                trace("respuesta descartada: %s", e.getClass().getSimpleName());

                System.err.println("[neo] respuesta descartada: " + e);
                if (droppedResponses.incrementAndGet() <= 5) {
                    e.printStackTrace();
                }
            }
        };
        final UiDispatcher ui = uiDispatcher();
        if (ui != null) {
            ui.runLater(safe);
        } else {
            try {
                responder.execute(safe);
            } catch (final Exception ignored) {
                
            }
        }
    }

    private volatile boolean pilotAnswering;

    private void pilotRespond(final Runnable action) {
        final IGameController gc = getGameController();
        final Runnable marked = () -> {
            final boolean outer = pilotAnswering;
            pilotAnswering = true;
            try {
                action.run();
            } finally {
                pilotAnswering = outer;
            }
        };
        final UiDispatcher ui = uiDispatcher();
        if (!pilotAnswering || !(ui instanceof forge.neo.platform.FxUiDispatcher)
                || !ui.isUiThread()) {
            respondLater(marked);
            return;
        }
        final Object asked = pendingInput(gc);
        decisions.incrementAndGet();
        javafx.application.Platform.runLater(() -> {
            if (pendingInput(gc) != asked) {
                trace("piloto: la pregunta ya no es la misma, respuesta descartada");
                return;
            }
            try {
                marked.run();
            } catch (final Exception e) {
                trace("respuesta descartada: %s", e.getClass().getSimpleName());
            }
        });
    }

    private static Object pendingInput(final IGameController gc) {
        return gc instanceof forge.player.PlayerControllerHuman human
                ? human.getInputQueue().getInput() : null;
    }

    private static UiDispatcher uiDispatcher() {
        final Object base = GuiBase.getInterface();
        return base instanceof NeoGuiBase ? ((NeoGuiBase) base).getDispatcher() : null;
    }

    private <T> T askUser(final Consumer<Consumer<T>> shower, final T fallback) {
        final UiDispatcher ui = uiDispatcher();
        if (ui == null || table == null) {
            return fallback;
        }
        if (ui.isUiThread()) {
            return askOnUiThread(shower, fallback);
        }

        final CompletableFuture<T> answer = new CompletableFuture<>();
        ui.runLater(() -> {
            try {

                final javafx.scene.layout.Region[] shown = new javafx.scene.layout.Region[1];

                final javafx.scene.layout.Region before = table.getOverlay().getContent();
                shower.accept(value -> {
                    if (shown[0] != null) {
                        table.clearEngineDialog(shown[0]);
                    }
                    table.getOverlay().hide();
                    answer.complete(value);
                });

                final javafx.scene.layout.Region after = table.getOverlay().getContent();
                if (!answer.isDone() && after != null && after != before) {
                    shown[0] = after;
                    table.markEngineDialog(after);
                }
            } catch (final Exception e) {
                e.printStackTrace();
                answer.complete(fallback);
            }
        });

        try {
            return answer.get();
        } catch (final Exception e) {
            Thread.currentThread().interrupt();
            return fallback;
        }
    }

    private <T> T askOnUiThread(final Consumer<Consumer<T>> shower, final T fallback) {
        final Object key = new Object();
        final java.util.concurrent.atomic.AtomicBoolean answered =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final List<T> box = new ArrayList<>(1);
        box.add(fallback);

        final javafx.scene.layout.Region[] shown = new javafx.scene.layout.Region[1];

        final javafx.scene.layout.Region before = table.getOverlay().getContent();
        try {
            shower.accept(value -> {
                
                if (!answered.compareAndSet(false, true)) {
                    return;
                }
                if (shown[0] != null) {
                    table.clearEngineDialog(shown[0]);
                }
                table.getOverlay().hide();
                box.set(0, value);
                javafx.application.Platform.exitNestedEventLoop(key, null);
            });
            final javafx.scene.layout.Region after = table.getOverlay().getContent();
            if (!answered.get() && after != null && after != before) {
                shown[0] = after;
                table.markEngineDialog(after);
            }
        } catch (final Exception e) {
            e.printStackTrace();
            return fallback;
        }

        if (answered.get()) {

            return box.get(0);
        }

        try {
            javafx.application.Platform.enterNestedEventLoop(key);
        } catch (final RuntimeException e) {
            System.err.println("[neo] no se ha podido abrir el dialogo anidado: " + e);
            return fallback;
        }
        return box.get(0);
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> askPile(final String message, final List<T> choices) {
        final List<CardView> cards = new ArrayList<>();
        for (final T o : choices) {
            if (!(o instanceof CardView)) {
                return null;
            }
            cards.add((CardView) o);
        }
        final CardView picked = askUser(reply -> {
            final forge.neo.ui.PileDialog dialog = new forge.neo.ui.PileDialog(
                    message, cards, handCardWidth(), reply::accept);
            table.getOverlay().show(dialog);
        }, null);
        if (picked == null) {
            return null;
        }
        final List<T> out = new ArrayList<>();
        out.add((T) picked);
        return out;
    }

    private <T> List<T> askChoice(final String title, final List<T> options,
                                  final int min, final int max,
                                  final FSerializableFunction<T, String> display) {
        return askChoice(title, options, min, max, display, List.of());
    }

    private <T> List<T> askChoice(final String title, final List<T> options,
                                  final int min, final int max,
                                  final FSerializableFunction<T, String> display,
                                  final List<TriggerSubject.Subject> about) {
        return askChoice(title, options, min, max, display, about, false);
    }

    private <T> List<T> askChoice(final String title, final List<T> options,
                                  final int min, final int max,
                                  final FSerializableFunction<T, String> display,
                                  final List<TriggerSubject.Subject> about,
                                  final boolean ordered) {
        return askChoice(title, options, min, max, display, about, ordered, null);
    }

    private <T> List<T> askChoice(final String title, final List<T> asked,
                                  final int min, final int max,
                                  final FSerializableFunction<T, String> display,
                                  final List<TriggerSubject.Subject> about,
                                  final boolean ordered, final String note) {

        final List<T> options = asked;
        if (options == null || options.isEmpty()) {
            return new ArrayList<>();
        }
        if (Boolean.getBoolean("neo.trace.choices")) {
            System.out.println("[eleccion] " + title + " -> " + options);
        }

        final boolean reveal = min < 0 || max <= 0;
        final int lo = reveal ? -1 : Math.max(0, Math.min(min, options.size()));
        final int hi = reveal ? -1 : Math.max(lo, Math.min(max, options.size()));

        final int macro = reveal ? 0 : macroMode();
        final List<String> macroLabels = new ArrayList<>();
        if (macro != 0) {
            for (final T o : options) {
                macroLabels.add(display == null ? defaultLabel(o) : display.apply(o));
            }
        }
        if (macro == 2) {
            final List<String> want = macroChoices.replay("choice", title, macroLabels);
            final List<Integer> at = want == null ? null : MacroChoices.indicesOf(macroLabels, want);
            if (at != null && at.size() >= lo && at.size() <= hi) {
                final List<T> out = new ArrayList<>();
                for (final int i : at) {
                    out.add(options.get(i));
                }
                trace("macro: '%s' -> %s", title, want);
                return out;
            }
        }

        final List<T> picked = askUser(reply -> {
            final ChoiceDialog<T> dialog = new ChoiceDialog<>(
                    title, options, lo, hi,
                    display == null ? NeoMatchUI::defaultLabel : display::apply,
                    handCardWidth(), null, ordered, reply::accept);
            javafx.scene.control.Label line = null;
            if (note != null) {
                line = new javafx.scene.control.Label(note);
                line.getStyleClass().add("dialog-note");
                line.setWrapText(true);
                line.setMaxWidth(forge.neo.ui.UiScale.px(760));
            }
            final boolean cards = about != null && !about.isEmpty();
            if (cards && line != null) {

                final javafx.scene.layout.VBox both = new javafx.scene.layout.VBox(
                        forge.neo.ui.UiScale.px(8), line, subjectRow(about));
                dialog.setContext(both);
            } else if (cards) {
                dialog.setContext(subjectRow(about));
            } else if (line != null) {
                dialog.setContext(line);
            }
            table.getOverlay().show(dialog);
        }, null);

        final DetailLog detailLog = detail;
        if (detailLog != null) {
            final java.util.function.Function<T, String> label = o -> display == null
                    ? defaultLabel(o) : display.apply(o);
            final List<String> shown = new ArrayList<>();
            for (final T o : options) {
                shown.add(label.apply(o));
            }
            final List<String> chosen = new ArrayList<>();
            if (picked != null) {
                for (final T o : picked) {
                    chosen.add(label.apply(o));
                }
            }
            detailLog.asked(title + (reveal ? " (shown, nothing to pick)" : " (pick " + lo + "-" + hi + ")"),
                    shown, picked == null ? null : chosen.isEmpty() ? "(none)" : String.join(" | ", chosen));
        }
        if (macro == 1 && picked != null) {
            final List<String> chosenLabels = new ArrayList<>();
            for (final T o : picked) {
                chosenLabels.add(display == null ? defaultLabel(o) : display.apply(o));
            }
            macroChoices.record("choice", title, macroLabels, chosenLabels);
        }
        if (picked != null) {
            return picked;
        }
        
        final List<T> safe = new ArrayList<>();
        for (int i = 0; i < Math.max(0, lo); i++) {
            safe.add(options.get(i));
        }
        return safe;
    }

    private forge.neo.ui.TriggerSubjectRow subjectRow(final List<TriggerSubject.Subject> about) {
        final List<forge.neo.ui.TriggerSubjectRow.Entry> entries = new ArrayList<>();
        for (final TriggerSubject.Subject s : about) {
            final GameEntityView who = s.attacking();
            final String attacking = who instanceof PlayerView p ? PlayerName.of(p)
                    : who instanceof CardView c ? forge.neo.card.CardText.nameOf(c)
                    : null;
            entries.add(new forge.neo.ui.TriggerSubjectRow.Entry(
                    s.card(), forge.neo.card.CardText.nameOf(s.card()), attacking));
        }
        return new forge.neo.ui.TriggerSubjectRow(entries, handCardWidth() * 0.7);
    }

    public int selfTestAskOption() {
        return askOption("Prueba", NeoText.get("choice.title"),
                java.util.List.of("Primera", "Segunda"), 0);
    }

    private int askCard(final CardView card, final String title, final String question,
                        final List<String> options, final int defaultOption) {
        if (options == null || options.isEmpty()) {
            return defaultOption;
        }
        final Integer index = askUser(reply -> {
            final CardPromptDialog dialog = new CardPromptDialog(
                    card, title, question, options, defaultOption,
                    table.zoomCardWidth() * 0.62, reply::accept);
            table.getOverlay().show(dialog);
        }, null);
        askedDetail((card == null ? "" : "[" + DetailLog.card(card) + "] ") + question, options, index);
        return index == null ? defaultOption : index;
    }

    public int askWithCard(final CardView card, final String question,
                           final List<String> options, final int defaultOption) {
        if (interactive()) {
            return askCard(card, null, question, options, defaultOption);
        }
        trace("askWithCard: %s -> %d", question, defaultOption);
        return defaultOption;
    }

    private int askOption(final String title, final String message,
                          final List<String> options, final int defaultOption) {
        if (options == null || options.isEmpty()) {
            return defaultOption;
        }
        final Integer index = askUser(reply -> {
            final ConfirmDialog dialog = new ConfirmDialog(
                    title, message, options, defaultOption, reply::accept);
            table.getOverlay().show(dialog);
        }, null);
        askedDetail(message, options, index);
        return index == null ? defaultOption : index;
    }

    private void askedDetail(final String question, final List<String> options, final Integer index) {
        final DetailLog d = detail;
        if (d != null) {
            d.asked(question, options, index == null || index < 0 || index >= options.size()
                    ? null : options.get(index));
        }
    }

    private double handCardWidth() {
        return 132;
    }

    @Override
    public boolean confirm(final CardView c, final String question, final boolean defaultYes,
                           final List<String> options) {
        if (interactive()) {
            final List<String> opts = options == null || options.size() < 2
                    ? List.of("Si", "No") : options;

            return askCard(c, null, question, opts, defaultYes ? 0 : 1) == 0;
        }
        trace("confirm: %s -> %s", question, defaultYes);
        return defaultYes;
    }

    @Override
    public boolean showConfirmDialog(final String message, final String title, final String yes,
                                     final String no, final boolean defaultYes) {

        askGen.incrementAndGet();
        if (autoConfirm) {
            
            return true;
        }
        if (interactive()) {
            final List<String> opts = List.of(
                    yes == null || yes.isBlank() ? "Si" : yes,
                    no == null || no.isBlank() ? "No" : no);
            
            return askCard(null, title, message, opts, defaultYes ? 0 : 1) == 0;
        }
        trace("confirmDialog: %s -> %s", message, defaultYes);
        return defaultYes;
    }

    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon,
                                final List<String> options, final int defaultOption) {
        if (interactive()) {
            
            return askCard(null, title, message, options, defaultOption);
        }
        trace("optionDialog: %s -> %d", message, defaultOption);
        return defaultOption;
    }

    @Override
    public Integer getInteger(final String message, final int min, final int max, final int cutoff) {
        return super.getInteger(message, min, max, AffordableX.cutoff(min, max, cutoff));
    }

    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon,
                                  final String initialInput, final List<String> inputOptions,
                                  final boolean isNumeric) {
        if (!interactive()) {
            trace("inputDialog (sin jugador): %s", message);
            return initialInput;
        }
        
        if (inputOptions != null && !inputOptions.isEmpty()) {
            final List<String> picked =
                    askChoice(headingOf(title, message), new ArrayList<>(inputOptions), 1, 1, null);
            return picked.isEmpty() ? null : picked.get(0);
        }
        return askUser(reply -> {
            final javafx.scene.layout.Region dialog = isNumeric
                    ? forge.neo.ui.TextDialog.number(headingOf(title, null), message,
                            initialInput, reply::accept, () -> reply.accept(null))
                    : forge.neo.ui.TextDialog.line(headingOf(title, null), message,
                            initialInput, reply::accept, () -> reply.accept(null));
            table.getOverlay().show(dialog);
        }, null);
    }

    @Override
    public void updateDayTime(final String daytime) {
        super.updateDayTime(daytime);
        if (table == null) {
            return;
        }
        runOnUi(() -> table.getPhaseRail().setDayTime(daytime));
    }

    private void actionableChanged() {
        if (!overlookedFresh) {
            overlookedIds = java.util.Set.of();
        }
        overlookedFresh = false;
        pushActionable();
    }

    private volatile boolean overlookedFresh;

    @Override
    public void setOverlooked(final Collection<CardView> cards) {
        overlookedFresh = true;
        final java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (final CardView cv : cards) {
            ids.add(cv.getId());
        }
        if (ids.equals(overlookedIds)) {
            return;
        }
        overlookedIds = java.util.Set.copyOf(ids);
        pushActionable();
    }

    private volatile java.util.Set<Integer> overlookedIds = java.util.Set.of();

    private void pushActionable() {
        if (verbose) {
            
            trace("resaltado debil: %d cartas", weaklySelectableCount());
        }
        if (table == null) {
            return;
        }
        runOnUi(() -> table.setActionable(this::actionableStrength));
    }

    private int weaklySelectableCount() {
        int n = 0;
        final forge.game.GameView gv = getGameView();
        if (gv == null || gv.getPlayers() == null) {
            return 0;
        }
        for (final PlayerView p : gv.getPlayers()) {
            for (final forge.game.zone.ZoneType z : new forge.game.zone.ZoneType[] {
                    forge.game.zone.ZoneType.Battlefield, forge.game.zone.ZoneType.Hand}) {
                final var cards = p.getCards(z);
                if (cards == null) {
                    continue;
                }
                for (final CardView cv : cards) {
                    if (actionableStrength(cv) > 0) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    public int actionableStrength(final CardView card) {
        if (card == null) {
            return 0;
        }
        try {
            final int engine = getActionableStrength(card);
            if (engine > 0) {
                return engine;
            }
            if (overlookedIds.contains(card.getId())) {
                return 1;
            }

            return castablePreparedSpell(card) != null ? 1 : 0;
        } catch (final RuntimeException e) {
            return 0;
        }
    }

    private static String headingOf(final String title, final String extra) {
        final String t = title == null || title.isBlank()
                ? NeoText.get("input.title") : title;
        return extra == null || extra.isBlank() ? t : t + " — " + extra;
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max,
                                  final List<T> choices, final List<T> selected,
                                  final FSerializableFunction<T, String> display) {
        if (isSilencedAiDeckWarning(message)) {

            return new ArrayList<>();
        }
        if (interactive()) {

            if (max == 1 && forge.neo.ui.PileDialog.looksLikePiles(choices)) {
                final List<T> one = askPile(message, choices);
                if (one != null) {
                    return one;
                }
            }

            final List<TriggerSubject.Subject> about = subjectsFor(choices);
            return askChoice(TriggerSubject.titleOf(message), choices, min, max, display,
                    about, false, TriggerSubject.detailFor(choices, message));
        }

        final List<TriggerSubject.Subject> shownAbout = subjectsFor(choices);
        if (!shownAbout.isEmpty()) {
            lastShownSubject = shownAbout.get(0).card();
        }
        final String detail = TriggerSubject.detailFor(choices);
        if (detail != null) {
            lastTriggerDetail = detail;
        }
        final String wired = TriggerSubject.wiredDetail(message);
        if (wired != null) {
            lastWiredDetail = wired;
            lastWiredTitle = TriggerSubject.titleOf(message);
        }
        
        final List<T> out = new ArrayList<>();
        if (choices != null) {
            final int want = Math.min(Math.max(min, 0), choices.size());
            for (int i = 0; i < want; i++) {
                out.add(choices.get(i));
            }
        }
        trace("getChoices(%d..%d de %d) -> %d", min, max,
                choices == null ? 0 : choices.size(), out.size());
        return out;
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(final String title,
                                                      final List<? extends GameEntityView> optionList,
                                                      final DelayedReveal delayedReveal,
                                                      final boolean isOptional) {
        if (interactive() && optionList != null && !optionList.isEmpty()) {
            final List<GameEntityView> opts = new ArrayList<>(optionList);
            final List<GameEntityView> picked =
                    askChoice(title, opts, isOptional ? 0 : 1, 1, null);
            return picked.isEmpty() ? null : picked.get(0);
        }
        final GameEntityView pick =
                (optionList == null || optionList.isEmpty()) ? null : optionList.get(0);
        trace("chooseSingleEntity: %s", title);
        return pick;
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(final String title,
                                                        final List<? extends GameEntityView> optionList,
                                                        final int min, final int max,
                                                        final DelayedReveal delayedReveal) {
        if (interactive() && optionList != null && !optionList.isEmpty()) {
            return askChoice(title, new ArrayList<GameEntityView>(optionList), min, max, null);
        }
        final List<GameEntityView> out = new ArrayList<>();
        if (optionList != null) {
            final int want = Math.min(Math.max(min, 0), optionList.size());
            for (int i = 0; i < want; i++) {
                out.add(optionList.get(i));
            }
        }
        return out;
    }

    private void showStackMenu(final StackItemView item) {
        if (!interactive() || item == null || table == null) {
            return;
        }
        final CardView source = item.getSourceCard();
        final IGameController gc = getGameController();
        if (gc == null) {
            table.showZoom(source);
            return;
        }

        final String key = item.getKey();
        final boolean hasKey = key != null && !key.isEmpty();

        final boolean mineTrigger = hasKey && item.isOptionalTrigger()
                && isMe(item.getActivatingPlayer());
        final boolean canYield = hasKey && item.isAbility();

        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        labels.add(NeoText.get("stack.menu.zoom"));
        actions.add(() -> table.showZoom(source));

        if (canYield) {
            final boolean yielding = gc.shouldAutoYield(key);
            labels.add(NeoText.get(yielding ? "stack.menu.yield.on" : "stack.menu.yield.off"));
            actions.add(() -> {
                gc.setShouldAutoYield(key, !yielding, abilityScope(gc));
                refreshYieldUi(getCurrentPlayer());
            });
        }

        if (mineTrigger) {
            final AutoYieldStore.TriggerDecision now = gc.getTriggerDecision(key);
            labels.add(NeoText.get(now == AutoYieldStore.TriggerDecision.ACCEPT
                    ? "stack.menu.alwaysYes.on" : "stack.menu.alwaysYes.off"));
            actions.add(() -> {
                gc.setTriggerDecision(key,
                        now == AutoYieldStore.TriggerDecision.ACCEPT
                                ? AutoYieldStore.TriggerDecision.ASK
                                : AutoYieldStore.TriggerDecision.ACCEPT,
                        abilityScope(gc));
                refreshYieldUi(getCurrentPlayer());
            });
            labels.add(NeoText.get(now == AutoYieldStore.TriggerDecision.DECLINE
                    ? "stack.menu.alwaysNo.on" : "stack.menu.alwaysNo.off"));
            actions.add(() -> {
                gc.setTriggerDecision(key,
                        now == AutoYieldStore.TriggerDecision.DECLINE
                                ? AutoYieldStore.TriggerDecision.ASK
                                : AutoYieldStore.TriggerDecision.DECLINE,
                        abilityScope(gc));
                refreshYieldUi(getCurrentPlayer());
            });
        }

        if (hasRemembered(gc)) {
            labels.add(NeoText.get("stack.menu.forget"));
            actions.add(() -> {
                gc.getYieldController().clearAutoYields();
                gc.getYieldController().setDisableAutoTriggers(false);
                forgetTriggerDecisions(gc);
                refreshYieldUi(getCurrentPlayer());
            });
        }

        final boolean[] all = new boolean[labels.size()];
        java.util.Arrays.fill(all, true);

        final AbilityMenu menu = new AbilityMenu(source, labels, all,
                table.zoomCardWidth() * 0.62, picked -> {
                    table.getMenuOverlay().hide();
                    if (picked != null && picked >= 0 && picked < actions.size()) {
                        actions.get(picked).run();
                    }
                });

        table.getMenuOverlay().setOnBackgroundClick(table.getMenuOverlay()::hide);
        table.getMenuOverlay().show(menu);
    }

    private static boolean abilityScope(final IGameController gc) {
        return gc.getYieldController() != null && gc.getYieldController().isAbilityScope();
    }

    private static boolean hasRemembered(final IGameController gc) {
        final YieldController yc = gc.getYieldController();
        if (yc == null) {
            return false;
        }
        return yc.getAutoYields().iterator().hasNext()
                || yc.getAutoTriggers().iterator().hasNext();
    }

    private static void forgetTriggerDecisions(final IGameController gc) {
        final YieldController yc = gc.getYieldController();
        if (yc == null) {
            return;
        }

        final List<String> keys = new ArrayList<>();
        for (final Map.Entry<String, AutoYieldStore.TriggerDecision> e : yc.getAutoTriggers()) {
            keys.add(e.getKey());
        }
        for (final String k : keys) {
            gc.setTriggerDecision(k, AutoYieldStore.TriggerDecision.ASK, abilityScope(gc));
        }
    }

    @Override
    public void refreshYieldUi(final PlayerView player) {

        if (player == null || isLocalPlayer(player)) {
            pushToTable();
        }
    }

    @Override
    public SpellAbilityView getAbilityToPlay(final CardView hostCard,
                                             final List<SpellAbilityView> abilities,
                                             final ITriggerEvent triggerEvent) {

        List<SpellAbilityView> offered = abilities;
        final int wanted = pendingEquipCard;
        if (wanted >= 0) {
            pendingEquipCard = -1;
            if (hostCard != null && hostCard.getId() == wanted && abilities != null
                    && System.currentTimeMillis() - pendingEquipAt < EQUIP_PENDING_MS) {
                final List<SpellAbilityView> equips = new ArrayList<>();
                for (final SpellAbilityView sa : abilities) {
                    if (sa != null && sa.canPlay() && isEquip(sa)) {
                        equips.add(sa);
                    }
                }
                if (equips.isEmpty()) {

                    showWhy(NeoText.get("zoom.equip.cant"));
                    return null;
                }
                if (equips.size() == 1) {
                    return equips.get(0);
                }

                offered = equips;
            }
        }
        final List<SpellAbilityView> choices = offered;

        if (interactive() && choices != null && choices.size() > 1) {

            final int[] grouped = forge.neo.ui.AbilityMenu.groupedOriginal(hostCard, choices);
            if (grouped.length == 1) {
                return choices.get(grouped[0]);
            }

            final int macro = macroMode();
            final String hostName = hostCard == null || hostCard.getCurrentState() == null
                    ? "" : hostCard.getCurrentState().getName();
            final List<String> abilityLabels = new ArrayList<>();
            for (final SpellAbilityView sa : choices) {
                abilityLabels.add(sa == null ? "" : String.valueOf(sa.getDescription()));
            }
            if (macro == 2) {
                final List<String> want = macroChoices.replayInOrder("ability", hostName, abilityLabels);
                final List<Integer> at = want == null ? null : MacroChoices.indicesOf(abilityLabels, want);
                if (at != null && at.size() == 1 && choices.get(at.get(0)).canPlay()) {
                    trace("macro: habilidad de %s -> %s", hostName, want);
                    return choices.get(at.get(0));
                }
            }

            pendingAuraTarget = null;
            final Integer picked = askUser(reply -> {
                final AbilityMenu menu = new AbilityMenu(
                        hostCard, choices, table.zoomCardWidth() * 0.62, reply::accept);
                table.getOverlay().show(menu);
            }, null);

            if (picked == null || picked < 0 || picked >= choices.size()) {
                return null;
            }
            if (macro == 1) {
                macroChoices.record("ability", hostName, abilityLabels,
                        java.util.List.of(abilityLabels.get(picked)));
            }
            return choices.get(picked);
        }
        return (choices == null || choices.isEmpty()) ? null : choices.get(0);
    }

    private volatile int pendingEquipCard = -1;
    private volatile long pendingEquipAt;
    private static final long EQUIP_PENDING_MS = 5000;

    private static final java.util.regex.Pattern EQUIP_DESC =
            java.util.regex.Pattern.compile("^(?:[^—]*—\\s*)?Equip(?!ped)\\b");

    private static boolean isEquip(final SpellAbilityView sa) {
        final String d = sa.getDescription();
        return d != null && EQUIP_DESC.matcher(d.trim()).find();
    }

    private String equipLabel(final CardView card) {
        if (!interactive() || card == null || finished.get() || payingMana || isSelecting()
                || card.getZone() != ZoneType.Battlefield || !isMe(card.getController())) {
            return null;
        }
        final GameView gv = getGameView();
        if (gv == null || !isMe(gv.getPlayerTurn())
                || (gv.getPhase() != PhaseType.MAIN1 && gv.getPhase() != PhaseType.MAIN2)
                || (gv.getStack() != null && !gv.getStack().isEmpty())) {
            return null;
        }
        final CardView.CardStateView st = card.getCurrentState();
        if (st == null || st.getKeywords() == null || actionableStrength(card) <= 0) {
            return null;
        }
        final java.util.Set<String> costs = new java.util.LinkedHashSet<>();
        for (final forge.game.keyword.KeywordView k : st.getKeywords()) {
            if (k == null || k.keyword() != forge.game.keyword.Keyword.EQUIP) {
                continue;
            }
            costs.add(equipCost(k.original()));
        }
        if (costs.isEmpty()) {
            return null;
        }
        costs.remove("");
        return NeoText.get("zoom.equip", String.join(" / ", costs)).trim();
    }

    private static String equipCost(final String original) {
        try {
            final String[] k = original.split(":");
            if (k.length < 2) {
                return "";
            }
            return new forge.game.cost.Cost(k[1], true).toSimpleString();
        } catch (final RuntimeException e) {
            return "";
        }
    }

    private void equipFromZoom(final CardView card) {
        if (equipLabel(card) == null) {
            return;
        }
        pendingEquipCard = card.getId();
        pendingEquipAt = System.currentTimeMillis();
        onCardClicked(card);
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(final CardView attacker,
                                                     final List<CardView> blockers, final int damage,
                                                     final GameEntityView defender,
                                                     final boolean overrideOrder,
                                                     final boolean maySkip) {
        if (interactive() && blockers != null && !blockers.isEmpty()) {
            final boolean trample = attacker != null && attacker.getCurrentState() != null
                    && attacker.getCurrentState().hasTrample();
            final GameView gv = getGameView();
            final int poison = gv == null ? 10 : gv.getPoisonCountersToLose();
            final Map<CardView, Integer> picked = askUser(reply -> {
                final DamageDialog dialog = new DamageDialog(
                        attacker, blockers, damage, defender, overrideOrder, maySkip,
                        trample, poison, handCardWidth(), reply::accept);
                table.getOverlay().show(dialog);
            }, null);
            if (picked != null) {
                return picked;
            }

            if (maySkip) {
                return null;
            }
        }
        
        final Map<CardView, Integer> map = new HashMap<>();
        if (blockers != null && !blockers.isEmpty()) {
            map.put(blockers.get(0), damage);
        }
        return map;
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(final CardView effectSource,
                                                    final Map<Object, Integer> targets,
                                                    final int amount, final boolean atLeastOne,
                                                    final String amountLabel) {
        if (targets == null || targets.isEmpty() || amount <= 0) {
            return new HashMap<>();
        }
        if (interactive() && targets.size() > 1) {
            final Map<Object, Integer> picked = askUser(reply -> {
                final AmountDialog dialog = new AmountDialog(
                        effectSource, targets, amount, atLeastOne, amountLabel,
                        handCardWidth(), reply::accept);
                table.getOverlay().show(dialog);
            }, null);
            if (picked != null && !picked.isEmpty()) {
                return picked;
            }
        }

        return AmountDialog.autoSplit(targets, amount, atLeastOne);
    }

    static String defaultLabel(final Object option) {
        if (option instanceof forge.card.MagicColor.Color c) {
            return c.getTranslatedName();
        }
        
        if (option instanceof forge.game.card.CardFaceView face) {
            return forge.neo.card.CardText.nameOf(face);
        }

        if (option instanceof forge.game.spellability.SpellAbilityView sa) {
            return forge.neo.card.FaceDownName.label(sa, NeoMatchUI::mayFlipHere);
        }
        return String.valueOf(option);
    }

    public static boolean mayFlipHere(final CardView card) {
        return card.canFaceDownBeShownToAny(forge.neo.ui.CardZoom.localViewers());
    }

    Map<forge.card.MagicColor.Color, Integer> askManaCombo(final CardView source, final forge.card.ColorSet options,
                                                final int amount,
                                                final Map<forge.card.MagicColor.Color, Integer> suggested,
                                                final String costText, final CardView payingCard,
                                                final boolean asLastTime) {
        if (!interactive()) {
            return suggested;
        }
        final Map<Object, Integer> targets = new java.util.LinkedHashMap<>();
        final Map<Object, Integer> start = new java.util.LinkedHashMap<>();
        for (final Map.Entry<forge.card.MagicColor.Color, Integer> e : suggested.entrySet()) {
            targets.put(e.getKey(), amount);
            start.put(e.getKey(), e.getValue());
        }
        final String note;
        if (costText != null) {
            final String what = payingCard == null
                    ? "" : forge.neo.card.CardText.nameOf(payingCard);
            note = what.isEmpty() ? NeoText.get("amount.mana.forCost", costText)
                    : NeoText.get("amount.mana.forCostOf", costText, what);
        } else {
            note = asLastTime ? NeoText.get("amount.mana.lastTime") : null;
        }
        final Map<Object, Integer> picked = askUser(reply -> {
            final AmountDialog dialog = AmountDialog.forMana(
                    source, targets, amount, start, note, handCardWidth(), reply::accept);
            table.getOverlay().show(dialog);
        }, null);
        if (picked == null) {
            return null;
        }
        final Map<forge.card.MagicColor.Color, Integer> out = new java.util.LinkedHashMap<>();
        for (final Map.Entry<Object, Integer> e : picked.entrySet()) {
            if (e.getKey() instanceof forge.card.MagicColor.Color c && e.getValue() != null) {
                out.put(c, e.getValue());
            }
        }
        return out;
    }

    @Override
    public <T> IGuiGame.OrderResult<T> order(final String title, final String top, final int min,
                                             final int max, final List<T> sourceChoices,
                                             final List<T> destChoices, final CardView referenceCard,
                                             final boolean referenceCardIsSource,
                                             final boolean rememberOption) {
        final List<T> source = sourceChoices == null ? List.of() : sourceChoices;
        final List<T> already = destChoices == null ? new ArrayList<>() : new ArrayList<>(destChoices);
        if (rememberOption && interactive()) {
            return orderRemembering(title, top, source, already);
        }
        if (source.isEmpty()) {
            return new IGuiGame.OrderResult<>(already, false);
        }
        if (!interactive()) {
            final List<T> all = new ArrayList<>(already);
            all.addAll(source);
            trace("order(%s) sin jugador -> %d", title, all.size());
            return new IGuiGame.OrderResult<>(all, false);
        }

        final int n = source.size();
        final int pickMin = max < 0 ? 0 : Math.max(0, n - max);
        final int pickMax = min < 0 ? n : Math.min(n, n - min);

        final boolean attach = forge.neo.match.AttachOrder.applies(source);
        final String heading = attach ? NeoText.get("order.attach.title")
                : top == null || top.isBlank() ? title
                : (title == null || title.isBlank() ? top : title + " — " + top);

        final String pile = pileHint(title);
        final List<T> picked = askChoice(heading, source, pickMin, Math.max(pickMin, pickMax), null,
                List.of(), pile == null, attach ? NeoText.get("order.attach.hint")
                        : pile != null ? pile : orderHint(top, Math.max(pickMin, pickMax)));
        final List<T> ordered = new ArrayList<>(already);
        if (picked != null) {
            ordered.addAll(picked);
        }
        return new IGuiGame.OrderResult<>(ordered, false);
    }

    public static String pileHint(final String title) {
        if (title == null) {
            return null;
        }
        final forge.util.Localizer l = forge.util.Localizer.getInstance();
        if (title.equals(l.getMessage("lblSelectCardForFaceDownPile"))) {
            return NeoText.get("pile.split.faceDown");
        }
        if (title.equals(l.getMessage("lblDivideCardIntoTwoPiles"))) {
            return NeoText.get("pile.split.two");
        }
        if (title.equals(l.getMessage("lblSelectCardForLeftPile"))) {
            return NeoText.get("pile.split.left");
        }
        return null;
    }

    public static String orderHint(final String top, final int count) {
        if (top == null || count < 2) {
            return null;
        }
        final forge.util.Localizer l = forge.util.Localizer.getInstance();
        if (top.equals(l.getMessage("lblClosestToTop")) || top.equals(l.getMessage("lblTopOfLibrary"))) {
            return NeoText.get("order.hint.top", count);
        }
        if (top.equals(l.getMessage("lblClosestToBottom"))) {
            return NeoText.get("order.hint.bottom", count);
        }
        return null;
    }

    private <T> IGuiGame.OrderResult<T> orderRemembering(final String title, final String top,
                                                         final List<T> source, final List<T> already) {
        final List<T> all = new ArrayList<>(already);
        all.addAll(source);
        if (all.isEmpty()) {
            return new IGuiGame.OrderResult<>(all, false);
        }
        final String heading = top == null || top.isBlank() ? title
                : (title == null || title.isBlank() ? top : title + " — " + top);
        final java.util.concurrent.atomic.AtomicBoolean keep =
                new java.util.concurrent.atomic.AtomicBoolean();
        final List<T> picked = askUser(reply -> {
            final List<ChoiceDialog<T>> self = new ArrayList<>(1);
            final ChoiceDialog<T> dialog = new ChoiceDialog<>(
                    heading, all, all.size(), all.size(), String::valueOf,
                    handCardWidth(), already.isEmpty() ? null : already, true,
                    chosen -> {
                        keep.set(!self.isEmpty() && self.get(0).remember());
                        reply.accept(chosen);
                    });
            self.add(dialog);
            dialog.offerRemember(true);
            table.getOverlay().show(dialog);
        }, null);
        if (picked == null || picked.size() != all.size()) {
            return new IGuiGame.OrderResult<>(all, false);
        }
        if (keep.get()) {
            savedOrders = true;
        }
        return new IGuiGame.OrderResult<>(picked, keep.get());
    }

    private volatile boolean savedOrders;

    public boolean hasSavedOrders() {
        return savedOrders;
    }

    public void forgetSavedOrders() {
        savedOrders = false;
        final IGameController controller = getGameController();
        if (controller != null) {
            controller.sendYieldUpdate(new forge.gamemodes.match.YieldUpdate.ClearAbilityOrders());
        }
    }

    @Override
    public List<CardView> manipulateCardList(final String title, final Iterable<CardView> cards,
                                             final Iterable<CardView> manipulable, final boolean toTop,
                                             final boolean toBottom, final boolean toAnywhere) {
        final List<CardView> out = new ArrayList<>();
        if (cards != null) {
            cards.forEach(out::add);
        }
        return out;
    }

    @Override
    public List<PaperCard> sideboard(final CardPool sideboard, final CardPool main,
                                     final String message) {
        if (!interactive()) {
            return null; 
        }
        final List<PaperCard> current = main.toFlatList();
        
        final List<PaperCard> pool = BestOf.sideboardPool(sideboard, main);
        final int size = current.size();

        final GameView now = getGameView();
        final int[] range = BestOf.legalMainRange(now == null ? null : now.getGameType(), size, pool.size());
        final List<PaperCard> picked = askUser(reply -> {
            final ChoiceDialog<PaperCard> dialog = new ChoiceDialog<>(
                    NeoText.get("sideboard.title"), pool, range[0], range[1],
                    PaperCard::getName, handCardWidth(), current, reply::accept);
            table.getOverlay().show(dialog);
        }, null);
        return picked;
    }

    @Override
    public void showCombat() {
        pushToTable();
    }

    @Override
    public void showManaPool(final PlayerView player) {
        manaPool(player, true);
    }

    @Override
    public void hideManaPool(final PlayerView player) {
        manaPool(player, false);
    }

    private void manaPool(final PlayerView player, final boolean on) {
        if (!interactive()) {
            return;
        }
        final UiDispatcher ui = uiDispatcher();
        if (ui != null) {
            ui.runLater(() -> table.setManaPoolActive(player, on));
        }
    }

    @Override public void updateShards(final Iterable<PlayerView> players) { }
    @Override public void enableOverlay() { }
    @Override public void disableOverlay() { }
    
    @Override
    public void setCard(final CardView card) {

        askingCard = card;
        askingAt = System.currentTimeMillis();
    }

    private List<TriggerSubject.Subject> subjectsFor(final List<?> choices) {
        final List<TriggerSubject.Subject> about = TriggerSubject.forChoices(choices);
        final CardView asking = takeAskingCard();
        if (about.isEmpty() && asking != null && choices != null && !choices.isEmpty()
                && !(choices.get(0) instanceof CardView)) {
            return List.of(new TriggerSubject.Subject(asking, null));
        }
        return about;
    }

    private volatile CardView lastShownSubject;

    public CardView lastShownSubject() {
        return lastShownSubject;
    }

    private volatile CardView askingCard;
    private volatile long askingAt;

    private CardView takeAskingCard() {
        final CardView c = askingCard;
        askingCard = null;
        return c != null && System.currentTimeMillis() - askingAt < 4000 ? c : null;
    }
    @Override public void setPanelSelection(final CardView card) { }
    @Override public void setPlayerAvatar(final LobbyPlayer player, final IHasIcon icon) { }
    
    @Override
    public void flashIncorrectAction() {
        trace("accion incorrecta");

        final String why = CantPlay.reason(getGameController(), lastClicked);
        showWhy(why != null ? why : NeoText.get("why.incorrect"));
    }

    @Override public void alertUser() { }

    @Override
    public void message(final String message, final String title) {
        log("[%s] %s", title, message);
        if (message == null || message.isBlank()) {
            return;
        }
        final forge.game.GameView gv = getGameView();

        if (ATTACK_INVALID.equals(message.trim())) {

            final forge.game.Game game = gv == null ? null : gv.getGame();
            final java.util.List<String> engine = WhyNot.explainAttack(game == null ? null : game.getCombat());
            if (!engine.isEmpty()) {
                trace("por que no se puede: ataque | motor=%s", engine);
                showWhy(NeoText.get("why.atk.head") + "\n" + String.join("\n", engine));
                return;
            }
            final java.util.List<String> quoted = WhyNot.recentCompulsion(gv, 3);
            trace("por que no se puede: ataque | registro=%d lineas", quoted.size());
            showWhy(quoted.isEmpty()
                    ? NeoText.get("why.attack")
                    : NeoText.get("why.attack") + "\n\n"
                            + NeoText.get("why.log", String.join("\n", quoted)));
            return;
        }

        final String menaced = menacedWithOneBlocker(gv == null ? null : gv.getCombat());
        final String translated = WhyNot.translate(message);
        trace("por que no se puede: amenaza=%s | traducido=%s | motor=%s",
                menaced == null ? "(ninguna)" : menaced,
                translated == null ? "(no)" : "si", message.trim());

        if (menaced != null) {
            showWhy(NeoText.get("why.menace", menaced));
        } else if (translated != null) {
            showWhy(translated);
        } else {

            showInfo(NeoText.get("engine.says", message.trim()));
        }
    }

    private void showInfo(final String text) {
        if (!interactive() || table == null) {
            return;
        }
        final UiDispatcher ui = uiDispatcher();
        if (ui == null) {
            return;
        }
        ui.runLater(() -> table.getActionBar().setWarning(text, false));
    }

    public static String menacedWithOneBlocker(final forge.game.combat.CombatView combat) {
        try {
            if (combat == null || combat.getAttackers() == null) {
                return null;
            }
            final StringBuilder names = new StringBuilder();
            for (final CardView attacker : combat.getAttackers()) {
                if (attacker == null || attacker.getCurrentState() == null) {
                    continue;
                }
                if (!attacker.getCurrentState().hasKeyword(forge.game.keyword.Keyword.MENACE)) {
                    continue;
                }
                if (blockerCount(combat, attacker) != 1) {
                    continue;
                }
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(forge.neo.card.CardText.nameOf(attacker.getCurrentState()));
            }
            return names.length() == 0 ? null : names.toString();
        } catch (final RuntimeException e) {
            
            return null;
        }
    }

    private static int blockerCount(final forge.game.combat.CombatView combat,
                                    final CardView attacker) {

        final var planned = combat.getPlannedBlockers(attacker);
        if (planned != null && !planned.isEmpty()) {
            return planned.size();
        }
        final var blockers = combat.getBlockers(attacker);
        return blockers == null ? 0 : blockers.size();
    }

    private static final String ATTACK_INVALID = "Attack declaration invalid";

    private void showWhy(final String text) {
        if (!interactive() || table == null) {
            return;
        }
        final UiDispatcher ui = uiDispatcher();
        if (ui == null) {
            return;
        }
        ui.runLater(() -> table.getActionBar().setWarning(text));
    }

    @Override
    public void showErrorDialog(final String message, final String title) {
        System.err.println("  [ERROR] " + title + ": " + message);
        if (!interactive() || table == null) {
            return;
        }
        final UiDispatcher ui = uiDispatcher();
        if (ui == null) {
            return;
        }
        ui.runLater(() -> {
            final ConfirmDialog dialog = new ConfirmDialog(
                    title, message, List.of(NeoText.get("common.accept")), 0,
                    index -> table.getOverlay().hide());
            table.getOverlay().show(dialog);
        });
    }

    private final java.util.Set<PhaseType> stops = loadStops(forge.neo.NeoSettings.PHASE_STOPS_MINE);
    private final java.util.Set<PhaseType> theirStops = loadStops(forge.neo.NeoSettings.PHASE_STOPS_THEIRS);

    private static final java.util.Set<PhaseType> DEFAULT_STOPS = java.util.EnumSet.of(
            PhaseType.MAIN1,
            PhaseType.MAIN2,
            PhaseType.COMBAT_DECLARE_ATTACKERS,
            PhaseType.COMBAT_DECLARE_BLOCKERS);

    private static java.util.Set<PhaseType> loadStops(final String key) {
        final java.util.Set<PhaseType> set = java.util.EnumSet.noneOf(PhaseType.class);
        final String raw = forge.neo.NeoSettings.get(key, null);
        if (raw == null || raw.isBlank()) {
            set.addAll(DEFAULT_STOPS);
        } else {
            for (final String name : raw.split(",")) {
                try {
                    set.add(PhaseType.valueOf(name.trim()));
                } catch (final IllegalArgumentException ignored) {
                    
                }
            }
        }
        return java.util.Collections.synchronizedSet(set);
    }

    public void toggleStop(final boolean mine, final PhaseType phase) {
        final java.util.Set<PhaseType> set = mine ? stops : theirStops;
        if (!set.remove(phase)) {
            set.add(phase);
        }
        if (eventSpy == null) {
            final StringBuilder sb = new StringBuilder();
            synchronized (set) {
                for (final PhaseType p : set) {
                    sb.append(sb.length() == 0 ? "" : ",").append(p.name());
                }
            }
            forge.neo.NeoSettings.set(mine ? forge.neo.NeoSettings.PHASE_STOPS_MINE
                    : forge.neo.NeoSettings.PHASE_STOPS_THEIRS, sb.length() == 0 ? "-" : sb.toString());
            forge.neo.NeoSettings.save();
        }
    }

    public boolean hasStop(final boolean mine, final PhaseType phase) {
        return (mine ? stops : theirStops).contains(phase);
    }

    @Override
    public boolean isUiSetToSkipPhase(final PlayerView player, final PhaseType phase) {
        if (phase == null) {
            return false;
        }

        final boolean mine = player == null || isLocalPlayer(player);
        if ((mine ? stops : theirStops).contains(phase)) {

            if (!forge.gui.FThreads.isGuiThread()
                    && getGameController() instanceof forge.player.PlayerControllerHuman human) {
                NewlyCastable.observe(human.getPlayer());

                MainPhaseActivity.observe(human.getPlayer());
            }
            return false;
        }

        return !(!forge.gui.FThreads.isGuiThread()
                && getGameController() instanceof forge.player.PlayerControllerHuman human
                && (PhaseOnlyAbilities.worthStopping(human.getPlayer(), phase)

                        || NewlyCastable.worthStopping(human.getPlayer())));
    }

    private final AtomicBoolean selectionZoneShown = new AtomicBoolean();

    private boolean showSelectionZone(final Iterable<CardView> cards) {
        if (!interactive() || cards == null) {
            return false;
        }
        for (final CardView c : cards) {
            final ZoneType zone = c == null ? null : c.getZone();
            if (zone == null || c.getOwner() == null || zone == ZoneType.Battlefield
                    || zone == ZoneType.Hand || zone == ZoneType.Stack) {
                continue;
            }

            final PlayerView owner = c.getOwner();
            final UiDispatcher ui = uiDispatcher();
            if (ui != null) {
                ui.runLater(() -> table.showZone(owner, zone));
            }
            return true;
        }
        return false;
    }

    @Override
    public void openZones(final PlayerView controller, final Collection<ZoneType> zones,
                          final Map<PlayerView, Object> players) {
        if (!interactive() || zones == null || players == null) {
            return;
        }
        for (final ZoneType zone : zones) {
            if (zone == null || zone == ZoneType.Battlefield || zone == ZoneType.Hand
                    || zone == ZoneType.Stack) {
                continue;
            }
            for (final PlayerView owner : players.keySet()) {
                if (owner != null && owner.getZoneSize(zone) > 0) {
                    final UiDispatcher ui = uiDispatcher();
                    if (ui != null) {
                        ui.runLater(() -> table.showZone(owner, zone));
                    }
                    return;
                }
            }
        }
    }
}
