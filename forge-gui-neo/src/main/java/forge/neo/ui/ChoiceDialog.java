package forge.neo.ui;

import forge.neo.NeoText;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import forge.game.card.CardView;
import forge.neo.card.CardNode;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public class ChoiceDialog<T> extends VBox implements NumberedChoice {

    private Consumer<List<T>> done;

    @Override
    public boolean acknowledge() {
        if (!readOnly || done == null) {
            return false;
        }
        accept.fire();
        return true;
    }

    @Override
    public boolean pickNumber(final int n, final boolean repeat) {
        if (readOnly || searchMode || n < 1 || n > nodesInOrder.size() || done == null) {
            return false;
        }
        final int idx = n - 1;
        if (cardSearch != null && idx < cardBoxes.size() && !cardBoxes.get(idx).isVisible()) {
            return false;
        }
        final T option = optionsInOrder.get(idx);
        if (max == 1) {
            chosen.clear();
            chosen.add(option);
            done.accept(new ArrayList<>(chosen));
            return true;
        }
        if (!repeat) {
            toggle(option, nodesInOrder.get(idx));
        }
        return true;
    }

    private final List<T> chosen = new ArrayList<>();

    private final LinkedHashMap<Region, T> selected = new LinkedHashMap<>();
    private final int min;
    private final int max;
    private final Label counter = new Label();
    private final Button accept = new Button();
    private final Button selectAll = new Button();
    private final Button auto = new Button();
    private final javafx.scene.control.CheckBox remember = new javafx.scene.control.CheckBox();

    private final List<Region> nodesInOrder = new ArrayList<>();
    private final List<T> optionsInOrder = new ArrayList<>();

    private final boolean readOnly;

    private final Label heading;
    private final FlowPane items;
    private final ScrollPane scroll;
    private final boolean anyCard;
    
    private final boolean leftText;
    private boolean sized;

    static final int SEARCH_FROM = 60;
    
    static final int SHOWN = 120;

    private final boolean searchMode;
    
    private boolean hideChosen;
    private javafx.scene.control.TextField search;
    private final Label searchNote = new Label();
    private List<T> allOptions;
    private String[] searchKeys;
    private Region[] nodeCache;
    private Function<T, String> searchDisplay;
    private double searchCardWidth;
    
    static final int CARD_SEARCH_FROM = 12;
    
    private javafx.scene.control.TextField cardSearch;
    
    private final List<javafx.scene.Node> cardBoxes = new ArrayList<>();
    
    private final List<String> cardKeys = new ArrayList<>();

    private double optionWidth = -1;

    public ChoiceDialog(final String title, final List<T> options, final int min, final int max,
                        final Function<T, String> display, final double cardWidth,
                        final Consumer<List<T>> onDone) {
        this(title, options, min, max, display, cardWidth, null, onDone);
    }

    public ChoiceDialog(final String title, final List<T> options, final int min, final int max,
                        final Function<T, String> display, final double cardWidth,
                        final List<T> preselected,
                        final Consumer<List<T>> onDone) {
        this(title, options, min, max, display, cardWidth, preselected, false, onDone);
    }

    private static volatile Consumer<forge.card.CardEdition> editionPeek;

    public static void setEditionPeek(final Consumer<forge.card.CardEdition> peek) {
        editionPeek = peek;
    }

    public ChoiceDialog(final String title, final List<T> options, final int min, final int max,
                        final Function<T, String> display, final double cardWidth,
                        final List<T> preselected, final boolean ordered,
                        final Consumer<List<T>> onDone) {

        this.readOnly = min < 0 || max <= 0;
        this.min = readOnly ? 0 : min;
        this.max = readOnly ? 0 : Math.max(min, max);
        
        this.ordered = ordered && !readOnly && this.max > 1;

        getStyleClass().add("dialog");
        setSpacing(12);
        setPadding(new Insets(18));
        setMaxWidth(Region.USE_PREF_SIZE);
        setMaxHeight(Region.USE_PREF_SIZE);

        heading = new Label(title == null ? NeoText.get("choice.title") : title);
        heading.getStyleClass().add("dialog-title");
        heading.setWrapText(true);
        heading.setMaxWidth(UiScale.px(760));

        items = new FlowPane(10, 10);
        items.setAlignment(Pos.CENTER);
        items.setPrefWrapLength(UiScale.px(760));

        boolean card0 = false;
        for (final T option : options) {
            if (option instanceof CardView || option instanceof forge.item.PaperCard) {
                card0 = true;
                break;
            }
        }
        searchMode = !card0 && !readOnly && options.size() > SEARCH_FROM;
        boolean longest = false;
        for (final T option : options) {
            try {
                final String text = display == null ? String.valueOf(option) : display.apply(option);
                if (text != null && text.length() > LEFT_FROM) {
                    longest = true;
                    break;
                }
            } catch (final RuntimeException ignored) {
                
            }
        }
        leftText = longest;
        for (final T option : searchMode ? List.<T>of() : options) {
            final Region node = optionNode(option, display, cardWidth, items);
            if (readOnly) {
                node.setOnMouseClicked(null);

                if (!(node instanceof CardNode)) {
                    node.setDisable(true);
                }
                node.setOpacity(0.95);
            }
            Region box = this.ordered ? withBadge(node) : node;
            final Consumer<forge.card.CardEdition> peek = editionPeek;
            if (peek != null && option instanceof forge.card.CardEdition edition) {
                final Button look = new Button(NeoText.get("shop.preview"));
                look.getStyleClass().add("choice-peek");
                look.setOnAction(e -> peek.accept(edition));
                final javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(6, box, look);
                row.setAlignment(Pos.CENTER);
                box = row;
            }
            items.getChildren().add(box);
            nodesInOrder.add(node);
            optionsInOrder.add(option);
            
            if (!readOnly && node instanceof Button nb && nodesInOrder.size() <= 9 && options.size() > 1) {
                nb.setGraphic(NumberedChoice.hint(nodesInOrder.size()));
            }
            if (card0) {
                cardBoxes.add(box);
                cardKeys.add(cardKey(option));
            }
        }

        boolean card = false;
        for (final T option : options) {
            if (option instanceof CardView || option instanceof forge.item.PaperCard) {
                card = true;
                break;
            }
        }
        anyCard = card;

        final Insets badgeRoom = this.ordered ? new Insets(8, 4, 4, 8) : Insets.EMPTY;

        final Insets hoverRoom = anyCard ? CardNode.hoverRoomFor(cardWidth) : Insets.EMPTY;
        items.setPadding(new Insets(
                Math.max(badgeRoom.getTop(), hoverRoom.getTop()),
                Math.max(badgeRoom.getRight(), hoverRoom.getRight()),
                Math.max(badgeRoom.getBottom(), hoverRoom.getBottom()),
                Math.max(badgeRoom.getLeft(), hoverRoom.getLeft())));
        scroll = new ScrollPane(items);
        scroll.getStyleClass().add("dialog-scroll");
        scroll.setFitToWidth(true);

        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        scroll.setPrefViewportHeight(anyCard
                ? cardWidth * CardNode.ASPECT + 40 + items.getPadding().getTop() + items.getPadding().getBottom()
                : 200);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        counter.getStyleClass().add("dialog-counter");
        counter.setVisible(!readOnly);

        accept.getStyleClass().add("btn-primary");
        done = onDone;
        accept.setOnAction(e -> {
            chosen.clear();
            chosen.addAll(selected.values());
            onDone.accept(new ArrayList<>(chosen));
        });

        final boolean autoFits = this.ordered && this.min == options.size();
        auto.setId("choice-auto");
        auto.getStyleClass().add("btn-secondary");
        auto.setText(NeoText.get("choice.auto"));
        auto.setVisible(autoFits);
        auto.setManaged(autoFits);

        auto.setOnAction(e -> {
            chosen.clear();
            chosen.addAll(selected.values());
            if (searchMode) {

                final java.util.Set<T> taken = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                taken.addAll(chosen);
                for (final T o : allOptions) {
                    if (!taken.contains(o)) {
                        chosen.add(o);
                    }
                }
            } else {
                for (int i = 0; i < nodesInOrder.size(); i++) {
                    if (!selected.containsKey(nodesInOrder.get(i))) {
                        chosen.add(optionsInOrder.get(i));
                    }
                }
            }
            onDone.accept(new ArrayList<>(chosen));
        });

        final boolean worthIt = !readOnly && max > 1 && options.size() > 3 && !autoFits;
        selectAll.getStyleClass().add("btn-secondary");
        selectAll.setText(NeoText.get("choice.selectAllInOrder"));
        selectAll.setVisible(worthIt);
        selectAll.setManaged(worthIt);
        selectAll.setOnAction(e -> selectAllInOrder());

        final Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        remember.setText(NeoText.get("choice.rememberOrder"));
        remember.getStyleClass().add("dialog-counter");
        remember.setVisible(false);
        remember.setManaged(false);
        final HBox footer = new HBox(10, counter, remember, gap, selectAll, auto, accept);
        footer.setAlignment(Pos.CENTER_LEFT);

        if (searchMode) {

            selectAll.setVisible(false);
            selectAll.setManaged(false);
            allOptions = new ArrayList<>(options);
            searchDisplay = display;
            searchCardWidth = cardWidth;
            nodeCache = new Region[allOptions.size()];
            searchKeys = new String[allOptions.size()];
            for (int i = 0; i < searchKeys.length; i++) {
                final T o = allOptions.get(i);
                searchKeys[i] = fold(display == null ? String.valueOf(o) : display.apply(o));
            }
            search = new javafx.scene.control.TextField();
            search.setId("choice-search");
            search.getStyleClass().add("text-input");
            search.setPromptText(NeoText.get("choice.search.prompt"));
            search.textProperty().addListener((obs, was, now) -> refilter());

            search.setOnAction(e -> {
                final Region first = items.getChildren().isEmpty() ? null
                        : (Region) items.getChildren().get(0);
                final int idx = indexOfNode(first);
                if (idx >= 0 && !selected.containsKey(first)) {
                    toggle(allOptions.get(idx), first);
                }
            });
            searchNote.getStyleClass().add("dialog-counter");
            getChildren().addAll(heading, search, searchNote, scroll, footer);
            refilter();
            sceneProperty().addListener((obs, was, now) -> {
                if (now != null) {
                    javafx.application.Platform.runLater(search::requestFocus);
                }
            });
        } else if (card0 && options.size() >= CARD_SEARCH_FROM) {

            cardSearch = new javafx.scene.control.TextField();
            cardSearch.setId("choice-card-search");
            cardSearch.getStyleClass().add("text-input");
            cardSearch.setPromptText(NeoText.get("choice.search.cards"));
            cardSearch.setMaxWidth(UiScale.px(420));
            cardSearch.textProperty().addListener((obs, was, now) -> refilterCards());
            
            cardSearch.setOnAction(e -> {
                if (readOnly) {
                    return;
                }
                for (int i = 0; i < cardBoxes.size(); i++) {
                    if (cardBoxes.get(i).isVisible() && !selected.containsKey(nodesInOrder.get(i))) {
                        toggle(optionsInOrder.get(i), nodesInOrder.get(i));
                        break;
                    }
                }
            });
            searchNote.getStyleClass().add("dialog-counter");
            searchNote.setVisible(false);
            searchNote.setManaged(false);
            getChildren().addAll(heading, cardSearch, searchNote, scroll, footer);

            final String pre = System.getProperty("neo.choice.search");
            if (pre != null) {
                final javafx.animation.PauseTransition later =
                        new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1));
                later.setOnFinished(e -> cardSearch.setText(pre));
                later.play();
            }
        } else {
            getChildren().addAll(heading, scroll, footer);
        }

        if (preselected != null && !readOnly) {
            final List<Integer> used = new ArrayList<>();
            for (final T option : preselected) {
                int idx = -1;
                for (int i = 0; i < options.size(); i++) {
                    if (!used.contains(i) && Objects.equals(options.get(i), option)) {
                        idx = i;
                        break;
                    }
                }
                if (idx >= 0) {
                    used.add(idx);
                    toggle(option, searchMode ? searchNode(idx) : nodesInOrder.get(idx));
                }
            }
        }
        updateState();
    }

    private Region optionNode(final T option, final Function<T, String> display,
                              final double cardWidth, final FlowPane items) {
        
        if (option instanceof CardView cv) {
            final CardNode node = new CardNode(cardWidth);
            node.setCard(cv);
            node.setOnMouseClicked(e -> {
                
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                    toggle(option, node);
                }
            });
            return node;
        }

        if (option instanceof forge.item.PaperCard pc) {
            final CardNode node = new CardNode(cardWidth);
            node.setRotationEnabled(false);
            node.setCard(CardView.getCardForUi(pc));
            node.setOnMouseClicked(e -> {
                
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                    toggle(option, node);
                }
            });

            return node;
        }
        final Button b = new Button(display == null ? String.valueOf(option) : display.apply(option));
        b.getStyleClass().add("choice-item");
        b.setWrapText(true);
        b.setMaxWidth(UiScale.px(340));

        b.setMinHeight(Region.USE_PREF_SIZE);

        if (leftText) {
            b.setAlignment(Pos.CENTER_LEFT);
            b.setTextAlignment(javafx.scene.text.TextAlignment.LEFT);
        }
        b.setOnAction(e -> toggle(option, b));
        return b;
    }

    private void selectAllInOrder() {
        final int room = Math.min(max, nodesInOrder.size());
        final boolean undo = selected.size() >= room;

        for (final Region node : new ArrayList<>(selected.keySet())) {
            unmark(node);
        }
        selected.clear();

        if (!undo) {
            for (int i = 0; i < nodesInOrder.size() && selected.size() < room; i++) {
                final Region node = nodesInOrder.get(i);
                selected.put(node, optionsInOrder.get(i));
                mark(node);
            }
        }
        updateState();
    }

    private void mark(final Region node) {
        if (!node.getStyleClass().contains("chosen")) {
            node.getStyleClass().add("chosen");
        }
        if (node instanceof CardNode cn) {
            cn.setSelectable(true);
        }
    }

    private void unmark(final Region node) {
        node.getStyleClass().remove("chosen");
        if (node instanceof CardNode cn) {
            cn.setSelectable(false);
        }
    }

    private void toggle(final T option, final Region node) {
        if (selected.containsKey(node)) {
            selected.remove(node);
        } else {
            
            if (max == 1) {
                selected.clear();
                for (final javafx.scene.Node n : lookupAll(".chosen")) {
                    n.getStyleClass().remove("chosen");
                    if (n instanceof CardNode cn) {
                        cn.setSelectable(false);
                    }
                }
            }
            if (selected.size() < max) {
                selected.put(node, option);
            }
        }
        final boolean on = selected.containsKey(node);
        node.getStyleClass().remove("chosen");
        if (on) {
            node.getStyleClass().add("chosen");
        }
        if (node instanceof CardNode cn) {
            cn.setSelectable(on);
        }
        updateState();
        if (searchMode && on && selected.size() < allOptions.size()
                && items.getChildren().stream().allMatch(selected::containsKey)) {
            hideChosen = true;
            refilter();
        }
    }

    @Override
    protected void layoutChildren() {
        final javafx.scene.Scene scene = getScene();
        if (!sized && scene != null && scene.getWidth() > 0 && scene.getHeight() > 0) {
            sized = true;
            
            final double wrap = Math.max(UiScale.px(560),
                    Math.min(UiScale.px(1280), scene.getWidth() * 0.78));
            items.setPrefWrapLength(wrap);
            heading.setMaxWidth(wrap);

            if (!anyCard) {
                final double each = (wrap - 30) / 2;

                for (final Region r : nodesInOrder) {
                    r.setMaxWidth(each);
                    r.setPrefWidth(each);
                }
                optionWidth = each;
                if (searchMode) {
                    for (final javafx.scene.Node n : items.getChildren()) {
                        ((Region) n).setMaxWidth(each);
                        ((Region) n).setPrefWidth(each);
                    }
                }
            }

            final double above = context == null ? 0 : context.prefHeight(wrap) + getSpacing();
            final double room = Math.max(220, scene.getHeight() * 0.74 - above);
            final double needed = items.prefHeight(wrap) + 16;
            scroll.setPrefViewportHeight(Math.min(room, needed));
        }
        super.layoutChildren();
    }

    public void offerRemember(final boolean initial) {
        remember.setSelected(initial);
        remember.setVisible(true);
        remember.setManaged(true);
    }

    public boolean remember() {
        return remember.isVisible() && remember.isSelected();
    }

    private Region context;

    public void setContext(final Region content) {
        if (context != null) {
            getChildren().remove(context);
        }
        context = content;
        if (content != null) {
            getChildren().add(getChildren().indexOf(heading) + 1, content);
        }
        sized = false;
        requestLayout();
    }

    private final boolean ordered;
    
    private final java.util.Map<Region, Label> badges = new java.util.HashMap<>();

    private Region withBadge(final Region node) {
        final Label badge = new Label();
        badge.getStyleClass().add("choice-order");
        badge.setMouseTransparent(true);
        badge.setVisible(false);
        badges.put(node, badge);
        final javafx.scene.layout.StackPane box = new javafx.scene.layout.StackPane(node, badge);
        javafx.scene.layout.StackPane.setAlignment(badge, Pos.TOP_LEFT);
        javafx.scene.layout.StackPane.setMargin(badge, new Insets(-6, 0, 0, -6));

        node.hoverProperty().addListener((o, was, is) -> box.setViewOrder(is ? -1 : 0));
        return box;
    }

    private void renumber() {
        if (!ordered) {
            return;
        }
        for (final Label badge : badges.values()) {
            badge.setVisible(false);
        }
        int i = 1;
        for (final Region node : selected.keySet()) {
            final Label badge = badges.get(node);
            if (badge != null) {
                badge.setText(String.valueOf(i));
                badge.setVisible(true);
            }
            i++;
        }
    }

    private void refilter() {
        final String q = fold(search.getText() == null ? "" : search.getText().trim());
        final List<javafx.scene.Node> shown = new ArrayList<>();
        int matches = 0;

        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < searchKeys.length; i++) {
                final boolean hit = q.isEmpty() ? pass == 0
                        : pass == 0 ? searchKeys[i].startsWith(q)
                        : !searchKeys[i].startsWith(q) && searchKeys[i].contains(q);
                if (hit && hideChosen && nodeCache[i] != null && selected.containsKey(nodeCache[i])) {
                    continue;
                }
                if (hit) {
                    if (shown.size() < SHOWN) {
                        shown.add(searchNode(i));
                    }
                    matches++;
                }
            }
        }
        items.getChildren().setAll(shown);
        searchNote.setText(matches == 0 ? NeoText.get("choice.search.none")
                : matches > shown.size() ? NeoText.get("choice.search.more", shown.size(), matches)
                : "");
        searchNote.setVisible(!searchNote.getText().isEmpty());
        searchNote.setManaged(searchNote.isVisible());
        scroll.setVvalue(0);
    }

    private void refilterCards() {
        final String q = fold(cardSearch.getText() == null ? "" : cardSearch.getText().trim());

        final double h = scroll.getViewportBounds() == null ? 0 : scroll.getViewportBounds().getHeight();
        if (h > 0 && scroll.getMinViewportHeight() < h) {
            scroll.setMinViewportHeight(h);
        }
        int shown = 0;
        for (int i = 0; i < cardBoxes.size(); i++) {
            final boolean hit = q.isEmpty() || cardKeys.get(i).contains(q);
            cardBoxes.get(i).setVisible(hit);
            cardBoxes.get(i).setManaged(hit);
            if (hit) {
                shown++;
            }
        }
        searchNote.setText(shown == 0 ? NeoText.get("choice.search.none")
                : q.isEmpty() ? "" : NeoText.get("choice.search.cardsShown", shown, cardBoxes.size()));
        searchNote.setVisible(!searchNote.getText().isEmpty());
        searchNote.setManaged(searchNote.isVisible());
        scroll.setVvalue(0);
    }

    private static String cardKey(final Object option) {
        final StringBuilder sb = new StringBuilder();
        try {
            if (option instanceof CardView cv) {
                final forge.game.card.CardView.CardStateView st = cv.getCurrentState();
                if (st != null) {
                    sb.append(st.getName()).append(" | ")
                            .append(forge.neo.card.CardText.nameOf(st)).append(" | ")
                            .append(forge.neo.card.CardText.typeOf(st));
                }
            } else if (option instanceof forge.item.PaperCard pc) {
                sb.append(pc.getName()).append(" | ")
                        .append(forge.neo.card.CardText.nameOf(pc)).append(" | ")
                        .append(forge.neo.card.CardText.typeOf(pc));
            }
        } catch (final RuntimeException e) {
            sb.append(String.valueOf(option));
        }
        return fold(sb.toString());
    }

    private static final int LEFT_FROM = 60;

    private Region searchNode(final int i) {
        Region node = nodeCache[i];
        if (node == null) {
            node = optionNode(allOptions.get(i), searchDisplay, searchCardWidth, items);
            if (optionWidth > 0) {
                node.setMaxWidth(optionWidth);
                node.setPrefWidth(optionWidth);
            }
            nodeCache[i] = node;
        }
        return node;
    }

    private int indexOfNode(final Region node) {
        if (node == null) {
            return -1;
        }
        for (int i = 0; i < nodeCache.length; i++) {
            if (nodeCache[i] == node) {
                return i;
            }
        }
        return -1;
    }

    private static String fold(final String s) {
        final String n = java.text.Normalizer.normalize(s.toLowerCase(java.util.Locale.ROOT),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replace("æ", "ae");
    }

    private void updateState() {
        renumber();
        if (readOnly) {
            accept.setDisable(false);
            accept.setText(NeoText.get("banner.understood"));
            return;
        }
        final int n = selected.size();
        if (min == max) {
            counter.setText(NeoText.get("choice.of", n, min));
        } else {
            counter.setText(NeoText.get("choice.range", n, min, max));
        }
        accept.setDisable(n < min);
        accept.setText(n < min ? NeoText.get("choice.more", min - n) : NeoText.get("common.accept"));
    }
}
