package forge.neo.card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import forge.neo.NeoText;

import com.google.common.collect.Multiset;

import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.card.CounterEnumType;
import forge.game.card.CounterType;
import forge.game.zone.ZoneType;
import javafx.animation.Interpolator;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Pos;
import javafx.scene.CacheHint;
import javafx.scene.control.Label;
import javafx.scene.effect.BlendMode;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

public class CardNode extends StackPane {

    public static final double ASPECT = 88.0 / 63.0;

    private static final Duration TAP_TIME = Duration.millis(220);
    private static final Duration HOVER_TIME = Duration.millis(140);

    private static volatile boolean animations = true;

    public static void setAnimationsEnabled(final boolean on) {
        animations = on;
    }

    public static boolean areAnimationsEnabled() {
        return animations;
    }

    private static volatile boolean foilEffect = true;

    public static void setFoilEffectEnabled(final boolean on) {
        foilEffect = on;
    }

    public static boolean isFoilEffectEnabled() {
        return foilEffect;
    }

    private static volatile boolean keywordBadges = true;

    public static void setKeywordBadgesEnabled(final boolean on) {
        keywordBadges = on;
    }

    public static boolean areKeywordBadgesEnabled() {
        return keywordBadges;
    }

    private static volatile boolean foilMotion = !Boolean.getBoolean("neo.foil.static");

    public static void setFoilMotionEnabled(final boolean on) {
        foilMotion = on;
    }

    public static boolean isFoilMotionEnabled() {
        return foilMotion;
    }

    private static final boolean FOIL_EVERYTHING = Boolean.getBoolean("neo.foil.all");

    private static final LinearGradient FOIL_TINT = new LinearGradient(
            0, 0, 0.11, 0.11, true, CycleMethod.REPEAT,
            new Stop(0.00, Color.rgb(255, 170, 90, 0.09)),
            new Stop(0.25, Color.rgb(120, 255, 205, 0.09)),
            new Stop(0.50, Color.rgb(110, 190, 255, 0.09)),
            new Stop(0.75, Color.rgb(215, 140, 255, 0.09)),
            new Stop(1.00, Color.rgb(255, 170, 90, 0.09)));

    private static final LinearGradient FOIL_SPECULAR = new LinearGradient(
            0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
            new Stop(0.00, Color.rgb(255, 255, 255, 0.00)),
            new Stop(0.26, Color.rgb(140, 255, 220, 0.13)),
            new Stop(0.42, Color.rgb(255, 255, 255, 0.17)),
            new Stop(0.50, Color.rgb(255, 255, 255, 0.40)),
            new Stop(0.58, Color.rgb(255, 255, 255, 0.17)),
            new Stop(0.74, Color.rgb(205, 155, 255, 0.13)),
            new Stop(1.00, Color.rgb(255, 255, 255, 0.00)));

    private static final double FOIL_TILT = 22;

    private final ImageView art = new ImageView();
    private final Rectangle clip = new Rectangle();
    private final Rectangle foilTint = new Rectangle();
    private final Rectangle foilShine = new Rectangle();

    private final double foilPhase = nextFoilPhase();

    private static double foilPhaseSeed;

    private static synchronized double nextFoilPhase() {
        foilPhaseSeed += 0.6180339887;
        foilPhaseSeed -= Math.floor(foilPhaseSeed);
        return foilPhaseSeed;
    }

    private boolean foilTicking;
    private final Rectangle border = new Rectangle();

    private final VBox fallback = new VBox(2);
    private final Label fbName = new Label();
    private final Label fbCost = new Label();
    private final Label fbType = new Label();
    private final Label fbPt = new Label();
    private final Label ptBadge = new Label();
    private final Label damageBadge = new Label();
    private final Label rankBadge = new Label();
    
    private final Label taxBadge = new Label();
    private int commanderTax;

    private final HBox counters = new HBox(3);
    private final VBox markers = new VBox(2);

    private final VBox keywords = new VBox(0);

    private String shownKeywords = "";

    private final HBox ptRow = new HBox(3, damageBadge, ptBadge);

    private final DropShadow lift = new DropShadow(18, Color.rgb(0, 0, 0, 0.65));

    private CardView card;
    private double cardWidth;
    private boolean tapped;
    
    private boolean stateShown;
    private boolean hoverEnabled = true;
    private String shownImageKey;

    private int combatDirection;

    private double baseTranslateY;
    private boolean advanced;

    private double baseViewOrder;

