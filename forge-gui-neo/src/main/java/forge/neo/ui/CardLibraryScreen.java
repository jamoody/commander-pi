package forge.neo.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import forge.card.CardEdition;
import forge.game.GameFormat;
import forge.game.card.CardView;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.neo.NeoText;
import forge.neo.card.CardNode;
import forge.neo.deck.CardLibrary;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

public class CardLibraryScreen extends StackPane {

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private static final double[] SIZES = {0.6, 0.75, 0.95, 1.2, 1.5};
    
    private static int sizeStep = 1;
    
    private static boolean sizeChosen;

    private final double baseWidth;

    private final TextField search = new TextField();
    private final Button rulesButton = new Button(NeoText.get("deck.rulesText"));
    
    private boolean rulesText = true;
    private final CardFilterBar filters = new CardFilterBar(this::refilter);
    private final ComboBox<SetChoice> set = new ComboBox<>();
    private final ComboBox<GameFormat> format = new ComboBox<>();
    private final ComboBox<CardLibrary.Sort> sort = new ComboBox<>();
    private final ComboBox<CardLibrary.Ownership> ownership = new ComboBox<>();
    private final ComboBox<String> questSave = new ComboBox<>();
    private final Button colourMode = new Button();
    private final Label summary = new Label();
    private final Label resultCount = new Label();
    private final VBox gridHolder = new VBox();

    private ListView<Integer> rows;
    
    private int cols = 1;

    private CardLibrary library;

    private final BorderPane layout = new BorderPane();
    private final Overlay overlay = new Overlay();

    private static final java.util.Map<String, PaperCard> chosenArt =
            new java.util.concurrent.ConcurrentHashMap<>();
    private List<PaperCard> results = Collections.emptyList();

    private final Button tokensButton = new Button(NeoText.get("library.tokens"));
    private boolean tokensMode;
    private List<forge.item.PaperToken> tokenResults = Collections.emptyList();
    
    private final List<javafx.scene.Node> cardOnly = new ArrayList<>();
    
    private Set<String> owned;
    private int ownedFor = -1;

    private record SetChoice(CardEdition edition) {
        @Override
        public String toString() {
            if (edition == null) {
                return NeoText.get("library.set.all");
            }
            final String year = edition.getDate() == null ? ""
                    : "  ·  " + (1900 + edition.getDate().getYear());
            return edition.getName() + "  (" + edition.getCode() + ")" + year;
        }
    }

    public CardLibraryScreen(final double cardWidth, final Runnable onBack) {
        this.baseWidth = cardWidth;
        getStyleClass().addAll("table-root", "deck-builder", "library");

        layout.setTop(header(onBack));
        layout.setCenter(body());

        final Label hint = new Label(NeoText.get("library.hint"));
        hint.getStyleClass().add("home-summary");
        hint.setMinWidth(0);
        layout.setBottom(BackBar.of(onBack, hint));
        getChildren().addAll(layout, overlay);
        rebuildGrid();

        CardZoom.install(overlay);

        summary.setText(NeoText.get("library.loading"));
        resultCount.setText(NeoText.get("library.loading"));
        if (CardLibrary.isReady()) {
            ready(CardLibrary.get());
        } else {
            
            final Thread t = new Thread(() -> {
                final CardLibrary lib = CardLibrary.get();
                Platform.runLater(() -> ready(lib));
            }, "neo-library");
            t.setDaemon(true);
            t.start();
        }
    }

    private Region header(final Runnable onBack) {
        final Label title = new Label(NeoText.get("library.title"));
        title.getStyleClass().add("builder-title");
        summary.getStyleClass().add("home-summary");

        final HBox row = new HBox(14, new VBox(2, title, summary));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("builder-header");
        row.setPadding(new Insets(16, 28, 12, 30));
        return row;
    }

