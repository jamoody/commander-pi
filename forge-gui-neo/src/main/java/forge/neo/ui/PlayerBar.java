package forge.neo.ui;

import java.util.ArrayList;
import java.util.List;

import forge.card.mana.ManaAtom;
import forge.neo.NeoText;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

public class PlayerBar extends HBox {

    private final Label name = new Label();
    private final Label life = new Label("40");
    private final Label hand = new Label();
    private final Label library = new Label();
    private final Label graveyard = new Label();
    private final Label exile = new Label();
    private final Label command = new Label();
    private final HBox manaPips = new HBox(4);

    private final HBox playerCounters = new HBox(5);

    private final HBox turnChips = new HBox(5);

    
    private final Label manaCaption = new Label(NeoText.get("bar.mana"));
    private final Circle avatar = new Circle(18);

    private forge.game.player.PlayerView player;
    private java.util.function.Consumer<forge.game.player.PlayerView> onClick;
    private final Label turnMark = new Label(NeoText.get("bar.turn"));

    private final Label allyMark = new Label(NeoText.get("bar.ally"));

    private final HBox commanderDamage = new HBox(4);

    private String detailsText = "";

    private final HBox nameRow;
    private final HBox zones;
    private final VBox info;
    private final VBox manaBox;
    private final VBox lifeBox;
    private final Region spacer;
    private boolean compact;

    private final HBox commanderThumbs = new HBox(4);
    private java.util.function.Consumer<forge.neo.card.CardNode> onThumbHover;
    
    private boolean hasThumbs;

    private boolean slim;

    private double rightReserve;

    private static final javafx.css.PseudoClass SLIM =
            javafx.css.PseudoClass.getPseudoClass("slim");

    public void setSlim(final boolean on) {
        if (slim == on) {
            return;
        }
        slim = on;
        pseudoClassStateChanged(SLIM, on);
        avatar.setRadius(on ? 13 : 18);
        applyPadding();
        requestLayout();
    }

    public void setRightReserve(final double px) {
        if (Math.abs(px - rightReserve) < 0.5) {
            return;
        }
        rightReserve = px;
        applyPadding();
    }

    private void applyPadding() {
        final double v = slim ? 2 : (compact ? 6 : 5);
        final double hz = compact ? 10 : (slim ? 12 : 18);
        setPadding(new Insets(v, hz + rightReserve, v, hz));
    }

