package forge.neo.ui;

import forge.neo.NeoText;
import forge.neo.card.CardText;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.neo.card.CardNode;
import forge.neo.tutorial.Gesture;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Line;

public class TableScreen extends Pane {

    private static final double LINE_H = 14;
    private static final double PAD = 10;

    private final double cardWidth;
    private final double sideWidth;

    private final OpponentTabs opponentTabs = new OpponentTabs();
    private final PlayerBar opponentBar = new PlayerBar(true);
    private final PlayerBar selfBar = new PlayerBar(false);
    private final PlayerField opponentField;
    private final PlayerField selfField;

    private final List<PlayerBar> oppBars = new ArrayList<>();
    private final List<PlayerField> oppFields = new ArrayList<>();

    private final List<Line> seatDividers = new ArrayList<>();

    private static final double MULTI_OPP_SHARE = 0.52;

    private static final double SEAT_GAP = 10;
    private final HandFan hand;
    
    private final CardNode handPeek;
    private final CommandZone commandZone;
    private final PhaseRail phaseRail = new PhaseRail();
    private final CardDetailPanel detail;
    
    private final Region sideSpace = new Region();
    private final VBox stackBox = new VBox(6);
    private final ActionBar actionBar = new ActionBar();
    private final CombatOverlay combatOverlay = new CombatOverlay();
    
    private final ImpactLayer impactLayer = new ImpactLayer();
    private final Overlay overlay = new Overlay();
    private final PromptBanner promptBanner;
    private final TurnBanner turnBanner;
    
    private final CoinFlip coinFlip = new CoinFlip();
    
    private final DiceRoll diceRoll = new DiceRoll();

    private final Button logButton = new Button(NeoText.get("table.log"));

    private final Button turnButton = new Button(NeoText.get("turnStats.button"));

    private final Button menuButton = new Button(NeoText.get("table.menu"));

    private final Button macroRecordButton = new Button(NeoText.get("macro.record"));
    private final Button macroPlayButton = new Button(NeoText.get("macro.play"));

    private final ImageCooldownBadge cooldownBadge = new ImageCooldownBadge();

    private final Overlay menuOverlay = new Overlay();

    private final Overlay zoomOverlay = new Overlay();

    private long lastZoomStep;

    private final Overlay curtain = new Overlay();
    private volatile boolean curtainUp;

    public void showCurtain(final String who) {
        final javafx.scene.control.Label title = new javafx.scene.control.Label(
                forge.neo.NeoText.get("hotseat.curtain.title", who));
        title.getStyleClass().add("hotseat-curtain-title");
        title.setWrapText(true);
        final javafx.scene.control.Label hint = new javafx.scene.control.Label(
                forge.neo.NeoText.get("hotseat.curtain.hint", who));
        hint.getStyleClass().add("hotseat-curtain-hint");
        hint.setWrapText(true);
        final javafx.scene.control.Button go = new javafx.scene.control.Button(
                forge.neo.NeoText.get("hotseat.curtain.go", who));
        go.getStyleClass().addAll("btn-primary", "btn-play");
        go.setOnAction(e -> hideCurtain());
        final javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(18, title, hint, go);
        box.setAlignment(javafx.geometry.Pos.CENTER);
        box.setMaxWidth(forge.neo.ui.UiScale.px(720));
        curtain.show(box);
        curtainUp = true;
        javafx.application.Platform.runLater(go::requestFocus);

        final int auto = Integer.getInteger("neo.hotseat.autoReveal", 0);
        if (auto > 0) {
            final javafx.animation.PauseTransition later =
                    new javafx.animation.PauseTransition(javafx.util.Duration.millis(auto));
            later.setOnFinished(e -> {
                if (curtain.getChildren().contains(box)) {
                    hideCurtain();
                }
            });
            later.play();
        }
    }

    public void hideCurtain() {
        curtainUp = false;
        curtain.hide();
    }

    public boolean isCurtainUp() {
        return curtainUp;
    }

    private final Label playerDetails = new Label();

    private final javafx.scene.image.ImageView playmat = new javafx.scene.image.ImageView();
    private final javafx.scene.shape.Rectangle playmatVeil = new javafx.scene.shape.Rectangle();

    private final ArenaBackdrop arenaBackdrop = new ArenaBackdrop();
    private final VBox side;

    private boolean folded;
    private boolean slimBars;
    private final Button stackChip = new Button();
    
    private boolean stackPeek;
    private final Line combatLine = new Line();

    private final List<CardNode> extraNodes = new ArrayList<>();
    private Consumer<CardNode> onCardHover;

    private CoachPanel coach;

    private final javafx.scene.shape.Rectangle spotlight =
            new javafx.scene.shape.Rectangle();

    private String spotName;

    private Consumer<String> gestureSpy;

    public TableScreen(final double cardWidth, final double sideWidth) {

        Rtl.keepLtr(this);
        this.cardWidth = cardWidth;

        sceneProperty().addListener((o, was, is) -> {
            if (is != null) {
                live = new java.lang.ref.WeakReference<>(this);
            } else {
                aiThinking.stop();
            }
        });
        this.sideWidth = sideWidth;
        this.hand = new HandFan(cardWidth * 1.12);
        this.handPeek = new CardNode(cardWidth * 2.4);
        handPeek.setHoverEnabled(false);
        handPeek.setRotationEnabled(false);

        handPeek.setBlankWhileLoading(true);
        handPeek.setMouseTransparent(true);
        handPeek.setManaged(false);
        handPeek.setVisible(false);
        handPeek.getStyleClass().add("hand-peek");
        this.commandZone = new CommandZone(cardWidth * 1.12);
        this.detail = new CardDetailPanel(sideWidth - 52);
        this.opponentField = new PlayerField(cardWidth * 0.92, true);
        this.selfField = new PlayerField(cardWidth, false);

        getStyleClass().add("table-root");

        opponentField.setOnCardHover(this::hovered);
        selfField.setOnCardHover(this::hovered);
        opponentField.setOnCardClick(this::cardClicked);
        selfField.setOnCardClick(this::cardClicked);
        opponentField.setOnStackCountClick(this::stackCountClicked);
        selfField.setOnStackCountClick(this::stackCountClicked);
        opponentField.setOnAttachPeek((host, carried) -> showAttached(host, carried, false));
        selfField.setOnAttachPeek((host, carried) -> showAttached(host, carried, true));

        oppBars.add(opponentBar);
        oppFields.add(opponentField);

        combatLine.getStyleClass().add("combat-line");
        hand.getStyleClass().add("hand-area");

        final Label stackTitle = new Label(NeoText.get("table.stack"));
        stackTitle.getStyleClass().add("caption");
        stackBox.getStyleClass().add("stack-box");
        stackBox.setPadding(new Insets(8, 10, 8, 10));
        final Label stackEmpty = new Label(NeoText.get("table.stack.empty"));
        stackEmpty.getStyleClass().add("hud-sub");
        stackBox.getChildren().addAll(stackTitle, stackEmpty);

        VBox.setVgrow(sideSpace, Priority.ALWAYS);
        side = new VBox(10, stackBox, sideSpace, actionBar);
        side.getStyleClass().add("arena-sidebar");
        side.setPadding(new Insets(PAD));
        applyDetailSetting();

        stackChip.getStyleClass().add("stack-chip");
        stackChip.setFocusTraversable(false);
        stackChip.setVisible(false);
        stackChip.setOnAction(e -> {
            stackPeek = !stackPeek;
            requestLayout();
        });

        commandZone.setOnCardHover(this::hovered);

        commandZone.setOnCardClick(this::cardClicked);

        promptBanner = new PromptBanner(cardWidth * 1.55);
        turnBanner = new TurnBanner();
        logButton.getStyleClass().add("log-button");
        for (final Button b : List.of(macroRecordButton, macroPlayButton)) {
            b.getStyleClass().addAll("log-button", "macro-button");
            
            b.setFocusTraversable(false);
        }

        macroRecordButton.setVisible(false);
        macroPlayButton.setVisible(false);
        
        chatButton.getStyleClass().add("log-button");
        turnButton.getStyleClass().add("log-button");
        turnButton.setFocusTraversable(false);
        turnButton.setVisible(false);
        chatButton.setFocusTraversable(false);
        chatButton.setVisible(false);

        menuButton.getStyleClass().add("log-button");
        menuButton.setFocusTraversable(false);
        menuButton.setVisible(false);

        playerDetails.getStyleClass().add("player-details");
        playerDetails.setWrapText(true);
        playerDetails.setMouseTransparent(true);
        playerDetails.setVisible(false);
        playerDetails.setManaged(false);
        playerDetails.setMaxWidth(UiScale.px(420));
        playerDetails.setMinHeight(Region.USE_PREF_SIZE);
        hoverDetails(opponentBar, true);
        hoverDetails(selfBar, false);

        final Integer hoverAt = Integer.getInteger("neo.playerDetails.hover");
        if (hoverAt != null) {
            final javafx.animation.PauseTransition later =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(10));
            later.setOnFinished(e -> {
                if (hoverAt >= 0 && hoverAt < oppBars.size()
                        && oppBars.get(hoverAt).getOnMouseEntered() != null) {
                    oppBars.get(hoverAt).getOnMouseEntered().handle(null);
                    System.out.println("[detalles] raton sobre el rival " + hoverAt + ": "
                            + (playerDetails.isVisible() ? "sale" : "NO sale"));
                }
            });
            later.play();
        }

        playmat.setPreserveRatio(false);
        playmat.setMouseTransparent(true);
        playmatVeil.setMouseTransparent(true);
        playmatVeil.setFill(javafx.scene.paint.Color.web("#0B0E13", 0.62));
        getChildren().addAll(arenaBackdrop, playmat, playmatVeil);
        applyPlaymat();

        board.getChildren().addAll(opponentField, combatLine, selfField);
        viewport.getChildren().add(board);
        viewport.setClip(viewportClip);
        viewport.setPickOnBounds(false);
        board.setPickOnBounds(false);

        spotlight.getStyleClass().add("coach-spot");
        spotlight.setMouseTransparent(true);
        spotlight.setVisible(false);
        spotlight.setManaged(false);
        spotlight.setArcWidth(14);
        spotlight.setArcHeight(14);
        spotlight.setFill(javafx.scene.paint.Color.TRANSPARENT);

        getChildren().addAll(opponentTabs, opponentBar, viewport,
                selfBar, hand, commandZone, phaseRail, side, combatOverlay, impactLayer, logButton, turnButton, menuButton, chatButton, macroRecordButton, macroPlayButton, cooldownBadge,
                stackChip, promptBanner, notices, turnBanner, coinFlip, diceRoll, playerDetails, zoomBadge, macroBadge, spotlight,
                handPeek, overlay, menuOverlay, zoomOverlay, curtain);
        curtain.getStyleClass().add("hotseat-curtain");

        zoomOverlay.setOnBackgroundClick(this::hideZoom);

        final javafx.beans.value.ChangeListener<Boolean> watchPause =
                (o, was, is) -> playerReading =
                        zoomOverlay.isShowing() || menuOverlay.isShowing();
        zoomOverlay.visibleProperty().addListener(watchPause);
        menuOverlay.visibleProperty().addListener(watchPause);

        final javafx.event.EventHandler<MouseEvent> popOutHover = e -> {
            final CardNode n = cardNodeAt(e.getTarget(), (Node) e.getSource());
            if (n != null) {
                showBigCard(n);
            }
        };
        overlay.addEventFilter(MouseEvent.MOUSE_ENTERED_TARGET, popOutHover);
        menuOverlay.addEventFilter(MouseEvent.MOUSE_ENTERED_TARGET, popOutHover);

        overlay.setPeekable(forge.neo.NeoText.get("overlay.peek"),
                forge.neo.NeoText.get("overlay.back"));

        final javafx.event.EventHandler<MouseEvent> peekGuard = e -> {
            if (!overlay.isPeeking() || e.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                return;
            }
            final Object t = e.getTarget();
            if (isInside(t, overlay) || isInside(t, menuOverlay) || isInside(t, zoomOverlay)
                    || isInside(t, logButton) || isInside(t, turnButton) || isInside(t, menuButton)
                    || isInside(t, chatButton) || isInside(t, opponentTabs)
                    || isZoneOpener(t)) {
                return;
            }
            e.consume();
        };
        addEventFilter(MouseEvent.MOUSE_PRESSED, peekGuard);
        addEventFilter(MouseEvent.MOUSE_RELEASED, peekGuard);
        addEventFilter(MouseEvent.MOUSE_CLICKED, peekGuard);