    public CardNode(final double width) {
        
        forge.neo.ui.Rtl.keepLtr(this);
        getStyleClass().add("card");
        setAlignment(Pos.CENTER);
        setPickOnBounds(true);

        art.setPreserveRatio(false);
        art.setSmooth(true);

        art.imageProperty().addListener((o, was, now) -> updateSideways());

        border.setFill(Color.TRANSPARENT);
        border.getStyleClass().add("card-border");

        foilTint.setFill(FOIL_TINT);
        foilTint.setBlendMode(BlendMode.SCREEN);
        foilTint.setMouseTransparent(true);
        foilTint.setVisible(false);

        foilShine.setFill(FOIL_SPECULAR);
        foilShine.setMouseTransparent(true);
        foilShine.setVisible(false);
        foilShine.setRotate(FOIL_TILT);

        fallback.getStyleClass().add("card-fallback");
        fallback.setAlignment(Pos.TOP_LEFT);
        fbName.getStyleClass().add("card-fb-name");
        fbName.setWrapText(true);
        fbCost.getStyleClass().add("card-fb-cost");
        fbType.getStyleClass().add("card-fb-type");
        fbType.setWrapText(true);
        fbPt.getStyleClass().add("card-fb-pt");
        final Region spacer = new Region();
        VBox.setVgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        fallback.getChildren().addAll(fbName, fbCost, fbType, spacer, fbPt);

        ptBadge.getStyleClass().add("card-pt");
        ptBadge.setVisible(false);

        damageBadge.getStyleClass().add("card-damage");
        damageBadge.setVisible(false);

        rankBadge.getStyleClass().add("card-draft-rank");
        rankBadge.setVisible(false);
        rankBadge.setMouseTransparent(true);
        StackPane.setAlignment(rankBadge, Pos.BOTTOM_LEFT);

        taxBadge.getStyleClass().add("card-commander-tax");
        taxBadge.setVisible(false);
        taxBadge.setMouseTransparent(true);
        StackPane.setAlignment(taxBadge, Pos.TOP_RIGHT);

        ptRow.getStyleClass().add("card-pt-row");
        ptRow.setAlignment(Pos.BOTTOM_RIGHT);
        ptRow.setMouseTransparent(true);
        StackPane.setAlignment(ptRow, Pos.BOTTOM_RIGHT);

        counters.getStyleClass().add("card-counters");
        counters.setAlignment(Pos.TOP_LEFT);
        StackPane.setAlignment(counters, Pos.TOP_LEFT);
        
        counters.setMouseTransparent(true);
        counters.setVisible(false);

        markers.getStyleClass().add("card-markers");
        markers.setAlignment(Pos.TOP_RIGHT);

        StackPane.setAlignment(markers, Pos.TOP_RIGHT);
        markers.setMouseTransparent(true);
        markers.setVisible(false);

        markers.layoutBoundsProperty().addListener((o, a, b) -> placeKeywords());

        keywords.setFillWidth(false);
        StackPane.setAlignment(keywords, Pos.TOP_LEFT);
        keywords.setMouseTransparent(true);
        keywords.setVisible(false);

        getChildren().addAll(fallback, art, foilTint, foilShine, border,
                counters, markers, keywords, ptRow, rankBadge, taxBadge);
        setClip(clip);

        setCardWidth(width);
        setCache(true);
        setCacheHint(CacheHint.QUALITY);

        sceneProperty().addListener((o, was, now) -> updateFoilMotion());
        visibleProperty().addListener((o, was, now) -> updateFoilMotion());

        setOnMouseEntered(e -> { if (hoverEnabled) { entering(e); } });
        
        setOnMouseExited(e -> { if (hoverEnabled) { leaving(e); } });
    }

    private static final double FOIL_TRAVEL = 2.2;

    private static final double FOIL_ON_CARD = 1.08;

    private static final double FOIL_FROZEN =
            Double.parseDouble(System.getProperty("neo.foil.phase", "-1"));

    private boolean foil;

    private void setFoil(final boolean on) {
        if (foilTint.isVisible() != on) {
            foilTint.setVisible(on);
        }
        this.foil = on;
        updateFoilMotion();
    }

    public static void refreshCachesIn(final javafx.scene.Node root) {
        if (root instanceof CardNode cn) {
            if (!cn.foilTicking) {
                cn.setCache(false);
                cn.setCache(true);
            }
        }
        if (root instanceof javafx.scene.Parent p) {
            for (final javafx.scene.Node child : p.getChildrenUnmodifiable()) {
                refreshCachesIn(child);
            }
        }
    }

    private void updateFoilMotion() {
        final boolean want = foil && foilEffect && foilMotion
                && getScene() != null && isVisible();
        if (want == foilTicking) {
            return;
        }
        foilTicking = want;
        if (want) {

            setCache(false);
            FoilClock.register(this);
        } else {
            FoilClock.unregister(this);
            foilShine.setVisible(false);
            setCache(true);
        }
    }

    void tickFoil(final double phase) {
        double p = FOIL_FROZEN >= 0 ? FOIL_FROZEN : phase + foilPhase;
        p -= Math.floor(p);
        final double tx = p * FOIL_TRAVEL - FOIL_TRAVEL / 2;
        if (Math.abs(tx) >= FOIL_ON_CARD) {
            if (foilShine.isVisible()) {
                foilShine.setVisible(false);
            }
            return;
        }
        foilShine.setTranslateX(tx * cardWidth);
        if (!foilShine.isVisible()) {
            foilShine.setVisible(true);
        }
    }

    private final javafx.beans.property.ReadOnlyBooleanWrapper sideways =
            new javafx.beans.property.ReadOnlyBooleanWrapper(false);

    public javafx.beans.property.ReadOnlyBooleanProperty sidewaysArtProperty() {
        return sideways.getReadOnlyProperty();
    }

    private final javafx.beans.property.ReadOnlyDoubleWrapper readRotation =
            new javafx.beans.property.ReadOnlyDoubleWrapper(0);

    public javafx.beans.property.ReadOnlyDoubleProperty readRotationProperty() {
        return readRotation.getReadOnlyProperty();
    }

    private void updateSideways() {
        final Image img = art.getImage();
        final CardStateView st = card == null ? null : shownState();
        final forge.card.CardTypeView t = st == null ? null : st.getType();
        final boolean landscapeCard = t != null && (t.isPlane() || t.isPhenomenon() || t.isBattle());
        final boolean wide = img != null && img.getWidth() > img.getHeight() * 1.15;
        final boolean on = landscapeCard && wide;
        readRotation.set(!landscapeCard || img == null ? 0 : wide ? -90 : 90);
        if (on != sideways.get()) {
            sideways.set(on);
            fitArt();
        }
    }

    private void fitArt() {
        final double w = cardWidth;
        final double h = w * ASPECT;
        if (sideways.get()) {
            art.setFitWidth(h);
            art.setFitHeight(w);
            art.setRotate(90);
        } else {
            art.setFitWidth(w);
            art.setFitHeight(h);
            art.setRotate(0);
        }
    }