    public PlayerBar(final boolean opponent) {
        getStyleClass().addAll("player-bar", opponent ? "player-bar-opponent" : "player-bar-self");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(18);
        setPadding(new Insets(5, 18, 5, 18));

        avatar.getStyleClass().add("avatar");

        name.getStyleClass().add("player-name");
        turnMark.getStyleClass().add("turn-mark");
        turnMark.setVisible(false);

        commanderDamage.setAlignment(Pos.CENTER_LEFT);
        commanderDamage.setVisible(false);
        commanderDamage.setManaged(false);

        allyMark.getStyleClass().add("ally-mark");
        allyMark.setVisible(false);
        allyMark.setManaged(false);

        turnChips.setAlignment(Pos.CENTER_LEFT);
        final HBox nameRow = new HBox(8, name, allyMark, turnMark, commanderDamage, turnChips);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        life.getStyleClass().add("life");
        final Label lifeCaption = new Label(NeoText.get("bar.life"));
        lifeCaption.getStyleClass().add("caption");
        final VBox lifeBox = new VBox(-4, life, lifeCaption);
        lifeBox.setAlignment(Pos.CENTER);

        handBox = zone(NeoText.get("zone.hand"), hand, forge.game.zone.ZoneType.Hand);
        final HBox zones = new HBox(14,
                handBox,
                zone(NeoText.get("zone.library"), library, forge.game.zone.ZoneType.Library),
                zone(NeoText.get("zone.graveyard"), graveyard, forge.game.zone.ZoneType.Graveyard),
                zone(NeoText.get("zone.exile"), exile, forge.game.zone.ZoneType.Exile),
                zone(NeoText.get("zone.command"), command, forge.game.zone.ZoneType.Command));
        zones.setAlignment(Pos.CENTER_LEFT);

        playerCounters.setAlignment(Pos.CENTER_LEFT);
        playerCounters.setVisible(false);
        playerCounters.setManaged(false);

        final VBox info = new VBox(4, nameRow, zones, playerCounters);
        info.setAlignment(Pos.CENTER_LEFT);
        this.nameRow = nameRow;
        this.zones = zones;
        this.info = info;

        manaPips.setAlignment(Pos.CENTER_LEFT);
        manaPips.getStyleClass().add("mana-pool");
        manaPips.setPadding(new Insets(3, 8, 3, 8));

        final Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        manaCaption.getStyleClass().add("caption");
        manaCaption.setVisible(false);
        final VBox manaBox = new VBox(-2, manaPips, manaCaption);
        manaBox.setAlignment(Pos.CENTER_LEFT);
        this.manaBox = manaBox;
        this.lifeBox = lifeBox;
        this.spacer = gap;

        
        
        

        getChildren().addAll(avatar, lifeBox, info, gap, manaBox);

        commanderThumbs.setAlignment(Pos.CENTER_RIGHT);
        commanderThumbs.getStyleClass().add("bar-commanders");
        commanderThumbs.setVisible(false);
        commanderThumbs.setManaged(false);

        setOnMouseClicked(e -> {
            if (onClick != null && player != null) {
                onClick.accept(player);
            }
        });
    }

    public void setCompact(final boolean on) {
        if (compact == on) {
            return;
        }
        compact = on;
        if (on) {
            nameRow.getChildren().addAll(spacer, commanderThumbs, lifeBox);
            info.getChildren().setAll(nameRow, zones, manaBox, playerCounters);
            zones.setSpacing(6);
            setSpacing(10);
            applyPadding();
            getChildren().setAll(avatar, info);
            HBox.setHgrow(info, Priority.ALWAYS);
        } else {
            nameRow.getChildren().removeAll(spacer, commanderThumbs, lifeBox);
            info.getChildren().setAll(nameRow, zones, playerCounters);
            zones.setSpacing(14);
            setSpacing(18);
            applyPadding();
            getChildren().setAll(avatar, lifeBox, info, spacer, manaBox);
        }
        requestLayout();
    }

    public void setOnCommanderHover(final java.util.function.Consumer<forge.neo.card.CardNode> handler) {
        this.onThumbHover = handler;
    }

    public void setCommanders(final List<forge.game.card.CardView> commandZone) {
        setCommanders(commandZone, false);
    }