        zoomOverlay.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (CardZoom.isInteractiveTarget(e.getTarget())) {
                return;
            }
            e.consume();
            hideZoom();
        });

        zoomOverlay.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (zoomRing.size() <= 1 || e.getDeltaY() == 0) {
                return;
            }
            e.consume();
            final long now = System.currentTimeMillis();
            if (now - lastZoomStep < 90) {
                return;
            }
            lastZoomStep = now;
            stepZoom(e.getDeltaY() < 0 ? 1 : -1);
        });
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (!zoomOverlay.isShowing() || zoomRing.size() <= 1) {
                return;
            }
            switch (e.getCode()) {
                case RIGHT, DOWN -> {
                    e.consume();
                    stepZoom(1);
                }
                case LEFT, UP -> {
                    e.consume();
                    stepZoom(-1);
                }
                default -> {
                }
            }
        });

        combatOverlay.setLocator(this::boundsOf);
        impactLayer.setLocator(this::impactBoundsOf);

        impactLayer.setManaged(false);
        opponentBar.setOnZoneClicked(this::showZone);
        opponentBar.setOnCommanderHover(this::hovered);
        selfBar.setOnZoneClicked(this::showZone);

        opponentField.setOnZoneClicked(this::showZone);
        selfField.setOnZoneClicked(this::showZone);

        installBoardZoom();
        installDragGestures();
    }

    private void setFolded(final boolean on) {
        folded = on;
        stackPeek = false;
        if (on) {
            side.getChildren().removeAll(stackBox, actionBar);
            final int at = getChildren().indexOf(promptBanner);
            getChildren().addAll(at < 0 ? getChildren().size() : at, List.of(stackBox, actionBar));
            stackBox.getStyleClass().add("stack-floating");
            actionBar.setInline(true);
            side.setVisible(false);
            stackChip.setVisible(true);
        } else {
            getChildren().removeAll(stackBox, actionBar);
            stackBox.getStyleClass().remove("stack-floating");
            stackBox.setVisible(true);
            side.getChildren().add(0, stackBox);
            side.getChildren().add(actionBar);
            actionBar.setInline(false);
            side.setVisible(true);
            stackChip.setVisible(false);
            selfBar.setRightReserve(0);
        }
        refreshStackChip();
    }

    private void refreshStackChip() {
        stackChip.setText(stackSize == 0 ? NeoText.get("table.stack")
                : NeoText.get("table.stack.count", stackSize));
        stackChip.pseudoClassStateChanged(ACTIVE_STACK, stackSize > 0);
        if (stackSize == 0) {
            stackPeek = false;
        }
    }

    private static final javafx.css.PseudoClass ACTIVE_STACK =
            javafx.css.PseudoClass.getPseudoClass("active");

    private void syncSpaceSettings() {
        final boolean wantFold = forge.neo.NeoSettings.getBool(forge.neo.NeoSettings.SIDE_FOLDED, false);
        if (wantFold != folded) {
            setFolded(wantFold);
        }
        slimBars = forge.neo.NeoSettings.getBool(forge.neo.NeoSettings.SLIM_BARS, false);
        barBesideHand = !folded && (Boolean.getBoolean("neo.table.barBesideHand")
                || forge.neo.NeoSettings.getBool(forge.neo.NeoSettings.SELF_BAR_BESIDE_HAND, false));

        selfBar.setCompact(barBesideHand);
        selfBar.setSlim(slimBars);
        opponentBar.setSlim(slimBars);
        for (final PlayerBar b : oppBars) {
            b.setSlim(slimBars);
        }
    }

    private boolean barBesideHand;

    @Override
    protected void layoutChildren() {
        syncSpaceSettings();
        final double w = getWidth();
        final double h = getHeight();
        final double panelW = Math.max(230, Math.min(UiScale.sideWidthNow(sideWidth), w * 0.145));
        final double railW = folded ? 0 : panelW;
        final double contentW = Math.max(200, w - railW);
        arenaBackdrop.resizeRelocate(0, 0, contentW, h);

        if (!folded) {
            side.resizeRelocate(contentW, 0, railW, h);
        }

        double abW = 0;
        double abH = 0;
        double chipW = 0;
        if (folded) {
            abW = Math.min(UiScale.px(620), Math.max(UiScale.px(400), contentW * 0.40));
            abH = actionBar.prefHeight(abW);
            chipW = stackChip.prefWidth(-1);
            selfBar.setRightReserve(abW + chipW + PAD * 3);
        }

        if (playerDetails.isVisible()) {
            final double pw = Math.min(420, contentW - 2 * PAD);
            final double ph = playerDetails.prefHeight(pw);

            final PlayerBar anchor = detailsAnchor != null && detailsAnchor.getParent() == this
                    ? detailsAnchor : (detailsBelow ? opponentBar : selfBar);
            final javafx.geometry.Bounds ab = anchor.getBoundsInParent();
            final double py = detailsBelow ? ab.getMaxY() + 6 : ab.getMinY() - ph - 6;
            final double px = Math.max(PAD, Math.min(ab.getMinX() + PAD, contentW - pw - PAD));
            playerDetails.resizeRelocate(px, Math.max(PAD, py), pw, ph);
        }

        playmat.setFitWidth(w);
        playmat.setFitHeight(h);
        playmatVeil.setWidth(w);
        playmatVeil.setHeight(h);

        final double lbW = logButton.prefWidth(-1);
        final double lbH = logButton.prefHeight(lbW);
        logButton.resizeRelocate(PAD, h - lbH - PAD, lbW, lbH);

        double cornerTop = h - lbH - PAD;
        double mbW = Math.max(Math.max(lbW, chatButton.prefWidth(-1)),
                Math.max(macroRecordButton.prefWidth(-1), macroPlayButton.prefWidth(-1)));
        if (turnButton.isVisible()) {
            mbW = Math.max(mbW, turnButton.prefWidth(-1));
        }

        if (menuButton.isVisible()) {
            mbW = Math.max(mbW, menuButton.prefWidth(-1));
        }
        for (final Button b : List.of(turnButton, menuButton, chatButton, macroRecordButton, macroPlayButton)) {
            if (b.isVisible()) {
                final double bh = b.prefHeight(mbW);
                cornerTop -= bh + 4;
                b.resizeRelocate(PAD, cornerTop, mbW, bh);
            }
        }
        combatOverlay.resizeRelocate(0, 0, w, h);
        impactLayer.resizeRelocate(0, 0, w, h);

        if (cooldownBadge.isVisible()) {
            final double cbW = cooldownBadge.prefWidth(-1);
            final double cbH = cooldownBadge.prefHeight(cbW);
            cooldownBadge.resizeRelocate(PAD, cornerTop - cbH - 6, cbW, cbH);
        }

        if (turnBanner.isVisible()) {
            final double bw = Math.min(UiScale.px(TurnBanner.MAX_WIDTH),
                    Math.max(turnBanner.bannerWidth(), contentW * 0.42));
            final double bh = turnBanner.bannerHeight(bw);
            turnBanner.resizeRelocate((contentW - bw) / 2, h * 0.13, bw, bh);
        }

        if (coinFlip.isVisible()) {
            final double cw = coinFlip.prefWidth(-1);
            final double ch = coinFlip.prefHeight(cw);
            coinFlip.resizeRelocate((contentW - cw) / 2, (h - ch) / 2, cw, ch);
        }
        
        if (diceRoll.isVisible()) {
            final double dw = diceRoll.prefWidth(-1);
            final double dh = diceRoll.prefHeight(dw);
            diceRoll.resizeRelocate((contentW - dw) / 2, (h - dh) / 2, dw, dh);
        }
        overlay.resizeRelocate(0, 0, w, h);
        menuOverlay.resizeRelocate(0, 0, w, h);
        zoomOverlay.resizeRelocate(0, 0, w, h);
        curtain.resizeRelocate(0, 0, w, h);

        double coachH = 0;
        if (coach != null && coach.isVisible()) {
            coachH = coach.prefHeight(contentW);
            coach.resizeRelocate(0, 0, contentW, coachH);
        }

        final boolean multi = isMultiBoard();
        final double seatW = multi
                ? (contentW - SEAT_GAP * (oppFields.size() - 1)) / oppFields.size()
                : contentW;

        final double tabsH = multi ? 0 : opponentTabs.barHeight();

        double oppBarH = 0;
        for (final PlayerBar b : oppBars) {
            oppBarH = Math.max(oppBarH, b.prefHeight(seatW));
        }

        final double cardX = PAD + mbW + PAD;

        final double cardW = barBesideHand
                ? Math.min(contentW * 0.36, Math.max(UiScale.px(300), selfBar.prefWidth(-1) + 4))
                : 0;
        final double cardH = barBesideHand ? selfBar.prefHeight(cardW) : 0;
        final double selfBarH = barBesideHand ? 0 : Math.max(selfBar.prefHeight(contentW), abH);
        double handH = Math.min(hand.prefHeight(contentW), h * 0.205);
        
        handH = Math.max(handH, cardH);

        final double fixed = coachH + phaseRail.arenaHeight(contentW) + tabsH + oppBarH + selfBarH + LINE_H;
        final double minFields = cardWidth * 1.2;
        if (fixed + handH + minFields > h) {
            handH = Math.max(cardWidth * 0.7, h - fixed - minFields);
        }

        final double fieldsH = Math.max(0, h - fixed - handH);

        final double oppH = multi ? fieldsH * MULTI_OPP_SHARE : fieldsH / 2.0;
        final double selfH = fieldsH - oppH;

        final double phaseH = phaseRail.arenaHeight(contentW);
        phaseRail.resizeRelocate(0, coachH, contentW, phaseH);
        double y = coachH + phaseH;
        if (tabsH > 0) {
            opponentTabs.resizeRelocate(0, y, contentW, tabsH);
            y += tabsH;
        }
        for (int i = 0; i < oppBars.size(); i++) {
            oppBars.get(i).resizeRelocate(i * (seatW + SEAT_GAP), y, seatW, oppBarH);
        }
        y += oppBarH;

        final double boardTop = y;
        final double boardH = oppH + selfH + LINE_H;
        viewport.resizeRelocate(0, boardTop, contentW, boardH);
        viewportClip.setWidth(contentW);
        viewportClip.setHeight(boardH);
        board.resizeRelocate(0, 0, contentW, boardH);
        applyBoardTransform();

        for (int i = 0; i < oppFields.size(); i++) {
            oppFields.get(i).resizeRelocate(i * (seatW + SEAT_GAP), 0, seatW, oppH);
        }

        for (int i = 0; i < seatDividers.size(); i++) {
            final double x = (i + 1) * (seatW + SEAT_GAP) - SEAT_GAP / 2;
            final Line d = seatDividers.get(i);
            d.setStartX(x);
            d.setEndX(x);
            d.setStartY(0);
            d.setEndY(oppH);
        }
        y += oppH;

        combatLine.setStartX(PAD * 3);
        combatLine.setEndX(contentW - PAD * 3);
        combatLine.setStartY(y - boardTop + LINE_H / 2);
        combatLine.setEndY(y - boardTop + LINE_H / 2);

        if (promptBanner.isVisible()) {
            final double bw = Math.min(contentW - PAD * 4, promptBanner.prefWidth(-1));
            final double bh = promptBanner.prefHeight(bw);
            promptBanner.resizeRelocate((contentW - bw) / 2, y + LINE_H / 2 - bh / 2, bw, bh);
            
            promptBanner.fitInto(contentW, getHeight());
        }

        y += LINE_H;

        selfField.resizeRelocate(0, y - boardTop, contentW, selfH);
        y += selfH;

        if (zoomBadge.isVisible()) {
            final double bw = zoomBadge.prefWidth(-1);
            final double bh = zoomBadge.prefHeight(bw);
            zoomBadge.resizeRelocate(PAD, boardTop + PAD, bw, bh);
        }

        if (macroBadge.isVisible()) {
            final double mw = macroBadge.prefWidth(-1);
            final double mh = macroBadge.prefHeight(mw);
            final double my = boardTop + PAD
                    + (zoomBadge.isVisible() ? zoomBadge.prefHeight(-1) + 6 : 0);
            macroBadge.resizeRelocate(PAD, my, mw, mh);
        }

        if (notices.hasNotices()) {
            final double nw = Math.min(380, Math.max(220, contentW * 0.34));
            final double nh = Math.min(boardH - PAD * 2, notices.prefHeight(nw));
            notices.resizeRelocate(contentW - PAD - nw, boardTop + PAD, nw, nh);
        }

        if (!barBesideHand) {
            selfBar.resizeRelocate(0, y, contentW, selfBarH);
        }
        if (folded) {
            actionBar.resizeRelocate(w - abW - PAD, y + (selfBarH - abH) / 2, abW, abH);
            final double chipH = stackChip.prefHeight(chipW);
            stackChip.resizeRelocate(w - abW - PAD - 8 - chipW, y + (selfBarH - chipH) / 2, chipW, chipH);

            if (Boolean.getBoolean("neo.table.stackPeek")) {
                stackPeek = true;
            }
            stackBox.setVisible(stackPeek);
            if (stackPeek) {
                final double room = Math.max(UiScale.px(120), y - boardTop - PAD);
                final double sh = Math.min(stackBox.prefHeight(panelW), room);
                stackBox.resizeRelocate(w - panelW - PAD, y - sh - 6, panelW, sh);
            }
        }
        y += selfBarH;

        final double handArea = Math.max(0, h - y);

        final double cmdW = Math.min(contentW * 0.34, commandZone.preferredWidth() + 16);
        if (barBesideHand) {
            
            final double cy = y + Math.max(0, handArea - cardH - PAD);
            selfBar.resizeRelocate(cardX, cy, cardW, Math.min(cardH, handArea));
            final double handX = cardX + cardW + PAD;
            hand.resizeRelocate(handX, y, Math.max(120, contentW - cmdW - handX), handArea);
        } else {
            hand.resizeRelocate(0, y, Math.max(120, contentW - cmdW), handArea);
        }
        commandZone.resizeRelocate(contentW - cmdW, y, cmdW, handArea);

        layoutSpotlight();
    }

    public void setCoach(final CoachPanel panel) {
        if (coach != null) {
            getChildren().remove(coach);
        }
        coach = panel;
        if (coach != null) {
            getChildren().add(getChildren().indexOf(spotlight), coach);
        }
        requestLayout();
    }

    public CoachPanel getCoach() {
        return coach;
    }

    public void spotlight(final String name) {
        this.spotName = name;
        requestLayout();
    }

    private void layoutSpotlight() {
        final Region target = spotTarget(spotName);
        if (target == null || target.getWidth() <= 1 || target.getHeight() <= 1) {
            spotlight.setVisible(false);
            return;
        }

        final Bounds b = sceneToLocal(target.localToScene(target.getBoundsInLocal()));
        final double pad = 5;

        final double x0 = Math.max(1, b.getMinX() - pad);
        final double y0 = Math.max(1, b.getMinY() - pad);
        final double x1 = Math.min(getWidth() - 1, b.getMaxX() + pad);
        final double y1 = Math.min(getHeight() - 1, b.getMaxY() + pad);
        if (x1 <= x0 || y1 <= y0) {
            spotlight.setVisible(false);
            return;
        }
        spotlight.setX(x0);
        spotlight.setY(y0);
        spotlight.setWidth(x1 - x0);
        spotlight.setHeight(y1 - y0);
        spotlight.setVisible(true);
    }

    private Region spotTarget(final String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "HAND" -> hand;
            case "BOARD_SELF" -> selfField;
            case "BOARD_OPP" -> opponentField;
            case "SIDE" -> actionBar;
            case "PHASES" -> phaseRail;
            case "STACK" -> stackBox;
            case "SELF_BAR" -> selfBar;
            case "OPP_BAR" -> opponentBar;
            case "COMMAND" -> commandZone;
            case "LOG" -> logButton;
            default -> null;
        };
    }

    public void setGestureSpy(final Consumer<String> spy) {
        this.gestureSpy = spy;
    }

    public void gesture(final String what) {
        final Consumer<String> spy = gestureSpy;
        if (spy != null) {
            spy.accept(what);
        }
    }

    private final Pane viewport = new Pane() {
        @Override
        protected void layoutChildren() {
            
        }
    };

    private final Pane board = new Pane() {
        @Override
        protected void layoutChildren() {
            
        }
    };

    private final javafx.scene.shape.Rectangle viewportClip =
            new javafx.scene.shape.Rectangle();

    private double zoom = 1;
    private double panX;
    private double panY;

    private static final double ZOOM_MIN = 1;
    private static final double ZOOM_MAX = 3;
    private static final double ZOOM_STEP = 1.12;

    private boolean panning;
    private double panPressX;
    private double panPressY;
    private double panFromX;
    private double panFromY;

    private final javafx.scene.control.Label zoomBadge = new javafx.scene.control.Label();

    private final NoticeStack notices = new NoticeStack();

    private final javafx.scene.control.Label macroBadge = macroBadge();

    private static javafx.scene.control.Label macroBadge() {
        final javafx.scene.control.Label l = new javafx.scene.control.Label(NeoText.get("macro.badge"));
        l.getStyleClass().add("macro-badge");
        l.setMouseTransparent(true);
        l.setVisible(false);
        l.setManaged(false);
        return l;
    }

    public void setMacroRecording(final boolean on) {
        setMacroState(on, macroPlayButton.isVisible() || on);
    }

    public void setMacroState(final boolean recording, final boolean hasMacro) {
        macroBadge.setVisible(recording);
        macroRecordButton.setText(NeoText.get(recording ? "macro.stop" : "macro.record"));
        macroRecordButton.pseudoClassStateChanged(RECORDING, recording);
        macroPlayButton.setVisible(hasMacro && !recording);
        requestLayout();
    }

    public Button getMacroRecordButton() {
        return macroRecordButton;
    }

    public Button getMacroPlayButton() {
        return macroPlayButton;
    }

    private static final javafx.css.PseudoClass RECORDING =
            javafx.css.PseudoClass.getPseudoClass("recording");

    public void showNotice(final String text) {
        notices.push(text);
        requestLayout();
    }

    public NoticeStack getNotices() {
        return notices;
    }

    private void installBoardZoom() {
        zoomBadge.getStyleClass().add("zoom-badge");
        UiScale.fixedFont(zoomBadge, 12);
        zoomBadge.setVisible(false);
        zoomBadge.setManaged(false);
        zoomBadge.setOnMouseClicked(e -> {
            e.consume();
            resetBoardZoom();
        });

        addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (!forge.neo.platform.NeoOs.ctrl(e) || isViewBlocked()) {
                return;
            }

            if (forge.neo.platform.NeoOs.MAC && (e.isInertia() || e.getDeltaY() == 0)) {
                e.consume();
                return;
            }
            final Point2D m = viewport.sceneToLocal(e.getSceneX(), e.getSceneY());
            zoomAt(zoom * (e.getDeltaY() >= 0 ? ZOOM_STEP : 1 / ZOOM_STEP),
                    m.getX(), m.getY());
            e.consume();
        });

        if (forge.neo.platform.NeoOs.MAC) {
            addEventFilter(javafx.scene.input.ZoomEvent.ZOOM, e -> {
                if (isViewBlocked()) {
                    return;
                }
                final Point2D m = viewport.sceneToLocal(e.getSceneX(), e.getSceneY());
                zoomAt(zoom * e.getZoomFactor(), m.getX(), m.getY());
                e.consume();
            });
        }

        addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (isViewBlocked() || !isPanGesture(e)) {
                return;
            }
            panning = true;
            panClick = true;
            panPressX = e.getSceneX();
            panPressY = e.getSceneY();
            panFromX = panX;
            panFromY = panY;
            setCursor(javafx.scene.Cursor.CLOSED_HAND);
            e.consume();
        });

        addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!panning) {
                return;
            }
            panX = panFromX + (e.getSceneX() - panPressX);
            panY = panFromY + (e.getSceneY() - panPressY);
            applyBoardTransform();
            gesture(Gesture.BOARD_PAN);
            e.consume();
        });

        addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (!panning) {
                return;
            }
            panning = false;
            setCursor(null);
            e.consume();
        });

        addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
            if (panClick) {
                panClick = false;
                e.consume();
            }
        });
    }

    private boolean panClick;

    private boolean isPanGesture(final MouseEvent e) {
        return zoom > 1.001
                && (e.getButton() == javafx.scene.input.MouseButton.MIDDLE
                    || (e.getButton() == javafx.scene.input.MouseButton.PRIMARY
                        && forge.neo.platform.NeoOs.panModifier(e)));
    }

    private void zoomAt(final double wanted, final double mx, final double my) {
        final double next = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, wanted));
        if (Math.abs(next - zoom) < 0.0001) {
            return;
        }

        final double cx = board.getWidth() / 2;
        final double cy = board.getHeight() / 2;
        panX = mx - cx - (next / zoom) * (mx - cx - panX);
        panY = my - cy - (next / zoom) * (my - cy - panY);
        zoom = next;
        applyBoardTransform();
        gesture(Gesture.BOARD_ZOOM);
    }

    public void resetBoardZoom() {
        zoom = 1;
        panX = 0;
        panY = 0;
        applyBoardTransform();
    }

    private void applyBoardTransform() {

        final double maxX = Math.max(0, (zoom - 1) * board.getWidth() / 2);
        final double maxY = Math.max(0, (zoom - 1) * board.getHeight() / 2);
        panX = Math.max(-maxX, Math.min(maxX, panX));
        panY = Math.max(-maxY, Math.min(maxY, panY));

        board.setScaleX(zoom);
        board.setScaleY(zoom);
        board.setTranslateX(panX);
        board.setTranslateY(panY);

        sharpenAfterZoom.playFromStart();

        final boolean on = zoom > 1.001;
        zoomBadge.setText(NeoText.get("table.zoom", String.format("%.1f", zoom)));
        if (zoomBadge.isVisible() != on) {
            
            zoomBadge.setVisible(on && !Boolean.getBoolean("neo.board.noBadge"));
            requestLayout();
        }
        if (!on) {
            setCursor(null);
        }
    }

    private final javafx.animation.PauseTransition sharpenAfterZoom = createSharpenAfterZoom();

    private javafx.animation.PauseTransition createSharpenAfterZoom() {
        final javafx.animation.PauseTransition p =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(180));
        p.setOnFinished(e -> CardNode.refreshAllIn(board));
        return p;
    }

    public double boardZoom() {
        return zoom;
    }

    public void setOpponentBattlefield(final List<CardView> cards) {
        setOpponentBattlefield(0, cards);
    }

    public void setOpponentBattlefield(final int seat, final List<CardView> cards) {
        while (oppCards.size() < oppFields.size()) {
            oppCards.add(List.of());
        }
        if (seat < 0 || seat >= oppCards.size()) {
            return;
        }
        oppCards.set(seat, cards == null ? List.of() : cards);
        repaintFields();
    }

    public void setSelfBattlefield(final List<CardView> cards) {
        selfCards = cards == null ? List.of() : cards;
        repaintFields();
    }

    private final List<List<CardView>> oppCards = new ArrayList<>(List.of(List.of()));
    private List<CardView> selfCards = List.of();

    private void repaintFields() {

        final List<CardView> everywhere = new ArrayList<>(selfCards);
        for (final List<CardView> board : oppCards) {
            everywhere.addAll(board);
        }
        for (int i = 0; i < oppFields.size(); i++) {
            oppFields.get(i).setCards(
                    i < oppCards.size() ? oppCards.get(i) : List.of(), everywhere);
        }
        selfField.setCards(selfCards, everywhere);
        reindex();
    }

    public void setOpponentZonePiles(final int graveyard, final int exile) {
        setOpponentZonePiles(graveyard, exile, false, false);
    }

    public void setOpponentZonePiles(final int graveyard, final int exile,
                                     final boolean gravePlayable, final boolean exilePlayable) {
        setOpponentZonePiles(0, graveyard, exile, gravePlayable, exilePlayable);
    }

    public void setOpponentZonePiles(final int seat, final int graveyard, final int exile,
                                     final boolean gravePlayable, final boolean exilePlayable) {
        if (seat < 0 || seat >= oppFields.size()) {
            return;
        }
        oppFields.get(seat).setZoneOwner(oppBars.get(seat).getPlayer());
        oppFields.get(seat).setZoneCounts(graveyard, exile, gravePlayable, exilePlayable);
    }

    public void setOpponentCommand(final int seat, final List<CardView> cards) {
        if (seat < 0 || seat >= oppFields.size()) {
            return;
        }
        oppFields.get(seat).setCommand(cards);
        if (seat < oppBars.size()) {
            oppBars.get(seat).setCommanders(cards);
        }
    }

    public PlayerBar opponentBar(final int seat) {
        return oppBars.get(Math.max(0, Math.min(seat, oppBars.size() - 1)));
    }

    public PlayerField opponentField(final int seat) {
        return oppFields.get(Math.max(0, Math.min(seat, oppFields.size() - 1)));
    }

    public void setSelfZonePiles(final int graveyard, final int exile) {
        setSelfZonePiles(graveyard, exile, false, false);
    }

    public void setSelfZonePiles(final int graveyard, final int exile,
                                 final boolean gravePlayable, final boolean exilePlayable) {
        selfField.setZoneOwner(selfBar.getPlayer());
        selfField.setZoneCounts(graveyard, exile, gravePlayable, exilePlayable);
    }

    public void setSelfLibraryPile(final int size, final CardView top) {
        selfField.setLibrary(size, top != null && mayView.test(top) ? top : null);
    }

    private List<CardView> lastHand = List.of();

    private static List<CardView> orderedHand(final List<CardView> cards) {
        return forge.neo.card.HandOrder.sorted(cards);
    }

    public static void reorderHandsIn(final javafx.scene.Node root) {
        if (root instanceof TableScreen t) {
            t.setHand(t.lastHand);
            return;
        }
        if (root instanceof javafx.scene.Parent parent) {
            for (final javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                reorderHandsIn(child);
            }
        }
    }

    public void setHand(final List<CardView> cards) {
        lastHand = cards == null ? List.of() : List.copyOf(cards);
        final List<CardView> order = forge.neo.NeoSettings.orderHand() ? orderedHand(lastHand) : lastHand;

        if (sameHand(order)) {
            for (int i = 0; i < order.size(); i++) {
                showInHand(extraNodes.get(i), order.get(i));
            }
            reindex();
            return;
        }
        final java.util.Map<Integer, CardNode> old = new java.util.HashMap<>();
        for (final CardNode n : extraNodes) {
            if (n.getCard() != null && n.getCard().getId() >= 0) {
                old.put(n.getCard().getId(), n);
            }
        }
        hand.clearCards();
        extraNodes.clear();
        for (final CardView cv : order) {
            CardNode n = cv != null && cv.getId() >= 0 ? old.remove(cv.getId()) : null;
            if (n == null) {
                n = newHandNode();
            }
            showInHand(n, cv);
            extraNodes.add(n);
            hand.add(n);
        }
        reindex();
    }

    private boolean sameHand(final List<CardView> order) {
        if (order.size() != extraNodes.size()) {
            return false;
        }
        for (int i = 0; i < order.size(); i++) {
            final CardView now = order.get(i);
            final CardView was = extraNodes.get(i).getCard();
            if (now == null || was == null || now.getId() < 0 || now.getId() != was.getId()) {
                return false;
            }
        }
        return true;
    }

    private static void showInHand(final CardNode n, final CardView cv) {
        if (n.getCard() == cv) {
            n.refresh();
        } else {
            n.setCard(cv);
        }
    }

    private CardNode newHandNode() {
        final CardNode n = new CardNode(cardWidth * 1.12);
        n.hoverProperty().addListener((o, was, is) -> {
            if (is) {
                hovered(n);
            }
        });
        n.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                cardClicked(n);
            }
        });
        return n;
    }

    public void setOpponentSeats(final int seats) {
        final int want = Math.max(1, seats);
        if (want == oppFields.size()) {
            return;
        }

        while (oppFields.size() > want) {
            final PlayerField f = oppFields.remove(oppFields.size() - 1);
            final PlayerBar b = oppBars.remove(oppBars.size() - 1);
            board.getChildren().remove(f);
            getChildren().remove(b);
        }

        while (oppFields.size() < want) {
            final PlayerField f = new PlayerField(cardWidth * 0.92, true);
            f.setOnCardHover(this::hovered);
            f.setOnCardClick(this::cardClicked);
            f.setOnStackCountClick(this::stackCountClicked);
            f.setOnZoneClicked(this::showZone);

            f.setPilesOnBoard(false);
            oppFields.add(f);

            final PlayerBar b = new PlayerBar(true);
            b.setOnZoneClicked(this::showZone);
            b.setOnCommanderHover(this::hovered);
            b.setOnPlayerClicked(onPlayerClicked);
            b.setSelectable(playersSelectable);

            hoverDetails(b, true);
            oppBars.add(b);

            board.getChildren().add(board.getChildren().indexOf(combatLine), f);
            getChildren().add(getChildren().indexOf(viewport), b);
        }

        while (seatDividers.size() > want - 1) {
            board.getChildren().remove(seatDividers.remove(seatDividers.size() - 1));
        }
        while (seatDividers.size() < want - 1) {
            final Line d = new Line();

            d.getStyleClass().add("seat-divider");
            d.setMouseTransparent(true);
            seatDividers.add(d);

            board.getChildren().add(0, d);
        }

        while (oppCards.size() > want) {
            oppCards.remove(oppCards.size() - 1);
        }
        while (oppCards.size() < want) {
            oppCards.add(List.of());
        }

        opponentField.setPilesOnBoard(want == 1);
        for (final PlayerBar b : oppBars) {
            b.setCompact(want > 1);
        }
        requestLayout();
    }

    public int opponentSeats() {
        return oppFields.size();
    }

    private double compactBarWidth;

    public int maxOpponentSeats() {
        final double w = getWidth();
        if (w <= 0) {
            return 1;
        }
        final double railW = folded ? 0 : Math.max(230, Math.min(UiScale.sideWidthNow(sideWidth), w * 0.145));
        final double contentW = Math.max(200, w - railW);

        if (compactBarWidth <= 0) {
            final PlayerBar probe = new PlayerBar(true);
            probe.setCompact(true);
            probe.applyCss();
            compactBarWidth = Math.max(200, probe.prefWidth(-1));
        }
        return Math.max(1, (int) Math.floor((contentW + SEAT_GAP) / (compactBarWidth + SEAT_GAP)));
    }

    public boolean isMultiBoard() {
        return oppFields.size() > 1;
    }

    public void setOpponents(final List<PlayerView> opponents,
                             final PlayerView selected,
                             final PlayerView activeTurn,
                             final PlayerView me) {
        if (isMultiBoard()) {

            opponentTabs.setVisible(false);
            opponentTabs.setManaged(false);
        } else {
            opponentTabs.setOpponents(opponents, selected, activeTurn, me);
        }
        requestLayout();
    }

    public void skipOpponentRemovalAnimation() {
        for (final PlayerField f : oppFields) {
            f.skipRemovalAnimation();
        }
    }

    public void setOnOpponentSelected(final Consumer<PlayerView> h) {
        opponentTabs.setOnSelect(h);
        opponentTabs.setPickable(this::hasSelectableCard);
    }

    public void setCommandZone(final List<CardView> command,
                               final PlayerView owner) {
        commandZone.setCards(command, owner);
        reindex();
    }

    public void showDetail(final CardView card) {
        applyDetailSetting();
        detail.show(card);
    }

    public void hoverForTest(final CardNode n) {
        hovered(n);
    }

    public String popOutHoverForTest() {
        final java.util.List<CardNode> nodes = new java.util.ArrayList<>();
        final Node layer = menuOverlay.isShowing() ? menuOverlay : overlay;
        CardZoom.collectCardNodes(layer, nodes);
        return bigCardSweep(nodes, layer);
    }

    public String stackHoverForTest() {
        int shown = 0;
        int bad = 0;
        for (final Region row : stackRows.values()) {
            if (row.getOnMouseEntered() == null || row.getScene() == null) {
                continue;
            }
            row.getOnMouseEntered().handle(null);
            if (!bigCard.isVisible()) {
                continue;
            }
            shown++;
            if (bigCard.getBoundsInParent().getMaxX() > getWidth() - sideWidthNow()) {
                bad++;
            }
        }
        return stackRows.size() + " filas, " + shown + " con carta grande, " + bad + " tapando la columna";
    }

    public String tableHoverForTest() {
        final java.util.List<CardNode> all = new java.util.ArrayList<>();
        CardZoom.collectCardNodes(this, all);
        final java.util.List<CardNode> nodes = new java.util.ArrayList<>();
        for (final CardNode n : all) {
            if (!isInside(n, overlay) && !isInside(n, menuOverlay) && !isInside(n, zoomOverlay)
                    && n.isVisible() && n.getScene() != null) {
                nodes.add(n);
            }
        }
        return bigCardSweep(nodes, null);
    }

    private String bigCardSweep(final java.util.List<CardNode> nodes, final Node layer) {
        int shown = 0;
        int hidden = 0;
        int bad = 0;
        final double grown = Math.max(1.0, forge.neo.NeoSettings.hoverZoom());
        for (final CardNode n : nodes) {
            if (n == bigCard || n.getCard() == null) {
                continue;
            }
            showBigCard(n);
            if (!bigCard.isVisible()) {
                hidden++;
                continue;
            }
            shown++;
            final javafx.geometry.Bounds c = sceneToLocal(n.localToScene(n.getBoundsInLocal()));
            final double half = c.getWidth() / 2 * grown;
            final javafx.geometry.Bounds g = bigCard.getBoundsInParent();
            final boolean touches = g.getMinX() < c.getCenterX() + half && g.getMaxX() > c.getCenterX() - half;
            final boolean outside = g.getMinX() < 0 || g.getMaxX() > getWidth()
                    || g.getMinY() < 0 || g.getMaxY() > getHeight();
            final boolean below = layer != null
                    ? getChildren().indexOf(bigCard) != getChildren().indexOf(layer) + 1
                    : getChildren().indexOf(bigCard) > getChildren().indexOf(overlay);
            if (touches || outside || below) {
                bad++;
                System.out.printf(java.util.Locale.ROOT, "[grande] MAL en %s: tapa=%s fuera=%s debajo=%s carta=%.0f-%.0f grande=%.0f-%.0f%n",
                        n.getCard().getName(), touches, outside, below, c.getCenterX() - half, c.getCenterX() + half,
                        g.getMinX(), g.getMaxX());
            }
        }
        return shown + " con carta grande, " + hidden + " sin sitio, " + bad + " mal";
    }

    public void closePopOutForTest() {
        menuOverlay.hide();
    }

    public boolean bigCardShownForTest() {
        return bigCard.isVisible();
    }

    private void hovered(final CardNode n) {
        applyDetailSetting();
        detail.show(n.getCard());
        showBigCard(n);
        if (onCardHover != null) {
            onCardHover.accept(n);
        }
    }

    private final CardNode bigCard = new CardNode(300);
    
    private Node bigCardOf;
    
    private final javafx.animation.PauseTransition bigCardHide =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(120));

    {
        bigCard.setMouseTransparent(true);
        bigCard.setManaged(false);
        bigCard.setVisible(false);
        bigCard.setRotationEnabled(false);
        bigCard.setHoverEnabled(false);

        bigCard.setBlankWhileLoading(true);
        bigCard.getStyleClass().add("big-hover-card");
        bigCardHide.setOnFinished(e -> bigCard.setVisible(false));
    }

    private void showBigCard(final CardNode n) {
        showBigCard(n, n == null ? null : n.getCard());
    }

    private void showBigCard(final Node n, final CardView card) {
        if (!forge.neo.NeoSettings.bigHoverCard() || n == null || card == null
                || zoomOverlay.isShowing()) {
            return;
        }
        bigCardHide.stop();
        if (bigCardOf != n) {
            bigCardOf = n;
            n.hoverProperty().addListener(new javafx.beans.value.ChangeListener<Boolean>() {
                @Override
                public void changed(final javafx.beans.value.ObservableValue<? extends Boolean> o,
                                    final Boolean was, final Boolean is) {
                    if (!is) {
                        o.removeListener(this);
                        if (bigCardOf == n) {
                            bigCardOf = null;
                            bigCardHide.playFromStart();
                        }
                    }
                }
            });

            n.sceneProperty().addListener(new javafx.beans.value.ChangeListener<javafx.scene.Scene>() {
                @Override
                public void changed(final javafx.beans.value.ObservableValue<? extends javafx.scene.Scene> o,
                                    final javafx.scene.Scene was, final javafx.scene.Scene is) {
                    if (is == null) {
                        o.removeListener(this);
                        if (bigCardOf == n) {
                            bigCardOf = null;
                            bigCardHide.playFromStart();
                        }
                    }
                }
            });
        }

        final double[] spot = bigCardSpot(n);
        if (spot == null) {

            bigCardOf = null;
            bigCard.setVisible(false);
            return;
        }
        bigCard.setCardWidth(spot[2]);
        bigCard.setCard(card);

        bigCard.resizeRelocate(spot[0], spot[1], spot[2], spot[3]);

        final Node layer = isInside(n, menuOverlay) ? menuOverlay : isInside(n, overlay) ? overlay : null;
        final int now = getChildren().indexOf(bigCard);
        final boolean placed = layer != null
                ? now >= 0 && now == getChildren().indexOf(layer) + 1
                : now >= 0 && now < getChildren().indexOf(overlay);
        if (!placed) {
            getChildren().remove(bigCard);
            final int at = layer != null ? getChildren().indexOf(layer) + 1 : getChildren().indexOf(overlay);
            getChildren().add(at < 0 ? getChildren().size() : at, bigCard);
        }
        bigCard.setVisible(true);
    }

    private double[] bigCardSpot(final Node n) {
        final javafx.geometry.Bounds b = sceneToLocal(n.localToScene(n.getBoundsInLocal()));
        if (b == null) {
            return null;
        }
        final double margin = UiScale.px(24);
        final double minX = margin;
        final double maxX = getWidth() - sideWidthNow() - margin;
        final double grown = Math.max(1.0, forge.neo.NeoSettings.hoverZoom());
        final double half = b.getWidth() / 2 * grown;
        final double cardLeft = b.getCenterX() - half;
        final double cardRight = b.getCenterX() + half;
        
        final double leftRoom = cardLeft - margin - minX;
        final double rightRoom = maxX - (cardRight + margin);

        double h = Math.min(getHeight() * 0.62, UiScale.px(720));
        double w = h / CardNode.ASPECT;
        final boolean preferRight = b.getCenterX() < getWidth() / 2;
        boolean right;
        if ((preferRight ? rightRoom : leftRoom) >= w) {
            right = preferRight;
        } else if ((preferRight ? leftRoom : rightRoom) >= w) {
            right = !preferRight;
        } else {
            right = rightRoom >= leftRoom;
            w = Math.max(0, right ? rightRoom : leftRoom);
            h = w * CardNode.ASPECT;
        }
        if (w < UiScale.px(150)) {
            return null;
        }
        final double x = right ? maxX - w : minX;
        final double y = Math.max(margin, (getHeight() - h) / 2);
        return new double[] {x, y, w, h};
    }

    private CardNode cardNodeAt(final Object target, final Node layer) {
        Node n = target instanceof Node ? (Node) target : null;
        while (n != null && n != layer) {
            if (n instanceof CardNode c && c != bigCard && c.getCard() != null) {
                return c;
            }
            n = n.getParent();
        }
        return null;
    }

    private double sideWidthNow() {
        return side.isVisible() ? side.getWidth() : 0;
    }

    private void applyDetailSetting() {
        final boolean want = forge.neo.NeoSettings.hoverDetail();
        final boolean has = side.getChildren().contains(detail);
        if (want == has) {
            return;
        }
        if (!want) {
            side.getChildren().remove(detail);
            if (!side.getChildren().contains(sideSpace)) {
                final int at = side.getChildren().indexOf(actionBar);
                side.getChildren().add(at < 0 ? side.getChildren().size() : at, sideSpace);
            }
            return;
        }

        side.getChildren().remove(sideSpace);

        detail.setMinHeight(0);
        detail.setPrefHeight(0);

        VBox.setVgrow(detail, Priority.SOMETIMES);
        final int before = side.getChildren().indexOf(actionBar);
        side.getChildren().add(before < 0 ? side.getChildren().size() : before, detail);
    }

    public PlayerBar getOpponentBar() {
        return opponentBar;
    }

    public PlayerField getOpponentField() {
        return opponentField;
    }

    public PlayerField getSelfField() {
        return selfField;
    }

    public ZonePile getSelfGraveyardPile() {
        return selfField.getGraveyardPile();
    }

    public PlayerBar getSelfBar() {
        return selfBar;
    }

    private forge.game.Game stackGame;

    public void setStackGame(final forge.game.Game game) {
        stackGame = game;
    }

    public void setStack(final Iterable<StackItemView> items, final PlayerView me) {
        stackBox.getChildren().clear();
        stackRows.clear();

        final List<StackItemView> list = new ArrayList<>();
        if (items != null) {
            for (final StackItemView item : items) {
                list.add(item);
            }
        }
        stackSize = list.size();
        lastStack = list;
        lastStackMe = me;
        refreshStackChip();
        applyStackTargets();

        stackOpen.keySet().retainAll(idsOf(list));

        stackArt.keySet().retainAll(idsOf(list));

        final List<forge.neo.match.StackGroups.Run> runs = forge.neo.NeoSettings.groupStack()
                ? forge.neo.match.StackGroups.runs(list) : singleRuns(list);
        final List<StackItemView> shown = new ArrayList<>();
        for (final forge.neo.match.StackGroups.Run run : runs) {
            shown.add(run.first());
        }
        final boolean overflow = shown.size() > visibleRows();
        VBox.setVgrow(stackBox, overflow ? Priority.ALWAYS : Priority.NEVER);
        VBox.setVgrow(sideSpace, overflow ? Priority.NEVER : Priority.ALWAYS);

        final Label title = new Label(list.isEmpty()
                ? NeoText.get("table.stack")
                : NeoText.get("table.stack.count", list.size()));
        title.getStyleClass().add("caption");

        if (list.isEmpty()) {
            stackBox.getChildren().add(title);
            final Label empty = new Label(NeoText.get("table.stack.empty"));
            empty.getStyleClass().add("hud-sub");
            stackBox.getChildren().add(empty);
            return;
        }

        stackBox.getChildren().add(stackHeader(title, list, me));

        final VBox rows = new VBox(2);
        rows.getChildren().add(topRow(runs.get(0).first(), runs.get(0).count(), me));
        for (int r = 1; r < runs.size(); r++) {
            final forge.neo.match.StackGroups.Run run = runs.get(r);
            rows.getChildren().add(waitingRow(run.first(), run.start() + 1, run.count(), me));
        }

        final ScrollPane scroll = new ScrollPane(rows);
        scroll.getStyleClass().add("stack-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(viewportHeight(shown));

        scroll.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        stackBox.getChildren().add(scroll);
        
        applyStackTargets();
    }

    private Region stackHeader(final Label title, final List<StackItemView> list,
                               final PlayerView me) {
        final boolean allOpen = allStackOpen(list);
        final HBox header = new HBox(5, chevron(allOpen), title);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("stack-header");
        header.setCursor(javafx.scene.Cursor.HAND);
        header.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                return;
            }
            for (final StackItemView item : list) {
                stackOpen.put(item.getId(), !allOpen);
            }
            setStack(list, me);
            e.consume();
        });
        return header;
    }

    public boolean toggleAllStackEntries() {
        if (lastStack.isEmpty()) {
            return false;
        }
        final boolean allOpen = allStackOpen(lastStack);
        for (final StackItemView item : lastStack) {
            stackOpen.put(item.getId(), !allOpen);
        }
        
        setStack(new ArrayList<>(lastStack), lastStackMe);
        return true;
    }

    private static List<forge.neo.match.StackGroups.Run> singleRuns(final List<StackItemView> list) {
        final List<forge.neo.match.StackGroups.Run> out = new ArrayList<>();
        for (final StackItemView item : list) {
            out.addAll(forge.neo.match.StackGroups.runs(java.util.Collections.singletonList(item)));
        }
        return out;
    }

    public int[] stackRowsAndItems() {
        final int items = lastStack.size();
        final int rows = forge.neo.NeoSettings.groupStack()
                ? forge.neo.match.StackGroups.runs(lastStack).size() : items;
        return new int[] {rows, items};
    }

    private boolean allStackOpen(final List<StackItemView> list) {
        for (int i = 0; i < list.size(); i++) {
            if (!isStackOpen(list.get(i), i == 0)) {
                return false;
            }
        }
        return true;
    }

    private double viewportHeight(final List<StackItemView> list) {
        int open = 0;
        for (int i = 1; i < list.size(); i++) {
            if (isStackOpen(list.get(i), false)) {
                open++;
            }
        }
        return Math.min(list.size(), visibleRows()) * rowHeight() + UiScale.px(TOP_ROW_EXTRA)
                + Math.min(open, MAX_OPEN_ROWS) * UiScale.px(EXPANDED_EXTRA);
    }

    private double rowHeight() {
        return forge.neo.NeoSettings.stackCards()
                ? stackArtWidth() * CardNode.ASPECT + 8 : UiScale.px(ROW_H);
    }

    private int visibleRows() {
        return forge.neo.NeoSettings.stackCards() ? 3 : VISIBLE_ROWS;
    }

    private static java.util.Set<Integer> idsOf(final List<StackItemView> list) {
        final java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (final StackItemView item : list) {
            ids.add(item.getId());
        }
        return ids;
    }

    private static final int VISIBLE_ROWS = 6;

    private static final double ROW_H = 23;

    private static final double TOP_ROW_EXTRA = 38;

    private static final double EXPANDED_EXTRA = 34;

    private static final int MAX_OPEN_ROWS = 2;

    private final Map<Integer, Boolean> stackOpen = new HashMap<>();

    private final Map<Integer, CardNode> stackArt = new HashMap<>();

    private double stackArtWidth() {
        return Math.max(38, Math.min(UiScale.px(54), UiScale.sideWidthNow(sideWidth) * 0.18));
    }

    private CardNode stackCardArt(final StackItemView item) {
        if (!forge.neo.NeoSettings.stackCards()) {
            return null;
        }
        final CardView source = item.getSourceCard();
        if (source == null) {
            return null;
        }
        CardNode node = stackArt.get(item.getId());
        if (node == null) {
            node = new CardNode(stackArtWidth());
            node.setRotationEnabled(false);
            node.setHoverEnabled(false);
            node.setBadgesVisible(false);
            node.setCountersVisible(false);

            node.setMouseTransparent(true);
            stackArt.put(item.getId(), node);
        }
        node.setCard(source);
        return node;
    }

    private List<StackItemView> lastStack = new ArrayList<>();

    private PlayerView lastStackMe;

    private boolean isStackOpen(final StackItemView item, final boolean top) {
        final Boolean choice = stackOpen.get(item.getId());
        return choice == null ? top : choice;
    }

    private Region topRow(final StackItemView item, final int count, final PlayerView me) {
        final Label header = new Label(headerFor(item, me));
        header.getStyleClass().add("stack-now");
        header.pseudoClassStateChanged(MINE, isMine(item, me));

        final Label name = new Label(headlineOf(item, me, stackGame));
        name.getStyleClass().add("stack-top-name");
        name.setWrapText(true);
        name.setMaxWidth(Double.MAX_VALUE);
        name.setMinHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(name, Priority.ALWAYS);

        final Region detailBox = stackDetail(item, count);
        final boolean expandable = detailBox != null;
        final boolean open = expandable && isStackOpen(item, true);

        final HBox line = new HBox(5, name);
        line.setAlignment(Pos.TOP_LEFT);
        if (expandable) {
            line.getChildren().add(0, chevron(open));
        }
        if (count > 1) {
            line.getChildren().add(countBadge(count));
        }

        final VBox texts = new VBox(2, header, line);
        if (open) {
            texts.getChildren().add(detailBox);
        }
        HBox.setHgrow(texts, Priority.ALWAYS);
        texts.setMaxWidth(Double.MAX_VALUE);

        final CardNode art = stackCardArt(item);
        final Region box;
        if (art == null) {
            box = texts;
        } else {
            final HBox row = new HBox(8, art, texts);
            row.setAlignment(Pos.TOP_LEFT);
            box = row;
        }
        box.getStyleClass().add("stack-top");
        wireRow(box, item, true, expandable);
        return box;
    }

    private Region waitingRow(final StackItemView item, final int order, final int count,
                              final PlayerView me) {
        final Label num = new Label(String.valueOf(order));
        num.getStyleClass().add("stack-order");
        num.setMinWidth(UiScale.px(15));

        final Label who = new Label(ownerName(item, me));
        who.getStyleClass().add("stack-who");
        who.pseudoClassStateChanged(MINE, isMine(item, me));
        who.setMinWidth(Region.USE_PREF_SIZE);

        final Label name = new Label(headlineOf(item, me, stackGame));
        name.getStyleClass().add(item.isTrigger() ? "stack-trigger" : "stack-spell");
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        final Region detailBox = stackDetail(item, count);
        final boolean expandable = detailBox != null;
        final boolean open = expandable && isStackOpen(item, false);

        if (open) {
            name.setWrapText(true);
            name.setMinHeight(Region.USE_PREF_SIZE);
        }

        final HBox line = new HBox(6, num, who, name);
        line.setAlignment(Pos.CENTER_LEFT);
        if (expandable) {
            line.getChildren().add(1, chevron(open));
        }
        if (count > 1) {
            line.getChildren().add(countBadge(count));
        }

        final VBox texts = new VBox(2, line);
        if (open) {
            texts.getChildren().add(detailBox);
        }
        HBox.setHgrow(texts, Priority.ALWAYS);
        texts.setMaxWidth(Double.MAX_VALUE);

        final CardNode art = stackCardArt(item);
        final Region box;
        if (art == null) {
            box = texts;
        } else {

            final HBox row = new HBox(8, art, texts);
            row.setAlignment(open ? Pos.TOP_LEFT : Pos.CENTER_LEFT);
            box = row;
        }
        box.getStyleClass().add("stack-strip");
        wireRow(box, item, false, expandable);
        return box;
    }

    private static Label countBadge(final int count) {
        final Label badge = new Label("×" + count);

        badge.getStyleClass().add("stack-group-count");
        badge.setMinWidth(Region.USE_PREF_SIZE);
        return badge;
    }

    private static Region stackDetail(final StackItemView item, final int count) {
        final VBox box = new VBox(2);
        box.getStyleClass().add("stack-detail");

        final String what = count > 1
                ? forge.neo.match.StackGroups.withoutCause(effectText(item)) : effectText(item);
        if (!what.isEmpty()) {
            final Label text = new Label(what);
            text.getStyleClass().add(item.isTrigger() ? "stack-trigger" : "stack-spell");
            text.setWrapText(true);
            text.setMaxWidth(Double.MAX_VALUE);
            text.setMinHeight(Region.USE_PREF_SIZE);
            box.getChildren().add(text);
        }

        final String targets = targetsOf(item, what);
        if (!targets.isEmpty()) {
            final Label line = new Label(NeoText.get("stack.targets", targets));
            line.getStyleClass().add("stack-targets");
            line.setWrapText(true);
            line.setMaxWidth(Double.MAX_VALUE);
            line.setMinHeight(Region.USE_PREF_SIZE);
            box.getChildren().add(line);
        }
        return box.getChildren().isEmpty() ? null : box;
    }

    public static String bannerText(final StackItemView item, final PlayerView me) {
        return bannerText(item, me, 1);
    }

    public static String bannerText(final StackItemView item, final PlayerView me, final int count) {
        return bannerText(item, me, count, null);
    }

    public static String bannerText(final StackItemView item, final PlayerView me, final int count,
                                    final forge.game.Game game) {
        if (item == null) {
            return "";
        }
        final java.util.List<String> lines = new java.util.ArrayList<>();
        final String who = ownerName(item, me);
        if (!who.isBlank()) {
            lines.add(who);
        }
        final String head = headlineOf(item, me, game);
        if (!head.isBlank()) {
            lines.add(count > 1 ? head + "  ×" + count : head);
        }
        String what = count > 1
                ? forge.neo.match.StackGroups.withoutCause(effectText(item)) : effectText(item);
        final int targeting = what.lastIndexOf("(Targeting:");
        if (targeting >= 0) {
            what = what.substring(0, targeting).trim();
        }
        if (!what.isBlank() && !what.equals(head)) {
            lines.add(what);
        }

        final String targets = targetsByController(item, me);
        if (!targets.isEmpty()) {
            lines.add(NeoText.get("stack.targets", targets));
        }
        return lines.isEmpty() ? NeoText.get("alert.aSpell") : String.join("\n", lines);
    }

    static String targetsByController(final StackItemView item, final PlayerView me) {
        return forge.neo.match.StackTargets.byController(item, me,
                c -> c.getCurrentState() == null ? null : CardText.nameOf(c.getCurrentState()),
                forge.neo.match.PlayerName::of);
    }

    private static String targetsOf(final StackItemView item, final String what) {
        final StringBuilder sb = new StringBuilder();
        try {
            final Iterable<CardView> cards = item.getTargetCards();
            if (cards != null) {
                for (final CardView c : cards) {
                    appendTarget(sb, c == null || c.getCurrentState() == null
                            ? null : CardText.nameOf(c.getCurrentState()), what);
                }
            }
            final Iterable<PlayerView> players = item.getTargetPlayers();
            if (players != null) {
                for (final PlayerView p : players) {
                    appendTarget(sb, p == null ? null
                            : forge.neo.match.PlayerName.of(p), what);
                }
            }
        } catch (final RuntimeException e) {

            return sb.toString();
        }
        return sb.toString();
    }

    private static void appendTarget(final StringBuilder sb, final String name,
                                     final String what) {
        if (name == null || name.isBlank() || what.contains(name)
                || sb.indexOf(name) >= 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(name);
    }

    private static Label chevron(final boolean open) {
        final Label c = new Label(open ? "▾" : "▸");
        c.getStyleClass().add("stack-chevron");
        c.setMinWidth(Region.USE_PREF_SIZE);
        return c;
    }

    private void wireRow(final Region row, final StackItemView item,
                         final boolean top, final boolean expandable) {
        row.setCursor(javafx.scene.Cursor.HAND);
        stackRows.put(item.getId(), row);
        
        row.setOnMouseEntered(e -> {
            hoveredStackId = item.getId();
            applyStackTargets();
            showBigCard(row, item.getSourceCard());
        });
        row.setOnMouseExited(e -> {
            if (hoveredStackId == item.getId()) {
                hoveredStackId = -1;
                applyStackTargets();
            }
        });
        row.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                final CardView source = item.getSourceCard();
                if (source == null) {
                    return;
                }

                final Consumer<StackItemView> menu = onStackMenu;
                if (menu != null) {
                    menu.accept(item);
                } else {
                    showZoom(source);
                }
                e.consume();
                return;
            }
            if (expandable) {
                stackOpen.put(item.getId(), !isStackOpen(item, top));
                setStack(lastStack, lastStackMe);
                e.consume();
            }
        });
    }

    public void setOnStackMenu(final Consumer<StackItemView> handler) {
        onStackMenu = handler;
    }

    private Consumer<StackItemView> onStackMenu;

    private static final javafx.css.PseudoClass MINE =
            javafx.css.PseudoClass.getPseudoClass("mine");

    private static String headerFor(final StackItemView item, final PlayerView me) {
        final String owner = ownerName(item, me);
        return owner.isEmpty()
                ? NeoText.get("stack.now.plain")
                : NeoText.get("stack.now", owner);
    }

    private static boolean isMine(final StackItemView item, final PlayerView me) {
        return me != null && me.equals(item.getActivatingPlayer());
    }

    private static String ownerName(final StackItemView item, final PlayerView me) {
        final PlayerView who = item.getActivatingPlayer();
        if (who == null) {
            return "";
        }
        return isMine(item, me) ? NeoText.get("stack.you")
                : forge.neo.match.PlayerName.of(who);
    }

    private static String headlineOf(final StackItemView item, final PlayerView me) {
        return headlineOf(item, me, null);
    }

    private static String headlineOf(final StackItemView item, final PlayerView me, final forge.game.Game game) {
        final CardView src = item.getSourceCard();
        String name = src == null || src.getCurrentState() == null
                ? "" : CardText.nameOf(src.getCurrentState());
        
        final String real = forge.neo.card.FaceDownName.of(src, forge.neo.match.NeoMatchUI::mayFlipHere);
        if (real != null) {
            name = name + " (" + real + ")";
        }
        String head = item.isTrigger() ? NeoText.get("stack.triggerOf", name) : name;

        if (item.isOptionalTrigger() && isMine(item, me)) {
            head = NeoText.get("stack.optional") + " " + head;
        }

        String extra = optionalCostOf(item);

        final Integer x = forge.neo.match.StackX.of(game, item);
        if (x != null) {
            extra = extra.isEmpty() ? "(X = " + x + ")" : "(X = " + x + ") " + extra;
        }
        return extra.isEmpty() ? head : head + "  " + extra;
    }

    private static String optionalCostOf(final StackItemView item) {
        try {
            final String s = item.getOptionalCostString();
            return s == null || s.isBlank() ? "" : "(" + s.trim() + ")";
        } catch (final RuntimeException e) {
            return "";
        }
    }

    private static String effectText(final StackItemView item) {
        String text = item.getText();
        if (text == null) {
            return "";
        }

        text = CARD_ID.matcher(text).replaceAll("").trim();
        final CardView src = item.getSourceCard();
        if (src != null) {
            final String translated = src.getCurrentState() == null
                    ? null : CardText.nameOf(src.getCurrentState());
            for (final String name : new String[] {translated, src.getName()}) {
                if (name != null && !name.isBlank() && text.startsWith(name)) {
                    text = text.substring(name.length()).trim();
                    
                    while (!text.isEmpty() && SEPARATORS.indexOf(text.charAt(0)) >= 0) {
                        text = text.substring(1).trim();
                    }
                    break;
                }
            }
        }
        return text;
    }

    private static final String SEPARATORS = "-\u2013\u2014:\u00b7";

    private static final java.util.regex.Pattern CARD_ID = java.util.regex.Pattern.compile(" \\(\\d+\\)");

    private void hoverDetails(final PlayerBar bar, final boolean opponent) {
        bar.setOnMouseEntered(e -> {
            final String text = bar.getDetailsText();
            if (text == null || text.isBlank()) {
                return;
            }
            playerDetails.setText(text);
            playerDetails.setVisible(true);
            detailsBelow = opponent;
            detailsAnchor = bar;
            requestLayout();
        });
        bar.setOnMouseExited(e -> {
            playerDetails.setVisible(false);
            requestLayout();
        });
    }

    private boolean detailsBelow;
    
    private PlayerBar detailsAnchor;

    public final void applyPlaymat() {
        javafx.scene.image.Image image = null;
        try {
            image = forge.neo.look.NeoLook.currentPlaymat().image();
        } catch (final RuntimeException e) {
            System.err.println("[neo] no se ha podido poner el tapete: " + e);
        }
        playmat.setImage(image);
        final boolean on = image != null;
        playmat.setVisible(on);
        playmatVeil.setVisible(on);
    }

    public void setPhase(final PhaseType phase) {
        phaseRail.setCurrent(phase);
    }

    public void setPrompt(final String text) {
        actionBar.setPrompt(text);
        lastPrompt = text;
    }

    public Button getLogButton() {
        return logButton;
    }

    public void enablePauseButton(final Runnable open) {
        menuButton.setOnAction(e -> open.run());
        menuButton.setVisible(true);
        requestLayout();
    }

    public Button getMenuButton() {
        return menuButton;
    }

    public void enableTurnStats(final java.util.function.Supplier<forge.neo.match.TurnStats.Snapshot> source) {
        turnButton.setOnAction(e -> showTurnStats(source));
        turnButton.setVisible(true);
        requestLayout();
    }

    public void showTurnStats(final java.util.function.Supplier<forge.neo.match.TurnStats.Snapshot> source) {
        if (source == null || source.get() == null) {
            showNotice(NeoText.get("turnStats.localOnly"));
            return;
        }
        final TurnStatsView view = new TurnStatsView(source, menuOverlay::hide);
        menuOverlay.setOnBackgroundClick(menuOverlay::hide);
        menuOverlay.show(view);
    }

    public Button getTurnButton() {
        return turnButton;
    }

    public void showGameLog(final forge.game.GameLog log, final PlayerView me) {
        showGameLog(log, me, null);
    }

    public void showGameLog(final forge.game.GameLog log, final PlayerView me,
                            final java.util.function.IntFunction<forge.game.card.CardView> lookup) {
        final GameLogView view = new GameLogView(menuOverlay::hide);
        view.update(log, me, lookup);
        logButton.pseudoClassStateChanged(UNREAD, false);
        menuOverlay.setOnBackgroundClick(menuOverlay::hide);
        menuOverlay.show(view);
        gesture(Gesture.LOG_OPEN);
    }

    private final Button chatButton = new Button(NeoText.get("lobby.chat"));
    private java.util.function.Supplier<List<String>> chatHistory;
    private java.util.function.Consumer<String> chatSend;

    public void enableChat(final java.util.function.Supplier<List<String>> history,
                           final java.util.function.Consumer<String> send) {
        this.chatHistory = history;
        this.chatSend = send;
        chatButton.setVisible(true);
        requestLayout();
    }

    public void markLogUnread() {
        logButton.pseudoClassStateChanged(UNREAD, true);
    }

    private static final javafx.css.PseudoClass UNREAD =
            javafx.css.PseudoClass.getPseudoClass("unread");

    public void flyBannerToStack() {
        final PromptBanner banner = getPromptBanner();
        if (banner == null || !banner.isVisible() || banner.isAlerting()) {
            return;
        }
        final Node target = stackBox.getScene() != null && stackBox.isVisible() ? stackBox
                : stackChip.getScene() != null && stackChip.isVisible() ? stackChip : null;
        if (target == null || !ImpactLayer.isEnabled() || banner.getScene() == null) {
            banner.hide();
            return;
        }
        final javafx.geometry.Bounds from = banner.localToScene(banner.getBoundsInLocal());
        final javafx.geometry.Bounds to = target.localToScene(target.getBoundsInLocal());
        final javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
        params.setFill(javafx.scene.paint.Color.TRANSPARENT);
        final javafx.scene.image.WritableImage img;
        try {
            img = banner.snapshot(params, null);
        } catch (final RuntimeException e) {
            banner.hide();
            return;
        }
        banner.hide();
        final javafx.geometry.Bounds fromL = impactLayer.sceneToLocal(from);
        final javafx.geometry.Bounds toL = impactLayer.sceneToLocal(to);
        if (fromL == null || toL == null || fromL.getWidth() <= 0) {
            return;
        }
        final javafx.scene.image.ImageView ghost = new javafx.scene.image.ImageView(img);
        ghost.setMouseTransparent(true);
        ghost.setManaged(false);
        ghost.setFitWidth(fromL.getWidth());
        ghost.setFitHeight(fromL.getHeight());
        ghost.relocate(fromL.getMinX(), fromL.getMinY());
        impactLayer.getChildren().add(ghost);

        final double s = Math.min(1, Math.max(0.12, toL.getWidth() / fromL.getWidth()));
        final double cx = toL.getCenterX();
        final double cy = toL.getMinY() + Math.min(toL.getHeight() / 2, fromL.getHeight() * s / 2 + UiScale.px(24));
        final double dx = cx - fromL.getCenterX();
        final double dy = cy - fromL.getCenterY();
        final javafx.util.Duration d = javafx.util.Duration.millis(Integer.getInteger("neo.bannerFlyMs", 320));
        final javafx.animation.Timeline t = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(d,
                        new javafx.animation.KeyValue(ghost.translateXProperty(), dx, javafx.animation.Interpolator.EASE_IN),
                        new javafx.animation.KeyValue(ghost.translateYProperty(), dy, javafx.animation.Interpolator.EASE_IN),
                        new javafx.animation.KeyValue(ghost.scaleXProperty(), s, javafx.animation.Interpolator.EASE_IN),
                        new javafx.animation.KeyValue(ghost.scaleYProperty(), s, javafx.animation.Interpolator.EASE_IN),
                        new javafx.animation.KeyValue(ghost.opacityProperty(), 0.35, javafx.animation.Interpolator.EASE_IN)));
        t.setOnFinished(e -> impactLayer.getChildren().remove(ghost));
        if (Boolean.getBoolean("neo.impact.log")) {
            System.out.println("[cartel] vuela al stack");
        }
        t.play();
    }

    public PromptBanner getPromptBanner() {
        return promptBanner;
    }

    public String getLastPrompt() {
        return lastPrompt;
    }

    private String lastPrompt = "";

    public ActionBar getActionBar() {
        return actionBar;
    }

    public Overlay getOverlay() {
        return overlay;
    }

    public OpponentTabs getOpponentTabs() {
        return opponentTabs;
    }

    public void showInfo(final String title, final String text) {
        final javafx.scene.control.Label heading = new javafx.scene.control.Label(
                title == null ? "" : title);
        heading.getStyleClass().add("dialog-title");

        final Label body = new Label(text == null ? "" : text.replace("\\n", "\n"));
        body.getStyleClass().add("dialog-text");
        body.setWrapText(true);
        body.setMaxWidth(UiScale.px(560));
        body.setMinHeight(Region.USE_PREF_SIZE);

        final javafx.scene.control.Button ok = new javafx.scene.control.Button(NeoText.get("banner.understood"));
        ok.getStyleClass().add("btn-primary");
        ok.setOnAction(e -> menuOverlay.hide());

        final javafx.scene.layout.HBox footer = new javafx.scene.layout.HBox(ok);
        footer.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        final VBox box = new VBox(12, heading, body, footer);
        box.getStyleClass().add("dialog");
        box.setPadding(new Insets(22, 26, 20, 26));
        box.setMaxWidth(Region.USE_PREF_SIZE);
        box.setMaxHeight(Region.USE_PREF_SIZE);
        menuOverlay.show(box);
    }

    public void showZone(final PlayerView owner, final forge.game.zone.ZoneType zone) {
        if (owner == null) {
            return;
        }
        final List<CardView> cards = new ArrayList<>();
        final var view = owner.getCards(zone);
        if (view != null) {
            for (final CardView cv : view) {
                cards.add(cv);
            }
        }
        menuOverlay.show(new ZoneViewer(forge.neo.match.PlayerName.of(owner), zone, cards,
                mayView, cardWidth * 1.15, selectable, playableOutside, this::pickedInZone,
                menuOverlay::hide));
        gesture(Gesture.ZONE_OPEN);
    }

    public void showAttached(final CardView host, final List<CardView> attached) {
        showAttached(host, attached, false);
    }

    public void showAttached(final CardView host, final List<CardView> attached, final boolean ownField) {
        if (host == null || attached == null || attached.isEmpty()) {
            return;
        }
        final java.util.function.ToIntFunction<CardView> strength = actionable;
        final Predicate<CardView> usable = c -> {
            if (strength != null && strength.applyAsInt(c) > 0) {
                return true;
            }
            return ownField && c.getController() != null
                    && c.getController().equals(host.getController());
        };
        final ZoneViewer viewer = new ZoneViewer(null, forge.game.zone.ZoneType.Battlefield,
                attached, mayView, cardWidth * 1.15, selectable, playableOutside, usable,
                this::pickedInZone, menuOverlay::hide);
        viewer.setHeading(NeoText.get("zoneViewer.attachedTo",
                forge.neo.card.CardText.nameOf(host)));
        menuOverlay.show(viewer);
        gesture(Gesture.ZONE_OPEN);
    }

    private void pickedInZone(final CardView card) {
        menuOverlay.hide();
        cardClickedDirect(card);
    }

    private java.util.function.Predicate<CardView> selectable = c -> false;

    private java.util.function.Predicate<CardView> playableOutside = c -> false;

    public void setPlayableOutside(final Predicate<CardView> test) {
        this.playableOutside = test == null ? c -> false : test;
    }

    public boolean isPlayableOutside(final CardView card) {
        return card != null && playableOutside.test(card);
    }

    private ZoneViewer revealedViewer;

    public void showRevealed(final PlayerView owner, final List<CardView> cards) {
        if (cards == null || cards.isEmpty()) {
            return;
        }
        final ZoneViewer viewer = new ZoneViewer(
                owner == null ? null : forge.neo.match.PlayerName.of(owner),
                forge.game.zone.ZoneType.Hand, cards, c -> true, cardWidth * 1.15,
                menuOverlay::hide);
        viewer.setHeading(owner == null ? NeoText.get("zoneViewer.revealedAny")
                : NeoText.get("zoneViewer.revealed", forge.neo.match.PlayerName.of(owner)));
        revealedViewer = viewer;
        menuOverlay.show(viewer);
    }

    public void hideRevealed() {
        if (revealedViewer != null && menuOverlay.isShowing() && !menuOverlay.getChildren().isEmpty()
                && menuOverlay.getChildren().get(0) == revealedViewer) {
            menuOverlay.hide();
        }
        revealedViewer = null;
    }

    public void hideZoneViewer() {
        if (menuOverlay.isShowing() && !menuOverlay.getChildren().isEmpty()
                && menuOverlay.getChildren().get(0) instanceof ZoneViewer) {
            menuOverlay.hide();
        }
    }

    public void setCardVisibility(final Predicate<CardView> test) {
        this.mayView = test == null ? c -> true : test;
    }

    private Predicate<CardView> mayView = c -> true;

    public Overlay getMenuOverlay() {
        return menuOverlay;
    }

    private final java.util.ArrayDeque<Region> engineDialogs =
            new java.util.ArrayDeque<>();

    private javafx.animation.Timeline dialogGuard;

    public void markEngineDialog(final Region dialog) {
        if (dialog == null) {
            return;
        }
        engineDialogs.remove(dialog);
        engineDialogs.push(dialog);
        if (dialogGuard == null) {

            dialogGuard = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1),
                            e -> guardEngineDialog()));
            dialogGuard.setCycleCount(javafx.animation.Animation.INDEFINITE);

            sceneProperty().addListener((o, was, is) -> {
                if (is == null) {
                    dialogGuard.stop();
                } else if (!engineDialogs.isEmpty()) {
                    dialogGuard.playFromStart();
                }
            });
        }
        dialogGuard.playFromStart();
    }

    public void clearEngineDialog(final Region dialog) {
        engineDialogs.remove(dialog);
        if (engineDialogs.isEmpty()) {
            if (dialogGuard != null) {
                dialogGuard.stop();
            }
            return;
        }
        guardEngineDialog();
    }

    public void forgetEngineDialogs() {
        engineDialogs.clear();
        if (dialogGuard != null) {
            dialogGuard.stop();
        }
    }

    private void guardEngineDialog() {
        final Region top = engineDialogs.peek();
        if (top == null) {
            if (dialogGuard != null) {
                dialogGuard.stop();
            }
            return;
        }

        if (overlay.isShowing() && overlay.getContent() == top) {
            return;
        }
        System.out.println("[neo] el dialogo que el motor espera se habia perdido: se repone");
        overlay.show(top);
    }

    private final javafx.animation.Timeline aiThinking = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.millis(100), e -> {
                if (!refreshAiThinking()) {
                    stopAiThinking();
                }
            }));

    private static volatile java.lang.ref.WeakReference<TableScreen> live = new java.lang.ref.WeakReference<>(null);

    static {

        forge.util.AITimeoutTracker.setRepaintHook(() -> javafx.application.Platform.runLater(() -> {
            final TableScreen t = live.get();
            if (t != null) {
                t.kickAiThinking();
            }
        }));
    }

    public void setAiGameId(final int id) {
        for (final PlayerBar b : thinkingBars()) {
            b.setGameId(id);
        }
    }

    private void kickAiThinking() {
        if (getScene() == null) {
            return;
        }
        if (refreshAiThinking()) {
            if (aiThinking.getStatus() != javafx.animation.Animation.Status.RUNNING) {
                aiThinking.setCycleCount(javafx.animation.Animation.INDEFINITE);
                aiThinking.play();
            }
        } else {
            stopAiThinking();
        }
    }

    private void stopAiThinking() {
        aiThinking.stop();
        refreshAiThinking();
    }

    private boolean refreshAiThinking() {
        boolean any = false;
        for (final PlayerBar b : thinkingBars()) {
            any |= b.refreshThinking();
        }
        return any;
    }

    private List<PlayerBar> thinkingBars() {
        final List<PlayerBar> out = new ArrayList<>(oppBars);
        
        if (!out.contains(opponentBar)) {
            out.add(opponentBar);
        }
        out.add(selfBar);
        return out;
    }

    public boolean isModalShowing() {
        return overlay.isShowing() || menuOverlay.isShowing() || zoomOverlay.isShowing();
    }

    public boolean isConsultShowing() {
        return menuOverlay.isShowing() || zoomOverlay.isShowing();
    }

    private boolean isViewBlocked() {
        return (overlay.isShowing() && !overlay.isPeeking())
                || menuOverlay.isShowing() || zoomOverlay.isShowing();
    }

    public double zoomCardWidth() {
        return Math.max(320, getHeight() * 0.86) / CardNode.ASPECT;
    }

    public void showZoom(final CardView card) {
        zoomRing = List.of();
        renderZoom(card);
    }

    public void showZoom(final List<CardView> ring, final int index) {
        if (ring == null || ring.isEmpty()) {
            return;
        }
        if (ring.size() == 1 || index < 0 || index >= ring.size()) {
            showZoom(ring.get(Math.max(0, Math.min(index, ring.size() - 1))));
            return;
        }
        zoomRing = new ArrayList<>(ring);
        zoomIndex = index;
        renderZoom(zoomRing.get(index));
        
        zoomOverlay.requestFocus();
    }

    private void stepZoom(final int delta) {
        final int next = zoomIndex + delta;
        if (zoomRing.size() <= 1 || next < 0 || next >= zoomRing.size()) {
            return;
        }
        zoomIndex = next;
        renderZoom(zoomRing.get(next));
    }

    public void zoomRingTest(final int index, final int steps) {
        final List<CardNode> nodes = new ArrayList<>();
        CardZoom.collectCardNodes(overlay, nodes);
        if (index >= nodes.size()) {
            System.out.println("[zoom] el dialogo no tiene carta " + index + " (tiene " + nodes.size() + ")");
            return;
        }
        final CardNode n = nodes.get(index);
        final javafx.geometry.Point2D p = n.localToScene(n.getWidth() / 2, n.getHeight() / 2);
        n.fireEvent(new MouseEvent(MouseEvent.MOUSE_PRESSED, p.getX(), p.getY(), p.getX(), p.getY(),
                javafx.scene.input.MouseButton.SECONDARY, 1, false, false, false, false,
                false, false, true, true, false, true, null));
        
        for (int i = 0; i < Math.abs(steps); i++) {
            zoomOverlay.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                    "", "", steps > 0 ? javafx.scene.input.KeyCode.RIGHT : javafx.scene.input.KeyCode.LEFT,
                    false, false, false, false));
        }
        System.out.println("[zoom] ampliada: " + zoomOverlay.isShowing() + " | serie " + zoomRing.size()
                + " | se ve la " + (zoomRing.isEmpty() ? 0 : zoomIndex + 1)
                + (zoomRing.isEmpty() ? "" : " (" + zoomRing.get(zoomIndex).getName() + ")"));
    }

    public int[] zoomRingState() {
        return new int[] {zoomRing.size(), zoomIndex};
    }

    private static List<CardNode> ringOf(final CardNode node, final Node layer) {
        Node container = layer;
        for (Node n = node.getParent(); n != null && n != layer; n = n.getParent()) {
            if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) {
                container = sp.getContent();
                break;
            }
        }
        final List<CardNode> out = new ArrayList<>();
        CardZoom.collectCardNodes(container, out);
        return out;
    }

    private List<CardView> zoomRing = List.of();
    private int zoomIndex;

    private void renderZoom(final CardView card) {
        if (card == null) {
            return;
        }

        final boolean paused = pausesWhileReading();

        final String equip = equipLabel == null ? null : equipLabel.apply(card);

        final boolean browsing = zoomRing.size() > 1;
        final boolean below = paused || equip != null || browsing;
        final Region big = CardZoom.compose(card, zoomCardWidth() * (below ? (equip != null ? 0.9 : 0.95) : 1),
                Math.max(getWidth(), 640), this::hideZoom);
        if (below) {
            final VBox box = new VBox(8, big);
            if (equip != null) {
                final Button b = new Button(equip);
                b.getStyleClass().addAll("btn-primary", "zoom-equip");

                b.setOnAction(e -> {
                    hideZoom();
                    if (onEquip != null) {
                        onEquip.accept(card);
                    }
                });
                box.getChildren().add(b);
            }
            if (browsing) {

                final Label where = new Label(NeoText.get("zoom.browse", zoomIndex + 1, zoomRing.size()));
                where.getStyleClass().add("zoom-browse-hint");
                where.setMouseTransparent(true);
                box.getChildren().add(where);
            }
            if (paused) {
                final Label note = new Label(NeoText.get("zoom.paused"));
                note.getStyleClass().add("zoom-browse-hint");
                note.setMouseTransparent(true);
                box.getChildren().add(note);
            }
            box.setAlignment(Pos.CENTER);
            box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            zoomOverlay.show(box);
        } else {
            zoomOverlay.show(big);
        }
        gesture(Gesture.ZOOM_CARD);
    }

    public void hideZoom() {
        zoomRing = List.of();
        zoomOverlay.hide();
    }

    private java.util.function.Function<CardView, String> equipLabel;
    
    private Consumer<CardView> onEquip;

    public void setEquipAction(final java.util.function.Function<CardView, String> label,
                               final Consumer<CardView> action) {
        this.equipLabel = label;
        this.onEquip = action;
    }

    public String equipLabelFor(final CardView card) {
        return equipLabel == null || card == null ? null : equipLabel.apply(card);
    }

    public boolean isZoomShowing() {
        return zoomOverlay.isShowing();
    }

    public boolean isPlayerReading() {
        return playerReading;
    }

    private volatile boolean playerReading;

    public void setEnginePausable(final boolean can) {
        this.enginePausable = can;
    }

    private boolean enginePausable;

    private boolean pausesWhileReading() {
        return enginePausable && forge.neo.NeoSettings.pauseWhileReading();
    }

    public void announceTurn(final boolean yours, final String who, final int turn) {
        announceTurn(yours, who, turn, 0);
    }

    public void announceTurn(final boolean yours, final String who, final int turn, final int round) {
        turnBanner.announce(yours, who, turn, round);
        requestLayout();
    }

    public void flipCoin(final boolean heads, final String caption, final String headsWord,
                         final String tailsWord, final Runnable done) {
        coinFlip.flip(heads, caption, headsWord, tailsWord, done);
        requestLayout();
    }

    public void rollDice(final int sides, final java.util.List<Integer> results,
                         final String caption, final Runnable done) {
        diceRoll.roll(sides, results, caption, done);
        requestLayout();
    }

    public TurnBanner getTurnBanner() {
        return turnBanner;
    }

    public PhaseRail getPhaseRail() {
        return phaseRail;
    }

    public CardDetailPanel getDetailPanel() {
        return detail;
    }

    public void setGroupingEnabled(final boolean on) {
        commandZone.setGroupingEnabled(on);
    }

    public void setSelectable(final Predicate<CardView> test) {

        this.selectable = test == null ? c -> false : test;

        selfField.setSelectableTest(this.selectable);
        for (final PlayerField f : oppFields) {
            f.setSelectableTest(this.selectable);
        }
        for (final CardNode n : everyNode()) {
            final CardView cv = n.getCard();
            n.setSelectable(cv != null && test != null && test.test(cv));
        }

        opponentTabs.refreshPickable();
    }

    private static final forge.game.zone.ZoneType[] PICK_ZONES = {
            forge.game.zone.ZoneType.Battlefield, forge.game.zone.ZoneType.Command,
            forge.game.zone.ZoneType.Graveyard, forge.game.zone.ZoneType.Exile};

    private boolean hasSelectableCard(final PlayerView p) {
        if (p == null) {
            return false;
        }
        for (final forge.game.zone.ZoneType z : PICK_ZONES) {
            final var cards = p.getCards(z);
            if (cards == null) {
                continue;
            }
            for (final CardView cv : cards) {
                if (cv != null && selectable.test(cv)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void applyStackTargets() {
        final java.util.Set<Integer> cards = new java.util.HashSet<>();

        final java.util.Set<CardView> loose = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        final java.util.Set<Integer> players = new java.util.HashSet<>();
        final java.util.Set<Integer> spells = new java.util.HashSet<>();
        StackItemView item = null;
        for (final StackItemView s : lastStack) {
            if (s.getId() == hoveredStackId) {
                item = s;
                break;
            }
        }
        if (item == null && !lastStack.isEmpty()) {
            item = lastStack.get(0);
        }
        if (item != null) {
            try {
                if (item.getTargetCards() != null) {
                    for (final CardView c : item.getTargetCards()) {
                        if (c != null && c.getId() > 0) {
                            cards.add(c.getId());
                        } else if (c != null) {
                            loose.add(c);
                        }
                    }
                }
                if (item.getTargetPlayers() != null) {
                    for (final PlayerView p : item.getTargetPlayers()) {
                        if (p != null) {
                            players.add(p.getId());
                        }
                    }
                }
                
                if (item.getTargetStackItems() != null) {
                    for (final StackItemView s : item.getTargetStackItems()) {
                        if (s != null) {
                            spells.add(s.getId());
                        }
                    }
                }
            } catch (final RuntimeException e) {
                
            }
        }
        for (final CardNode n : everyNode()) {
            final CardView cv = n.getCard();
            n.setStackTarget(cv != null && (cv.getId() > 0 ? cards.contains(cv.getId()) : loose.contains(cv)));
        }
        for (final PlayerBar b : allBars()) {
            b.setStackTarget(b.getPlayer() != null && players.contains(b.getPlayer().getId()));
        }
        for (final java.util.Map.Entry<Integer, Region> r : stackRows.entrySet()) {
            r.getValue().pseudoClassStateChanged(STACK_TARGET_ROW, spells.contains(r.getKey()));
        }
    }

    public void noteAttachmentsBefore() {
        attachedBefore.clear();
        if (lastHosts.isEmpty()) {
            return;
        }
        for (final CardNode n : everyNode()) {
            final CardView cv = n.getCard();
            if (cv != null && cv.getId() > 0 && lastHosts.containsKey(cv.getId())
                    && n.getScene() != null && n.isVisible()) {
                attachedBefore.put(cv.getId(), n.localToScene(n.getBoundsInLocal()));
            }
        }
    }

    public void attachmentsMoved() {
        final java.util.Map<Integer, Integer> now = new java.util.HashMap<>();
        final java.util.Map<Integer, javafx.geometry.Bounds> moved = new java.util.HashMap<>();
        for (final CardNode n : everyNode()) {
            final CardView cv = n.getCard();
            if (cv == null || cv.getId() <= 0) {
                continue;
            }
            final CardView host;
            try {
                host = cv.getAttachedTo();
            } catch (final RuntimeException e) {
                continue;
            }
            if (host == null) {
                continue;
            }
            now.put(cv.getId(), host.getId());
            final Integer was = lastHosts.get(cv.getId());
            final javafx.geometry.Bounds from = attachedBefore.get(cv.getId());
            if (was != null && was != host.getId() && from != null) {
                moved.put(cv.getId(), from);
            }
        }
        lastHosts = now;
        attachedBefore.clear();
        if (moved.isEmpty() || !ImpactLayer.isEnabled()) {
            return;
        }
        
        javafx.application.Platform.runLater(() -> {
            applyCss();
            layout();
            for (final CardNode n : everyNode()) {
                final CardView cv = n.getCard();
                final javafx.geometry.Bounds from = cv == null ? null : moved.get(cv.getId());
                if (from != null && n.getScene() != null) {
                    flyCard(n, from);
                }
            }
        });
    }

    private int lastDropId = -1;
    private double lastDropX;
    private double lastDropY;
    private long lastDropAt;

    public void flyIn(final CardView card, final boolean fromStack, final boolean mine, final PlayerView owner) {
        if (card == null || !CardNode.areAnimationsEnabled()) {
            return;
        }
        final javafx.geometry.Bounds from = flightSource(card, fromStack, mine, owner);
        if (from != null) {
            flyInLater(card, from, 0);
        }
    }

    private void flyInLater(final CardView card, final javafx.geometry.Bounds from, final int attempt) {
        final javafx.animation.PauseTransition wait =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(attempt == 0 ? 20 : 60));
        wait.setOnFinished(e -> {
            final CardNode n = nodeOf(card);
            if (n == null || n.getScene() == null || n.getWidth() <= 0) {
                
                if (attempt < 12) {
                    flyInLater(card, from, attempt + 1);
                }
                return;
            }
            applyCss();
            layout();
            if (Boolean.getBoolean("neo.impact.log")) {
                System.out.println("[vuelo] " + card.getName() + " desde " + (int) from.getCenterX() + ","
                        + (int) from.getCenterY() + " (intento " + attempt + ")");
            }
            flyCard(n, from);
        });
        wait.play();
    }

    private javafx.geometry.Bounds flightSource(final CardView card, final boolean fromStack, final boolean mine,
            final PlayerView owner) {
        javafx.geometry.Point2D c = null;
        if (mine && card.getId() == lastDropId && System.currentTimeMillis() - lastDropAt < 8000) {
            c = new javafx.geometry.Point2D(lastDropX, lastDropY);
        } else if (fromStack) {
            final Node s = stackBox.getScene() != null && stackBox.isVisible() ? stackBox
                    : stackChip.getScene() != null && stackChip.isVisible() ? stackChip : null;
            c = centerOf(s);
        } else if (mine) {
            c = centerOf(hand);
        } else if (owner != null) {
            c = centerOf(portraitOf(owner));
        }
        if (c == null) {
            return null;
        }
        final double w = UiScale.px(70);
        final double h = w * 7 / 5;
        return new javafx.geometry.BoundingBox(c.getX() - w / 2, c.getY() - h / 2, w, h);
    }

    private static javafx.geometry.Point2D centerOf(final Node n) {
        if (n == null || n.getScene() == null) {
            return null;
        }
        final javafx.geometry.Bounds b = n.localToScene(n.getBoundsInLocal());
        return new javafx.geometry.Point2D(b.getCenterX(), b.getCenterY());
    }

    private void flyCard(final CardNode n, final javafx.geometry.Bounds fromScene) {
        final javafx.geometry.Bounds toScene = n.localToScene(n.getBoundsInLocal());
        if (toScene.getWidth() <= 0 || fromScene.getWidth() <= 0
                || Math.hypot(toScene.getMinX() - fromScene.getMinX(),
                        toScene.getMinY() - fromScene.getMinY()) < 4) {
            return;
        }
        final javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
        params.setFill(javafx.scene.paint.Color.TRANSPARENT);
        final javafx.scene.image.WritableImage img;
        try {
            img = n.snapshot(params, null);
        } catch (final RuntimeException e) {
            return;
        }

        final javafx.geometry.Bounds fromL = impactLayer.sceneToLocal(fromScene);
        final javafx.geometry.Bounds toL = impactLayer.sceneToLocal(toScene);
        if (fromL == null || toL == null) {
            return;
        }
        final javafx.scene.image.ImageView ghost = new javafx.scene.image.ImageView(img);
        ghost.setMouseTransparent(true);
        ghost.setManaged(false);
        
        ghost.setFitWidth(toL.getWidth());
        ghost.setFitHeight(toL.getHeight());
        ghost.relocate(toL.getMinX(), toL.getMinY());
        final double s0 = fromL.getWidth() / toL.getWidth();
        final double dx = fromL.getCenterX() - toL.getCenterX();
        final double dy = fromL.getCenterY() - toL.getCenterY();
        ghost.setTranslateX(dx);
        ghost.setTranslateY(dy);
        ghost.setScaleX(s0);
        ghost.setScaleY(s0);
        ghost.setEffect(new javafx.scene.effect.DropShadow(18, javafx.scene.paint.Color.rgb(224, 166, 60, 0.85)));
        impactLayer.getChildren().add(ghost);
        n.setOpacity(0);
        
        final javafx.util.Duration d = javafx.util.Duration.millis(Integer.getInteger("neo.attachFlyMs", 340));
        final double lift = Math.max(s0, 1) * 1.12;
        final javafx.animation.Timeline t = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.ZERO,
                        new javafx.animation.KeyValue(ghost.translateXProperty(), dx),
                        new javafx.animation.KeyValue(ghost.translateYProperty(), dy)),
                
                new javafx.animation.KeyFrame(d.multiply(0.5),
                        new javafx.animation.KeyValue(ghost.scaleXProperty(), lift, javafx.animation.Interpolator.EASE_OUT),
                        new javafx.animation.KeyValue(ghost.scaleYProperty(), lift, javafx.animation.Interpolator.EASE_OUT)),
                new javafx.animation.KeyFrame(d,
                        new javafx.animation.KeyValue(ghost.translateXProperty(), 0, javafx.animation.Interpolator.EASE_BOTH),
                        new javafx.animation.KeyValue(ghost.translateYProperty(), 0, javafx.animation.Interpolator.EASE_BOTH),
                        new javafx.animation.KeyValue(ghost.scaleXProperty(), 1, javafx.animation.Interpolator.EASE_IN),
                        new javafx.animation.KeyValue(ghost.scaleYProperty(), 1, javafx.animation.Interpolator.EASE_IN)));
        t.setOnFinished(e -> {
            impactLayer.getChildren().remove(ghost);
            n.setOpacity(1);
        });
        if (Boolean.getBoolean("neo.impact.log")) {
            System.out.println("[equipo] vuela " + n.getCard().getName());
        }
        t.play();
    }

    private java.util.Map<Integer, Integer> lastHosts = new java.util.HashMap<>();
    
    private final java.util.Map<Integer, javafx.geometry.Bounds> attachedBefore = new java.util.HashMap<>();

    private int hoveredStackId = -1;

    private final java.util.Map<Integer, Region> stackRows = new java.util.HashMap<>();

    private static final javafx.css.PseudoClass STACK_TARGET_ROW =
            javafx.css.PseudoClass.getPseudoClass("stack-target");

    public void setActionable(final java.util.function.ToIntFunction<CardView> strength) {
        this.actionable = strength;
        for (final CardNode n : everyNode()) {
            final CardView cv = n.getCard();
            n.setActionable(cv == null || strength == null ? 0 : strength.applyAsInt(cv));
        }
    }

    private java.util.function.ToIntFunction<CardView> actionable;

    public void setOnPlayerClicked(final Consumer<PlayerView> handler) {
        this.onPlayerClicked = handler;
        for (final PlayerBar b : oppBars) {
            b.setOnPlayerClicked(handler);
        }
        selfBar.setOnPlayerClicked(handler);
    }

    private Consumer<PlayerView> onPlayerClicked;
    private boolean playersSelectable;

    public void setPlayersSelectable(final boolean on) {
        this.playersSelectable = on;
        for (final PlayerBar b : oppBars) {
            b.setSelectable(on);
        }
        selfBar.setSelectable(on);
    }

    public void setOnCardClicked(final Consumer<CardView> handler) {
        this.onCardClicked = handler;
    }

    private Consumer<CardView> onCardClicked;

    private void cardClicked(final CardNode n) {
        if (onCardClicked != null && n.getCard() != null) {
            onCardClicked.accept(n.getCard());
        }
    }

    public void setOnStackCountClicked(final Consumer<java.util.List<CardView>> handler) {
        this.onStackCountClicked = handler;
    }

    private Consumer<java.util.List<CardView>> onStackCountClicked;

    private void stackCountClicked(final java.util.List<CardView> cards) {
        if (cards == null || cards.isEmpty()) {
            return;
        }
        if (onStackCountClicked != null) {
            onStackCountClicked.accept(cards);
        } else {
            cardClickedDirect(cards.get(0));
        }
    }

    public void askPileCount(final CardView card, final int most,
            final java.util.function.IntConsumer onPick) {
        final String name = card == null || card.getCurrentState() == null ? ""
                : forge.neo.card.CardText.nameOf(card.getCurrentState());
        final Label heading = new Label(NeoText.get("pile.count.title"));
        heading.getStyleClass().add("dialog-title");
        final Label body = new Label(NeoText.get("pile.count.body", name, most));
        body.getStyleClass().add("dialog-text");
        body.setWrapText(true);
        body.setMaxWidth(UiScale.px(460));
        body.setMinHeight(Region.USE_PREF_SIZE);

        final javafx.scene.control.Slider slider = new javafx.scene.control.Slider(1, most, most);
        slider.setBlockIncrement(1);
        slider.setMajorTickUnit(1);
        slider.setMinorTickCount(0);
        slider.setSnapToTicks(true);
        slider.setPrefWidth(UiScale.px(320));
        final Button less = new Button("−");
        final Button more = new Button("+");
        less.getStyleClass().add("btn-secondary");
        more.getStyleClass().add("btn-secondary");
        less.setOnAction(e -> slider.setValue(Math.max(1, Math.round(slider.getValue()) - 1)));
        more.setOnAction(e -> slider.setValue(Math.min(most, Math.round(slider.getValue()) + 1)));
        final Label value = new Label();
        value.getStyleClass().add("dialog-title");
        value.setMinWidth(UiScale.px(48));
        value.setAlignment(javafx.geometry.Pos.CENTER);
        final HBox picker = new HBox(10, less, slider, more, value);
        picker.setAlignment(javafx.geometry.Pos.CENTER);

        final Button cancel = new Button(NeoText.get("common.cancel"));
        cancel.getStyleClass().add("btn-secondary");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> menuOverlay.hide());
        final Button ok = new Button();
        ok.getStyleClass().add("btn-primary");
        ok.setDefaultButton(true);
        final Runnable sync = () -> {
            final int n = (int) Math.round(slider.getValue());
            value.setText(Integer.toString(n));
            ok.setText(NeoText.get("pile.count.pick", n));
        };
        slider.valueProperty().addListener((o, a, b) -> sync.run());
        sync.run();
        ok.setOnAction(e -> {
            menuOverlay.hide();
            onPick.accept((int) Math.round(slider.getValue()));
        });

        final HBox footer = new HBox(10, cancel, ok);
        footer.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        final VBox box = new VBox(14, heading, body, picker, footer);
        box.getStyleClass().add("dialog");
        box.setPadding(new Insets(22, 26, 20, 26));
        box.setMaxWidth(Region.USE_PREF_SIZE);
        box.setMaxHeight(Region.USE_PREF_SIZE);
        menuOverlay.setOnBackgroundClick(menuOverlay::hide);
        menuOverlay.show(box);
        javafx.application.Platform.runLater(ok::requestFocus);
    }

    private void cardClickedDirect(final CardView card) {
        if (onCardClicked != null && card != null) {
            onCardClicked.accept(card);
        }
    }

    public void setOnCardHover(final Consumer<CardNode> handler) {
        this.onCardHover = handler;
    }

    public void refreshAll() {

        CardNode.refreshAllIn(overlay);
        CardNode.refreshAllIn(menuOverlay);
        CardNode.refreshAllIn(zoomOverlay);

        CardNode.refreshAllIn(promptBanner);
        for (final PlayerField f : oppFields) {
            f.refreshAll();
        }
        selfField.refreshAll();
        commandZone.refreshAll();
        for (final CardNode n : extraNodes) {
            n.refresh();
        }
        if (handPeek.isVisible()) {
            handPeek.refresh();
        }
        detail.refresh();
    }

    private List<CardNode> everyNode() {
        final List<CardNode> all = new ArrayList<>(extraNodes);
        for (final PlayerField f : oppFields) {
            all.addAll(f.nodes());
        }
        all.addAll(selfField.nodes());
        all.addAll(commandZone.nodes());
        return all;
    }

    public boolean hasPreparedCard() {
        return preparedCardNode() != null;
    }

    public List<CardView> shownSelectableCards() {
        final List<CardView> out = new ArrayList<>();
        for (final CardNode n : everyNode()) {
            if (n.getCard() != null && n.isSelectable() && n.isVisible()) {
                out.add(n.getCard());
            }
        }
        return out;
    }

    public CardNode preparedCardNode() {
        for (final CardNode n : everyNode()) {
            if (n.getCard() != null && n.getCard().getPreparedSpell() != null) {
                return n;
            }
        }
        return null;
    }

    public void reapplyHighlighted() {
        if (highlighted != null) {
            setHighlighted(highlighted);
        }
    }

    private Predicate<Object> highlighted;

    public int nodeCount() {
        return everyNode().size();
    }

    public long nodesWithArt() {
        return everyNode().stream().filter(CardNode::hasArt).count();
    }

    private final Map<Integer, CardNode> byCardId = new HashMap<>();

    private final java.util.IdentityHashMap<CardView, CardNode> byCardRef =
            new java.util.IdentityHashMap<>();

    private void reindex() {
        byCardId.clear();
        byCardRef.clear();
        for (final CardNode n : everyNode()) {
            final CardView cv = n.getCard();
            if (cv == null) {
                continue;
            }
            byCardRef.put(cv, n);
            if (cv.getId() >= 0) {
                byCardId.put(cv.getId(), n);
            }
        }
    }

    public void flashHit(final CardView card) {
        final CardNode node = nodeOf(card);
        if (node != null) {
            Anim.hit(node);
        }
    }

    public void bumpCard(final CardView card) {
        final CardNode node = nodeOf(card);
        if (node != null) {
            Anim.bump(node);
        }
    }

    private CardNode nodeOf(final CardView card) {
        if (card == null) {
            return null;
        }
        final CardNode byId = card.getId() >= 0 ? byCardId.get(card.getId()) : null;
        return byId != null ? byId : byCardRef.get(card);
    }

    private PlayerBar barOf(final PlayerView player) {
        if (player == null) {
            return null;
        }
        for (final PlayerBar b : oppBars) {
            if (player.equals(b.getPlayer())) {
                return b;
            }
        }
        return player.equals(selfBar.getPlayer()) ? selfBar : null;
    }

    private List<PlayerBar> allBars() {
        final List<PlayerBar> all = new ArrayList<>(oppBars);
        all.add(selfBar);
        return all;
    }

    public void impactHit(final Object entity, final List<javafx.scene.paint.Color> colors, final Object source) {
        
        if (entity instanceof CardView cv && ImpactLayer.isEnabled()) {
            GhostHold.hold(cv.getId());
            selfField.holdGhost(cv);
            for (final PlayerField f : oppFields) {
                f.holdGhost(cv);
            }
        }
        impactLayer.hit(entity, colors, source);
    }

    public void spellBlast(final forge.neo.match.SpellSignature signature, final Object target) {
        final Bounds b = signature.atTarget && target != null ? impactBoundsOf(target) : null;
        if (signature.atTarget && b == null) {
            return;
        }
        impactLayer.spell(signature, b, () -> Anim.shake(viewport));
    }

    public void spellTest() {
        
        if (System.getProperty("neo.spell.freezeAt") != null && impactLayer.blastShowing()) {
            return;
        }
        forge.neo.match.SpellSignature sig;
        try {
            sig = forge.neo.match.SpellSignature.valueOf(
                    System.getProperty("neo.spell.test", "BLASPHEMOUS_ACT").trim().toUpperCase(java.util.Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            sig = forge.neo.match.SpellSignature.BLASPHEMOUS_ACT;
        }
        CardView target = null;
        for (final PlayerField f : oppFields) {
            for (final CardNode n : f.nodes()) {
                if (target == null && n.getCard() != null) {
                    target = n.getCard();
                }
            }
        }
        spellBlast(sig, target);
    }

    public void commanderEntrance(final CardView card, final List<javafx.scene.paint.Color> colors,
            final String subtitle, final forge.neo.match.CommanderSignature signature) {
        impactLayer.commander(card, colors, subtitle, signature);
    }

    public boolean entranceShowing() {
        return impactLayer.entranceShowing();
    }

    public void entranceTest() {
        CardNode pick = null;
        for (final CardNode n : selfField.nodes()) {
            if (n.getCard() != null && (pick == null || n.getCard().isCommander())) {
                pick = n;
            }
        }
        if (pick != null) {
            impactLayer.commander(pick.getCard(), List.of(ImpactLayer.RED, ImpactLayer.WHITE),
                    forge.neo.NeoText.get("entrance.commander"),
                    forge.neo.match.CommanderSignature.of(pick.getCard(), true));
        }
    }

    public void strike(final CardView source, final Object target) {
        final CardNode n = nodeOf(source);
        final Bounds to = impactBoundsOf(target);
        if (Boolean.getBoolean("neo.impact.log")) {
            System.out.println("[golpe] " + source + " -> " + target
                    + (n == null || n.getScene() == null ? " (atacante no se ve)" : to == null ? " (victima no se ve)" : ""));
        }
        if (n == null || to == null || n.getScene() == null) {
            return;
        }
        Anim.strike(n, localToScene(to.getCenterX(), to.getCenterY()));
    }

    public void damagePop(final Object entity, final int amount, final boolean combat, final boolean poison) {
        if (entity instanceof CardView cv) {
            GhostHold.hold(cv.getId());
            selfField.holdGhost(cv);
            for (final PlayerField f : oppFields) {
                f.holdGhost(cv);
            }
        }
        impactLayer.damage(entity, amount, combat, poison, () -> {
            if (entity instanceof CardView cv) {
                CardNode n = nodeOf(cv);
                if (n == null || n.getScene() == null) {
                    n = selfField.ghostOf(cv);
                    for (int i = 0; n == null && i < oppFields.size(); i++) {
                        n = oppFields.get(i).ghostOf(cv);
                    }
                }
                Anim.hit(n);
            } else if (entity instanceof PlayerView pv) {
                Anim.shake(portraitOf(pv));
            }
        });
    }

    private Node portraitOf(final PlayerView pv) {
        final PlayerBar bar = barOf(pv);
        if (bar != null && bar.getPortrait() != null && bar.getPortrait().getScene() != null) {
            return bar.getPortrait();
        }
        return opponentTabs.tabOf(pv);
    }

    public void impactArrive(final Object entity, final List<javafx.scene.paint.Color> colors) {
        impactLayer.arrive(entity, colors);
    }

    public int impactCount() {
        return impactLayer.liveCount();
    }

    private int impactTick;

    public void impactTest() {
        final List<javafx.scene.paint.Color> palette = List.of(ImpactLayer.RED, ImpactLayer.BLUE,
                ImpactLayer.GREEN, ImpactLayer.BLACK, ImpactLayer.WHITE, ImpactLayer.COLORLESS);
        
        final int only = Integer.getInteger("neo.impact.only", -1);
        final Object me = selfBar.getPlayer();
        int i = 0;

        if (Boolean.getBoolean("neo.impact.damage")) {
            int k = impactTick++;
            for (final PlayerField f : oppFields) {
                for (final CardNode n : f.nodes()) {
                    if (n.getCard() != null) {
                        damagePop(n.getCard(), 1 + k++ % 6, true, false);
                    }
                }
            }
            damagePop(me, 3, true, false);
            if (opponentBar.getPlayer() != null) {
                damagePop(opponentBar.getPlayer(), 1 + k % 5, false, k % 2 == 0);
            }
            return;
        }
        
        if (Boolean.getBoolean("neo.impact.one")) {
            final List<CardNode> targets = new ArrayList<>();
            for (final PlayerField f : oppFields) {
                for (final CardNode n : f.nodes()) {
                    if (n.getCard() != null && n.getCard().getCurrentState() != null
                            && n.getCard().getCurrentState().isCreature()) {
                        targets.add(n);
                    }
                }
            }
            if (!targets.isEmpty()) {
                final int k = impactTick++;
                impactLayer.hit(targets.get(k % targets.size()).getCard(),
                        List.of(palette.get(k % palette.size())), me);
            }
            return;
        }
        
        final boolean arriveOnly = Boolean.getBoolean("neo.impact.arriveOnly");
        for (final PlayerField f : arriveOnly ? List.<PlayerField>of() : oppFields) {
            for (final CardNode n : f.nodes()) {
                final javafx.scene.paint.Color c = palette.get(only >= 0 ? only : i++ % palette.size());
                impactLayer.hit(n.getCard(), List.of(c), me);
            }
        }
        for (final CardNode n : selfField.nodes()) {
            impactLayer.arrive(n.getCard(), List.of(palette.get(only >= 0 ? only : i++ % palette.size())));
        }
    }

    private Bounds impactBoundsOf(final Object entity) {
        final Bounds live = boundsOf(entity);
        if (live != null) {
            return live;
        }
        
        if (entity instanceof PlayerView pv) {
            final Node tab = opponentTabs.tabOf(pv);
            return tab == null || tab.getScene() == null ? null
                    : sceneToLocal(tab.localToScene(tab.getBoundsInLocal()));
        }
        if (!(entity instanceof CardView cv)) {
            return null;
        }
        CardNode ghost = selfField.ghostOf(cv);
        for (int i = 0; ghost == null && i < oppFields.size(); i++) {
            ghost = oppFields.get(i).ghostOf(cv);
        }
        if (ghost != null && ghost.getScene() != null) {
            return boundsOfNode(ghost);
        }
        
        Bounds was = selfField.lastSceneBoundsOf(cv);
        for (int i = 0; was == null && i < oppFields.size(); i++) {
            was = oppFields.get(i).lastSceneBoundsOf(cv);
        }
        return was == null ? null : sceneToLocal(was);
    }

    private Bounds boundsOf(final Object entity) {
        Node node = null;
        if (entity instanceof CardView cv) {
            node = nodeOf(cv);
        } else if (entity instanceof PlayerView pv) {
            final PlayerBar bar = barOf(pv);
            if (bar != null) {
                node = bar.getPortrait();
            }
        }
        if (node == null || node.getScene() == null) {
            return null;
        }
        return boundsOfNode(node);
    }

    private Bounds boundsOfNode(final Node node) {
        final Bounds bounds = sceneToLocal(node.localToScene(node.getBoundsInLocal()));

        for (Node parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof BattlefieldPane || parent instanceof HandFan) {
                final Region row = (Region) parent;
                final Bounds visible = sceneToLocal(row.localToScene(
                        new javafx.geometry.BoundingBox(0, 0, row.getWidth(), row.getHeight())));
                final double left = Math.max(visible.getMinX(), Math.min(visible.getMaxX(), bounds.getMinX()));
                final double right = Math.max(left, Math.min(visible.getMaxX(), bounds.getMaxX()));
                return new javafx.geometry.BoundingBox(left, bounds.getMinY(), right - left, bounds.getHeight());
            }
        }
        return bounds;
    }

    public void setCombatLinks(final List<Object[]> links) {
        combatOverlay.setLinks(links);
    }

    public int combatLinkCount() {
        return combatOverlay.linkCount();
    }

    public List<CardNode> handNodes() {
        return new ArrayList<>(extraNodes);
    }

    public List<CardNode> opponentFieldNodes() {
        return opponentField.nodes();
    }

    public List<CardNode> oppFieldNodes() {
        final List<CardNode> out = new ArrayList<>();
        for (final PlayerField f : oppFields) {
            out.addAll(f.nodes());
        }
        return out;
    }

    public List<CardNode> selfFieldNodes() {
        return selfField.nodes();
    }

    public int stackSize() {
        return stackSize;
    }

    private int stackSize;

    public int fightLinkCount() {
        return combatOverlay.fightLinkCount();
    }

    public void redrawCombat() {
        combatOverlay.redrawNow();
    }

    public interface DropHandler {
        
        void onDrop(CardView source, boolean fromHand, Object target);
    }

    private DropHandler onDrop;
    private CardNode dragNode;
    private boolean dragFromHand;
    private boolean dragActive;

    private double pressX;
    private double pressY;
    
    private CardNode peeking;
    private CardNode lastDropNode;
    private boolean swallowNextClick;

    private static final double DRAG_SLOP = 9;

    private static final boolean DRAG_DEBUG = Boolean.getBoolean("neo.drag.debug");

    public void setOnCardDropped(final DropHandler handler) {
        this.onDrop = handler;
    }

    public boolean hasDropHandler() {
        return onDrop != null;
    }

    private void installDragGestures() {
        addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {

            if (promptBanner.isVisible() && !isInside(e.getTarget(), promptBanner)) {
                promptBanner.fireDismiss();
            }

            if (stackPeek && !isInside(e.getTarget(), stackBox) && !isInside(e.getTarget(), stackChip)) {
                stackPeek = false;
                requestLayout();
            }

            if (e.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                final CardNode zoomed = cardNodeAt(e.getTarget());
                CardView card = zoomed == null ? null : zoomed.getCard();
                if (card == null && isInside(e.getTarget(), detail)) {

                    card = detail.getCurrent();
                }
                if (card != null) {

                    final Node layer = zoomed == null ? null
                            : isInside(zoomed, overlay) ? overlay
                            : isInside(zoomed, menuOverlay) ? menuOverlay : null;
                    final List<CardNode> ring = layer == null ? List.of() : ringOf(zoomed, layer);
                    final int at = ring.indexOf(zoomed);
                    if (ring.size() > 1 && at >= 0) {
                        final List<CardView> cards = new ArrayList<>(ring.size());
                        for (final CardNode n : ring) {
                            cards.add(n.getCard());
                        }
                        showZoom(cards, at);
                    } else {
                        showZoom(card);
                    }
                } else if (zoomOverlay.isShowing()) {
                    hideZoom();
                }
                e.consume();
                return;
            }
            dragNode = draggableAt(e.getTarget());
            dragFromHand = dragNode != null && extraNodes.contains(dragNode);
            dragActive = false;

            swallowNextClick = false;
            pressX = e.getSceneX();
            pressY = e.getSceneY();
            hidePeek();
            if (dragFromHand && e.getButton() == javafx.scene.input.MouseButton.PRIMARY
                    && touchHandFor(e) && !isModalShowing() && !overlay.isPeeking()) {

                showPeek(dragNode);
                dragNode = null;
                dragFromHand = false;
            }
        });

        addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (peeking != null) {
                if (!isOverBoard(e.getSceneX(), e.getSceneY()) || isModalShowing()) {

                    final javafx.geometry.Bounds hb = hand.localToScene(hand.getLayoutBounds());
                    final CardNode under = e.getSceneY() >= hb.getMinY()
                            ? handCardAt(e.getSceneX()) : null;
                    if (under != null && under != peeking) {
                        showPeek(under);
                    }
                    e.consume();
                    return;
                }

                dragNode = peeking;
                dragFromHand = true;
                hidePeek();
                dragActive = true;
                dragNode.setDragging(true);
                combatOverlay.startDrag(dragNode.getCard());
            }
            if (dragNode == null || isModalShowing()) {
                return;
            }
            if (!dragActive) {
                if (Math.hypot(e.getSceneX() - pressX, e.getSceneY() - pressY) < DRAG_SLOP) {
                    return;
                }
                dragActive = true;
                dragNode.setDragging(true);
                combatOverlay.startDrag(dragNode.getCard());
            }
            final Point2D p = sceneToLocal(e.getSceneX(), e.getSceneY());
            final boolean cancelling = dragFromHand && !isOverBoard(e.getSceneX(), e.getSceneY());
            final Object target = cancelling ? null : entityAt(e.getSceneX(), e.getSceneY());
            markDropTarget(target);

            combatOverlay.moveDrag(p.getX(), p.getY(), target != null);
            e.consume();
        });

        addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (peeking != null) {

                final CardNode shown = peeking;
                final boolean tap = Math.hypot(e.getSceneX() - pressX,
                        e.getSceneY() - pressY) < DRAG_SLOP;
                hidePeek();
                swallowNextClick = true;
                if (tap && shown.isSelectable() && !isModalShowing()) {
                    cardClicked(shown);
                }
                e.consume();
                return;
            }
            if (!dragActive) {
                dragNode = null;
                return;
            }
            final Object target = entityAt(e.getSceneX(), e.getSceneY());
            final CardView source = dragNode.getCard();
            final boolean fromHand = dragFromHand;
            dragNode.setDragging(false);
            markDropTarget(null);
            combatOverlay.endDrag();
            dragNode = null;
            dragActive = false;
            swallowNextClick = true;

            final boolean cancelled = fromHand && !isOverBoard(e.getSceneX(), e.getSceneY());

            if (DRAG_DEBUG) {
                System.out.printf("[drag] %s (mano=%s) -> %s%s%n",
                        source, fromHand, target, cancelled ? " [CANCELADO]" : "");
            }
            if (!cancelled && onDrop != null && source != null) {
                
                if (fromHand) {
                    lastDropId = source.getId();
                    lastDropX = e.getSceneX();
                    lastDropY = e.getSceneY();
                    lastDropAt = System.currentTimeMillis();
                }
                onDrop.onDrop(source, fromHand, target);
            }

            if (cancelled && fromHand && source != null && onHandReorder != null
                    && isOverHand(e.getSceneX(), e.getSceneY())) {
                final int index = handDropIndex(e.getSceneX(), source);
                if (index >= 0) {
                    onHandReorder.accept(source, index);
                }
            }
            e.consume();
        });

        addEventFilter(javafx.scene.input.ScrollEvent.ANY, e -> {
            if (peeking != null && e.isDirect()) {
                e.consume();
            }
        });

        addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
            if (swallowNextClick) {
                swallowNextClick = false;
                e.consume();
            }
        });
    }

    private boolean isOverBoard(final double sceneX, final double sceneY) {

        final Point2D v = viewport.sceneToLocal(sceneX, sceneY);
        if (v == null || v.getX() < 0 || v.getY() < 0
                || v.getX() > viewport.getWidth() || v.getY() > viewport.getHeight()) {
            return false;
        }
        final Point2D p = board.sceneToLocal(sceneX, sceneY);
        return board.getBoundsInLocal().contains(p);
    }

    private static boolean touchHandFor(final MouseEvent e) {
        final String mode = forge.neo.NeoSettings.touchHand();
        return forge.neo.NeoSettings.TOUCH_HAND_ALWAYS.equals(mode)
                || forge.neo.NeoSettings.TOUCH_HAND_FINGER.equals(mode) && e.isSynthesized();
    }

    private java.util.function.BiConsumer<CardView, Integer> onHandReorder;

    public void setOnHandReorder(final java.util.function.BiConsumer<CardView, Integer> handler) {
        this.onHandReorder = handler;
    }

    public boolean hasHandReorderHandler() {
        return onHandReorder != null;
    }

    private boolean isOverHand(final double sceneX, final double sceneY) {
        final javafx.geometry.Bounds b = hand.localToScene(hand.getLayoutBounds());
        return b != null && b.contains(sceneX, sceneY);
    }

    private int handDropIndex(final double sceneX, final CardView source) {
        if (forge.neo.NeoSettings.orderHand()) {
            return -1;
        }
        final List<CardNode> cards = hand.getCards();
        final double x = hand.sceneToLocal(sceneX, 0).getX();
        int current = -1;
        int index = 0;
        for (int i = 0; i < cards.size(); i++) {
            final CardNode c = cards.get(i);
            if (c.getCard() != null && c.getCard().getId() == source.getId()) {
                current = i;
                continue;
            }
            if (c.getLayoutX() + c.getWidth() / 2 < x) {
                index++;
            }
        }
        return current < 0 || index == current ? -1 : index;
    }

    private CardNode handCardAt(final double sceneX) {
        final List<CardNode> cards = hand.getCards();
        if (cards.isEmpty()) {
            return null;
        }
        final double x = hand.sceneToLocal(sceneX, 0).getX();
        for (int i = cards.size() - 1; i >= 0; i--) {
            final CardNode c = cards.get(i);
            if (x >= c.getLayoutX() && x <= c.getLayoutX() + c.getWidth()) {
                return c;
            }
        }
        final CardNode first = cards.get(0);
        return x < first.getLayoutX() ? first : cards.get(cards.size() - 1);
    }

    private void showPeek(final CardNode over) {
        if (over == null || over.getCard() == null) {
            return;
        }
        peeking = over;
        final double contentW = getWidth() - side.getWidth();
        final double handTop = hand.getBoundsInParent().getMinY();
        final double top = phaseRail.getBoundsInParent().getMaxY() + PAD;
        double pw = Math.min(contentW / 3, Math.max(over.getWidth() * 2.2, UiScale.px(260)));
        if (pw * CardNode.ASPECT > handTop - top - PAD) {
            pw = Math.max(over.getWidth(), (handTop - top - PAD) / CardNode.ASPECT);
        }
        final double ph = pw * CardNode.ASPECT;
        final javafx.geometry.Bounds b = over.localToScene(over.getBoundsInLocal());
        final double centre = sceneToLocal(b.getCenterX(), b.getCenterY()).getX();
        final double x = Math.max(PAD, Math.min(contentW - pw - PAD, centre - pw / 2));
        handPeek.setCard(over.getCard());
        handPeek.setCardWidth(pw);
        handPeek.resizeRelocate(x, Math.max(top, handTop - ph - PAD), pw, ph);
        handPeek.setVisible(true);
    }

    private void hidePeek() {
        peeking = null;
        handPeek.setVisible(false);
    }

    public CardView peekedHandCard() {
        return peeking == null ? null : peeking.getCard();
    }

    private static boolean isInside(final Object target, final Node ancestor) {
        Node n = target instanceof Node ? (Node) target : null;
        while (n != null) {
            if (n == ancestor) {
                return true;
            }
            n = n.getParent();
        }
        return false;
    }

    private static boolean isZoneOpener(final Object target) {
        Node n = target instanceof Node ? (Node) target : null;
        while (n != null) {
            if (n instanceof ZonePile || n.getStyleClass().contains("zone-button")) {
                return true;
            }
            n = n.getParent();
        }
        return false;
    }

    private static CardNode cardNodeAt(final Object target) {
        Node n = target instanceof Node ? (Node) target : null;
        while (n != null) {
            if (n instanceof CardNode cn) {
                return cn;
            }
            n = n.getParent();
        }
        return null;
    }

    private CardNode draggableAt(final Object target) {
        Node n = target instanceof Node ? (Node) target : null;
        CardNode found = null;
        while (n != null) {
            if (found == null && n instanceof CardNode cn) {
                found = cn;
            }

            if (n == side || n == overlay || n == menuOverlay || n == promptBanner) {
                return null;
            }
            n = n.getParent();
        }
        return found;
    }

    private Object entityAt(final double sceneX, final double sceneY) {
        CardNode best = null;
        for (final CardNode n : everyNode()) {
            if (n == dragNode || n.getCard() == null || n.getScene() == null) {
                continue;
            }
            if (visibleHit(n, sceneX, sceneY)) {
                
                best = n;
            }
        }
        if (best != null) {
            return best.getCard();
        }

        for (final PlayerBar b : allBars()) {
            if (b.getPlayer() != null
                    && visibleHit(b, sceneX, sceneY)) {
                return b.getPlayer();
            }
        }
        return null;
    }

    private static boolean visibleHit(final Node node, final double sceneX, final double sceneY) {
        if (node.getScene() == null || node.isDisabled()) return false;
        final Point2D point = node.sceneToLocal(sceneX, sceneY);
        if (point == null || !node.contains(point)) return false;
        for (Node current = node; current != null; current = current.getParent()) {
            if (!current.isVisible() || current.getOpacity() <= 0 || current.isMouseTransparent()) return false;
            final Node clip = current.getClip();
            if (clip != null) {
                final Point2D local = current.sceneToLocal(sceneX, sceneY);
                final Point2D clipped = local == null ? null : clip.parentToLocal(local);
                if (clipped == null || !clip.contains(clipped)) return false;
            }
        }
        return true;
    }

    private void markDropTarget(final Object target) {
        final CardNode node = target instanceof CardView cv ? nodeOf(cv) : null;
        if (node == lastDropNode) {
            return;
        }
        if (lastDropNode != null) {
            lastDropNode.setDropTarget(false);
        }
        lastDropNode = node;
        if (node != null) {
            node.setDropTarget(true);
        }
    }

    public void setHighlighted(final Predicate<Object> test) {
        this.highlighted = test;

        final Predicate<CardView> picked =
                cv -> cv != null && test != null && test.test(cv);
        selfField.setPickedTest(picked);
        for (final PlayerField f : oppFields) {
            f.setPickedTest(picked);
        }
        for (final CardNode n : everyNode()) {
            final CardView cv = n.getCard();
            n.setHighlighted(cv != null && test != null && test.test(cv));
        }
        for (final PlayerBar b : allBars()) {
            b.setHighlighted(test != null && b.getPlayer() != null
                    && test.test(b.getPlayer()));
        }
    }

    public void setManaPoolActive(final PlayerView player, final boolean on) {
        if (player == null) {
            return;
        }
        final PlayerBar bar = barOf(player);
        if (bar != null) {
            bar.setManaPoolActive(on);
        }
    }

    public void setOnManaClicked(final Consumer<Byte> handler) {
        selfBar.setOnManaClicked(handler);
    }

    public void setOnPhaseToggled(final java.util.function.BiConsumer<Boolean, PhaseType> handler) {

        phaseRail.setOnToggle((mine, phase) -> {
            gesture(Gesture.PHASE_STOP);
            if (handler != null) {
                handler.accept(mine, phase);
            }
        });
    }

    public void setPhaseStops(final Predicate<PhaseType> mine, final Predicate<PhaseType> theirs) {
        phaseRail.setStops(mine, theirs);
    }

    public List<CardView> selfCreatureViews() {
        return creatureViews(selfField);
    }

    public List<CardView> opponentCreatureViews() {
        return creatureViews(opponentField);
    }

    private static List<CardView> creatureViews(final PlayerField field) {
        final List<CardView> out = new ArrayList<>();
        for (final CardNode n : field.nodes()) {
            final CardView cv = n.getCard();
            if (cv != null && cv.getCurrentState() != null && cv.getCurrentState().isCreature()) {
                out.add(cv);
            }
        }
        return out;
    }

    public void dumpLayout() {
        System.out.printf("[layout] table=%.0fx%.0f%n", getWidth(), getHeight());
        dump("oppBar", opponentBar);
        dump("oppField", opponentField);
        dump("selfField", selfField);
        dump("selfBar", selfBar);
        dump("hand", hand);

        dump("side", side);
        dump("  phaseRail", phaseRail);
        dump("  stackBox", stackBox);
        dump("  actionBar", actionBar);
    }

    private static void dump(final String label, final Region r) {
        System.out.printf("[layout] %-10s y=%.0f h=%.0f%n",
                label, r.getLayoutY(), r.getHeight());
    }
}