    public final void setCardWidth(final double w) {
        this.cardWidth = w;
        final double h = w * ASPECT;
        final double radius = w * 0.05;

        setPrefSize(w, h);
        setMinSize(w, h);
        setMaxSize(w, h);

        fitArt();

        clip.setWidth(w);
        clip.setHeight(h);
        clip.setArcWidth(radius * 2);
        clip.setArcHeight(radius * 2);

        border.setWidth(w - 1);
        border.setHeight(h - 1);
        border.setArcWidth(radius * 2);
        border.setArcHeight(radius * 2);

        foilTint.setWidth(w);
        foilTint.setHeight(h);
        foilTint.setArcWidth(radius * 2);
        foilTint.setArcHeight(radius * 2);

        foilShine.setWidth(w * 0.34);
        foilShine.setHeight(h * 1.60);
        updateFoilMotion();

        fallback.setPrefSize(w, h);
        fallback.setMinSize(w, h);
        fallback.setMaxSize(w, h);

        final double pad = Math.max(4, w * 0.06);
        fallback.setPadding(new javafx.geometry.Insets(pad));
        final double textWidth = w - pad * 2;
        fbName.setMaxWidth(textWidth);
        fbName.setPrefWidth(textWidth);
        fbType.setMaxWidth(textWidth);
        fbType.setPrefWidth(textWidth);
        fbCost.setMaxWidth(textWidth);
        fbPt.setMaxWidth(textWidth);
        final double fs = Math.max(7, w * 0.085);
        fbName.setStyle("-fx-font-size:" + fs + "px;");
        fbCost.setStyle("-fx-font-size:" + (fs * 0.85) + "px;");
        fbType.setStyle("-fx-font-size:" + (fs * 0.78) + "px;");
        fbPt.setStyle("-fx-font-size:" + (fs * 1.05) + "px;");

        ptBadge.setStyle("-fx-font-size:" + (fs * 1.2) + "px;");
        damageBadge.setStyle("-fx-font-size:" + (fs * 1.05) + "px;");
        rankBadge.setStyle("-fx-font-size:" + (fs * 1.05) + "px;");
        taxBadge.setStyle("-fx-font-size:" + (fs * 1.15) + "px;");
        StackPane.setMargin(taxBadge, new javafx.geometry.Insets(w * 0.035, w * 0.035, 0, 0));
        counterFontPx = fs * 0.95;
        for (final javafx.scene.Node n : counters.getChildren()) {
            applyCounterFont((Label) n);
        }
        for (final javafx.scene.Node n : markers.getChildren()) {
            ((Label) n).setStyle("-fx-font-size:" + (counterFontPx * 0.92) + "px;");
            ((Label) n).setMaxWidth(Math.max(36, w * 0.68));
        }

        refreshKeywords();

        applyAdvance(false);
    }

    public boolean hasArt() {
        return art.getImage() != null;
    }

    public double getCardWidth() {
        return cardWidth;
    }

    public double getCardHeight() {
        return cardWidth * ASPECT;
    }

    public CardView getCard() {
        return card;
    }

    public void setCard(final CardView card) {
        this.card = card;
        this.shownImageKey = null;
        refresh();
    }

    public static void refreshAllIn(final javafx.scene.Node root) {
        if (root instanceof CardNode) {
            ((CardNode) root).refresh();
            return;
        }
        if (root instanceof javafx.scene.Parent) {
            for (final javafx.scene.Node child
                    : ((javafx.scene.Parent) root).getChildrenUnmodifiable()) {
                refreshAllIn(child);
            }
        }
    }

    public void setFace(final CardView card, final CardStateView face) {
        this.forcedFace = face;
        setCard(card);
    }

    private CardStateView forcedFace;

    private CardStateView shownState() {
        if (forcedFace != null) {
            return forcedFace;
        }
        final CardStateView current = card.getCurrentState();
        if (card.isFaceDown() && card.getZone() != ZoneType.Battlefield) {
            try {
                if (card.canFaceDownBeShownToAny(forge.neo.ui.CardZoom.localViewers())) {
                    final CardStateView real = card.getAlternateState();
                    if (real != null && real != current && real.getImageKey() != null) {
                        return real;
                    }
                }
            } catch (final RuntimeException e) {
                
            }
        }
        return current;
    }

    private boolean blankWhileLoading;

    public void setBlankWhileLoading(final boolean on) {
        this.blankWhileLoading = on;
    }

    private Image fullArt;

    private void sharpenArt() {
        final Image full = fullArt;
        if (full == null || shownImageKey == null || art.getImage() == null) {
            return;
        }
        final int[] px = wantedPixels();
        final Image sharp = px == null ? null : CardImages.scaled(shownImageKey, full, px[0], px[1]);
        Image want = sharp != null ? sharp : full;

        final Image current = art.getImage();
        if (sharp == null && px != null && current != null && current != full
                && current.getWidth() >= px[0] * 0.85 && current.getWidth() <= px[0] * 1.15) {
            want = current;
        }
        if (art.getImage() != want) {
            art.setImage(want);
        }
    }

    private int[] wantedPixels() {
        final javafx.scene.Scene sc = getScene();
        if (sc == null || sc.getWindow() == null) {
            return null;
        }
        final double fw = art.getFitWidth();
        final double fh = art.getFitHeight();
        if (fw <= 0 || fh <= 0) {
            return null;
        }
        final javafx.scene.transform.Transform t = getLocalToSceneTransform();
        double s = Math.hypot(t.getMxx(), t.getMyx());
        final double own = Math.hypot(getScaleX(), 0);
        if (own > 1e-6) {
            s /= own;
        }
        s *= sc.getWindow().getOutputScaleX();
        int w = (int) Math.ceil(fw * s);
        w = ((w + 3) / 4) * 4;
        final int h = (int) Math.round(w * fh / fw);
        return w < 8 || h < 8 ? null : new int[] {w, h};
    }