    public void setCommanders(final List<forge.game.card.CardView> commandZone, final boolean onlyCommanders) {
        final List<forge.game.card.CardView> cmds = new ArrayList<>();
        if (commandZone != null && PlayerField.SHOW_OPPONENT_COMMAND) {
            for (final forge.game.card.CardView c : commandZone) {
                if (c != null && (onlyCommanders || c.isCommander())) {
                    cmds.add(c);
                }
            }
        }
        final List<forge.neo.card.CardNode> nodes = new ArrayList<>();
        for (final javafx.scene.Node n : commanderThumbs.getChildren()) {
            nodes.add((forge.neo.card.CardNode) n);
        }
        
        while (nodes.size() > cmds.size()) {
            commanderThumbs.getChildren().remove(nodes.remove(nodes.size() - 1));
        }
        final double w = UiScale.px(40);
        while (nodes.size() < cmds.size()) {
            final forge.neo.card.CardNode n = new forge.neo.card.CardNode(w);
            n.setRotationEnabled(false);
            n.setBadgesVisible(false);
            n.setCursor(javafx.scene.Cursor.HAND);
            n.hoverProperty().addListener((o, was, is) -> {
                if (is && onThumbHover != null) {
                    onThumbHover.accept(n);
                }
            });
            n.setOnMouseClicked(e -> {
                
                e.consume();
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY
                        && onZone != null && player != null) {
                    onZone.accept(player, forge.game.zone.ZoneType.Command);
                }
            });
            nodes.add(n);
            commanderThumbs.getChildren().add(n);
        }
        for (int i = 0; i < cmds.size(); i++) {
            nodes.get(i).setCard(cmds.get(i));
        }
        hasThumbs = !cmds.isEmpty();
        requestLayout();
    }

    @Override
    protected void layoutChildren() {
        boolean want = compact && hasThumbs;
        if (want) {

            final double lifeH = lifeBox.prefHeight(-1);
            final double w = Math.max(16, Math.min(UiScale.px(40),
                    lifeH / forge.neo.card.CardNode.ASPECT));
            for (final javafx.scene.Node n : commanderThumbs.getChildren()) {
                final forge.neo.card.CardNode c = (forge.neo.card.CardNode) n;
                if (Math.abs(c.getCardWidth() - w) > 0.5) {
                    c.setCardWidth(w);
                }
            }
            final double thumbsW = commanderThumbs.getChildren().size() * (w + 4) + nameRow.getSpacing();
            final double rowW = commanderThumbs.isManaged()
                    ? nameRow.prefWidth(-1) : nameRow.prefWidth(-1) + thumbsW;
            final Insets in = getInsets();
            final double need = in.getLeft() + in.getRight() + avatar.prefWidth(-1) + getSpacing() + rowW;
            want = need <= getWidth() + 0.5;
        }
        if (commanderThumbs.isManaged() != want) {
            commanderThumbs.setVisible(want);
            commanderThumbs.setManaged(want);
        }
        super.layoutChildren();
    }

    public boolean isCompact() {
        return compact;
    }

    public void setPlayer(final forge.game.player.PlayerView p) {
        this.player = p;
    }

    private int gameId = -1;
    private boolean thinking;

    public void setGameId(final int id) {
        gameId = id;
    }

    public boolean refreshThinking() {
        final forge.game.player.PlayerView p = player;
        final float f = p == null || gameId < 0 ? -1f
                : forge.util.AITimeoutTracker.getRemainingFraction(
                        forge.util.AITimeoutTracker.key(gameId, p.getId()));
        if (f < 0f) {
            if (thinking) {
                thinking = false;
                avatar.setStyle("");
                avatar.getStrokeDashArray().clear();
                avatar.getStyleClass().remove("avatar-thinking");
            }
            return false;
        }
        final double around = 2 * Math.PI * avatar.getRadius();
        if (!thinking) {
            thinking = true;
            avatar.getStyleClass().add("avatar-thinking");
        }
        avatar.setStyle("-fx-stroke: #E0A63C; -fx-stroke-width: 3;");
        avatar.getStrokeDashArray().setAll(Math.max(0.5, around * f), around);
        return true;
    }

    public void setCommanderDamage(final int worst) {
        setCommanderDamage(worst > 0
                ? List.of(new CommanderHit(null, worst)) : List.of());
    }

    public void setCommanderDamage(final List<CommanderHit> hits) {
        final List<CommanderHit> list = hits == null ? List.<CommanderHit>of() : hits;
        final boolean show = !list.isEmpty();
        commanderDamage.setVisible(show);
        commanderDamage.setManaged(show);
        commanderDamage.getChildren().clear();
        if (!show) {
            return;
        }
        final List<CommanderHit> sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> Integer.compare(b.damage(), a.damage()));
        for (final CommanderHit hit : sorted) {
            final Label pill = new Label(hit.who() == null
                    ? NeoText.get("bar.commanderDamage", hit.damage(), LETHAL_COMMANDER)
                    : NeoText.get("bar.commanderDamage.from", hit.who(), hit.damage(),
                            LETHAL_COMMANDER));
            pill.getStyleClass().add("commander-damage");

            pill.pseudoClassStateChanged(CMD_WARN,
                    hit.damage() >= LETHAL_COMMANDER / 2
                            && hit.damage() < LETHAL_COMMANDER - 6);
            pill.pseudoClassStateChanged(CMD_DANGER, hit.damage() >= LETHAL_COMMANDER - 6);
            commanderDamage.getChildren().add(pill);
        }
    }

    public record CommanderHit(String who, int damage) {
    }

    public static final int LETHAL_COMMANDER = 21;

    private static final javafx.css.PseudoClass CMD_WARN =
            javafx.css.PseudoClass.getPseudoClass("warn");
    private static final javafx.css.PseudoClass CMD_DANGER =
            javafx.css.PseudoClass.getPseudoClass("danger");

    public void setDetails(final String text) {
        this.detailsText = text == null ? "" : text;
    }

    public String getDetailsText() {
        return detailsText;
    }

    public void setAvatarImage(final javafx.scene.image.Image image) {
        if (image == null) {
            SceneFill.set(avatar, null);
            avatar.getStyleClass().remove("avatar-image");
            return;
        }

        SceneFill.set(avatar, new javafx.scene.paint.ImagePattern(image, 0, 0, 1, 1, true));
        if (!avatar.getStyleClass().contains("avatar-image")) {
            avatar.getStyleClass().add("avatar-image");
        }
    }

    public forge.game.player.PlayerView getPlayer() {
        return player;
    }

    public javafx.scene.Node getPortrait() {
        return avatar;
    }

    public void setOnPlayerClicked(final java.util.function.Consumer<forge.game.player.PlayerView> h) {
        this.onClick = h;
    }

    public void setSelectable(final boolean on) {
        pseudoClassStateChanged(SELECTABLE, on);
        setCursor(on ? javafx.scene.Cursor.HAND : javafx.scene.Cursor.DEFAULT);
    }

    private static final javafx.css.PseudoClass SELECTABLE =
            javafx.css.PseudoClass.getPseudoClass("selectable");

    private static final javafx.css.PseudoClass HIGHLIGHTED =
            javafx.css.PseudoClass.getPseudoClass("highlighted");

    public void setHighlighted(final boolean on) {
        pseudoClassStateChanged(HIGHLIGHTED, on);
    }

    public void setStackTarget(final boolean on) {
        pseudoClassStateChanged(STACK_TARGET, on);
    }

    private static final javafx.css.PseudoClass STACK_TARGET =
            javafx.css.PseudoClass.getPseudoClass("stack-target");

    private Region zone(final String caption, final Label value,
                        final forge.game.zone.ZoneType type) {
        value.getStyleClass().add("zone-count");
        final Label c = new Label(caption);
        c.getStyleClass().add("caption");
        final VBox box = new VBox(-2, value, c);
        box.getStyleClass().add("zone-button");
        box.setAlignment(Pos.CENTER);
        box.setMinWidth(UiScale.px(42));
        box.setCursor(javafx.scene.Cursor.HAND);
        box.setOnMouseClicked(e -> {

            e.consume();
            if (onZone != null && player != null) {
                onZone.accept(player, type);
            }
        });
        return box;
    }

    private static String nameOf(final forge.game.card.CardView card) {
        try {
            return card == null || card.getCurrentState() == null
                    ? null : card.getCurrentState().getName();
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static String hex(final javafx.scene.paint.Color c) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }

    public void setOnZoneClicked(
            final java.util.function.BiConsumer<forge.game.player.PlayerView,
                    forge.game.zone.ZoneType> handler) {
        this.onZone = handler;
    }

    private java.util.function.BiConsumer<forge.game.player.PlayerView,
            forge.game.zone.ZoneType> onZone;

    public void setPlayerName(final String n) {
        name.setText(n);
    }

    public void setLife(final int v) {
        final boolean changed = lastLife != Integer.MIN_VALUE && v != lastLife;
        final boolean up = v > lastLife;
        lastLife = v;
        life.setText(String.valueOf(v));
        if (!changed) {
            return;
        }
        life.getStyleClass().removeAll("life-up", "life-down");
        life.getStyleClass().add(up ? "life-up" : "life-down");
        Anim.bump(life);
        if (Boolean.getBoolean("neo.anim.debug")) {
            System.out.printf("[anim] vida %s: %d (%s)%n",
                    name.getText(), v, up ? "sube" : "baja");
        }

        if (lifeReset == null) {
            lifeReset = new javafx.animation.PauseTransition(
                    javafx.util.Duration.millis(900));
            lifeReset.setOnFinished(e ->
                    life.getStyleClass().removeAll("life-up", "life-down"));
        }
        lifeReset.playFromStart();
    }

    private int lastLife = Integer.MIN_VALUE;
    private javafx.animation.PauseTransition lifeReset;

    public void setActiveTurn(final boolean active) {
        turnMark.setVisible(active);
        pseudoClassStateChanged(ACTIVE, active);
    }

    private static final javafx.css.PseudoClass ACTIVE =
            javafx.css.PseudoClass.getPseudoClass("active");

    public void setAlly(final boolean ally) {
        allyMark.setVisible(ally);
        allyMark.setManaged(ally);
        pseudoClassStateChanged(ALLY, ally);
    }

    private static final javafx.css.PseudoClass ALLY =
            javafx.css.PseudoClass.getPseudoClass("ally");

    private Region handBox;

    private static final javafx.css.PseudoClass REVEALED =
            javafx.css.PseudoClass.getPseudoClass("revealed");
    private static final javafx.css.PseudoClass PLAYABLE =
            javafx.css.PseudoClass.getPseudoClass("playable");

    public void setHandRevealed(final int visible, final int castable) {
        if (handBox == null) {
            return;
        }
        handBox.pseudoClassStateChanged(REVEALED, visible > 0 && castable == 0);
        handBox.pseudoClassStateChanged(PLAYABLE, castable > 0);
        final String tip = castable > 0 ? NeoText.get("bar.hand.castable", castable)
                : visible > 0 ? NeoText.get("bar.hand.revealed", visible) : null;
        javafx.scene.control.Tooltip.uninstall(handBox, handTip);
        handTip = null;
        if (tip != null) {
            handTip = new javafx.scene.control.Tooltip(tip);
            javafx.scene.control.Tooltip.install(handBox, handTip);
        }
    }

    private javafx.scene.control.Tooltip handTip;

    public void setZones(final int handSize, final int librarySize, final int graveSize,
                         final int exileSize, final int commandSize) {
        hand.setText(String.valueOf(handSize));
        library.setText(String.valueOf(librarySize));
        graveyard.setText(String.valueOf(graveSize));
        exile.setText(String.valueOf(exileSize));
        command.setText(String.valueOf(commandSize));
    }

    public void setMana(final int w, final int u, final int b, final int r, final int g, final int c) {
        manaPips.getChildren().clear();

        addPip(ManaAtom.WHITE, "W", w);
        addPip(ManaAtom.BLUE, "U", u);
        addPip(ManaAtom.BLACK, "B", b);
        addPip(ManaAtom.RED, "R", r);
        addPip(ManaAtom.GREEN, "G", g);
        addPip(ManaAtom.COLORLESS, "C", c);
        manaCaption.setVisible(!manaPips.getChildren().isEmpty());
    }

    public void setCounters(final com.google.common.collect.Multiset<forge.game.card.CounterType> counters,
                            final int shards, final int poisonToLose,
                            final String controlledBy,
                            final int speed, final String speedText) {
        playerCounters.getChildren().clear();
        addSpeedPill(speed, speedText);
        if (controlledBy != null && !controlledBy.isBlank()) {

            final Label pill = new Label(NeoText.get("bar.controlledBy", controlledBy));
            pill.getStyleClass().addAll("player-counter", "player-counter-control");
            playerCounters.getChildren().add(pill);
        }
        if (counters != null) {
            final java.util.List<forge.game.card.CounterType> types =
                    new java.util.ArrayList<>(counters.elementSet());
            types.sort(java.util.Comparator.comparing(forge.game.card.CounterType::getName));
            for (final forge.game.card.CounterType type : types) {
                final int n = counters.count(type);
                if (n <= 0) {
                    continue;
                }
                final Label pill = new Label(n + " " + counterName(type));
                pill.getStyleClass().add("player-counter");
                if (type.is(forge.game.card.CounterEnumType.POISON)) {
                    pill.getStyleClass().add("player-counter-poison");
                    pill.pseudoClassStateChanged(COUNTER_LETHAL,
                            poisonToLose > 0 && n >= poisonToLose);
                } else {
                    
                    pill.setStyle("-fx-border-color: rgb(" + type.getRed() + ","
                            + type.getGreen() + "," + type.getBlue() + ");");
                }
                playerCounters.getChildren().add(pill);
            }
        }
        if (shards > 0) {
            
            final Label pill = new Label(shards + " " + NeoText.get("bar.shards"));
            pill.getStyleClass().add("player-counter");
            playerCounters.getChildren().add(pill);
        }
        final boolean any = !playerCounters.getChildren().isEmpty();
        playerCounters.setVisible(any);
        playerCounters.setManaged(any);
    }

    public void setTitles(final java.util.List<String> titles) {
        playerCounters.getChildren().removeIf(n -> n.getStyleClass().contains("player-counter-title"));
        if (titles != null) {
            int at = 0;
            for (final String t : titles) {
                final Label pill = new Label(t);
                pill.getStyleClass().addAll("player-counter", "player-counter-title");
                playerCounters.getChildren().add(at++, pill);
            }
        }
        final boolean any = !playerCounters.getChildren().isEmpty();
        playerCounters.setVisible(any);
        playerCounters.setManaged(any);
    }

    public void setDrawnThisTurn(final int drawn) {
        turnChips.getChildren().removeIf(n -> n.getStyleClass().contains("player-counter-drawn"));
        if (drawn > 1) {
            final Label pill = new Label(drawn + " " + NeoText.get("bar.drawn"));
            pill.getStyleClass().addAll("player-counter", "player-counter-drawn");
            javafx.scene.control.Tooltip.install(pill,
                    new javafx.scene.control.Tooltip(NeoText.get("bar.drawn.tip", drawn)));
            turnChips.getChildren().add(0, pill);
        }
    }

    public void setSpellsThisTurn(final forge.neo.match.SpellsThisTurn.Count count) {
        turnChips.getChildren().removeIf(n -> n.getStyleClass().contains("player-counter-spells"));
        if (count != null && count.mine() > 0) {
            final Label pill = new Label(NeoText.get(count.mine() == 1 ? "bar.spells.one" : "bar.spells",
                    count.mine()));
            pill.getStyleClass().addAll("player-counter", "player-counter-spells");
            javafx.scene.control.Tooltip.install(pill, new javafx.scene.control.Tooltip(
                    NeoText.get("bar.spells.tip", count.mine(), count.all())));
            turnChips.getChildren().add(pill);
        }
    }

    public void setAttached(final java.util.List<forge.game.card.CardView> curses,
                            final java.util.List<forge.game.card.CardView> battles) {
        playerCounters.getChildren().removeIf(n -> n.getStyleClass().contains("player-counter-attached"));
        if (curses != null) {
            for (final forge.game.card.CardView c : curses) {
                playerCounters.getChildren().add(attachedPill(c, "player-counter-curse", "bar.curse.tip"));
            }
        }
        if (battles != null) {
            for (final forge.game.card.CardView c : battles) {
                playerCounters.getChildren().add(attachedPill(c, "player-counter-battle", "bar.battle.tip"));
            }
        }
        final boolean any = !playerCounters.getChildren().isEmpty();
        playerCounters.setVisible(any);
        playerCounters.setManaged(any);
    }

    private Label attachedPill(final forge.game.card.CardView card, final String style, final String tipKey) {
        final Label pill = new Label(forge.neo.match.AttachedToPlayer.nameOf(card));
        pill.getStyleClass().addAll("player-counter", "player-counter-attached", style);
        final forge.game.player.PlayerView owner = card.getController();
        pill.setTooltip(new javafx.scene.control.Tooltip(
                NeoText.get(tipKey, owner == null ? "?" : owner.getName())));
        pill.setCursor(javafx.scene.Cursor.HAND);
        
        pill.setOnMouseClicked(e -> {
            e.consume();
            forge.neo.ui.CardZoom.show(pill, card);
        });
        return pill;
    }

    private static final javafx.css.PseudoClass COUNTER_LETHAL =
            javafx.css.PseudoClass.getPseudoClass("lethal");

    private static final javafx.css.PseudoClass SPEED_MAX =
            javafx.css.PseudoClass.getPseudoClass("max");

    private void addSpeedPill(final int speed, final String speedText) {
        if (speed == forge.neo.match.PlayerSpeed.NONE) {
            return;
        }
        final String text;
        if (speed == forge.neo.match.PlayerSpeed.MAX) {
            text = NeoText.get("bar.speedMax");
        } else if (speed == forge.neo.match.PlayerSpeed.UNREADABLE) {

            if (speedText == null || speedText.isBlank()) {
                return;
            }
            text = speedText;
        } else {
            text = NeoText.get("bar.speed", String.valueOf(speed));
        }
        final Label pill = new Label(text);
        pill.getStyleClass().addAll("player-counter", "player-counter-speed");
        pill.pseudoClassStateChanged(SPEED_MAX, speed == forge.neo.match.PlayerSpeed.MAX);
        playerCounters.getChildren().add(pill);
    }

    private static String counterName(final forge.game.card.CounterType type) {
        if (type.is(forge.game.card.CounterEnumType.POISON)) {
            return NeoText.get("counter.poison");
        }
        if (type.is(forge.game.card.CounterEnumType.ENERGY)) {
            return NeoText.get("counter.energy");
        }
        if (type.is(forge.game.card.CounterEnumType.EXPERIENCE)) {
            return NeoText.get("counter.experience");
        }
        if (type.is(forge.game.card.CounterEnumType.RAD)) {
            return NeoText.get("counter.rad");
        }
        if (type.is(forge.game.card.CounterEnumType.TICKET)) {
            return NeoText.get("counter.ticket");
        }
        return type.getCounterOnCardDisplayName();
    }

    public void setOnManaClicked(final java.util.function.Consumer<Byte> handler) {
        this.onMana = handler;
    }

    public void setManaPoolActive(final boolean on) {
        manaPips.pseudoClassStateChanged(POOL_ACTIVE, on);
    }

    private static final javafx.css.PseudoClass POOL_ACTIVE =
            javafx.css.PseudoClass.getPseudoClass("pool-active");

    private java.util.function.Consumer<Byte> onMana;

    private void addPip(final int colour, final String letter, final int amount) {
        if (amount <= 0) {
            return;
        }
        final Label pip = new Label(amount > 1 ? String.valueOf(amount) : "");
        pip.getStyleClass().addAll("mana-pip", "mana-" + letter.toLowerCase());
        UiScale.fixedCircle(pip, 20);
        pip.setAlignment(Pos.CENTER);
        final StackPane sp = new StackPane(pip);
        sp.setCursor(javafx.scene.Cursor.HAND);
        sp.setOnMouseClicked(e -> {

            e.consume();
            if (onMana != null) {
                onMana.accept((byte) colour);
            }
        });
        manaPips.getChildren().add(sp);
    }
}