    private Region body() {
        search.setPromptText(NeoText.get(rulesText ? "deck.search.rules" : "deck.search"));
        search.getStyleClass().add("text-input");
        search.setPrefColumnCount(20);
        HBox.setHgrow(search, Priority.SOMETIMES);
        search.textProperty().addListener((o, was, is) -> refilter());

        rulesButton.getStyleClass().add("segment");
        rulesButton.setMinWidth(Region.USE_PREF_SIZE);
        rulesButton.pseudoClassStateChanged(SELECTED, rulesText);
        rulesButton.setOnAction(e -> {
            rulesText = !rulesText;
            rulesButton.pseudoClassStateChanged(SELECTED, rulesText);
            search.setPromptText(NeoText.get(rulesText ? "deck.search.rules" : "deck.search"));
            refilter();
        });

        colourMode.getStyleClass().add("segment");
        colourMode.setMinWidth(Region.USE_PREF_SIZE);
        colourMode.setOnAction(e -> {
            filters.setByIdentity(!filters.isByIdentity());
            updateColourMode();
        });
        updateColourMode();

        final Button smaller = sizeButton("−", -1);
        final Button bigger = sizeButton("+", 1);
        final HBox sizes = new HBox(4, caption(NeoText.get("library.size")), smaller, bigger);
        sizes.setAlignment(Pos.CENTER_LEFT);

        final Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        tokensButton.getStyleClass().add("segment");
        tokensButton.setMinWidth(Region.USE_PREF_SIZE);
        tokensButton.setOnAction(e -> {
            tokensMode = !tokensMode;
            tokensButton.pseudoClassStateChanged(SELECTED, tokensMode);
            for (final javafx.scene.Node n : cardOnly) {
                n.setDisable(tokensMode);
            }
            refilter();
        });
        final HBox row1 = new HBox(10, search, rulesButton, filters.bar(), colourMode, tokensButton, gap, sizes);
        row1.setAlignment(Pos.CENTER_LEFT);

        set.setVisibleRowCount(18);
        set.setPrefWidth(UiScale.px(300));
        set.setOnAction(e -> refilter());
        final Button latest = new Button(NeoText.get("library.latest"));
        latest.getStyleClass().add("segment");
        latest.setMinWidth(Region.USE_PREF_SIZE);

        latest.setOnAction(e -> showLatest());

        format.getItems().add(null);
        for (final GameFormat f : FModel.getFormats().getSanctionedList()) {
            format.getItems().add(f);
        }
        for (final GameFormat f : FModel.getFormats().getCasualList()) {
            format.getItems().add(f);
        }
        format.setConverter(new StringConverter<>() {
            @Override
            public String toString(final GameFormat f) {
                return f == null ? NeoText.get("library.format.any") : f.getName();
            }

            @Override
            public GameFormat fromString(final String s) {
                return null;
            }
        });
        format.setButtonCell(formatCell());
        format.setCellFactory(l -> formatCell());
        format.getSelectionModel().select(0);
        format.setOnAction(e -> refilter());

        sort.getItems().addAll(CardLibrary.Sort.values());
        sort.setConverter(new StringConverter<>() {
            @Override
            public String toString(final CardLibrary.Sort s) {
                if (s == null) {
                    return "";
                }
                switch (s) {
                    case COST: return NeoText.get("sort.cost");
                    case NEWEST: return NeoText.get("sort.newest");
                    case ADDED: return NeoText.get("sort.added");
                    case TYPE: return NeoText.get("sort.type");
                    default: return NeoText.get("sort.name");
                }
            }

            @Override
            public CardLibrary.Sort fromString(final String s) {
                return null;
            }
        });
        sort.getSelectionModel().select(CardLibrary.Sort.NAME);
        sort.setOnAction(e -> refilter());

        final HBox row2 = new HBox(10,
                caption(NeoText.get("library.set")), set, latest,
                caption(NeoText.get("library.format")), format);
        row2.setAlignment(Pos.CENTER_LEFT);

        final HBox ownRow = new HBox(8);
        ownRow.setAlignment(Pos.CENTER_LEFT);

        final Region gap2 = new Region();
        HBox.setHgrow(gap2, Priority.ALWAYS);
        final HBox row2b = new HBox(10, ownRow, gap2,
                caption(NeoText.get("sort.caption")), sort, resultCount);
        row2b.setAlignment(Pos.CENTER_LEFT);
        resultCount.getStyleClass().add("caption");

        cardOnly.addAll(List.of(rulesButton, filters.bar(), filters.advanced(), colourMode, row2, ownRow, sort));
        final FlowPane rows = new FlowPane(24, 8, row2, row2b);
        rows.setAlignment(Pos.CENTER_LEFT);

        VBox.setVgrow(gridHolder, Priority.ALWAYS);

        final VBox content = new VBox(10, row1, filters.advanced(), rows, gridHolder);
        content.getStyleClass().add("builder-catalogue");
        content.setPadding(new Insets(12, 16, 10, 16));
        final StackPane frame = new StackPane(content);
        frame.setPadding(new Insets(14, 24, 16, 24));
        return frame;
    }