    public void refresh() {
        if (card == null) {
            art.setImage(null);
            fallback.setVisible(true);
            
            setFoil(false);
            refreshKeywords();
            return;
        }
        final CardStateView st = shownState();

        final String key = st == null ? null : st.getImageKey();
        if (key == null) {
            if (blankWhileLoading) {
                art.setImage(null);
                shownImageKey = null;
                fullArt = null;
            }
        } else if (!key.equals(shownImageKey)) {
            final Image img = CardImages.get(key);
            if (img != null) {
                art.setImage(img);
                shownImageKey = key;
                fullArt = img;
            } else if (blankWhileLoading) {

                art.setImage(null);
                shownImageKey = null;
                fullArt = null;
            }
        } else {

            final Image now = CardImages.peek(key);
            if (now != null && now != fullArt) {
                art.setImage(now);
                fullArt = now;
            }
        }
        sharpenArt();
        updateSideways();
        final boolean hasArt = art.getImage() != null;
        art.setVisible(hasArt);

        fallback.setVisible(!hasArt);

        final boolean foil = hasArt && foilEffect
                && (FOIL_EVERYTHING
                    || (st != null && st.getFoilIndex() > 0) || card.hasPaperFoil());
        setFoil(foil);

        if (draftRank != null && hasArt) {
            rankBadge.setText(String.valueOf(draftRank));
            rankBadge.setVisible(true);
            rankBadge.pseudoClassStateChanged(RANK_GOOD, draftRank >= 66);
            rankBadge.pseudoClassStateChanged(RANK_POOR, draftRank < 33);
        } else {
            rankBadge.setVisible(false);
            rankBadge.pseudoClassStateChanged(RANK_GOOD, false);
            rankBadge.pseudoClassStateChanged(RANK_POOR, false);
        }

        if (!hasArt && st != null) {
            fbName.setText(CardText.nameOf(st));

            fbCost.setText(st.getManaCost() == null || st.getManaCost().isNoCost()
                    ? "" : st.getManaCost().toString());
            fbType.setText(CardText.typeOf(st));
            fbPt.setText(st.isCreature() ? st.getPower() + " / " + st.getToughness() : "");
            showKeywordCard(st);
        }

        final int dmg = card.getDamage();
        if (st != null && st.isCreature() && badgesVisible) {
            final int pow = st.getPower();
            final int tou = st.getToughness();
            ptBadge.setText(pow + "/" + tou);
            ptBadge.setVisible(hasArt);

            final int[] printed = printedPt(st);
            final boolean up = printed != null && (pow > printed[0] || tou > printed[1]);
            final boolean down = printed != null && (pow < printed[0] || tou < printed[1]);
            ptBadge.pseudoClassStateChanged(BUFFED, up && !down);
            ptBadge.pseudoClassStateChanged(WEAKENED, down);

            final int lethal = card.getLethalDamage();
            ptBadge.pseudoClassStateChanged(LETHAL, dmg > 0 && lethal <= 0);
        } else {
            ptBadge.setVisible(false);
            ptBadge.pseudoClassStateChanged(BUFFED, false);
            ptBadge.pseudoClassStateChanged(WEAKENED, false);
            ptBadge.pseudoClassStateChanged(LETHAL, false);
        }

        if (dmg > 0 && badgesVisible) {
            damageBadge.setText("-" + dmg);
            damageBadge.setVisible(true);
        } else {
            damageBadge.setVisible(false);
        }
        ptRow.setVisible(ptBadge.isVisible() || damageBadge.isVisible());

        refreshCounters();

        refreshMarkers(st);

        refreshKeywords();

        setTapped(card.isTapped(), stateShown);
        stateShown = true;
        setAdvanced(card.isAttacking() || card.isBlocking());
        pseudoClassStateChanged(SICK, card.isSick());
        pseudoClassStateChanged(ATTACKING, card.isAttacking());
        pseudoClassStateChanged(BLOCKING, card.isBlocking());
        pseudoClassStateChanged(COMMANDER, card.isCommander());

        pseudoClassStateChanged(PHASED, card.isPhasedOut());
    }

    private static final javafx.css.PseudoClass SICK = javafx.css.PseudoClass.getPseudoClass("sick");
    private static final javafx.css.PseudoClass ATTACKING = javafx.css.PseudoClass.getPseudoClass("attacking");
    private static final javafx.css.PseudoClass BLOCKING = javafx.css.PseudoClass.getPseudoClass("blocking");
    private static final javafx.css.PseudoClass COMMANDER = javafx.css.PseudoClass.getPseudoClass("commander");
    private static final javafx.css.PseudoClass SELECTABLE = javafx.css.PseudoClass.getPseudoClass("selectable");
    private static final javafx.css.PseudoClass HIGHLIGHTED = javafx.css.PseudoClass.getPseudoClass("highlighted");
    private static final javafx.css.PseudoClass DRAGGING = javafx.css.PseudoClass.getPseudoClass("dragging");
    private static final javafx.css.PseudoClass DROP_TARGET = javafx.css.PseudoClass.getPseudoClass("drop-target");
    private static final javafx.css.PseudoClass ACTIONABLE =
            javafx.css.PseudoClass.getPseudoClass("actionable");
    private static final javafx.css.PseudoClass AUTO_TAP =
            javafx.css.PseudoClass.getPseudoClass("auto-tap");
    private static final javafx.css.PseudoClass PHASED =
            javafx.css.PseudoClass.getPseudoClass("phased");

    public void setSelectable(final boolean on) {
        selectable = on;
        pseudoClassStateChanged(SELECTABLE, on);
    }

    public void setActionable(final int strength) {
        this.actionable = strength >= 1;
        pseudoClassStateChanged(ACTIONABLE, actionable);
        pseudoClassStateChanged(AUTO_TAP, strength >= 2);
    }

    public boolean isActionable() {
        return actionable;
    }

    private boolean actionable;

    public boolean isSelectable() {
        return selectable;
    }

    private boolean selectable;

    public void setHighlighted(final boolean on) {
        pseudoClassStateChanged(HIGHLIGHTED, on);
    }

    public void setStackTarget(final boolean on) {
        pseudoClassStateChanged(STACK_TARGET, on);
    }

    private static final javafx.css.PseudoClass STACK_TARGET =
            javafx.css.PseudoClass.getPseudoClass("stack-target");

    public void setDragging(final boolean on) {
        pseudoClassStateChanged(DRAGGING, on);
    }

    public void setDropTarget(final boolean on) {
        pseudoClassStateChanged(DROP_TARGET, on);
    }

    public void setCombatDirection(final int dir) {
        if (this.combatDirection != dir) {
            this.combatDirection = dir;
            applyAdvance(false);
        }
    }

    public void setAdvanced(final boolean on) {
        if (this.advanced == on) {
            return;
        }
        this.advanced = on;
        applyAdvance(true);
    }

    private static final javafx.css.PseudoClass BUFFED =
            javafx.css.PseudoClass.getPseudoClass("buffed");
    private static final javafx.css.PseudoClass WEAKENED =
            javafx.css.PseudoClass.getPseudoClass("weakened");
    private static final javafx.css.PseudoClass LETHAL =
            javafx.css.PseudoClass.getPseudoClass("lethal");

    private static final String KEYWORD_CARD = "Keyword Effects";

    private void showKeywordCard(final CardStateView st) {
        if (card == null || !card.isImmutable() || !KEYWORD_CARD.equals(st.getName())) {
            return;
        }
        fbName.setText(forge.neo.NeoText.get("card.keywordEffects"));
        final forge.game.player.PlayerView owner = card.getOwner();
        if (owner == null || owner.getKeywords() == null) {
            return;
        }
        final String keywords = forge.util.Lang.joinHomogenous(
                owner.getKeywords().getValues(), forge.game.keyword.KeywordView::title);
        if (keywords != null && !keywords.isBlank()) {
            fbType.setText(keywords);
        }
    }

    private static int[] printedPt(final CardStateView st) {
        final String key = st == null ? null : st.getImageKey();
        if (key == null || key.isEmpty()) {
            return null;
        }
        final int[] cached = PRINTED_PT.get(key);
        if (cached != null) {
            return cached.length == 0 ? null : cached;
        }
        int[] value = NO_PT;
        try {
            final forge.item.PaperCard pc = forge.util.ImageUtil.getPaperCardFromImageKey(key);
            if (pc != null && pc.getRules() != null && pc.getRules().getPower() != null) {
                value = new int[] {pc.getRules().getIntPower(), pc.getRules().getIntToughness()};
            }
        } catch (final RuntimeException ignored) {
            
        }
        PRINTED_PT.put(key, value);
        return value.length == 0 ? null : value;
    }

    public static int[] printedPowerToughness(final CardStateView st) {
        return printedPt(st);
    }

    private static final int[] NO_PT = new int[0];
    private static final java.util.Map<String, int[]> PRINTED_PT =
            new java.util.concurrent.ConcurrentHashMap<>();

    private double counterFontPx = 10;

    private static final int MAX_COUNTER_PILLS = 3;

    private void refreshCounters() {
        final Multiset<CounterType> all = card == null ? null : card.getCounters();
        final boolean inPlay = card != null && ZoneType.Battlefield == card.getZone();
        if (!inPlay || !countersVisible || all == null || all.isEmpty()) {
            counters.getChildren().clear();
            counters.setVisible(false);
            return;
        }

        final List<CounterType> types = new ArrayList<>(all.elementSet());
        types.sort(Comparator.comparingInt(CardNode::counterRank)
                .thenComparing(CounterType::getName));

        counters.getChildren().clear();

        final boolean wide = cardWidth >= 108 && namesFit(types, all);
        int shown = 0;
        for (final CounterType t : types) {
            if (shown == MAX_COUNTER_PILLS && types.size() > MAX_COUNTER_PILLS) {
                final Label more = pill("+" + (types.size() - shown), Color.rgb(28, 34, 44));
                counters.getChildren().add(more);
                break;
            }
            final Label pill = pill(counterText(t, all.count(t), wide),
                    Color.rgb(t.getRed(), t.getGreen(), t.getBlue()));
            if (t.is(CounterEnumType.LOYALTY) || t.is(CounterEnumType.DEFENSE)) {

                pill.getStyleClass().add("card-counter-loyalty");
                applyCounterFont(pill);
            }
            counters.getChildren().add(pill);
            shown++;
        }
        counters.setVisible(true);
    }

    private static final boolean KEYWORDS_ALL_TEST = Boolean.getBoolean("neo.kwbadges.all");

    private static final double KEYWORD_MIN_CARD_WIDTH = 50;

    private void refreshKeywords() {
        final boolean inPlay = card != null && ZoneType.Battlefield == card.getZone();
        final List<forge.localinstance.skin.FSkinProp> icons = inPlay && keywordBadges && badgesVisible
                && cardWidth >= KEYWORD_MIN_CARD_WIDTH && art.getImage() != null
                ? (KEYWORDS_ALL_TEST && card.getCurrentState() != null
                        && card.getCurrentState().isCreature()
                        ? KeywordIcon.all() : KeywordIcon.of(card))
                : Collections.<forge.localinstance.skin.FSkinProp>emptyList();
        final int w = (int) Math.round(cardWidth);
        final double size = w / 7;
        final StringBuilder sig = new StringBuilder();
        for (final forge.localinstance.skin.FSkinProp p : icons) {
            sig.append(p.name()).append('|');
        }
        final String signature = icons.isEmpty() ? "" : sig.append('@').append(w).toString();
        if (signature.equals(shownKeywords)) {
            return;
        }
        shownKeywords = signature;
        keywords.getChildren().clear();
        if (icons.isEmpty()) {
            keywords.setVisible(false);
            return;
        }
        for (final forge.localinstance.skin.FSkinProp p : icons) {
            final javafx.scene.image.ImageView v = KeywordIcon.view(p, size);
            if (v != null) {
                keywords.getChildren().add(v);
            }
        }
        keywords.setTranslateX(w / 2 + w / 3);
        placeKeywords();
        keywords.setVisible(!keywords.getChildren().isEmpty());
    }