    private static ListCell<GameFormat> formatCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(final GameFormat f, final boolean empty) {
                super.updateItem(f, empty);
                setText(empty ? null
                        : f == null ? NeoText.get("library.format.any") : f.getName());
            }
        };
    }

    private void rebuildGrid() {
        final int firstCard = rows == null ? 0 : firstVisibleRow() * cols;
        rows = new ListView<>();
        rows.getStyleClass().add("library-rows");
        rows.setFocusTraversable(false);
        VBox.setVgrow(rows, Priority.ALWAYS);
        final double w = cardWidth();

        final double tileW = w + 8;
        final double tileH = w * 7 / 5 + 8;
        rows.setFixedCellSize(tileH + 10);
        rows.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(final Integer row, final boolean empty) {
                super.updateItem(row, empty);
                setText(null);
                if (empty || row == null) {
                    setGraphic(null);
                    return;
                }
                final HBox line = new HBox(10);
                final int total = currentTotal();
                for (int i = row * cols; i < Math.min(total, (row + 1) * cols); i++) {
                    line.getChildren().add(tokensMode ? tokenTile(i) : tile(i));
                }
                setGraphic(line);
            }
        });
        
        rows.widthProperty().addListener((o, was, is) -> {
            final int c = Math.max(1, (int) ((is.doubleValue() - 24 + 10) / (tileW + 10)));
            if (c != cols) {
                final int first = firstVisibleRow() * cols;
                cols = c;
                fillRows();
                rows.scrollTo(first / cols);
            }
        });
        if (!sizeChosen && sizeStep > 0) {
            rows.heightProperty().addListener(new javafx.beans.value.ChangeListener<>() {
                @Override
                public void changed(final javafx.beans.value.ObservableValue<? extends Number> o,
                                    final Number was, final Number is) {
                    if (is.doubleValue() <= 0) {
                        return;
                    }
                    o.removeListener(this);
                    if (!sizeChosen && sizeStep > 0 && is.doubleValue() < 2 * tileH + 20) {
                        sizeStep--;
                        Platform.runLater(CardLibraryScreen.this::rebuildGrid);
                    }
                }
            });
        }
        gridHolder.getChildren().setAll(rows);
        fillRows();
        rows.scrollTo(firstCard / Math.max(1, cols));
    }

    private int currentTotal() {
        return tokensMode ? tokenResults.size() : results.size();
    }

    private void fillRows() {
        final int n = (currentTotal() + cols - 1) / cols;
        final List<Integer> idx = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            idx.add(i);
        }
        rows.getItems().setAll(idx);
    }

    private int firstVisibleRow() {
        if (rows == null) {
            return 0;
        }
        final javafx.scene.control.skin.VirtualFlow<?> flow =
                (javafx.scene.control.skin.VirtualFlow<?>) rows.lookup(".virtual-flow");
        if (flow == null || flow.getFirstVisibleCell() == null) {
            return 0;
        }
        return Math.max(0, flow.getFirstVisibleCell().getIndex());
    }

    private void restart() {
        fillRows();
        rows.scrollTo(0);
    }

    private double cardWidth() {
        return baseWidth * SIZES[sizeStep];
    }

    private Button sizeButton(final String text, final int delta) {
        final Button b = new Button(text);
        b.getStyleClass().add("segment");
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setOnAction(e -> {
            final int next = Math.max(0, Math.min(SIZES.length - 1, sizeStep + delta));
            sizeChosen = true;
            if (next != sizeStep) {
                sizeStep = next;
                rebuildGrid();
            }
        });
        return b;
    }

    private void updateColourMode() {
        colourMode.setText(NeoText.get(filters.isByIdentity()
                ? "library.colour.identity" : "library.colour.card"));
    }

    private void ready(final CardLibrary lib) {
        library = lib;
        set.getItems().add(new SetChoice(null));
        for (final CardEdition ed : lib.editions()) {
            set.getItems().add(new SetChoice(ed));
        }
        set.getSelectionModel().select(0);
        summary.setText(NeoText.get("library.summary", lib.size(), lib.editions().size()));
        applyStartupFlags();
        refilter();
    }

    private void showLatest() {
        selectSet(null);
        
        sort.getSelectionModel().select(CardLibrary.Sort.ADDED);
    }

    private void selectSet(final CardEdition edition) {
        for (final SetChoice c : set.getItems()) {
            if (c.edition() == edition) {
                set.getSelectionModel().select(c);
                return;
            }
        }
    }

    private void applyStartupFlags() {
        final String q = System.getProperty("neo.library.query");
        if (q != null) {
            search.setText(q);
        }
        final String code = System.getProperty("neo.library.set");
        if (code != null) {
            selectSet(FModel.getMagicDb().getEditions().get(code));
        }
        if (Boolean.getBoolean("neo.library.latest")) {
            showLatest();
        }

        final String down = System.getProperty("neo.library.scroll");
        if (down != null && rows != null) {
            final double v = Double.parseDouble(down);
            Platform.runLater(() -> rows.scrollTo((int) (rows.getItems().size() * v)));
        }
        
        if (Boolean.getBoolean("neo.library.tokens")) {
            tokensButton.fire();
        }

        final String arts = System.getProperty("neo.library.arts");
        if (arts != null) {
            final PaperCard card = FModel.getMagicDb().getCommonCards().getCard(arts);
            if (card != null) {
                Platform.runLater(() -> {
                    
                    final Integer pick = Integer.getInteger("neo.library.pick");
                    final List<PaperCard> all = library.printingsOf(card);
                    if (pick != null && pick >= 0 && pick < all.size()) {
                        chosenArt.put(card.getName().toLowerCase(java.util.Locale.ROOT), all.get(pick));
                        paint();
                    } else if (Boolean.getBoolean("neo.library.menu")) {
                        showCardMenu(card);
                    } else {
                        showArts(card);
                    }
                });
            }
        }

        if (Boolean.getBoolean("neo.library.zoom")) {
            Platform.runLater(() -> Platform.runLater(() -> {
                final List<PaperCard> list = results;
                if (rows != null && list != null && !list.isEmpty()) {
                    CardZoom.show(rows, list.size(), 0,
                            i -> CardView.getCardForUi(shown(list, i)));
                }
            }));
        }
        final String f = System.getProperty("neo.library.format");
        if (f != null) {
            format.getSelectionModel().select(FModel.getFormats().getFormat(f));
        }
        final String s = System.getProperty("neo.library.sort");
        if (s != null) {
            try {
                sort.getSelectionModel().select(CardLibrary.Sort.valueOf(s));
            } catch (final IllegalArgumentException ignored) {
                
            }
        }
        final String o = System.getProperty("neo.library.own");
        if (o != null && !ownership.getItems().isEmpty()) {
            try {
                ownership.getSelectionModel().select(CardLibrary.Ownership.valueOf(o));
            } catch (final IllegalArgumentException ignored) {
                
            }
        }
    }

    private void refilter() {
        if (tokensMode) {
            tokenResults = forge.neo.card.TokenLibrary.find(search.getText());
            restart();
            resultCount.setText(NeoText.get("library.tokens.count", tokenResults.size()));
            return;
        }
        if (library == null) {
            return;
        }
        final CardLibrary.Ownership own = ownership.getValue() == null
                ? CardLibrary.Ownership.ALL : ownership.getValue();

        final CardLibrary.Query q = new CardLibrary.Query();
        q.text = search.getText();
        q.rulesText = rulesText;
        q.extra = filters.isActive() ? filters.predicate() : null;
        final SetChoice sc = set.getValue();
        q.setCode = sc == null || sc.edition() == null ? null : sc.edition().getCode();
        q.format = format.getValue();
        q.sort = sort.getValue();
        q.ownership = own;
        q.owned = owned;
        results = library.find(q);

        restart();
        resultCount.setText(results.size() == library.size()
                ? NeoText.get("count.cards", results.size())
                : NeoText.get("collection.someOf", results.size(), library.size()));
    }

    private void paint() {
        if (rows != null) {
            rows.refresh();
        }
    }

    private Region tile(final int index) {
        final CardNode node = new CardNode(cardWidth());
        node.setRotationEnabled(false);
        node.setBadgesVisible(false);
        node.setCard(CardView.getCardForUi(shown(index)));

        final StackPane box = new StackPane(node);
        box.getStyleClass().add("catalogue-tile");
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        box.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                final List<PaperCard> list = results;
                CardZoom.show(node, list.size(), index,
                        i -> CardView.getCardForUi(shown(list, i)));
            } else if (e.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                showCardMenu(shown(index));
            }
        });
        return box;
    }

    private Region tokenTile(final int index) {
        final List<forge.item.PaperToken> list = tokenResults;
        final CardNode node = new CardNode(cardWidth());
        node.setRotationEnabled(false);
        node.setBadgesVisible(false);
        node.setCard(CardView.getCardForUi(list.get(index)));
        final StackPane box = new StackPane(node);
        box.getStyleClass().add("catalogue-tile");
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        box.setOnMouseClicked(e -> CardZoom.show(node, list.size(), index,
                i -> CardView.getCardForUi(list.get(i))));
        return box;
    }

    private PaperCard shown(final int index) {
        return shown(results, index);
    }

    private PaperCard shown(final List<PaperCard> list, final int index) {
        final PaperCard base = list.get(index);
        final PaperCard art = chosenArt.get(base.getName().toLowerCase(java.util.Locale.ROOT));
        if (art == null) {
            return base;
        }
        final SetChoice filter = set.getValue();
        return filter == null || filter.edition() == null
                || filter.edition().getCode().equals(art.getEdition()) ? art : base;
    }

    private void showCardMenu(final PaperCard card) {
        if (library == null) {
            return;
        }
        final int arts = library.printingsOf(card).size();
        final String key = card.getName().toLowerCase(java.util.Locale.ROOT);
        final List<CardActionMenu.Action> actions = new ArrayList<>();
        actions.add(new CardActionMenu.Action(NeoText.get("library.arts"),
                arts > 1 ? NeoText.get("deck.arts", arts) : NeoText.get("printing.only"),
                arts > 1, () -> showArts(card)));
        if (chosenArt.containsKey(key)) {
            actions.add(new CardActionMenu.Action(NeoText.get("library.arts.reset"), () -> {
                chosenArt.remove(key);
                paint();
            }));
        }
        overlay.setOnBackgroundClick(overlay::hide);
        overlay.show(new CardActionMenu(card, actions, cardWidth(), action -> {
            overlay.hide();
            action.run.run();
        }, overlay::hide));
    }

    private void showArts(final PaperCard current) {
        final List<PaperCard> printings = library.printingsOf(current);
        final double w = Math.max(cardWidth(), UiScale.px(150));
        final double tall = Math.max(UiScale.px(420), getHeight() * 0.62);
        overlay.setOnBackgroundClick(overlay::hide);
        overlay.show(new PrintingDialog(current, printings, w, picked -> {
            overlay.hide();
            chosenArt.put(picked.getName().toLowerCase(java.util.Locale.ROOT), picked);
            paint();
        }, overlay::hide,
                NeoText.get("library.arts.title", forge.neo.card.CardText.nameOf(current)),
                NeoText.get("library.arts.hint"), tall));
    }

    private static Label caption(final String text) {
        final Label l = new Label(text);
        l.getStyleClass().add("caption");
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    public void refreshArt() {
        CardNode.refreshAllIn(this);
    }
}