    private void placeKeywords() {
        double top = Math.round(cardWidth * 0.13);
        if (markers.isVisible() && !markers.getChildren().isEmpty()) {
            final double wrap = Math.max(36, cardWidth * 0.68) + 8;
            double bottom = markers.prefHeight(wrap);

            for (final javafx.scene.Node n : markers.getChildren()) {
                bottom = Math.max(bottom, n.getBoundsInParent().getMaxY());
            }
            top = Math.max(top, Math.ceil(bottom) + 3);
        }
        keywords.setTranslateY(top);
    }

    private void refreshMarkers(final CardStateView st) {
        final List<String> raw = card == null ? null : card.getMarkerText();
        final List<String> lines = raw == null ? new ArrayList<>() : markerLines(raw);

        if (isPrepared()) {
            lines.add(0, NeoText.get("marker.prepared"));
        }

        if (card != null && card.isRingBearer()) {
            lines.add(0, NeoText.get("marker.ringBearer"));
        }

        final forge.game.player.PlayerView enchanted =
                forge.neo.match.AttachedToPlayer.enchantedPlayer(card);
        if (enchanted != null) {
            lines.add(NeoText.get("marker.enchants", enchanted.getName()));
        }
        final forge.game.player.PlayerView protector =
                forge.neo.match.AttachedToPlayer.protector(card);
        if (protector != null) {
            lines.add(NeoText.get("marker.protector", protector.getName()));
        }
        if (st != null) {
            if (st.isContraption() && card.getSprocket() > 0) {
                lines.add(NeoText.get("marker.sprocket", card.getSprocket()));
            }
            if (st.isAttraction() && st.getAttractionLights() != null
                    && !st.getAttractionLights().isEmpty()) {
                final List<Integer> lit = new ArrayList<>(st.getAttractionLights());
                Collections.sort(lit);
                final StringBuilder sb = new StringBuilder();
                for (int i = 0; i < lit.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(lit.get(i));
                }
                lines.add(NeoText.get("marker.attractionLights", sb.toString()));
            }
        }

        final String chosen = forge.neo.card.ChosenValues.badge(card);
        if (chosen != null) {
            lines.add(chosen);
        }

        if (lines.isEmpty() || !countersVisible || !badgesVisible) {
            markers.getChildren().clear();
            markers.setVisible(false);
            placeKeywords();
            return;
        }
        markers.getChildren().clear();
        for (final String line : lines) {
            final Label l = new Label(line);
            l.getStyleClass().add("card-marker");

            if (line.equals(NeoText.get("marker.prepared"))) {
                l.getStyleClass().add("card-marker-ready");
            }
            l.setStyle("-fx-font-size:" + (counterFontPx * 0.92) + "px;");

            l.setWrapText(true);
            l.setMaxWidth(Math.max(36, cardWidth * 0.68));
            l.boundsInParentProperty().addListener((o, a, b) -> placeKeywords());
            markers.getChildren().add(l);
        }
        markers.setVisible(!markers.getChildren().isEmpty());
        placeKeywords();
    }

    private boolean isPrepared() {
        return card != null && card.getPreparedSpell() != null
                && card.getZone() == forge.game.zone.ZoneType.Battlefield;
    }

    private static List<String> markerLines(final List<String> raw) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            final String s = raw.get(i);
            if (s == null || s.isBlank()) {
                continue;
            }
            if ("In Room:".equals(s)) {
                
                final String room = i + 1 < raw.size() ? raw.get(i + 1) : "";
                out.add(NeoText.get("marker.room", room));
                i++;
            } else if (s.startsWith("CL:")) {
                out.add(NeoText.get("marker.classLevel", s.substring(3)));
            } else if (s.startsWith("RL:")) {
                out.add(NeoText.get("marker.ringLevel", s.substring(3)));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private static int counterRank(final CounterType t) {
        if (t.is(CounterEnumType.LOYALTY) || t.is(CounterEnumType.DEFENSE)) {
            return 0;
        }
        if (t.is(CounterEnumType.P1P1) || t.is(CounterEnumType.M1M1)) {
            return 1;
        }
        return 2;
    }

    private static String counterText(final CounterType t, final int n, final boolean wide) {
        if (t.is(CounterEnumType.P1P1)) {
            return "+" + n;
        }
        if (t.is(CounterEnumType.M1M1)) {
            return "-" + n;
        }
        if (t.is(CounterEnumType.LOYALTY) || t.is(CounterEnumType.DEFENSE)) {
            return String.valueOf(n);
        }
        return wide ? n + " " + t.getCounterOnCardDisplayName() : String.valueOf(n);
    }

    private boolean namesFit(final List<CounterType> types, final Multiset<CounterType> all) {
        final double em = counterFontPx > 0 ? counterFontPx : Math.max(7, cardWidth * 0.085) * 0.95;
        double total = 0;
        int shown = 0;
        for (final CounterType t : types) {
            if (shown == MAX_COUNTER_PILLS && types.size() > MAX_COUNTER_PILLS) {
                total += pillWidth("+" + (types.size() - shown), em) + 3;
                break;
            }
            final double loyal = t.is(CounterEnumType.LOYALTY) || t.is(CounterEnumType.DEFENSE) ? 1.28 : 1;
            total += pillWidth(counterText(t, all.count(t), true), em * loyal) + (shown > 0 ? 3 : 0);
            shown++;
        }
        return total <= cardWidth * 0.9;
    }

    private static double pillWidth(final String text, final double em) {
        return text.length() * em * 0.6 + 10;
    }

    private Label pill(final String text, final Color bg) {
        final Label l = new Label(text);
        l.getStyleClass().add("card-counter");

        l.setStyle("-fx-background-color:" + toRgba(bg, 0.92) + ";"
                + "-fx-text-fill:" + (isLight(bg) ? "#0E1218" : "#FFFFFF") + ";");
        applyCounterFont(l);
        return l;
    }

    private void applyCounterFont(final Label l) {
        final double px = l.getStyleClass().contains("card-counter-loyalty")
                ? counterFontPx * 1.28 : counterFontPx;
        final String colours = l.getStyle();
        l.setStyle(colours.replaceAll("-fx-font-size:[^;]*;", "")
                + "-fx-font-size:" + px + "px;");
    }

    private static boolean isLight(final Color c) {
        return 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue() > 0.62;
    }

    private static String toRgba(final Color c, final double alpha) {
        return String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,%.2f)",
                (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255), alpha);
    }

    private void applyAdvance(final boolean animate) {
        baseTranslateY = advanced ? combatDirection * cardWidth * 0.20 : 0;
        if (lifted) {
            return; 
        }
        if (!animate || !animations) {
            setTranslateY(baseTranslateY);
            return;
        }
        final TranslateTransition t = new TranslateTransition(TAP_TIME, this);
        t.setToY(baseTranslateY);
        t.setInterpolator(Interpolator.EASE_OUT);
        t.play();
    }

    public void setCommanderStyle(final boolean on) {
        pseudoClassStateChanged(COMMANDER, on);
    }

    public void setTapped(final boolean tapped, final boolean animate) {
        if (this.tapped == tapped) {
            return;
        }
        this.tapped = tapped;

        requestParentLayout();
        if (!rotationEnabled) {
            
            setRotate(0);
            return;
        }
        final double target = tapped ? 90 : 0;
        if (!animate || !animations) {
            setRotate(target);
            return;
        }
        final RotateTransition rt = new RotateTransition(TAP_TIME, this);
        rt.setToAngle(target);
        rt.setInterpolator(Interpolator.EASE_OUT);
        rt.play();
    }

    public void animateTapFrom(final boolean wasTapped) {
        if (wasTapped == tapped || !rotationEnabled || !animations) {
            return;
        }
        setRotate(wasTapped ? 90 : 0);
        final RotateTransition rt = new RotateTransition(TAP_TIME, this);
        rt.setToAngle(tapped ? 90 : 0);
        rt.setInterpolator(Interpolator.EASE_OUT);
        rt.play();
    }

    public boolean isTapped() {
        return tapped;
    }

    public void toggleTap() {
        setTapped(!tapped, true);
    }

    public void setHoverEnabled(final boolean on) {
        this.hoverEnabled = on;
        if (!on && lifted) {

            hoverOut();
        }
    }

    public void setRotationEnabled(final boolean on) {
        this.rotationEnabled = on;
        if (!on) {
            setRotate(0);
        }
    }

    private boolean rotationEnabled = true;

    public void setBaseViewOrder(final double order) {
        this.baseViewOrder = order;
        if (!lifted) {
            setViewOrder(order);
        }
    }

    public void setBottomInset(final double px) {
        if (Math.abs(px - bottomInset) < 0.5) {
            return;
        }
        this.bottomInset = px;
        StackPane.setMargin(ptRow, new javafx.geometry.Insets(0, 0, px, 0));
    }

    private double bottomInset;

    public void setCountersVisible(final boolean on) {
        this.countersVisible = on;
        refreshCounters();
    }

    private boolean countersVisible = true;

    public void setAttached(final boolean on) {
        pseudoClassStateChanged(ATTACHED, on);
    }

    private static final javafx.css.PseudoClass ATTACHED =
            javafx.css.PseudoClass.getPseudoClass("attached");

    public void setBadgesVisible(final boolean on) {
        badgesVisible = on;
        refresh();
    }

    private boolean badgesVisible = true;

    public void setCommanderTax(final int tax) {
        final int t = Math.max(0, tax);
        if (t == commanderTax) {
            return;
        }
        commanderTax = t;
        taxBadge.setText("+" + t);
        taxBadge.setVisible(t > 0);
    }

    public void setDraftRank(final Integer rank) {
        this.draftRank = rank == null ? null : Math.max(0, Math.min(99, rank));
        refresh();
    }

    private Integer draftRank;

    private static final javafx.css.PseudoClass RANK_GOOD =
            javafx.css.PseudoClass.getPseudoClass("rank-good");
    private static final javafx.css.PseudoClass RANK_POOR =
            javafx.css.PseudoClass.getPseudoClass("rank-poor");

    private void leaving(final javafx.scene.input.MouseEvent e) {
        final javafx.geometry.Bounds resting = restingBoundsInParent();
        final javafx.scene.Parent parent = getParent();
        if (resting == null || parent == null) {
            hoverOut();
            return;
        }

        final javafx.geometry.Point2D p = parent.sceneToLocal(e.getSceneX(), e.getSceneY());
        if (resting.contains(p)) {
            watchRealExit();
        } else {
            hoverOut();
        }
    }

    private javafx.geometry.Bounds restingBoundsInParent() {
        final double w = getWidth();
        final double h = getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        double bw = w;
        double bh = h;
        final double rot = getRotate();
        if (rot != 0) {
            final double a = Math.toRadians(rot);
            final double cos = Math.abs(Math.cos(a));
            final double sin = Math.abs(Math.sin(a));
            bw = w * cos + h * sin;
            bh = w * sin + h * cos;
        }
        final double cx = getLayoutX() + w / 2 + getTranslateX();
        final double cy = getLayoutY() + h / 2 + baseTranslateY;
        return new javafx.geometry.BoundingBox(cx - bw / 2, cy - bh / 2, bw, bh);
    }

    private void watchRealExit() {
        if (exitWatch != null) {
            return;
        }
        final javafx.scene.Scene sc = getScene();
        if (sc == null) {
            hoverOut();
            return;
        }
        watchedScene = sc;
        exitWatch = ev -> {
            final javafx.geometry.Bounds resting = restingBoundsInParent();
            final javafx.scene.Parent parent = getParent();

            if (parent == null || getScene() == null || resting == null || !isVisible()) {
                hoverOut();
                return;
            }
            final javafx.geometry.Point2D p =
                    parent.sceneToLocal(ev.getSceneX(), ev.getSceneY());
            if (!resting.contains(p)) {
                hoverOut();
            }
        };
        sc.addEventFilter(javafx.scene.input.MouseEvent.ANY, exitWatch);
    }

    private javafx.event.EventHandler<javafx.scene.input.MouseEvent> exitWatch;
    private javafx.scene.Scene watchedScene;

    private boolean lifted;

    private final javafx.beans.property.ReadOnlyBooleanWrapper liftedProp =
            new javafx.beans.property.ReadOnlyBooleanWrapper(false);

    public javafx.beans.property.ReadOnlyBooleanProperty liftedProperty() {
        return liftedProp.getReadOnlyProperty();
    }

    public boolean isLifted() {
        return lifted;
    }

    private void stopWatching() {
        if (exitWatch != null && watchedScene != null) {
            watchedScene.removeEventFilter(javafx.scene.input.MouseEvent.ANY, exitWatch);
        }
        exitWatch = null;
        watchedScene = null;
    }

    private static java.lang.ref.WeakReference<CardNode> lastLifted;

    private static java.lang.ref.WeakReference<CardNode> pendingHover;

    private static CardNode lifted(final java.lang.ref.WeakReference<CardNode> ref) {
        return ref == null ? null : ref.get();
    }

    private void entering(final javafx.scene.input.MouseEvent e) {
        final CardNode holder = lifted(lastLifted);
        if (holder != null && holder != this && holder.lifted
                && holder.getScene() == getScene() && holder.holdsPointer(e.getSceneX(), e.getSceneY())) {
            pendingHover = new java.lang.ref.WeakReference<>(this);
            holder.watchRealExit();
            return;
        }
        hoverIn();
    }

    private boolean holdsPointer(final double sceneX, final double sceneY) {
        final javafx.geometry.Bounds resting = restingBoundsInParent();
        final javafx.scene.Parent parent = getParent();
        return resting != null && parent != null && isVisible()
                && resting.contains(parent.sceneToLocal(sceneX, sceneY));
    }

    private void hoverIn() {

        final CardNode previous = lifted(lastLifted);
        if (previous != null && previous != this && previous.lifted) {
            previous.drop();
        }
        if (lifted(pendingHover) == this) {
            pendingHover = null;
        }
        lastLifted = new java.lang.ref.WeakReference<>(this);
        stopWatching();
        lifted = true;
        liftedProp.set(true);

        setViewOrder(Math.min(baseViewOrder, 0) - 1);
        setEffect(lift);
        final double zoom = forge.neo.NeoSettings.hoverZoom();
        final double up = hoverLift(zoom);
        if (!animations) {
            setScaleX(zoom);
            setScaleY(zoom);
            setTranslateY(baseTranslateY - up);
            return;
        }
        final ScaleTransition s = new ScaleTransition(HOVER_TIME, this);
        s.setToX(zoom);
        s.setToY(zoom);
        s.setInterpolator(Interpolator.EASE_OUT);
        s.play();
        final TranslateTransition t = new TranslateTransition(HOVER_TIME, this);
        t.setToY(baseTranslateY - up);
        t.setInterpolator(Interpolator.EASE_OUT);
        t.play();
    }

    private double hoverLift(final double zoom) {
        return hoverLiftFor(zoom, cardWidth);
    }

    public static double hoverLiftFor(final double zoom, final double width) {
        return width * (0.12 + (zoom - 1.08) * 0.7);
    }

    public static javafx.geometry.Insets hoverRoomFor(final double width) {
        final double zoom = forge.neo.NeoSettings.hoverZoom();
        final double lift = hoverLiftFor(zoom, width);
        final double growW = width * (zoom - 1) / 2;
        final double growH = width * ASPECT * (zoom - 1) / 2;
        final double pad = 4;
        return new javafx.geometry.Insets(Math.ceil(lift + growH + pad), Math.ceil(growW + pad),
                Math.ceil(Math.max(0, growH - lift) + pad), Math.ceil(growW + pad));
    }

    private void hoverOut() {
        final boolean was = lifted;
        drop();
        if (lifted(pendingHover) == this) {
            pendingHover = null;
        }

        if (was) {
            final CardNode next = lifted(pendingHover);
            pendingHover = null;
            if (next != null && next != this && next.hoverEnabled && next.isHover()
                    && next.getScene() != null) {
                next.hoverIn();
            }
        }
    }

    private void drop() {
        stopWatching();
        if (lifted(lastLifted) == this) {
            lastLifted = null;
        }
        lifted = false;
        liftedProp.set(false);
        setViewOrder(baseViewOrder);
        setEffect(null);
        if (!animations) {
            setScaleX(1);
            setScaleY(1);
            setTranslateY(baseTranslateY);
            return;
        }
        final ScaleTransition s = new ScaleTransition(HOVER_TIME, this);
        s.setToX(1);
        s.setToY(1);
        s.setInterpolator(Interpolator.EASE_OUT);
        s.play();
        final TranslateTransition t = new TranslateTransition(HOVER_TIME, this);
        t.setToY(baseTranslateY);
        t.setInterpolator(Interpolator.EASE_OUT);
        t.play();
    }
}
