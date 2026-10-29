package forge.neo.ui;

import forge.neo.NeoSettings;
import forge.neo.NeoText;
import forge.neo.card.CardText;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import forge.card.MagicColor;
import forge.deck.Deck;
import forge.game.card.CardView;
import forge.item.PaperCard;
import forge.neo.card.CardNode;
import forge.neo.deck.DeckEditor;
import forge.neo.deck.DeckImporter;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public class DeckBuilderScreen extends StackPane {

    private static final int MAX_RESULTS = 60;

    private final DeckEditor editor;
    private final double cardWidth;
    private final Runnable onBack;

    private final TextField search = new TextField();
    private final FlowPane results = new FlowPane(10, 10);
    private final Label resultCount = new Label();
    private final Pager pager = new Pager(MAX_RESULTS, this::fillResults);

    private final FlowPane basics = new FlowPane(8, 6);
    private final Label basicsCaption = new Label(NeoText.get("deck.basics"));
    
    private final javafx.scene.control.ComboBox<forge.card.CardEdition> basicSetBox =
            new javafx.scene.control.ComboBox<>();
    private List<PaperCard> basicHits = new ArrayList<>();
    private final VBox deckList = new VBox(2);
    private final HBox commanderRow = new HBox(10);
    
    private final HBox companionRow = new HBox(10);
    private final ManaCurvePane curve = new ManaCurvePane();
    private final Label title = new Label();
    private final Label status = new Label();
    private final Label deckCount = new Label();
    private final List<Button> filterButtons = new ArrayList<>();
    private final Button filterToggle = new Button();
    private final Overlay overlay = new Overlay();

    private final ImageCooldownBadge cooldownBadge = new ImageCooldownBadge();

    private final BorderPane layout = new BorderPane();
    private Region editView;
    private DeckStackView stackView;
    private boolean visualMode;
    private Button visualButton;
    
    private final Button sleeveButton = new Button(NeoText.get("deck.sleeve"));
    
    private final Button foilAllButton = new Button(NeoText.get("deck.foilAll"));
    
    private final Button houseRulesButton = new Button();

    private final Button cleanup = new Button(NeoText.get("deck.cleanup"));
    
    private final Button removeMissing = new Button(NeoText.get("deck.removeMissing"));
    
    private final Button exportMissing = new Button(NeoText.get("deck.exportMissing"));
    
    private final Button clearSide = new Button();
    private final Button generate = new Button(NeoText.get("deck.generate"));
    private final Button importer = new Button(NeoText.get("deck.import"));

    private final Button autoBuild = new Button(NeoText.get("deck.autoBuild"));
    private final Button clearMain = new Button(NeoText.get("deck.clearMain"));

    private final List<Byte> colours = new ArrayList<>();

    private final java.util.Set<forge.card.CardRarity> rarities =
            java.util.EnumSet.noneOf(forge.card.CardRarity.class);

    private final java.util.Set<Integer> cmcs = new java.util.TreeSet<>();

    private final java.util.Set<forge.card.CardType.CoreType> types =
            java.util.EnumSet.noneOf(forge.card.CardType.CoreType.class);

    private boolean searchRules = true;
    private Button rulesButton;

    private final DeckStatsPane stats = new DeckStatsPane();
    private boolean onlyLegal = true;

    private boolean commanderMode;

    private boolean pickingSpell;

    private final Label pickingBadge = pickingBadge();

    private static Label pickingBadge() {
        final Label l = new Label(NeoText.get("deck.pickingCommander"));
        l.getStyleClass().add("picking-commander-badge");
        l.setMinWidth(Region.USE_PREF_SIZE);
        l.setVisible(false);
        l.setManaged(false);
        return l;
    }

    private final Button commanderButton = new Button(NeoText.get("deck.pickCommander"));

    private final Button spellButton = new Button(NeoText.get("deck.pickSignature"));

    private final javafx.animation.PauseTransition debounce =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(180));

    public DeckBuilderScreen(final DeckEditor editor, final double cardWidth,
                             final Runnable onBack) {
        this.editor = editor;
        this.cardWidth = Math.max(144, cardWidth * 1.3);
        this.onBack = onBack;

        this.onlyLegal = editor.onlyFitsByDefault();

        getStyleClass().addAll("table-root", "deck-builder");

        UiScale.fixedFont(layout, 13);

        editView = columns();
        layout.setTop(header());
        layout.setCenter(editView);
        layout.setBottom(footer());

        getChildren().addAll(layout, overlay, cooldownBadge);
        initBigCard();
        StackPane.setAlignment(cooldownBadge, Pos.BOTTOM_LEFT);
        StackPane.setMargin(cooldownBadge, new Insets(0, 0, 16, 26));

        debounce.setOnFinished(e -> refreshCatalogue());
        search.textProperty().addListener((o, a, b) -> debounce.playFromStart());

        refreshCatalogue();
        refreshDeck();
    }

    private Region header() {
        title.getStyleClass().add("builder-title");
        title.setMinWidth(0);
        title.setMaxWidth(Double.MAX_VALUE);

        final Button rename = new Button(NeoText.get("deck.rename"));
        rename.getStyleClass().add("btn-secondary");
        rename.setOnAction(e -> askName());

        final boolean renameable = editor.getFormat().canRename();
        rename.setVisible(renameable);
        rename.setManaged(renameable);

        final Button copy = new Button(NeoText.get("deck.copy"));
        copy.getStyleClass().add("btn-secondary");
        copy.setMinWidth(Region.USE_PREF_SIZE);
        copy.setOnAction(e -> askCopyName());
        final boolean copyable = editor.getFormat().canCopy();
        copy.setVisible(copyable);
        copy.setManaged(copyable);

        status.getStyleClass().add("home-summary");

        status.setWrapText(false);
        status.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

        final Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        final Button edit = new Button(NeoText.get("deck.build"));
        visualButton = new Button(NeoText.get("deck.visual"));
        final Button visual = visualButton;
        edit.getStyleClass().add("segment");
        visual.getStyleClass().add("segment");
        edit.pseudoClassStateChanged(SELECTED, true);
        edit.setOnAction(e -> {
            setVisualMode(false);
            edit.pseudoClassStateChanged(SELECTED, true);
            visual.pseudoClassStateChanged(SELECTED, false);
        });
        visual.setOnAction(e -> {
            setVisualMode(true);
            edit.pseudoClassStateChanged(SELECTED, false);
            visual.pseudoClassStateChanged(SELECTED, true);
        });
        final HBox views = new HBox(4, edit, visual);
        views.setAlignment(Pos.CENTER_RIGHT);

        sleeveButton.getStyleClass().add("btn-secondary");
        sleeveButton.setMinWidth(Region.USE_PREF_SIZE);
        sleeveButton.setOnAction(e -> askSleeve());
        refreshSleeveButton();

        final Label format = new Label(editor.getFormat().getLabel());
        format.getStyleClass().add("builder-format");
        final VBox identity = new VBox(4, format, title);
        identity.setMinWidth(0);
        HBox.setHgrow(identity, Priority.ALWAYS);
        foilAllButton.getStyleClass().add("btn-secondary");
        foilAllButton.setMinWidth(Region.USE_PREF_SIZE);
        foilAllButton.setOnAction(e -> foilAll());
        refreshFoilAllButton();
        houseRulesButton.getStyleClass().add("btn-secondary");
        houseRulesButton.setMinWidth(Region.USE_PREF_SIZE);
        houseRulesButton.setId("builder-house-rules");
        houseRulesButton.setOnAction(e -> {
            editor.setHouseRules(!editor.isHouseRules());
            refreshHouseRulesButton();
            refreshDeck();
            refreshCatalogue();
        });
        refreshHouseRulesButton();
        final HBox row = new HBox(14, identity, rename, copy, foilAllButton, sleeveButton, views);
        if (editor.getFormat().allowsHouseRules()) {
            row.getChildren().add(row.getChildren().indexOf(sleeveButton) + 1, houseRulesButton);
        }
        row.setAlignment(Pos.CENTER_LEFT);

        final VBox box = new VBox(4, row, status);
        box.setPadding(new Insets(18, 24, 14, 24));
        box.getStyleClass().add("builder-header");
        return box;
    }

    private Region columns() {
        final Region left = catalogue();
        final Region right = deckPanel();

        final HBox row = new HBox(16, left, right);
        row.setPadding(new Insets(0, 24, 0, 24));
        row.getStyleClass().add("builder-columns");
        left.setMinWidth(0);
        HBox.setHgrow(left, Priority.ALWAYS);

        right.prefWidthProperty().bind(widthProperty().multiply(.28).add(18));
        right.setMinWidth(UiScale.px(310));
        right.setMaxWidth(UiScale.px(430));
        return row;
    }

    private Region catalogue() {
        search.setPromptText(NeoText.get(searchRules ? "deck.search.rules" : "deck.search"));
        search.getStyleClass().add("text-input");
        HBox.setHgrow(search, Priority.ALWAYS);

        final HBox filters = new HBox(6);
        filters.setAlignment(Pos.CENTER_LEFT);
        filters.getChildren().add(colourFilter("W", MagicColor.WHITE));
        filters.getChildren().add(colourFilter("U", MagicColor.BLUE));
        filters.getChildren().add(colourFilter("B", MagicColor.BLACK));
        filters.getChildren().add(colourFilter("R", MagicColor.RED));
        filters.getChildren().add(colourFilter("G", MagicColor.GREEN));

        filters.getChildren().add(extraColourFilter("C", "mana-c", NeoText.get("deck.colorless"), true));
        filters.getChildren().add(extraColourFilter("M", "mana-m", NeoText.get("deck.multicolor"), false));

        final Button legal = new Button(NeoText.get("deck.onlyFits"));
        legal.getStyleClass().add("segment");
        filterButtons.add(legal);
        legal.pseudoClassStateChanged(SELECTED, onlyLegal);
        legal.setOnAction(e -> {
            onlyLegal = !onlyLegal;
            legal.pseudoClassStateChanged(SELECTED, onlyLegal);
            refreshCatalogue();
        });

        final Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        resultCount.getStyleClass().add("dialog-counter");

        search.setMinWidth(UiScale.px(130));
        filterToggle.setId("builder-filters");
        filterToggle.getStyleClass().add("segment");
        filterToggle.setMinWidth(Region.USE_PREF_SIZE);
        filterToggle.setOnAction(e -> {
            final boolean show = !filterRow.isVisible();
            filterRow.setVisible(show);
            filterRow.setManaged(show);
            updateFilterToggle();
        });
        final HBox bar = new HBox(8, search, filters, filterToggle);
        bar.setAlignment(Pos.CENTER_LEFT);

        final HBox rarityRow = new HBox(4);
        rarityRow.setAlignment(Pos.CENTER_LEFT);
        rarityRow.getChildren().addAll(
                rarityFilter("C", forge.card.CardRarity.Common),
                rarityFilter("I", forge.card.CardRarity.Uncommon),
                rarityFilter("R", forge.card.CardRarity.Rare),
                rarityFilter("M", forge.card.CardRarity.MythicRare));

        final HBox cmcRow = new HBox(4);
        cmcRow.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i <= 7; i++) {
            cmcRow.getChildren().add(cmcFilter(i));
        }

        final FlowPane typeRow = new FlowPane(5, 5);
        typeRow.setAlignment(Pos.CENTER_LEFT);
        typeRow.getChildren().addAll(
                typeFilter(forge.card.CardType.CoreType.Creature),
                typeFilter(forge.card.CardType.CoreType.Instant),
                typeFilter(forge.card.CardType.CoreType.Sorcery),
                typeFilter(forge.card.CardType.CoreType.Artifact),
                typeFilter(forge.card.CardType.CoreType.Enchantment),
                typeFilter(forge.card.CardType.CoreType.Planeswalker),
                typeFilter(forge.card.CardType.CoreType.Battle),
                typeFilter(forge.card.CardType.CoreType.Land));

        final Button rules = new Button(NeoText.get("deck.rulesText"));
        rules.getStyleClass().add("segment");
        filterButtons.add(rules);
        rulesButton = rules;
        rules.pseudoClassStateChanged(SELECTED, searchRules);
        rules.setMinWidth(Region.USE_PREF_SIZE);
        rules.setOnAction(e -> {
            searchRules = !searchRules;
            rules.pseudoClassStateChanged(SELECTED, searchRules);
            search.setPromptText(searchRules
                    ? NeoText.get("deck.search.rules")
                    : NeoText.get("deck.search"));
            refreshCatalogue();
        });

        hideUsedButton.setId("builder-hide-used");
        hideUsedButton.getStyleClass().add("segment");
        hideUsedButton.setMinWidth(Region.USE_PREF_SIZE);
        hideUsedButton.setTooltip(new javafx.scene.control.Tooltip(NeoText.get("deck.hideUsed.tip")));
        filterButtons.add(hideUsedButton);
        hideUsedButton.pseudoClassStateChanged(SELECTED, hideUsed);
        hideUsedButton.setOnAction(e -> {
            setHideUsed(!hideUsed);
            refreshCatalogue();
        });

        final Button clear = new Button(NeoText.get("deck.clearFilters"));
        clear.getStyleClass().add("segment");
        clear.setMinWidth(Region.USE_PREF_SIZE);
        clear.setOnAction(e -> clearFilters());

        final Region gap2 = new Region();
        HBox.setHgrow(gap2, Priority.ALWAYS);

        final HBox powerRow = new HBox(4);
        powerRow.setAlignment(Pos.CENTER_LEFT);
        final HBox toughRow = new HBox(4);
        toughRow.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i <= 7; i++) {
            powerRow.getChildren().add(statFilter(i, powers));
            toughRow.getChildren().add(statFilter(i, toughnesses));
        }

        setButton.setMaxWidth(UiScale.px(260));
        setButton.setId("builder-set");
        setButton.getStyleClass().add("segment");
        setButton.setOnAction(e -> openSetPicker());
        updateSetButton();
        loadEditions();
        formatBox.setId("builder-format");
        formatBox.getStyleClass().add("builder-sort");
        formatBox.getItems().add(null);
        forge.model.FModel.getFormats().getSanctionedList().forEach(formatBox.getItems()::add);
        forge.model.FModel.getFormats().getCasualList().forEach(formatBox.getItems()::add);
        formatBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(final forge.game.GameFormat f) {
                return f == null ? NeoText.get("library.format.any") : f.getName();
            }

            @Override
            public forge.game.GameFormat fromString(final String s) {
                return null;
            }
        });
        formatBox.getSelectionModel().select(0);
        formatBox.setOnAction(e -> refreshCatalogue());

        final FlowPane fine = new FlowPane(10, 8,
                new HBox(6, label(NeoText.get("deck.rarity")), rarityRow),
                new HBox(6, label(NeoText.get("deck.cmc")), cmcRow),
                new HBox(6, label(caps("deck.power")), powerRow),
                new HBox(6, label(caps("deck.toughness")), toughRow),
                new HBox(6, label(caps("deck.set")), setButton),
                new HBox(6, label(caps("library.format")), formatBox),
                rules, hideUsedButton, clear);
        fine.setAlignment(Pos.CENTER_LEFT);

        final VBox typeRowLabeled = new VBox(6, label(NeoText.get("deck.type")), typeRow);
        typeRowLabeled.setAlignment(Pos.CENTER_LEFT);

        filterRow = new VBox(10, typeRowLabeled, fine);
        filterRow.setId("builder-advanced");
        filterRow.getStyleClass().add("builder-advanced");
        
        final boolean openFilters = Boolean.getBoolean("neo.builder.filters");
        filterRow.setVisible(openFilters);
        filterRow.setManaged(openFilters);

        results.setAlignment(Pos.TOP_LEFT);
        results.setPadding(new Insets(16, 4, 16, 0));
        results.setHgap(10);
        results.setVgap(16);

        basics.setAlignment(Pos.CENTER_LEFT);
        basicsCaption.getStyleClass().add("caption");
        basicsCaption.setMinWidth(Region.USE_PREF_SIZE);
        showBasics(false);

        final ScrollPane scroll = new ScrollPane(results);
        scroll.getStyleClass().add("dialog-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        final Label catalogueTitle = label(editor.getFormat().catalogueLabel());
        final HBox caption = new HBox(10, catalogueTitle, pickingBadge, gap, legal);

        caption.getChildren().add(caption.getChildren().indexOf(legal), groupBox());
        caption.getChildren().add(caption.getChildren().indexOf(legal), sortBox());
        caption.getChildren().add(caption.getChildren().indexOf(legal), sortDirection);
        
        if (editor.getFormat().collectionText() != null) {
            final Button copy = new Button(NeoText.get("deck.copyCollection"));
            copy.setId("builder-copy-collection");
            copy.getStyleClass().add("segment");
            copy.setMinWidth(Region.USE_PREF_SIZE);
            copy.setOnAction(e -> copyCollection());
            caption.getChildren().add(caption.getChildren().indexOf(legal), copy);
        }

        if (editor.canShowAllCards()) {
            caption.getChildren().add(caption.getChildren().indexOf(legal), showAllToggle(catalogueTitle));
        }
        if (editor.bulkAutoSell(List.of()) != null) {
            final Button sell = new Button(NeoText.get("deck.autoSellFiltered"));
            sell.setId("builder-autosell-filtered");
            sell.getStyleClass().add("segment");
            sell.setMinWidth(Region.USE_PREF_SIZE);
            sell.setOnAction(e -> askAutoSellFiltered());
            caption.getChildren().add(caption.getChildren().indexOf(legal), sell);
        }
        caption.setAlignment(Pos.CENTER_LEFT);

        final Region pageGap = new Region();
        HBox.setHgrow(pageGap, Priority.ALWAYS);
        final HBox pageBar = new HBox(12, resultCount, pageGap, pager);
        pageBar.setAlignment(Pos.CENTER_LEFT);
        pageBar.getStyleClass().add("builder-pagebar");
        resultCount.setMinWidth(0);

        final List<forge.card.CardEdition> landSets = editor.basicLandEditions();
        basicSetBox.getStyleClass().add("builder-sort");
        basicSetBox.setId("builder-basic-set");
        basicSetBox.setMaxWidth(UiScale.px(230));
        basicSetBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(final forge.card.CardEdition ed) {
                return ed == null ? "" : ed.getName();
            }

            @Override
            public forge.card.CardEdition fromString(final String s) {
                return null;
            }
        });
        basicSetBox.getItems().setAll(landSets);
        basicSetBox.getSelectionModel().select(editor.basicLandSet());
        basicSetBox.setTooltip(new javafx.scene.control.Tooltip(NeoText.get("deck.basics.set")));
        basicSetBox.setOnAction(e -> {
            editor.setBasicLandSet(basicSetBox.getValue());
            refreshCatalogue();
        });
        final boolean chooseLandSet = landSets.size() > 1;
        basicSetBox.visibleProperty().bind(basics.visibleProperty().and(
                javafx.beans.binding.Bindings.createBooleanBinding(() -> chooseLandSet)));
        basicSetBox.managedProperty().bind(basicSetBox.visibleProperty());
        final HBox basicsRow = new HBox(12, basicsCaption, basicSetBox, basics);
        basicsRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(basics, Priority.ALWAYS);
        basicsCaption.managedProperty().bind(basics.managedProperty());
        basicsRow.visibleProperty().bind(basics.visibleProperty());
        basicsRow.managedProperty().bind(basics.managedProperty());
        final VBox box = new VBox(10, caption, bar, filterRow, scroll,
                basicsRow, pageBar);
        box.getStyleClass().add("builder-catalogue");
        box.setPadding(new Insets(14, 16, 8, 16));
        return box;
    }

    private Region filterRow;

    private static final String HIDE_USED_KEY = "deckEditorHideUsed";

    private boolean hideUsed = NeoSettings.getBool(HIDE_USED_KEY, false);
    private final Button hideUsedButton = new Button(NeoText.get("deck.hideUsed"));

    private void setHideUsed(final boolean on) {
        hideUsed = on;
        NeoSettings.setBool(HIDE_USED_KEY, on);
        hideUsedButton.pseudoClassStateChanged(SELECTED, on);
    }

    private static boolean newestFirstChosen;

    private static DeckEditor.Sort sortChosen = fromProperty(DeckEditor.Sort.class, "neo.builder.sort", null);

    private static DeckEditor.Group groupChosen =
            fromProperty(DeckEditor.Group.class, "neo.builder.group", DeckEditor.Group.NONE);

    private static <E extends Enum<E>> E fromProperty(final Class<E> type, final String key, final E fallback) {
        final String v = System.getProperty(key);
        if (v == null || v.isEmpty()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, v.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            return fallback;
        }
    }

    private javafx.scene.control.ComboBox<DeckEditor.Group> groupBox() {
        final javafx.scene.control.ComboBox<DeckEditor.Group> box = new javafx.scene.control.ComboBox<>();
        box.setId("builder-group");
        box.getStyleClass().add("builder-sort");
        box.getItems().addAll(DeckEditor.Group.values());
        box.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(final DeckEditor.Group g) {
                return g == null ? "" : NeoText.get("deck.group",
                        NeoText.get("deck.group." + g.name().toLowerCase(java.util.Locale.ROOT)));
            }

            @Override
            public DeckEditor.Group fromString(final String t) {
                return null;
            }
        });
        box.setTooltip(new javafx.scene.control.Tooltip(NeoText.get("deck.group.tip")));
        box.setValue(groupChosen);
        editor.setGroup(groupChosen);
        box.valueProperty().addListener((o, was, is) -> {
            if (is == null) {
                return;
            }
            groupChosen = is;
            editor.setGroup(is);
            refreshCatalogue();
        });
        return box;
    }

    private static boolean sortReversedChosen;

    private final Button sortDirection = new Button();

    private static void showDirection(final Button b, final DeckEditor.Sort s, final boolean reversed) {
        final boolean up = DeckEditor.ascendingByDefault(s) != reversed;
        b.setText(up ? "\u25B2" : "\u25BC");
        b.setTooltip(new javafx.scene.control.Tooltip(NeoText.get(up ? "deck.sort.up" : "deck.sort.down")));
    }

    private static Button directionButton(final String id) {
        final Button b = new Button();
        b.setId(id);
        b.getStyleClass().add("segment");
        b.setMinWidth(Region.USE_PREF_SIZE);
        return b;
    }

    private static DeckEditor.Sort deckSortChosen;
    private static boolean deckSortReversedChosen;
    private final Button deckSortDirection = directionButton("builder-deck-sort-direction");

    private javafx.scene.control.ComboBox<DeckEditor.Sort> sortBox() {
        final javafx.scene.control.ComboBox<DeckEditor.Sort> box = new javafx.scene.control.ComboBox<>();
        box.setId("builder-sort");
        box.getStyleClass().add("builder-sort");
        for (final DeckEditor.Sort s : DeckEditor.Sort.values()) {
            if ((s != DeckEditor.Sort.NEWEST || editor.tracksAcquisition())
                    && (s != DeckEditor.Sort.PRICE || editor.hasPrices())
                    && (s != DeckEditor.Sort.COUNT || editor.isLimited())) {
                box.getItems().add(s);
            }
        }
        final javafx.util.StringConverter<DeckEditor.Sort> names = new javafx.util.StringConverter<>() {
            @Override
            public String toString(final DeckEditor.Sort s) {
                return s == null ? "" : NeoText.get("deck.sort", sortName(s));
            }

            @Override
            public DeckEditor.Sort fromString(final String t) {
                return null;
            }
        };
        box.setConverter(names);
        DeckEditor.Sort start = sortChosen;
        if (start == null) {
            start = newestFirstChosen && editor.tracksAcquisition()
                    ? DeckEditor.Sort.NEWEST : DeckEditor.Sort.NAME;
        }
        if (!box.getItems().contains(start)) {
            start = DeckEditor.Sort.NAME;
        }
        box.setValue(start);
        editor.setSort(start);
        editor.setSortReversed(sortReversedChosen);
        sortDirection.setId("builder-sort-direction");
        sortDirection.getStyleClass().add("segment");
        sortDirection.setMinWidth(Region.USE_PREF_SIZE);
        showDirection(sortDirection, start, sortReversedChosen);
        sortDirection.setOnAction(e -> {
            sortReversedChosen = !sortReversedChosen;
            editor.setSortReversed(sortReversedChosen);
            showDirection(sortDirection, editor.getSort(), sortReversedChosen);
            refreshCatalogue();
        });
        if (editor.tracksAcquisition()) {
            box.setTooltip(new javafx.scene.control.Tooltip(NeoText.get(editor.getFormat().acquisitionTip())));
        }
        box.valueProperty().addListener((o, was, is) -> {
            if (is == null) {
                return;
            }
            sortChosen = is;
            newestFirstChosen = is == DeckEditor.Sort.NEWEST;
            editor.setSort(is);
            
            sortReversedChosen = false;
            editor.setSortReversed(false);
            showDirection(sortDirection, is, false);
            refreshCatalogue();
        });
        return box;
    }

    private static String sortName(final DeckEditor.Sort s) {
        switch (s) {
            case NEWEST:
                return NeoText.get("deck.sort.acquired");
            case COST:
                return NeoText.get("deck.sort.cost");
            case COLOR:
                return NeoText.get("deck.sort.color");
            case TYPE:
                return NeoText.get("deck.sort.type");
            case RARITY:
                return NeoText.get("deck.sort.rarity");
            case SET:
                return NeoText.get("deck.sort.set");
            case POWER:
                return NeoText.get("deck.sort.power");
            case TOUGHNESS:
                return NeoText.get("deck.sort.toughness");
            case PRICE:
                return NeoText.get("deck.sort.price");
            case COUNT:
                return NeoText.get("deck.sort.count");
            default:
                return NeoText.get("deck.sort.name");
        }
    }

    private Region showAllToggle(final Label title) {
        final Button mine = new Button(NeoText.get("deck.showMine"));
        final Button all = new Button(NeoText.get("deck.showAll"));
        for (final Button b : new Button[] {mine, all}) {
            b.getStyleClass().add("segment");
            b.setMinWidth(Region.USE_PREF_SIZE);
            b.setTooltip(new javafx.scene.control.Tooltip(NeoText.get("deck.showAll.tip")));
        }
        mine.setId("builder-show-mine");
        all.setId("builder-show-all");
        final Runnable paint = () -> {
            mine.pseudoClassStateChanged(SELECTED, !editor.showsAllCards());
            all.pseudoClassStateChanged(SELECTED, editor.showsAllCards());
            
            title.setText(editor.getFormat().catalogueLabel());
        };
        mine.setOnAction(e -> {
            editor.setShowAllCards(false);
            paint.run();
            refreshCatalogue();
        });
        all.setOnAction(e -> {
            editor.setShowAllCards(true);
            paint.run();
            refreshCatalogue();
        });
        
        if (Boolean.getBoolean("neo.builder.showAll")) {
            editor.setShowAllCards(true);
            
            final String add = System.getProperty("neo.builder.addCard");
            final PaperCard extra = add == null ? null : forge.model.FModel.getMagicDb().getCommonCards().getCard(add);
            if (extra != null) {
                editor.add(extra, 2);
                
                final String name = extra.getName();
                javafx.application.Platform.runLater(() -> searchFor(name));
                
                if (Boolean.getBoolean("neo.builder.exportMissing")) {
                    final javafx.animation.PauseTransition later =
                            new javafx.animation.PauseTransition(javafx.util.Duration.millis(1500));
                    later.setOnFinished(ev -> exportMissing());
                    later.play();
                }
            }
        }
        paint.run();
        final HBox box = new HBox(2, mine, all);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void copyCollection() {
        final String text = editor.getFormat().collectionText();
        overlay.show(TextDialog.block(NeoText.get("deck.copyCollection.title"),
                NeoText.get("deck.copyCollection.hint"),
                text == null ? "" : text, NeoText.get("deck.export.copy"),
                t -> {
                    final javafx.scene.input.ClipboardContent content =
                            new javafx.scene.input.ClipboardContent();
                    content.putString(t);
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                    overlay.hide();
                },
                overlay::hide));
    }

    private final java.util.Set<Integer> powers = new java.util.HashSet<>();
    private final java.util.Set<Integer> toughnesses = new java.util.HashSet<>();
    
    private final java.util.Set<forge.card.CardEdition> chosenSets = new java.util.LinkedHashSet<>();
    private final Button setButton = new Button();
    
    private List<forge.card.CardEdition> allEditions = List.of();
    private final javafx.scene.control.ComboBox<forge.game.GameFormat> formatBox =
            new javafx.scene.control.ComboBox<>();
    
    private volatile forge.neo.deck.CardLibrary library;

    private Button statFilter(final int value, final java.util.Set<Integer> into) {
        final Button b = new Button(value == 7 ? "7+" : String.valueOf(value));
        b.getStyleClass().addAll("segment", "cmc-filter");
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setOnAction(e -> {
            if (!into.remove(value)) {
                into.add(value);
            }
            b.pseudoClassStateChanged(SELECTED, into.contains(value));
            refreshCatalogue();
        });
        filterButtons.add(b);
        return b;
    }

    private void loadEditions() {
        final Thread t = new Thread(() -> {
            final forge.neo.deck.CardLibrary lib = forge.neo.deck.CardLibrary.get();
            javafx.application.Platform.runLater(() -> {
                library = lib;
                allEditions = new ArrayList<>(lib.editions());
            });
        }, "neo-builder-sets");
        t.setDaemon(true);
        t.start();
    }

    private static String setName(final forge.card.CardEdition ed) {
        final String year = ed.getDate() == null ? ""
                : " (" + new java.text.SimpleDateFormat("yyyy").format(ed.getDate()) + ")";
        return ed.getName() + year;
    }

    private void updateSetButton() {
        setButton.setText(chosenSets.isEmpty() ? NeoText.get("library.set.all")
                : chosenSets.size() == 1 ? setName(chosenSets.iterator().next())
                : NeoText.get("deck.sets.count", chosenSets.size()));
        setButton.pseudoClassStateChanged(SELECTED, !chosenSets.isEmpty());
    }

    private void openSetPicker() {
        openSetPicker("");
    }

    private void openSetPicker(final String initialQuery) {
        final Label title = new Label(NeoText.get("deck.sets.title"));
        title.getStyleClass().add("dialog-title");
        final TextField find = new TextField();
        find.setPromptText(NeoText.get("deck.sets.search"));
        find.getStyleClass().add("text-input");
        find.setId("builder-set-search");
        final VBox list = new VBox(4);
        list.setPadding(new Insets(4, 8, 4, 4));
        final Runnable fill = () -> {
            final String q = find.getText() == null ? "" : find.getText().trim().toLowerCase(java.util.Locale.ROOT);
            list.getChildren().clear();
            for (final forge.card.CardEdition ed : allEditions) {
                final String name = setName(ed);
                if (!q.isEmpty() && !name.toLowerCase(java.util.Locale.ROOT).contains(q)
                        && !ed.getCode().toLowerCase(java.util.Locale.ROOT).contains(q)) {
                    continue;
                }
                final javafx.scene.control.CheckBox box = new javafx.scene.control.CheckBox(name + "  ·  " + ed.getCode());
                box.getStyleClass().add("neo-check");
                box.setSelected(chosenSets.contains(ed));
                box.setOnAction(e -> {
                    if (box.isSelected()) {
                        chosenSets.add(ed);
                    } else {
                        chosenSets.remove(ed);
                    }
                    updateSetButton();
                    updateFilterToggle();
                    refreshCatalogue();
                });
                list.getChildren().add(box);
            }
        };
        find.setText(initialQuery);
        find.textProperty().addListener((o, a, b) -> fill.run());
        fill.run();
        final ScrollPane scroll = new ScrollPane(list);
        scroll.getStyleClass().add("dialog-scroll");
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.max(240, getHeight() * 0.55));
        final Button all = new Button(NeoText.get("library.set.all"));
        all.getStyleClass().add("btn-secondary");
        all.setOnAction(e -> {
            chosenSets.clear();
            updateSetButton();
            updateFilterToggle();
            refreshCatalogue();
            fill.run();
        });
        final Button ok = new Button(NeoText.get("common.accept"));
        ok.getStyleClass().add("btn-primary");
        ok.setOnAction(e -> overlay.hide());
        final Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        final HBox buttons = new HBox(10, all, gap, ok);
        buttons.setAlignment(Pos.CENTER_LEFT);
        final VBox dialog = new VBox(10, title, find, scroll, buttons);
        dialog.getStyleClass().add("dialog");
        dialog.setPadding(new Insets(18));
        dialog.setMaxWidth(Math.min(UiScale.px(620), Math.max(360, getWidth() * 0.8)));
        dialog.setMaxHeight(Region.USE_PREF_SIZE);
        overlay.setOnBackgroundClick(overlay::hide);
        overlay.show(dialog);
        javafx.application.Platform.runLater(find::requestFocus);
    }

    private final CardNode bigCard = new CardNode(300);
    private final javafx.animation.PauseTransition bigCardHide =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(120));

    private void initBigCard() {
        bigCard.setMouseTransparent(true);
        bigCard.setManaged(false);
        bigCard.setVisible(false);
        bigCard.setRotationEnabled(false);
        bigCard.setHoverEnabled(false);
        bigCard.setBadgesVisible(false);
        bigCard.setBlankWhileLoading(true);
        bigCard.getStyleClass().add("big-hover-card");
        bigCardHide.setOnFinished(e -> bigCard.setVisible(false));

        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            bigCardHide.stop();
            bigCard.setVisible(false);
        });

        final String picker = System.getProperty("neo.builder.setPicker");
        if (picker != null) {
            final javafx.animation.PauseTransition later =
                    new javafx.animation.PauseTransition(javafx.util.Duration.millis(2500));
            later.setOnFinished(e -> openSetPicker(picker));
            later.play();
        }
    }

    private boolean hoverTestDone;

    private void hoverCard(final Region anchor, final PaperCard card) {
        if (!hoverTestDone && Boolean.getBoolean("neo.builder.hoverFirst")) {
            hoverTestDone = true;
            final javafx.animation.PauseTransition later =
                    new javafx.animation.PauseTransition(javafx.util.Duration.millis(2500));
            later.setOnFinished(e -> showBigCard(anchor, card));
            later.play();
        }
        anchor.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_ENTERED, e -> showBigCard(anchor, card));
        anchor.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_EXITED, e -> bigCardHide.playFromStart());
    }

    private void showBigCard(final Region anchor, final PaperCard card) {
        if (!NeoSettings.getBool(NeoSettings.BUILDER_HOVER_CARD, true) || overlay.isShowing()
                || card == null || getHeight() <= 0) {
            return;
        }
        bigCardHide.stop();
        final double h = Math.min(getHeight() * 0.62, UiScale.px(720));
        final double w = h / CardNode.ASPECT;
        bigCard.setCardWidth(w);
        bigCard.setCard(CardView.getCardForUi(card));
        final javafx.geometry.Bounds b = sceneToLocal(anchor.localToScene(anchor.getBoundsInLocal()));
        final double margin = UiScale.px(24);
        final boolean onLeft = b != null && b.getCenterX() < getWidth() / 2;
        bigCard.resizeRelocate(onLeft ? getWidth() - w - margin : margin,
                Math.max(margin, (getHeight() - h) / 2), w, h);
        if (!getChildren().contains(bigCard)) {
            final int at = getChildren().indexOf(overlay);
            getChildren().add(at < 0 ? getChildren().size() : at, bigCard);
        }
        bigCard.setVisible(true);
    }

    private boolean inChosenSet(final PaperCard card, final forge.card.CardEdition ed) {
        if (ed.getCode().equalsIgnoreCase(card.getEdition())) {
            return true;
        }

        if (editor.getFormat().pool() != null && !(editor.showsAllCards() && editor.owned(card) == 0)) {
            return false;
        }
        final forge.neo.deck.CardLibrary lib = library;
        return lib != null && lib.hasPrintingIn(ed.getCode(), card);
    }

    private void askAutoSellFiltered() {
        final List<PaperCard> all = editor.find(lastQuery, onlyLegal, lastFine,
                Integer.MAX_VALUE, searchRules).cards;
        final forge.neo.deck.DeckContext.BulkSell plan = editor.bulkAutoSell(all);
        if (plan == null || plan.copies() == 0) {
            overlay.show(new ConfirmDialog(NeoText.get("deck.autoSellFiltered"),
                    NeoText.get("deck.autoSellFiltered.none"),
                    List.of(NeoText.get("banner.understood")), 0, i -> overlay.hide()));
            return;
        }
        overlay.show(new ConfirmDialog(NeoText.get("deck.autoSellFiltered.ask"),
                NeoText.get("deck.autoSellFiltered.detail", plan.copies(), plan.cards(), plan.value()),
                List.of(NeoText.get("deck.autoSellFiltered.yes"), NeoText.get("common.cancel")), 1,
                i -> {
                    overlay.hide();
                    if (i != null && i == 0) {
                        plan.run().run();
                        refreshCatalogue();
                    }
                }));
    }

    private String lastQuery = "";
    private Predicate<PaperCard> lastFine;

    private boolean colourlessOn;
    private boolean multicolourOn;

    private Button extraColourFilter(final String letter, final String style, final String tip,
                                     final boolean colourless) {
        final Button b = new Button(letter);
        b.getStyleClass().addAll("segment", "colour-filter", style);
        b.setTooltip(new javafx.scene.control.Tooltip(tip));
        b.setOnAction(e -> {
            final boolean on;
            if (colourless) {
                colourlessOn = !colourlessOn;
                on = colourlessOn;
            } else {
                multicolourOn = !multicolourOn;
                on = multicolourOn;
            }
            b.pseudoClassStateChanged(SELECTED, on);
            refreshCatalogue();
        });
        filterButtons.add(b);
        return b;
    }

    private Button newestButton() {
        final Button b = new Button(NeoText.get("deck.newest"));
        b.setId("builder-newest");
        b.getStyleClass().add("segment");
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setTooltip(new javafx.scene.control.Tooltip(NeoText.get(editor.getFormat().acquisitionTip())));
        editor.setNewestFirst(newestFirstChosen);
        b.pseudoClassStateChanged(SELECTED, newestFirstChosen);
        b.setOnAction(e -> {
            newestFirstChosen = !newestFirstChosen;
            editor.setNewestFirst(newestFirstChosen);
            b.pseudoClassStateChanged(SELECTED, newestFirstChosen);
            refreshCatalogue();
        });
        return b;
    }

    private Button rarityFilter(final String letter, final forge.card.CardRarity rarity) {
        final Button b = new Button(letter);
        b.getStyleClass().addAll("segment", "rarity-filter",
                "rarity-" + letter.toLowerCase(java.util.Locale.ROOT));
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setOnAction(e -> {
            if (!rarities.remove(rarity)) {
                rarities.add(rarity);
            }
            b.pseudoClassStateChanged(SELECTED, rarities.contains(rarity));
            refreshCatalogue();
        });
        filterButtons.add(b);
        return b;
    }

    private Button cmcFilter(final int cmc) {
        final Button b = new Button(cmc == 7 ? "7+" : String.valueOf(cmc));
        b.getStyleClass().addAll("segment", "cmc-filter");
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setOnAction(e -> {
            if (!cmcs.remove(cmc)) {
                cmcs.add(cmc);
            }
            b.pseudoClassStateChanged(SELECTED, cmcs.contains(cmc));
            refreshCatalogue();
        });
        filterButtons.add(b);
        return b;
    }

    private Button typeFilter(final forge.card.CardType.CoreType type) {
        final Button b = new Button(type.getTranslatedName());
        b.getStyleClass().addAll("segment", "type-filter");
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setOnAction(e -> {
            if (!types.remove(type)) {
                types.add(type);
            }
            b.pseudoClassStateChanged(SELECTED, types.contains(type));
            refreshCatalogue();
        });
        filterButtons.add(b);
        return b;
    }

    private void clearFilters() {
        colours.clear();
        colourlessOn = false;
        multicolourOn = false;
        powers.clear();
        toughnesses.clear();
        chosenSets.clear();
        updateSetButton();
        formatBox.getSelectionModel().select(0);
        rarities.clear();
        cmcs.clear();
        types.clear();
        searchRules = true;
        onlyLegal = false;
        
        for (final Button n : filterButtons) {
            n.pseudoClassStateChanged(SELECTED, false);
        }
        if (rulesButton != null) {
            rulesButton.pseudoClassStateChanged(SELECTED, true);
        }
        setHideUsed(false);
        search.setPromptText(NeoText.get("deck.search.rules"));
        refreshCatalogue();
    }

    private Button colourFilter(final String letter, final byte colour) {
        final Button b = new Button(letter);
        b.getStyleClass().addAll("segment", "colour-filter", "mana-" + letter.toLowerCase(java.util.Locale.ROOT));
        b.setOnAction(e -> {
            if (colours.contains(colour)) {
                colours.remove(Byte.valueOf(colour));
            } else {
                colours.add(colour);
            }
            b.pseudoClassStateChanged(SELECTED, colours.contains(colour));
            refreshCatalogue();
        });
        filterButtons.add(b);
        return b;
    }

    private Region deckPanel() {
        commanderRow.setAlignment(Pos.CENTER_LEFT);

        commanderRow.setVisible(editor.usesCommander());
        commanderRow.setManaged(editor.usesCommander());

        final ScrollPane scroll = new ScrollPane(deckList);
        scroll.getStyleClass().add("dialog-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        deckCount.getStyleClass().add("builder-deck-count");
        deckCount.setMinWidth(0);
        final Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        deckFilterToggle.setId("builder-deck-filter");
        deckFilterToggle.getStyleClass().add("segment");
        deckFilterToggle.setMinWidth(Region.USE_PREF_SIZE);
        deckFilterToggle.setTooltip(new javafx.scene.control.Tooltip(NeoText.get("deck.filterDeck.tip")));
        deckFilterToggle.setOnAction(e -> {
            final boolean show = !deckFilterRow.isVisible();
            deckFilterRow.setVisible(show);
            deckFilterRow.setManaged(show);
            refreshDeckFilterToggle();
        });
        final HBox title = new HBox(8, label(NeoText.get("deck.theDeck")), gap, deckCount);
        title.setAlignment(Pos.CENTER_LEFT);

        final Region gap3 = new Region();
        HBox.setHgrow(gap3, Priority.ALWAYS);
        
        deckGridToggle.setId("builder-deck-grid");
        deckGridToggle.getStyleClass().add("segment");
        deckGridToggle.setMinWidth(Region.USE_PREF_SIZE);
        deckGridToggle.setTooltip(new javafx.scene.control.Tooltip(NeoText.get("deck.gridView.tip")));
        deckGridToggle.setOnAction(e -> {
            deckGrid = !deckGrid;
            forge.neo.NeoSettings.setBool(DECK_GRID_KEY, deckGrid);
            forge.neo.NeoSettings.save();
            refreshDeckGridToggle();
            refreshDeck();
        });
        refreshDeckGridToggle();
        final HBox view = new HBox(6, deckSortBox(), deckSortDirection, gap3, deckGridToggle, deckFilterToggle);
        view.setAlignment(Pos.CENTER_LEFT);
        final VBox heading = new VBox(6, title, view);
        deckFilterRow = deckFilterRow();
        refreshDeckFilterToggle();
        final javafx.scene.control.TitledPane details = new javafx.scene.control.TitledPane(NeoText.get("stats.caption"), stats);
        details.setExpanded(false);
        details.setAnimated(false);
        details.getStyleClass().add("builder-statistics");
        companionRow.setAlignment(Pos.CENTER_LEFT);
        final VBox box = new VBox(8, heading, deckFilterRow, commanderRow, companionRow, scroll,
                curve, details);
        scroll.setMinHeight(UiScale.px(60));
        box.setId("builder-deck-panel");
        box.getStyleClass().add("deck-panel");
        box.setPadding(new Insets(12, 14, 12, 14));
        return box;
    }

    private javafx.scene.control.ComboBox<DeckEditor.Sort> deckSortBox() {
        final javafx.scene.control.ComboBox<DeckEditor.Sort> box = new javafx.scene.control.ComboBox<>();
        box.setId("builder-deck-sort");
        box.getStyleClass().add("builder-sort");
        box.getItems().addAll(editor.deckSorts());
        box.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(final DeckEditor.Sort s) {
                return s == null ? "" : NeoText.get("deck.sort", sortName(s));
            }

            @Override
            public DeckEditor.Sort fromString(final String t) {
                return null;
            }
        });
        box.setTooltip(new javafx.scene.control.Tooltip(NeoText.get("deck.sort.deck.tip")));
        final DeckEditor.Sort start = deckSortChosen != null && box.getItems().contains(deckSortChosen)
                ? deckSortChosen : DeckEditor.Sort.COST;
        box.setValue(start);
        editor.setDeckSort(start, deckSortReversedChosen);
        showDirection(deckSortDirection, start, deckSortReversedChosen);
        box.valueProperty().addListener((o, was, is) -> {
            if (is == null) {
                return;
            }
            deckSortChosen = is;
            deckSortReversedChosen = false;
            editor.setDeckSort(is, false);
            showDirection(deckSortDirection, is, false);
            refreshDeck();
        });
        deckSortDirection.setOnAction(e -> {
            deckSortReversedChosen = !deckSortReversedChosen;
            editor.setDeckSort(editor.getDeckSort(), deckSortReversedChosen);
            showDirection(deckSortDirection, editor.getDeckSort(), deckSortReversedChosen);
            refreshDeck();
        });
        return box;
    }

    private final java.util.Set<Byte> deckColours = new java.util.LinkedHashSet<>();
    private boolean deckColourless;
    private final TextField deckSearch = new TextField();
    private final Button deckFilterToggle = new Button();

    private boolean deckGrid = Boolean.getBoolean("neo.builder.grid")
            || forge.neo.NeoSettings.getBool(DECK_GRID_KEY, false);
    private static final String DECK_GRID_KEY = "deck.gridView";
    private final Button deckGridToggle = new Button();

    private void refreshDeckGridToggle() {
        deckGridToggle.setText(NeoText.get(deckGrid ? "deck.listView" : "deck.gridView"));
    }
    private VBox deckFilterRow;
    
    private final java.util.Set<forge.card.CardType.CoreType> deckTypes =
            java.util.EnumSet.noneOf(forge.card.CardType.CoreType.class);
    private final java.util.Set<Integer> deckCosts = new java.util.TreeSet<>();
    private final java.util.Set<forge.card.CardRarity> deckRarities =
            java.util.EnumSet.noneOf(forge.card.CardRarity.class);
    private final List<Button> deckFineButtons = new ArrayList<>();

    private <T> Button deckToggle(final String text, final java.util.Set<T> set, final T value,
                                  final String... styles) {
        final Button b = new Button(text);
        b.getStyleClass().add("segment");
        b.getStyleClass().addAll(styles);
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setOnAction(e -> {
            if (!set.remove(value)) {
                set.add(value);
            }
            b.pseudoClassStateChanged(SELECTED, set.contains(value));
            refreshDeck();
        });
        deckFineButtons.add(b);
        return b;
    }

    private VBox deckFilterRow() {
        final HBox colourRow = new HBox(4);
        colourRow.setAlignment(Pos.CENTER_LEFT);
        final String[] letters = {"W", "U", "B", "R", "G"};
        final byte[] masks = {MagicColor.WHITE, MagicColor.BLUE, MagicColor.BLACK,
                MagicColor.RED, MagicColor.GREEN};
        final List<Button> buttons = new ArrayList<>();
        for (int i = 0; i < letters.length; i++) {
            final byte colour = masks[i];
            final Button b = new Button(letters[i]);
            b.getStyleClass().addAll("segment", "colour-filter",
                    "mana-" + letters[i].toLowerCase(java.util.Locale.ROOT));
            b.setOnAction(e -> {
                if (!deckColours.remove(colour)) {
                    deckColours.add(colour);
                }
                b.pseudoClassStateChanged(SELECTED, deckColours.contains(colour));
                refreshDeck();
            });
            buttons.add(b);
        }

        final Button colourless = new Button("C");
        colourless.getStyleClass().addAll("segment", "colour-filter", "mana-c");
        colourless.setTooltip(new javafx.scene.control.Tooltip(NeoText.get("deck.colorless")));
        colourless.setOnAction(e -> {
            deckColourless = !deckColourless;
            colourless.pseudoClassStateChanged(SELECTED, deckColourless);
            refreshDeck();
        });
        buttons.add(colourless);
        colourRow.getChildren().addAll(buttons);

        final Button clear = new Button("×");
        clear.getStyleClass().add("segment");
        clear.setMinWidth(Region.USE_PREF_SIZE);
        clear.setTooltip(new javafx.scene.control.Tooltip(NeoText.get("deck.clearFilters")));
        clear.setOnAction(e -> {
            deckColours.clear();
            deckColourless = false;
            for (final Button b : buttons) {
                b.pseudoClassStateChanged(SELECTED, false);
            }
            deckSearch.clear();
            deckTypes.clear();
            deckCosts.clear();
            deckRarities.clear();
            for (final Button b : deckFineButtons) {
                b.pseudoClassStateChanged(SELECTED, false);
            }
            refreshDeck();
        });

        deckSearch.setPromptText(NeoText.get("deck.filterDeck.search"));
        deckSearch.getStyleClass().add("text-input");

        final String typed = System.getProperty("neo.builder.deckSearch", "");
        deckSearch.setText(typed);
        deckSearch.textProperty().addListener((o, was, is) -> refreshDeck());
        deckSearch.setMinWidth(UiScale.px(70));
        deckSearch.setPrefColumnCount(6);
        HBox.setHgrow(deckSearch, Priority.ALWAYS);

        final HBox top = new HBox(4, colourRow, deckSearch, clear);
        top.setAlignment(Pos.CENTER_LEFT);

        final FlowPane typeRow = new FlowPane(4, 4);
        for (final forge.card.CardType.CoreType t : new forge.card.CardType.CoreType[] {
                forge.card.CardType.CoreType.Creature, forge.card.CardType.CoreType.Instant,
                forge.card.CardType.CoreType.Sorcery, forge.card.CardType.CoreType.Artifact,
                forge.card.CardType.CoreType.Enchantment, forge.card.CardType.CoreType.Planeswalker,
                forge.card.CardType.CoreType.Battle, forge.card.CardType.CoreType.Land}) {
            typeRow.getChildren().add(deckToggle(t.getTranslatedName(), deckTypes, t, "type-filter"));
        }
        final HBox costRow = new HBox(4);
        costRow.setAlignment(Pos.CENTER_LEFT);
        costRow.getChildren().add(label(NeoText.get("deck.cmc")));
        for (int i = 0; i <= 7; i++) {
            costRow.getChildren().add(deckToggle(i == 7 ? "7+" : String.valueOf(i), deckCosts, i, "cmc-filter"));
        }
        final String[] letters2 = {"C", "I", "R", "M"};
        final forge.card.CardRarity[] rarityValues = {forge.card.CardRarity.Common,
                forge.card.CardRarity.Uncommon, forge.card.CardRarity.Rare, forge.card.CardRarity.MythicRare};
        final HBox rarityRow = new HBox(4);
        rarityRow.setAlignment(Pos.CENTER_LEFT);
        rarityRow.getChildren().add(label(NeoText.get("deck.rarity")));
        for (int i = 0; i < letters2.length; i++) {
            rarityRow.getChildren().add(deckToggle(letters2[i], deckRarities, rarityValues[i], "rarity-filter",
                    "rarity-" + letters2[i].toLowerCase(java.util.Locale.ROOT)));
        }
        final FlowPane fine = new FlowPane(10, 4, costRow, rarityRow);

        final VBox row = new VBox(4, top, typeRow, fine);
        row.setId("builder-deck-filter-row");

        typeRow.prefWrapLengthProperty().bind(row.widthProperty());
        fine.prefWrapLengthProperty().bind(row.widthProperty());

        final boolean open = Boolean.getBoolean("neo.builder.deckFilter") || !typed.isEmpty();
        row.setVisible(open);
        row.setManaged(open);
        return row;
    }

    private boolean deckFilterActive() {
        return !deckColours.isEmpty() || deckColourless || !deckSearch.getText().isBlank()
                || !deckTypes.isEmpty() || !deckCosts.isEmpty() || !deckRarities.isEmpty();
    }

    private boolean deckFilterAccepts(final PaperCard card) {
        int mask = 0;
        for (final byte c : deckColours) {
            mask |= c;
        }
        return forge.neo.deck.DeckFilter.accepts(card, mask, deckColourless, deckSearch.getText(),
                deckTypes, deckCosts, deckRarities);
    }

    private void refreshDeckFilterToggle() {
        final boolean active = deckFilterActive();
        final boolean open = deckFilterRow != null && deckFilterRow.isVisible();
        deckFilterToggle.setText(NeoText.get("deck.filterDeck") + (active ? "  •" : ""));
        deckFilterToggle.pseudoClassStateChanged(SELECTED, active || open);
    }

    private static String caps(final String key) {
        return NeoText.get(key).toUpperCase(java.util.Locale.ROOT);
    }

    private static Label label(final String text) {
        final Label l = new Label(text);
        l.getStyleClass().add("caption");
        return l;
    }

    private Region footer() {
        final Button back = new Button(NeoText.get("common.back"));
        back.getStyleClass().add("btn-secondary");
        back.setOnAction(e -> leave());

        final Button commander = commanderButton;
        commander.getStyleClass().add("btn-secondary");

        final boolean withCommander = editor.usesCommander();
        commander.setVisible(withCommander);
        commander.setManaged(withCommander);
        commander.setOnAction(e -> openPicker(!(commanderMode && !pickingSpell), false));

        final boolean withSpell = editor.usesSignatureSpell();
        spellButton.getStyleClass().add("btn-secondary");
        spellButton.setVisible(withSpell);
        spellButton.setManaged(withSpell);
        spellButton.setOnAction(e -> openPicker(!pickingSpell, true));
        if (withSpell) {
            commander.setText(NeoText.get("deck.pickOathbreaker"));
        }

        generate.getStyleClass().add("btn-secondary");
        generate.setOnAction(e -> generateDeck());

        importer.getStyleClass().add("btn-secondary");
        importer.setOnAction(e -> importList());

        final Button export = new Button(NeoText.get("deck.export"));
        export.getStyleClass().add("btn-secondary");
        export.setOnAction(e -> exportList());

        final boolean limitedPool = editor.canAutoBuild();
        autoBuild.getStyleClass().add("btn-secondary");
        autoBuild.setOnAction(e -> autoBuildDeck());
        autoBuild.setVisible(limitedPool);
        autoBuild.setManaged(limitedPool);
        clearMain.getStyleClass().add("btn-secondary");
        clearMain.setOnAction(e -> clearMainDeck());
        clearMain.setVisible(limitedPool);
        clearMain.setManaged(limitedPool);

        cleanup.getStyleClass().add("btn-secondary");
        cleanup.setOnAction(e -> removeIllegal());
        cleanup.setVisible(false);
        cleanup.setManaged(false);
        removeMissing.getStyleClass().add("btn-secondary");
        removeMissing.setId("deck-remove-missing");
        removeMissing.setOnAction(e -> removeMissing());
        removeMissing.setVisible(false);
        removeMissing.setManaged(false);
        exportMissing.getStyleClass().add("btn-secondary");
        exportMissing.setId("deck-export-missing");
        exportMissing.setOnAction(e -> exportMissing());
        exportMissing.setVisible(false);
        exportMissing.setManaged(false);
        clearSide.getStyleClass().add("btn-secondary");
        clearSide.setId("deck-clear-sideboard");
        clearSide.setOnAction(e -> clearSideboard());
        clearSide.setVisible(false);
        clearSide.setManaged(false);

        final Button save = new Button(NeoText.get("common.save"));
        save.getStyleClass().add("btn-primary");
        save.setOnAction(e -> save());

        final Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        for (final Button b : new Button[] {back, cleanup, removeMissing, exportMissing, clearSide, commander, spellButton, generate,
                autoBuild, clearMain, importer, export, save}) {
            b.setMinWidth(Region.USE_PREF_SIZE);
        }

        final FlowPane actions =
                new FlowPane(8, 8, cleanup, removeMissing, exportMissing, clearSide, commander, spellButton, generate, autoBuild,
                        clearMain, importer, export);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMinWidth(0);
        HBox.setHgrow(actions, Priority.ALWAYS);
        save.setId("builder-save");
        back.setId("builder-back");

        final HBox row = new HBox(14, actions, back, save);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("home-footer");
        row.setPadding(new Insets(14, 26, 18, 26));
        return row;
    }

    private void setCommanderMode(final boolean on) {
        openPicker(on, false);
    }

    private void openPicker(final boolean on, final boolean spell) {
        commanderMode = on;
        pickingSpell = on && spell;
        final boolean twoSlots = editor.usesSignatureSpell();
        commanderButton.setText(NeoText.get(on && !pickingSpell ? "deck.backToCatalogue"
                : twoSlots ? "deck.pickOathbreaker" : "deck.pickCommander"));
        commanderButton.pseudoClassStateChanged(SELECTED, on && !pickingSpell);
        spellButton.setText(NeoText.get(pickingSpell
                ? "deck.backToCatalogue" : "deck.pickSignature"));
        spellButton.pseudoClassStateChanged(SELECTED, pickingSpell);
        pickingBadge.setText(NeoText.get(pickingSpell ? "deck.pickingSignature"
                : twoSlots ? "deck.pickingOathbreaker" : "deck.pickingCommander"));
        pickingBadge.setVisible(on);
        pickingBadge.setManaged(on);
        refreshCatalogue();
    }

    public void startByPickingCommander() {
        if (!editor.usesCommander() || commanderMode) {
            return;
        }
        if (editor.commanders().isEmpty()) {
            openPicker(true, false);
        } else if (editor.usesSignatureSpell() && editor.signatureSpell() == null) {

            openPicker(true, true);
        }
    }

    private void updateFilterToggle() {
        final int active = colours.size() + (colourlessOn ? 1 : 0) + (multicolourOn ? 1 : 0)
                + rarities.size() + cmcs.size() + types.size() + (searchRules ? 0 : 1)
                + powers.size() + toughnesses.size() + (hideUsed ? 1 : 0)
                + (chosenSets.isEmpty() ? 0 : 1) + (formatBox.getValue() != null ? 1 : 0);
        filterToggle.setText(NeoText.get("deck.filters") + (active == 0 ? "" : " · " + active)
                + (filterRow != null && filterRow.isVisible() ? "  −" : "  +"));
        filterToggle.pseudoClassStateChanged(SELECTED, active > 0 || (filterRow != null && filterRow.isVisible()));
    }

    private void refreshCatalogue() {
        updateFilterToggle();

        byte mask = 0;
        for (final byte c : colours) {
            mask |= c;
        }
        final byte colourMask = mask;
        final Predicate<PaperCard> colourFilter =
                colours.isEmpty() && !colourlessOn && !multicolourOn ? null
                        : card -> forge.neo.deck.ColourFilter.matches(card, colourMask, colourlessOn, multicolourOn);

        final java.util.Set<String> usedUp = hideUsed && !commanderMode
                ? editor.usedUpNames() : java.util.Set.of();

        final Predicate<PaperCard> fine = card -> {
            if (usedUp.contains(card.getName())) {
                return false;
            }
            
            if (!rarities.isEmpty() && !rarities.contains(card.getRarity())) {
                return false;
            }
            if (!types.isEmpty() && java.util.Collections.disjoint(
                    types, card.getRules().getType().getCoreTypes())) {
                return false;
            }
            if (!cmcs.isEmpty()) {
                final int cmc = card.getRules().getManaCost().getCMC();
                
                if (!cmcs.contains(Math.min(cmc, 7))) {
                    return false;
                }
            }
            
            if (!powers.isEmpty() || !toughnesses.isEmpty()) {
                if (!card.getRules().getType().isCreature()) {
                    return false;
                }
                if (!powers.isEmpty() && !powers.contains(
                        Math.max(0, Math.min(card.getRules().getIntPower(), 7)))) {
                    return false;
                }
                if (!toughnesses.isEmpty() && !toughnesses.contains(
                        Math.max(0, Math.min(card.getRules().getIntToughness(), 7)))) {
                    return false;
                }
            }
            if (!chosenSets.isEmpty() && chosenSets.stream().noneMatch(ed -> inChosenSet(card, ed))) {
                return false;
            }
            final forge.game.GameFormat gf = formatBox.getValue();
            if (gf != null && !gf.getFilterRules().test(card)) {
                return false;
            }
            return colourFilter == null || colourFilter.test(card);
        };

        final String query = search.getText();
        lastQuery = query;
        lastFine = fine;
        if (commanderMode) {

            catalogueHits = pickingSpell
                    ? editor.signatureCandidates(query, FETCH_LIMIT, fine, searchRules)
                    : editor.commanderCandidates(query, FETCH_LIMIT, fine, searchRules);
            catalogueTotal = catalogueHits.size();
        } else {
            
            final DeckEditor.SearchResult found =

                    editor.find(query, onlyLegal, fine, editor.getGroup() != DeckEditor.Group.NONE
                            ? Integer.MAX_VALUE : FETCH_LIMIT, searchRules);
            catalogueHits = found.cards;
            catalogueTotal = found.total;
        }

        basicHits = new ArrayList<>();
        if (editor.isLimited() && !commanderMode) {
            final List<PaperCard> rest = new ArrayList<>();
            for (final PaperCard c : catalogueHits) {
                if (editor.owned(c) == Integer.MAX_VALUE) {
                    basicHits.add(c);
                } else {
                    rest.add(c);
                }
            }
            catalogueTotal -= catalogueHits.size() - rest.size();
            catalogueHits = rest;
        }

        pager.reset();
        fillResults();
    }

    private void fillResults() {
        pager.setTotal(catalogueHits.size());

        results.getChildren().clear();

        final boolean grouped = editor.getGroup() != DeckEditor.Group.NONE && !commanderMode;
        final Map<String, Integer> perGroup = new java.util.HashMap<>();
        if (grouped) {
            
            for (final PaperCard c : catalogueHits) {
                perGroup.merge(editor.catalogueGroupOf(c), editor.catalogueCopies(c), Integer::sum);
            }
        }
        String lastGroup = null;
        for (int i = pager.from(); i < pager.to(); i++) {
            final PaperCard c = catalogueHits.get(i);
            if (grouped) {
                final String g = editor.catalogueGroupOf(c);
                if (!java.util.Objects.equals(g, lastGroup)) {
                    lastGroup = g;
                    final Label heading = new Label(DeckEditor.catalogueGroupLabel(g)
                            + "  (" + perGroup.getOrDefault(g, 0) + ")");
                    heading.getStyleClass().add("deck-group");
                    heading.setMaxWidth(Double.MAX_VALUE);
                    
                    heading.prefWidthProperty().bind(results.widthProperty()
                            .subtract(results.getPadding().getLeft() + results.getPadding().getRight() + 2));
                    results.getChildren().add(heading);
                }
            }
            results.getChildren().add(catalogueTile(c));
        }

        basics.getChildren().clear();
        for (final PaperCard c : basicHits) {
            basics.getChildren().add(basicChip(c));
        }
        showBasics(!basicHits.isEmpty());

        if (catalogueHits.isEmpty() && basicHits.isEmpty()) {
            final Label empty = new Label(!commanderMode ? NeoText.get("deck.noCard")
                    : pickingSpell ? NeoText.get("deck.noSignature")
                            : NeoText.get("deck.noCommander"));
            empty.getStyleClass().add("home-subtitle");
            results.getChildren().add(empty);
        }

        resultCount.setText(catalogueTotal > catalogueHits.size()
                ? NeoText.get("deck.someOf", catalogueHits.size(), catalogueTotal)
                : catalogueTotal == 1 ? NeoText.get("count.card")
                        : NeoText.get("count.cards", catalogueTotal));
    }

    private void showBasics(final boolean on) {
        basicsCaption.setVisible(on);
        basics.setVisible(on);
        basics.setManaged(on);
    }

    private static final int FETCH_LIMIT = 1000;

    private List<PaperCard> catalogueHits = new ArrayList<>();
    private int catalogueTotal;

    private Region catalogueTile(final PaperCard card) {
        return catalogueTile(card, cardWidth);
    }

    static Insets countMargin(final double cardWidth) {
        return new Insets(cardWidth * CardNode.ASPECT * 0.115, 4, 0, 0);
    }

    private Region catalogueTile(final PaperCard card, final double displayWidth) {
        final CardNode node = new CardNode(displayWidth);
        node.setHoverEnabled(false);
        node.setRotationEnabled(false);
        node.setBadgesVisible(false);
        node.setCard(CardView.getCardForUi(card));

        final Label count = new Label();
        count.getStyleClass().add("catalogue-count");
        final int have = editor.catalogueHave(card);
        count.setText(countLabel(have, editor.catalogueOf(card)));
        count.setVisible(!count.getText().isEmpty());
        
        if (editor.missingOf(card) > 0) {
            count.getStyleClass().add("catalogue-count-missing");
        }

        if (editor.showsAllCards() && editor.owned(card) == 0) {
            node.setEffect(new javafx.scene.effect.ColorAdjust(0, -0.85, -0.15, 0));
            count.setVisible(have > 0);
        }

        if (!commanderMode && editor.rejectionReason(card) != null) {
            node.setOpacity(0.4);
        }

        final StackPane box = new StackPane(node, count);
        StackPane.setAlignment(count, Pos.TOP_RIGHT);
        StackPane.setMargin(count, countMargin(displayWidth));
        box.getStyleClass().add("catalogue-tile");
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        box.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                catalogueMenu(card, box, e.getScreenX(), e.getScreenY());
                return;
            }
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (commanderMode) {
                if (!editor.setCommander(card)) {
                    message(NeoText.get(pickingSpell ? "deck.notSignature.title"
                                    : "deck.notCommander.title"),
                            NeoText.get(pickingSpell ? "deck.notSignature"
                                            : "deck.notCommander",
                                    CardText.nameOf(card),
                                    editor.getFormat().getLabel()));
                    return;
                }
                refreshDeck();

                if (!pickingSpell && editor.usesSignatureSpell()
                        && editor.signatureSpell() == null) {
                    openPicker(true, true);
                    return;
                }

                setCommanderMode(false);
                return;
            }
            final String no = editor.rejectionReason(card);
            if (no != null) {
                message(NeoText.get("deck.doesNotFit"), no);
                return;
            }
            editor.add(card, 1);
            final int now = editor.catalogueHave(card);
            count.setText(countLabel(now, editor.catalogueOf(card)));
            count.setVisible(!count.getText().isEmpty());
            
            count.getStyleClass().remove("catalogue-count-missing");
            if (editor.missingOf(card) > 0) {
                count.getStyleClass().add("catalogue-count-missing");
            }
            
            if (editor.rejectionReason(card) != null) {
                node.setOpacity(0.4);
            }
            refreshDeck();
        });
        hoverCard(box, card);
        return box;
    }

    private Region basicChip(final PaperCard card) {
        final Button chip = new Button();
        chip.getStyleClass().add("segment");
        chip.setMinWidth(Region.USE_PREF_SIZE);
        final Runnable label = () -> {
            final int have = editor.countOf(card);
            chip.setText(CardText.nameOf(card) + (have > 0 ? "   ×" + have : "") + "   +");
        };
        label.run();
        chip.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                catalogueMenu(card, chip, e.getScreenX(), e.getScreenY());
                return;
            }
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            final String no = editor.rejectionReason(card);
            if (no != null) {
                message(NeoText.get("deck.doesNotFit"), no);
                return;
            }
            editor.add(card, 1);
            label.run();
            refreshDeck();
        });
        return chip;
    }

    private String countLabel(final int have, final int owned) {
        if (editor.isLimited() && owned != Integer.MAX_VALUE) {
            return have + "/" + owned;
        }
        return have > 0 ? "×" + have : "";
    }

    private void setVisualMode(final boolean on) {
        if (visualMode == on) {
            return;
        }
        visualMode = on;
        if (on) {
            if (stackView == null) {
                stackView = new DeckStackView(editor, cardWidth, new DeckStackView.CardActions() {
                    @Override
                    public void zoom(final PaperCard card) {
                        DeckBuilderScreen.this.zoom(card);
                    }

                    @Override
                    public void menu(final PaperCard card, final Region anchor,
                                     final double screenX, final double screenY,
                                     final boolean isCommander) {
                        if (isCommander) {
                            commanderMenu(card, anchor, screenX, screenY);
                        } else {
                            cardMenu(card, anchor, screenX, screenY);
                        }
                    }

                    @Override
                    public boolean canEdit() {
                        return true;
                    }

                    @Override
                    public void add(final PaperCard card) {
                        addOne(card);
                    }

                    @Override
                    public void remove(final PaperCard card) {
                        removeOne(card);
                    }

                    @Override
                    public boolean accepts(final PaperCard card) {
                        return !deckFilterActive() || deckFilterAccepts(card);
                    }
                });
            } else {
                stackView.refresh();
            }
            layout.setCenter(stackView);
        } else {
            layout.setCenter(editView);
            refreshCatalogue();
        }
    }

    private static String oneLine(final String problem) {
        final String[] lines = problem.split("\r?\n");
        if (lines.length <= 1) {
            return problem;
        }
        int rest = 0;
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                rest++;
            }
        }
        return rest == 0 ? lines[0].trim()
                : lines[0].trim() + " " + lines[1].trim()
                        + (rest > 1 ? "  " + NeoText.get("list.more", rest - 1) : "");
    }

    private void refreshDeck() {
        title.setText(editor.getName() + (editor.isDirty() ? " *" : ""));
        refreshFoilAllButton();

        final String problem = editor.problem();
        final int main = editor.mainCount();
        deckCount.setText(NeoText.get("count.cards", main));
        status.setText(NeoText.get("deck.status",
                editor.getFormat().getLabel(), main,

                editor.commanders().isEmpty() ? ""
                        : NeoText.get(editor.usesSignatureSpell()
                                && editor.commanders().size() >= 2
                                        ? "deck.plusSignature" : "deck.plusCommander"),
                problem == null ? NeoText.get("deck.ready") : oneLine(problem)));
        status.pseudoClassStateChanged(INVALID, problem != null);

        refreshCommanderRow();
        refreshCompanionRow();

        final java.util.Set<String> illegal = new java.util.HashSet<>();
        for (final PaperCard c : editor.illegalCards()) {
            illegal.add(c.getName());
        }
        cleanup.setVisible(!illegal.isEmpty());
        cleanup.setManaged(!illegal.isEmpty());
        
        final boolean lacking = editor.missingTotal() > 0;
        removeMissing.setVisible(lacking);
        removeMissing.setManaged(lacking);
        exportMissing.setVisible(lacking);
        exportMissing.setManaged(lacking);
        final boolean sideTooBig = editor.sideboardOverflow() > 0;
        clearSide.setText(NeoText.get("deck.clearSideboard", editor.sideboardWithoutCompanion()));
        clearSide.setVisible(sideTooBig);
        clearSide.setManaged(sideTooBig);
        clearMain.setDisable(main == 0);

        final boolean filtering = deckFilterActive();
        int shown = 0;
        deckList.getChildren().clear();
        for (final Map.Entry<String, Integer> group : editor.typeCounts().entrySet()) {
            final List<Region> rows = new ArrayList<>();
            int inGroup = 0;
            for (final Map.Entry<PaperCard, Integer> e : editor.cardsInGroup(group.getKey())) {
                if (filtering && !deckFilterAccepts(e.getKey())) {
                    continue;
                }
                final Region row = deckGrid ? deckTile(e.getKey(), e.getValue())
                        : deckRow(e.getKey(), e.getValue());
                row.pseudoClassStateChanged(INVALID, illegal.contains(e.getKey().getName()));
                rows.add(row);
                inGroup += e.getValue();
            }
            if (rows.isEmpty()) {
                continue;
            }
            shown += inGroup;
            final Label heading = new Label(DeckEditor.groupLabel(group.getKey())
                    + "  (" + (filtering ? inGroup : group.getValue()) + ")");
            heading.getStyleClass().add("deck-group");
            deckList.getChildren().add(heading);
            if (deckGrid) {
                final javafx.scene.layout.FlowPane grid = new javafx.scene.layout.FlowPane(6, 6);
                grid.getStyleClass().add("deck-grid");
                grid.getChildren().addAll(rows);
                deckList.getChildren().add(grid);
            } else {
                deckList.getChildren().addAll(rows);
            }
        }

        if (!filtering) {
            final List<Map.Entry<PaperCard, Integer>> side = editor.sideboardCards();
            if (!side.isEmpty()) {
                int inSide = 0;
                final List<Region> rows = new ArrayList<>();
                for (final Map.Entry<PaperCard, Integer> e : side) {
                    rows.add(sideboardRow(e.getKey(), e.getValue()));
                    inSide += e.getValue();
                }
                final Label heading = new Label(NeoText.get("deck.sideboard", inSide));
                heading.getStyleClass().add("deck-group");
                heading.setId("deck-sideboard");
                deckList.getChildren().add(heading);
                deckList.getChildren().addAll(rows);
            }
        }
        if (filtering) {
            deckCount.setText(NeoText.get("count.cards", main) + "  ·  "
                    + NeoText.get("deck.filterDeck.showing", shown));
        }
        refreshDeckFilterToggle();
        if (deckList.getChildren().isEmpty()) {
            final Label empty = new Label(NeoText.get(filtering && main > 0
                    ? "deck.filterDeck.none" : "deck.empty"));
            empty.getStyleClass().add("home-subtitle");
            empty.setWrapText(true);
            deckList.getChildren().add(empty);
        }

        curve.update(editor);
        stats.update(editor);

        curve.setVisible(!filtering);
        curve.setManaged(!filtering);

        if (visualMode && stackView != null) {
            stackView.refresh();
        }
    }

    private Region deckRow(final PaperCard card, final int amount) {
        final Label qty = new Label(String.valueOf(amount));
        qty.getStyleClass().add("deck-row-qty");
        qty.setMinWidth(UiScale.px(22));

        final Label name = new Label(CardText.nameOf(card));
        name.getStyleClass().add("deck-row-name");
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        final Region cost = manaCost(card);
        
        final int missing = editor.missingOf(card);
        final Label lacking = new Label(missing > 0 ? NeoText.get("deck.missing", missing) : "");
        lacking.getStyleClass().add("deck-row-missing");
        lacking.setMinWidth(Region.USE_PREF_SIZE);
        lacking.setVisible(missing > 0);
        lacking.setManaged(missing > 0);

        final Button less = stepper("-", () -> removeOne(card));
        final Button more = stepper("+", () -> addOne(card));

        final HBox steps = new HBox(2, less, more);
        steps.setAlignment(Pos.CENTER_RIGHT);
        steps.setVisible(true);

        final HBox content = new HBox(7, qty, name, lacking, cost, steps);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPadding(new Insets(7, 8, 7, 8));
        final StackPane row = new StackPane(new DeckArtStrip(card), content);
        name.setMinWidth(0);
        name.setTooltip(new javafx.scene.control.Tooltip(CardText.nameOf(card)));
        row.setMinHeight(UiScale.px(44));
        row.setPrefHeight(UiScale.px(44));
        row.getStyleClass().add("deck-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(Insets.EMPTY);

        row.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                cardMenu(card, row, e.getScreenX(), e.getScreenY());
            } else if (e.getButton() == MouseButton.PRIMARY) {
                zoom(card);
            }
        });
        hoverCard(row, card);
        return row;
    }

    private void removeOne(final PaperCard card) {
        editor.remove(card, 1);
        refreshDeck();
        refreshCatalogue();
    }

    private void addOne(final PaperCard card) {
        final String no = editor.rejectionReason(card);
        if (no != null) {
            message(NeoText.get("deck.noMoreCopies"), no);
            return;
        }
        editor.add(card, 1);
        refreshDeck();
        refreshCatalogue();
    }

    private Region deckTile(final PaperCard card, final int amount) {
        
        final double w = UiScale.px(78);
        final forge.neo.card.CardNode node = new forge.neo.card.CardNode(w);
        node.setRotationEnabled(false);
        node.setBadgesVisible(false);
        node.setCard(forge.game.card.CardView.getCardForUi(card));
        final StackPane tile = new StackPane(node);
        tile.getStyleClass().add("deck-tile");
        if (amount > 1) {
            final Label count = new Label("\u00d7" + amount);
            count.getStyleClass().add("catalogue-count");
            tile.getChildren().add(count);
            StackPane.setAlignment(count, Pos.TOP_RIGHT);
            StackPane.setMargin(count, new Insets(3));
        }
        final int missing = editor.missingOf(card);
        if (missing > 0) {
            final Label lacking = new Label(NeoText.get("deck.missing", missing));
            lacking.getStyleClass().add("deck-row-missing");
            tile.getChildren().add(lacking);
            StackPane.setAlignment(lacking, Pos.TOP_LEFT);
            StackPane.setMargin(lacking, new Insets(3));
        }
        final HBox steps = new HBox(3, stepper("-", () -> removeOne(card)), stepper("+", () -> addOne(card)));
        steps.setAlignment(Pos.CENTER);
        steps.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        steps.setVisible(false);
        tile.getChildren().add(steps);
        StackPane.setAlignment(steps, Pos.BOTTOM_CENTER);
        StackPane.setMargin(steps, new Insets(0, 0, 4, 0));
        tile.hoverProperty().addListener((o, a, b) -> steps.setVisible(b));
        javafx.scene.control.Tooltip.install(tile, new javafx.scene.control.Tooltip(CardText.nameOf(card)));
        tile.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                cardMenu(card, tile, e.getScreenX(), e.getScreenY());
            } else if (e.getButton() == MouseButton.PRIMARY) {
                zoom(card);
            }
        });
        hoverCard(tile, card);
        return tile;
    }

    private Region sideboardRow(final PaperCard card, final int amount) {
        final Label qty = new Label(String.valueOf(amount));
        qty.getStyleClass().add("deck-row-qty");
        qty.setMinWidth(UiScale.px(22));
        final Label name = new Label(CardText.nameOf(card));
        name.getStyleClass().add("deck-row-name");
        name.setMaxWidth(Double.MAX_VALUE);
        name.setMinWidth(0);
        HBox.setHgrow(name, Priority.ALWAYS);
        final Button toMain = stepper("↑", () -> {
            editor.moveSideboardToMain(card);
            refreshDeck();
            refreshCatalogue();
        });
        javafx.scene.control.Tooltip.install(toMain,
                new javafx.scene.control.Tooltip(NeoText.get("deck.sideboard.toMain")));
        final Button less = stepper("-", () -> {
            editor.removeFromSideboard(card, 1);
            refreshDeck();
            refreshCatalogue();
        });
        final HBox content = new HBox(7, qty, name, manaCost(card), new HBox(2, toMain, less));
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPadding(new Insets(7, 8, 7, 8));
        final StackPane row = new StackPane(new DeckArtStrip(card), content);
        row.setMinHeight(UiScale.px(44));
        row.setPrefHeight(UiScale.px(44));
        row.getStyleClass().add("deck-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setOpacity(0.8);
        row.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                zoom(card);
            }
        });
        hoverCard(row, card);
        return row;
    }

    private Region manaCost(final PaperCard card) {
        final HBox pips = new HBox(2);
        pips.setAlignment(Pos.CENTER_RIGHT);
        pips.setMinWidth(Region.USE_PREF_SIZE);
        final String value = card.getRules().getManaCost().toString();
        final java.util.regex.Matcher symbols = java.util.regex.Pattern.compile("\\{([^}]+)\\}").matcher(value);
        while (symbols.find()) {
            final String symbol = symbols.group(1);
            final Label pip = new Label(symbol);
            pip.getStyleClass().add("builder-mana-pip");
            if (!symbol.isEmpty() && "WUBRG".contains(symbol.substring(0, 1))) {
                pip.getStyleClass().add("builder-mana-" + symbol.substring(0, 1).toLowerCase(java.util.Locale.ROOT));
            }
            pip.setMinWidth(symbol.length() > 1 ? 23 : 15);
            pips.getChildren().add(pip);
        }
        javafx.scene.control.Tooltip.install(pips, new javafx.scene.control.Tooltip(value));
        return pips;
    }

    private void commanderMenu(final PaperCard card, final Region anchor,
                               final double screenX, final double screenY) {
        showCardMenu(card, CardActionMenu.actions(
                printingAction(card),
                foilAction(card),
                ownImageAction(card),
                restoreImageAction(card),
                new CardActionMenu.Action(
                        NeoText.get(editor.isSignatureSpell(card)
                                ? "deck.dropSignature" : "deck.dropCommander"),
                        NeoText.get(editor.isSignatureSpell(card)
                                ? "deck.dropSignature.detail"
                                : "deck.dropCommander.detail"), true,
                        () -> {
                            editor.removeCommander(card);
                            refreshDeck();
                            refreshCatalogue();
                        })));
    }

    private void cardMenu(final PaperCard card, final Region anchor,
                          final double screenX, final double screenY) {
        final int have = editor.countOf(card);

        CardActionMenu.Action commander = null;
        if (editor.usesCommander()
                && (editor.deckFormat().isLegalCommander(card.getRules())
                        || editor.isSignatureSpell(card))) {

            final boolean spell = editor.isSignatureSpell(card);
            commander = new CardActionMenu.Action(
                    NeoText.get(spell ? "deck.makeSignature" : "deck.makeCommander"),
                    NeoText.get(spell ? "deck.makeSignature.detail"
                            : "deck.makeCommander.detail"), true,
                    () -> {
                        editor.setCommander(card);
                        refreshDeck();
                        refreshCatalogue();
                    });
        }

        showCardMenu(card, CardActionMenu.actions(
                printingAction(card),
                foilAction(card),
                ownImageAction(card),
                restoreImageAction(card),
                commander,
                secondCommanderAction(card),
                companionAction(card),
                coverAction(card),
                new CardActionMenu.Action(NeoText.get("deck.removeOne"),
                        have > 1 ? NeoText.get("deck.willKeep", have - 1) : null, have > 0,
                        () -> {
                            editor.remove(card, 1);
                            refreshDeck();
                            refreshCatalogue();
                        }),
                have > 1 ? new CardActionMenu.Action(NeoText.get("deck.removeAll"),
                        NeoText.get("deck.allCopies", have), true,
                        () -> {
                            editor.remove(card, have);
                            refreshDeck();
                            refreshCatalogue();
                        }) : null,
                
                editor.hasSideboard() && editor.copiesOfPrintingInMain(card) > 0
                        ? new CardActionMenu.Action(NeoText.get("deck.toSideboard"), null, true,
                                () -> {
                                    editor.moveMainToSideboard(card);
                                    refreshDeck();
                                    refreshCatalogue();
                                }) : null));
    }

    private CardActionMenu.Action coverAction(final PaperCard card) {
        if (editor.usesCommander()) {
            return null;
        }
        final boolean is = editor.isCover(card);
        return new CardActionMenu.Action(NeoText.get(is ? "deck.cover.unset" : "deck.cover.set"),
                is ? null : NeoText.get("deck.cover.set.detail"), true,
                () -> {
                    editor.toggleCover(card);
                    refreshDeck();
                });
    }

    private CardActionMenu.Action companionAction(final PaperCard card) {
        if (!editor.usesCompanion() || !DeckEditor.isCompanionCard(card)
                || card.equals(editor.companion())) {
            return null;
        }

        for (final PaperCard c : editor.commanders()) {
            if (c.getName().equals(card.getName())) {
                return null;
            }
        }
        return new CardActionMenu.Action(NeoText.get("deck.makeCompanion"),
                NeoText.get("deck.makeCompanion.detail"), true,
                () -> {
                    final String no = editor.setCompanion(card);
                    refreshDeck();
                    refreshCatalogue();
                    if (no != null) {
                        message(NeoText.get("deck.notCompanion.title"), no);
                    }
                });
    }

    private void companionMenu(final PaperCard card) {
        showCardMenu(card, CardActionMenu.actions(
                printingAction(card),
                foilAction(card),
                new CardActionMenu.Action(NeoText.get("deck.dropCompanion"), null, true,
                        () -> {
                            editor.removeCompanion();
                            refreshDeck();
                            refreshCatalogue();
                        })));
    }

    private void refreshCompanionRow() {
        companionRow.getChildren().clear();
        final PaperCard card = editor.companion();
        companionRow.setVisible(card != null);
        companionRow.setManaged(card != null);
        if (card == null) {
            return;
        }
        final Label tag = new Label(NeoText.get("deck.companion"));
        tag.getStyleClass().add("caption");
        tag.setMinWidth(Region.USE_PREF_SIZE);
        final Label name = new Label(CardText.nameOf(card));
        name.setWrapText(true);
        name.getStyleClass().add("builder-commander-name");
        final StackPane node = new StackPane(new DeckArtStrip(card), name);
        node.getStyleClass().add("builder-commander");

        node.pseudoClassStateChanged(INVALID, editor.illegalCards().contains(card));
        node.setMinHeight(UiScale.px(44));
        node.setPrefHeight(UiScale.px(44));
        node.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(node, Priority.ALWAYS);
        node.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                companionMenu(card);
            } else if (e.getButton() == MouseButton.PRIMARY) {
                zoom(card);
            }
        });
        companionRow.getChildren().addAll(tag, node);
    }

    private CardActionMenu.Action ownImageAction(final PaperCard card) {
        final String key = card.getImageKey(false);
        if (forge.neo.card.OwnImage.shownPath(key) == null) {
            return null;
        }
        return new CardActionMenu.Action(NeoText.get("zoom.ownImage"),
                NeoText.get("deck.ownImage.detail"), true, () -> {
                    final javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
                    fc.setTitle(NeoText.get("zoom.ownImage"));
                    fc.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter(
                            "JPG / PNG", "*.jpg", "*.jpeg", "*.png"));
                    final java.io.File chosen = fc.showOpenDialog(getScene() == null ? null : getScene().getWindow());
                    if (chosen == null) {
                        return;
                    }
                    try {
                        if (forge.neo.card.CardImages.useOwnImage(key, chosen) == null) {
                            message(NeoText.get("zoom.ownImage.failed"), chosen.getName());
                        }
                    } catch (final java.io.IOException ex) {
                        System.err.println("[neo-img] no se pudo copiar " + chosen + ": " + ex);
                        message(NeoText.get("zoom.ownImage.failed"), String.valueOf(ex.getMessage()));
                    }
                    refreshDeck();
                    refreshCatalogue();
                });
    }

    private CardActionMenu.Action restoreImageAction(final PaperCard card) {
        final String key = card.getImageKey(false);
        if (!forge.neo.card.OwnImage.isOwn(key)) {
            return null;
        }
        return new CardActionMenu.Action(NeoText.get("deck.ownImage.restore"),
                NeoText.get("deck.ownImage.restore.detail"), true, () -> {
                    forge.neo.card.CardImages.restoreOriginalImage(key);
                    refreshDeck();
                    refreshCatalogue();
                });
    }

    private CardActionMenu.Action printingAction(final PaperCard card) {

        final int printings = editor.allowsArtWishes()
                ? editor.allPrintingsOf(card).size() : editor.printingsOf(card).size();
        return new CardActionMenu.Action(NeoText.get("deck.changePrinting"),
                printings > 1 ? NeoText.get("deck.arts", printings)
                        : NeoText.get("printing.only"),
                printings > 1,
                () -> choosePrinting(card));
    }

    private CardActionMenu.Action foilAction(final PaperCard card) {
        final boolean toFoil = !card.isFoil();
        final boolean allowed = !toFoil || editor.canFoil(card);
        return new CardActionMenu.Action(
                NeoText.get(toFoil ? "deck.makeFoil" : "deck.removeFoil"),
                allowed ? null : NeoText.get("deck.foilLocked"),
                allowed,
                () -> {
                    final PaperCard target = toFoil ? card.getFoiled() : card.getUnFoiled();
                    if (editor.switchPrinting(card, target) > 0) {
                        refreshDeck();
                        refreshCatalogue();
                    }
                });
    }

    private void foilAll() {
        final boolean toFoil = !editor.isAllFoil();
        if (editor.setAllFoil(toFoil) > 0) {
            refreshDeck();
            refreshCatalogue();
        }
        refreshFoilAllButton();
    }

    private void refreshHouseRulesButton() {
        final boolean on = editor.isHouseRules();
        houseRulesButton.setText(NeoText.get(on ? "deck.houseRules.on" : "deck.houseRules"));
        houseRulesButton.getStyleClass().remove("ascent-button-on");
        if (on) {
            houseRulesButton.getStyleClass().add("ascent-button-on");
        }
        houseRulesButton.setTooltip(new javafx.scene.control.Tooltip(NeoText.get("deck.houseRules.tip")));
    }

    private CardActionMenu.Action secondCommanderAction(final PaperCard card) {
        if (!editor.canAddSecondCommander(card)) {
            return null;
        }
        return new CardActionMenu.Action(NeoText.get("deck.houseRules.secondCommander"),
                NeoText.get("deck.houseRules.secondCommander.detail"), true, () -> {
                    editor.addSecondCommander(card);
                    refreshDeck();
                    refreshCatalogue();
                });
    }

    private void refreshFoilAllButton() {
        final boolean all = editor.isAllFoil();
        foilAllButton.setText(NeoText.get(all ? "deck.foilAll.remove" : "deck.foilAll"));
        foilAllButton.setTooltip(new javafx.scene.control.Tooltip(NeoText.get(
                editor.isLimited() ? "deck.foilAll.limited" : "deck.foilAll.tip")));
    }

    private void showCardMenu(final PaperCard card, final List<CardActionMenu.Action> actions) {
        overlay.setOnBackgroundClick(overlay::hide);
        overlay.show(new CardActionMenu(card, actions, cardWidth,
                action -> {
                    overlay.hide();
                    action.run.run();
                },
                overlay::hide));
    }

    private void choosePrinting(final PaperCard card) {
        choosePrinting(card, false);
    }

    private void choosePrinting(final PaperCard card, final boolean all) {
        final boolean wishes = editor.allowsArtWishes();
        final List<PaperCard> printings = wishes && all ? editor.allPrintingsOf(card) : editor.printingsOf(card);
        if (printings.size() <= 1 && !wishes) {
            return;
        }
        overlay.setOnBackgroundClick(overlay::hide);
        final PrintingDialog[] dialog = new PrintingDialog[1];
        dialog[0] = new PrintingDialog(card, printings, cardWidth, picked -> {
            overlay.hide();

            final int changed = editor.switchPrinting(card, picked, dialog[0].copies());
            if (changed > 0) {
                refreshDeck();
                refreshCatalogue();
            }
        }, overlay::hide).offerCopies(editor.copiesOfPrintingInMain(card));
        if (wishes) {
            dialog[0].withSegments(List.of(NeoText.get("printing.mine"), NeoText.get("printing.all")),
                    all ? 1 : 0, which -> choosePrinting(card, which == 1));
            if (all) {
                dialog[0].withNote(p -> editor.ownsPrinting(p) ? null : NeoText.get("printing.notOwned"),
                        editor::ownsPrinting);
                dialog[0].withHelp(NeoText.get("printing.hint.wish"));
            }
        }
        overlay.show(dialog[0]);
    }

    private Button stepper(final String text, final Runnable action) {
        final Button b = new Button(text);
        b.getStyleClass().add("stepper");
        b.setOnAction(e -> action.run());
        
        b.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, javafx.event.Event::consume);
        return b;
    }

    private void refreshCommanderRow() {
        commanderRow.getChildren().clear();
        final List<PaperCard> cmd = editor.commanders();

        final boolean canGenerate = editor.usesCommander() && editor.mainCommander() != null;
        generate.setVisible(canGenerate);
        generate.setManaged(canGenerate);
        if (cmd.isEmpty()) {
            if (editor.usesCommander()) {
                final Label none = new Label(NeoText.get("deck.noCommanderYet"));
                none.getStyleClass().add("home-subtitle");
                commanderRow.getChildren().add(none);
            }
            return;
        }
        for (final PaperCard card : cmd) {
            final Label name = new Label(CardText.nameOf(card));
            name.setWrapText(true);
            name.getStyleClass().add("builder-commander-name");
            final StackPane node = new StackPane(new DeckArtStrip(card), name);
            node.getStyleClass().add("builder-commander");
            node.setMinHeight(UiScale.px(58));
            node.setPrefHeight(UiScale.px(58));
            node.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(node, Priority.ALWAYS);
            node.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.SECONDARY) {
                    commanderMenu(card, node, e.getScreenX(), e.getScreenY());
                } else if (e.getButton() == MouseButton.PRIMARY) {
                    zoom(card);
                }
            });
            commanderRow.getChildren().add(node);
        }
    }

    private void removeIllegal() {
        final List<PaperCard> bad = editor.illegalCards();
        if (bad.isEmpty()) {
            return;
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(NeoText.get(bad.size() == 1 ? "deck.willRemove.one" : "deck.willRemove"))
          .append(System.lineSeparator());
        final List<String> names = new ArrayList<>();
        for (final PaperCard c : bad) {
            names.add(CardText.nameOf(c));
        }
        appendSome(sb, names);

        overlay.setOnBackgroundClick(null);
        overlay.show(new ConfirmDialog(NeoText.get("deck.cleanup"), sb.toString(),
                java.util.List.of(NeoText.get("deck.removeThem"), NeoText.get("common.cancel")), 0,
                choice -> {
                    overlay.hide();
                    if (choice == 0) {
                        editor.removeIllegal();
                        refreshDeck();
                        refreshCatalogue();
                    }
                }));
    }

    private void removeMissing() {
        final java.util.Map<PaperCard, Integer> lacking = editor.missingCards();
        if (lacking.isEmpty()) {
            return;
        }
        int total = 0;
        final List<String> names = new ArrayList<>();
        for (final Map.Entry<PaperCard, Integer> e : lacking.entrySet()) {
            total += e.getValue();
            names.add(CardText.nameOf(e.getKey()) + (e.getValue() > 1 ? " \u00d7" + e.getValue() : ""));
        }
        final StringBuilder sb = new StringBuilder(total == 1 ? NeoText.get("deck.removeMissing.askOne")
                : NeoText.get("deck.removeMissing.ask", total))
                .append(System.lineSeparator());
        appendSome(sb, names);
        overlay.setOnBackgroundClick(null);
        overlay.show(new ConfirmDialog(NeoText.get("deck.removeMissing"), sb.toString(),
                java.util.List.of(NeoText.get("deck.removeThem"), NeoText.get("common.cancel")), 0,
                choice -> {
                    overlay.hide();
                    if (choice == 0) {
                        editor.removeMissing();
                        refreshDeck();
                        refreshCatalogue();
                    }
                }));
    }

    private void clearSideboard() {
        final int n = editor.sideboardWithoutCompanion();
        if (n <= 0) {
            return;
        }
        overlay.setOnBackgroundClick(null);
        overlay.show(new ConfirmDialog(NeoText.get("deck.clearSideboard", n),
                NeoText.get("deck.clearSideboard.ask", n),
                java.util.List.of(NeoText.get("deck.clearSideboard.yes"), NeoText.get("common.cancel")), 0,
                choice -> {
                    overlay.hide();
                    if (choice == 0) {
                        editor.clearSideboard();
                        refreshDeck();
                        refreshCatalogue();
                    }
                }));
    }

    private void importList() {
        overlay.show(TextDialog.block(NeoText.get("deck.import.title"),
                NeoText.get("deck.import.hint"), "", NeoText.get("deck.import.go"),
                text -> {
                    overlay.hide();

                    if (looksLikeDeckUrl(text)) {
                        applyImportUrl(text.trim());
                    } else {
                        applyImport(text);
                    }
                },
                overlay::hide));
    }

    private void applyImport(final String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        final DeckImporter.Result result =
                DeckImporter.importCommander(text, editor.getName());
        if (result.deck == null) {
            message(NeoText.get("deck.import.failed"),
                    NeoText.get("deck.import.failed.detail"));
            return;
        }
        applyImportedDeck(result.deck, result.unknown, result.problems);
    }

    private void applyImportedDeck(final Deck deck, final int unknown, final List<String> problems) {
        final List<String> badCommanders = new ArrayList<>();

        final List<PaperCard> companions = DeckImporter.companionsOf(deck, editor.usesCommander());
        for (final PaperCard c : deck.getCommanders()) {
            if (companions.contains(c)) {
                continue;
            }
            if (!editor.setCommander(c)) {
                badCommanders.add(CardText.nameOf(c));
            }
        }

        final List<String> newCommanders = new ArrayList<>();
        for (final PaperCard c : editor.commanders()) {
            newCommanders.add(CardText.nameOf(c));
        }

        int added = 0;
        for (final Map.Entry<PaperCard, Integer> e : deck.getMain()) {
            added += editor.addAnyway(e.getKey(), e.getValue());
        }

        final List<String> badCompanions = new ArrayList<>();
        boolean companionSet = false;
        for (final PaperCard c : companions) {
            if (companionSet) {
                break;
            }
            final String no = editor.setCompanion(c);
            if (no == null) {
                companionSet = true;
            } else {
                badCompanions.add(CardText.nameOf(c) + " - " + oneLine(no));
            }
        }

        refreshDeck();
        refreshCatalogue();

        final List<PaperCard> illegal = editor.illegalCards();
        final PaperCard companion = editor.companion();

        final int missing = editor.missingTotal();
        final String missingNote = missing == 0 ? "" : "\n\n" + NeoText.get("deck.import.missing", missing);
        if (unknown == 0 && illegal.isEmpty() && badCommanders.isEmpty() && badCompanions.isEmpty()) {
            message(NeoText.get("deck.import.ok"), NeoText.get("deck.import.added", added)
                    + (companion == null ? "" : "\n\n" + NeoText.get("deck.import.companion",
                            CardText.nameOf(companion)))
                    + missingNote);
            return;
        }

        final StringBuilder sb = new StringBuilder();
        sb.append(NeoText.get("deck.import.added", added)).append('\n');
        if (missing > 0) {
            sb.append(missingNote.substring(1)).append('\n');
        }

        if (!newCommanders.isEmpty()) {
            sb.append('\n').append(NeoText.get("deck.import.commander",
                    String.join(", ", newCommanders))).append('\n');
        }
        if (companion != null) {
            sb.append('\n').append(NeoText.get("deck.import.companion",
                    CardText.nameOf(companion))).append('\n');
        }
        if (!badCommanders.isEmpty()) {
            sb.append('\n').append(NeoText.get("deck.import.badCommander")).append(":\n");
            appendSome(sb, badCommanders);
        }
        if (!badCompanions.isEmpty()) {
            sb.append('\n').append(NeoText.get("deck.import.badCompanion")).append(":\n");
            appendSome(sb, badCompanions);
        }
        if (unknown > 0) {
            sb.append('\n').append(NeoText.get("deck.import.unknown", unknown))
              .append(":\n");
            appendSome(sb, problems);
        }
        if (!illegal.isEmpty()) {
            final List<String> names = new ArrayList<>();
            for (final PaperCard c : illegal) {
                final String why = editor.rejectionReason(c);
                names.add(CardText.nameOf(c) + (why == null ? "" : " - " + why));
            }
            sb.append('\n').append(NeoText.get("deck.import.kept")).append(":\n");
            appendSome(sb, names);
        }
        message(NeoText.get("deck.import.warnings"), sb.toString());
    }

    private static String explainUrlFailure(final String url, final String message) {
        final String site = forge.neo.deck.DeckUrlFailure.site(url);
        switch (forge.neo.deck.DeckUrlFailure.classify(message)) {
            case NOT_FOUND:
                return NeoText.get("deck.import.url.notFound", site);
            case BLOCKED:
                return NeoText.get("deck.import.url.blocked", site);
            case TOO_MANY:
                return NeoText.get("deck.import.url.tooMany", site);
            default:
                return message;
        }
    }

    private void applyImportUrl(final String url) {
        if (busyImportingUrl) {
            return;
        }
        busyImportingUrl = true;
        final String importerLabel = importer.getText();
        importer.setDisable(true);
        importer.setText(NeoText.get("deck.import.url.working"));

        final Thread t = new Thread(() -> {
            Deck deck = null;
            String failure = null;
            try {
                deck = forge.deck.DeckUrlLoader.load(url).getDeck();
            } catch (final java.io.IOException | RuntimeException e) {
                failure = explainUrlFailure(url, e.getMessage());
            }
            final Deck loaded = deck;
            final String why = failure;
            javafx.application.Platform.runLater(() -> {
                busyImportingUrl = false;
                importer.setDisable(false);
                importer.setText(importerLabel);
                if (loaded == null) {
                    message(NeoText.get("deck.import.failed"),
                            why == null ? NeoText.get("deck.import.failed.detail") : why);
                    return;
                }
                applyImportedDeck(loaded, 0, List.of());

                if (editor.getSavedAs() == null && editor.getFormat().canRename()
                        && loaded.getName() != null && !loaded.getName().isBlank()) {
                    editor.setName(editor.freeName(loaded.getName()));
                    refreshDeck();
                }
            });
        }, "Game-neo-deckurl");
        t.setDaemon(true);
        t.start();
    }

    private boolean busyImportingUrl;

    private static boolean looksLikeDeckUrl(final String text) {
        final String t = text == null ? "" : text.trim();
        if (t.isEmpty() || t.contains("\n") || t.contains(" ")) {
            return false;
        }
        final String lower = t.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://")
                || lower.contains("moxfield.com") || lower.contains("archidekt.com")
                || lower.contains("tappedout.net") || lower.contains("mtggoldfish.com");
    }

    private void generateDeck() {
        if (editor.mainCount() > 0) {
            confirmYesNo(NeoText.get("deck.generate.confirm.title"),
                    NeoText.get("deck.generate.confirm.body", editor.mainCount()),
                    this::doGenerateDeck);
        } else {
            doGenerateDeck();
        }
    }

    private void doGenerateDeck() {
        final int added = editor.generateForCommander();
        refreshDeck();
        refreshCatalogue();
        if (added < 0) {
            message(NeoText.get("deck.generate.failed.title"),
                    NeoText.get("deck.generate.failed.body"));
        }
    }

    private void autoBuildDeck() {
        if (editor.mainCount() > 0) {
            confirmYesNo(NeoText.get("deck.autoBuild.confirm.title"),
                    NeoText.get("deck.autoBuild.confirm.body", editor.mainCount()),
                    this::doAutoBuild);
        } else {
            doAutoBuild();
        }
    }

    private void doAutoBuild() {
        final int added = editor.autoBuildFromPool();
        refreshDeck();
        refreshCatalogue();
        if (added < 0) {
            message(NeoText.get("deck.generate.failed.title"),
                    NeoText.get("deck.autoBuild.failed.body"));
        }
    }

    public void autoBuildSaveLeaveForTest() {
        doAutoBuild();
        save();
        System.out.println("[lobby-auto] mazo montado: " + editor.getName()
                + " (" + editor.mainCount() + " cartas)");
        leave();
    }

    private void clearMainDeck() {
        if (editor.mainCount() == 0) {
            return;
        }
        confirmYesNo(NeoText.get("deck.clearMain.confirm.title"),
                NeoText.get("deck.clearMain.confirm.body", editor.mainCount()),
                () -> {
                    editor.clearToPool();
                    refreshDeck();
                    refreshCatalogue();
                });
    }

    private void confirmYesNo(final String title, final String body, final Runnable onYes) {
        final ConfirmDialog dialog = new ConfirmDialog(title, body,
                List.of(NeoText.get("common.yes"), NeoText.get("common.no")), 1,
                index -> {
                    overlay.hide();
                    if (index == 0) {
                        onYes.run();
                    }
                });
        overlay.show(dialog);
    }

    private static void appendSome(final StringBuilder sb, final List<String> items) {
        final int shown = Math.min(10, items.size());
        for (int i = 0; i < shown; i++) {
            sb.append("  ").append(items.get(i)).append('\n');
        }
        if (items.size() > shown) {
            sb.append("  ").append(NeoText.get("list.more", items.size() - shown))
              .append('\n');
        }
    }

    private void exportList() {
        overlay.show(TextDialog.block(NeoText.get("deck.export.title"),
                NeoText.get("deck.export.hint"),
                editor.toText(), NeoText.get("deck.export.copy"),
                text -> {
                    final javafx.scene.input.ClipboardContent content =
                            new javafx.scene.input.ClipboardContent();
                    content.putString(text);
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                    overlay.hide();
                },
                overlay::hide));
    }

    private void exportMissing() {
        final java.util.LinkedHashMap<forge.item.PaperCard, Integer> missing = editor.missingCards();
        int copies = 0;
        for (final int n : missing.values()) {
            copies += n;
        }
        overlay.show(TextDialog.block(NeoText.get("deck.exportMissing.title"),
                copies == 1 ? NeoText.get("deck.exportMissing.hintOne")
                        : NeoText.get("deck.exportMissing.hint", copies, missing.size()),
                editor.missingText(), NeoText.get("deck.export.copy"),
                text -> {
                    final javafx.scene.input.ClipboardContent content =
                            new javafx.scene.input.ClipboardContent();
                    content.putString(text);
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                    overlay.hide();
                },
                overlay::hide));
    }

    private void askName() {
        overlay.show(TextDialog.line(NeoText.get("deck.name.title"),
                NeoText.get("deck.name.hint"),
                editor.getName(),
                name -> {
                    overlay.hide();
                    rename(name);
                },
                overlay::hide));
    }

    private void askCopyName() {
        overlay.show(TextDialog.line(NeoText.get("deck.copy.title"),
                NeoText.get("deck.copy.hint"),
                editor.copyName(),
                name -> {
                    overlay.hide();
                    if (name == null || name.isBlank()) {
                        return;
                    }
                    if (!editor.saveAsCopy(name)) {
                        message(NeoText.get("deck.name.taken"), NeoText.get("deck.copy.taken", name.trim()));
                        return;
                    }
                    refreshDeck();
                    message(NeoText.get("deck.copy.ok"), NeoText.get("deck.copy.ok.detail", editor.getName()));
                },
                overlay::hide));
    }

    private void rename(final String name) {
        if (name == null || name.isBlank() || name.equals(editor.getName())) {
            return;
        }
        if (!editor.wouldOverwriteAnother(name.trim())) {
            editor.setName(name);
            refreshDeck();
            return;
        }
        overlay.setOnBackgroundClick(null);
        overlay.show(new ConfirmDialog(NeoText.get("deck.name.taken"),
                NeoText.get("deck.name.taken.detail", name.trim()),
                java.util.List.of(NeoText.get("deck.name.taken.yes"),
                        NeoText.get("common.cancel")), 1,
                choice -> {
                    overlay.hide();
                    if (choice != null && choice == 0) {
                        editor.setName(name);
                        refreshDeck();
                    }
                }));
    }

    private void save() {
        if (editor.getName() == null || editor.getName().isBlank()) {
            askName();
            return;
        }

        final String blocking = editor.blockingProblem();
        if (blocking != null) {
            overlay.setOnBackgroundClick(null);
            overlay.show(new ConfirmDialog(NeoText.get("deck.save.blocked"),
                    blocking + System.lineSeparator() + System.lineSeparator()
                    + NeoText.get("deck.save.blocked.detail"),
                    java.util.List.of(NeoText.get("deck.save.removeNow"),
                            NeoText.get("common.cancel")), 0,
                    choice -> {
                        overlay.hide();
                        if (choice == 0) {
                            editor.removeIllegal();
                            refreshDeck();
                            refreshCatalogue();
                            save();
                        }
                    }));
            return;
        }

        editor.save();
        refreshDeck();

        final String problem = editor.problem();
        if (problem == null) {
            message(NeoText.get("deck.save.ok"),
                    NeoText.get("deck.save.ok.detail", editor.getName()));
        } else {
            message(NeoText.get("deck.save.incomplete"),
                    NeoText.get("deck.save.incomplete.detail", editor.getName(),
                            editor.getFormat().getLabel())
                    + System.lineSeparator() + System.lineSeparator() + problem);
        }
    }

    private void leave() {
        if (!editor.isDirty()) {
            onBack.run();
            return;
        }
        overlay.setOnBackgroundClick(null);
        overlay.show(new ConfirmDialog(NeoText.get("deck.unsaved"),
                NeoText.get("deck.unsaved.detail", editor.getName()),
                java.util.List.of(NeoText.get("deck.unsaved.save"),
                        NeoText.get("deck.unsaved.leave"),
                        NeoText.get("deck.unsaved.stay")),
                0,
                choice -> {
                    overlay.hide();
                    if (choice == 2) {
                        return;
                    }
                    if (choice == 0) {
                        editor.save();
                    }
                    onBack.run();
                }));
    }

    private void catalogueMenu(final PaperCard card, final Region anchor,
                               final double screenX, final double screenY) {
        final List<CardActionMenu.Action> actions = new ArrayList<>();
        actions.add(new CardActionMenu.Action(NeoText.get("deck.viewCard"), () -> zoom(card)));

        final String no = editor.rejectionReason(card);
        final int room = editor.roomFor(card);
        actions.add(new CardActionMenu.Action(NeoText.get("deck.addOne"), no, no == null, () -> {
            editor.add(card, 1);
            refreshDeck();
            refreshCatalogue();
        }));
        if (no == null && room > 1) {
            final int many = Math.min(room, 4);
            actions.add(new CardActionMenu.Action(NeoText.get("deck.addMany", many), null, true, () -> {
                editor.add(card, many);
                refreshDeck();
                refreshCatalogue();
            }));
        }
        if (editor.hasSideboard()) {
            actions.add(new CardActionMenu.Action(NeoText.get("deck.addToSideboard"), no, no == null, () -> {
                final String why = editor.addToSideboard(card);
                refreshDeck();
                refreshCatalogue();
                if (why != null) {
                    message(NeoText.get("deck.noMoreCopies"), why);
                }
            }));
        }
        if (editor.countOf(card) > 0) {
            actions.add(new CardActionMenu.Action(NeoText.get("deck.removeOne"), null, true, () -> {
                editor.remove(card, 1);
                refreshDeck();
                refreshCatalogue();
            }));
        }
        if (editor.usesCommander()) {

            final boolean spell = editor.isSignatureSpell(card);
            final boolean can = spell
                    || editor.deckFormat().isLegalCommander(card.getRules());
            actions.add(new CardActionMenu.Action(
                    NeoText.get(spell ? "deck.makeSignature" : "deck.makeCommander"),
                    can ? null : NeoText.get("deck.cannotCommand",
                            editor.getFormat().getLabel()),
                    can, () -> {
                        editor.setCommander(card);
                        refreshDeck();

                        if (commanderMode) {
                            setCommanderMode(false);
                        } else {
                            refreshCatalogue();
                        }
                    }));
        }
        final CardActionMenu.Action second = secondCommanderAction(card);
        if (second != null) {
            actions.add(second);
        }
        final CardActionMenu.Action companion = companionAction(card);
        if (companion != null) {
            actions.add(companion);
        }
        
        for (final forge.neo.deck.DeckContext.Action extra : editor.contextActions(card)) {
            actions.add(new CardActionMenu.Action(extra.label(), extra.note(), extra.enabled(), () -> {
                extra.run().run();
                refreshDeck();
                refreshCatalogue();
            }));
        }

        overlay.setOnBackgroundClick(overlay::hide);

        overlay.show(new CardActionMenu(card, actions, menuCardWidth(cardWidth * 2.2),
                a -> {
                    overlay.hide();
                    a.run.run();
                }, overlay::hide));
    }

    private double menuCardWidth(final double wanted) {
        final double h = getHeight() > 0 ? getHeight()
                : getScene() != null ? getScene().getHeight() : 0;
        if (h <= 0) {
            return wanted;
        }
        return Math.min(wanted, h * 0.72 / (1.9 * forge.neo.card.CardNode.ASPECT));
    }

    private void zoom(final PaperCard card) {

        List<PaperCard> ring = deckOrder();
        int index = ring.indexOf(card);
        if (index < 0) {
            ring = new ArrayList<>(catalogueHits.subList(
                    Math.max(0, Math.min(pager.from(), catalogueHits.size())),
                    Math.max(0, Math.min(pager.to(), catalogueHits.size()))));
            index = ring.indexOf(card);
        }
        if (index < 0) {
            CardZoom.show(this, CardView.getCardForUi(card));
            return;
        }
        final List<PaperCard> list = ring;
        CardZoom.show(this, list.size(), index, i -> CardView.getCardForUi(list.get(i)));
    }

    private List<PaperCard> deckOrder() {
        final List<PaperCard> out = new ArrayList<>(editor.commanders());
        for (final String group : editor.typeCounts().keySet()) {
            for (final Map.Entry<PaperCard, Integer> e : editor.cardsInGroup(group)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private void refreshSleeveButton() {
        final String id = editor.getSleeve();
        final forge.neo.look.LookItem item = id == null
                ? forge.neo.look.NeoLook.currentSleeve()
                : forge.neo.look.NeoLook.sleeveOf(editor.getDeck());
        sleeveButton.setText(NeoText.get(id == null ? "deck.sleeve.default" : "deck.sleeve"));

        final javafx.scene.image.Image image = item.image();
        if (image == null) {
            sleeveButton.setGraphic(null);
            return;
        }
        final javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(image);
        view.setFitWidth(UiScale.px(20));
        view.setFitHeight(UiScale.px(28));
        view.setPreserveRatio(false);
        view.setSmooth(true);
        sleeveButton.setGraphic(view);
    }

    private void askSleeve() {
        final Label heading = new Label(NeoText.get("deck.sleeve.title"));
        heading.getStyleClass().add("dialog-title");

        final Label hint = new Label(NeoText.get("deck.sleeve.hint"));
        hint.getStyleClass().add("dialog-text");
        hint.setWrapText(true);
        hint.setMaxWidth(UiScale.px(560));
        hint.setMinHeight(Region.USE_PREF_SIZE);

        final String current = editor.getSleeve();
        final FlowPane grid = new FlowPane(10, 10);
        grid.setAlignment(Pos.TOP_LEFT);
        grid.setPrefWrapLength(UiScale.px(560));
        grid.getChildren().add(sleeveTile(null, NeoText.get("deck.sleeve.yours"), current));
        for (final forge.neo.look.LookItem item : forge.neo.look.NeoLook.sleeves()) {
            grid.getChildren().add(sleeveTile(item, null, current));
        }

        final ScrollPane scroll = new ScrollPane(grid);
        scroll.getStyleClass().add("dialog-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportHeight(UiScale.px(360));

        final Button close = new Button(NeoText.get("common.back"));
        close.getStyleClass().add("btn-secondary");
        close.setOnAction(e -> overlay.hide());

        final HBox footer = new HBox(close);
        footer.setAlignment(Pos.CENTER_RIGHT);

        final VBox box = new VBox(12, heading, hint, scroll, footer);
        box.getStyleClass().add("dialog");
        box.setPadding(new Insets(22, 26, 20, 26));
        box.setMaxWidth(Region.USE_PREF_SIZE);
        box.setMaxHeight(Region.USE_PREF_SIZE);
        overlay.setOnBackgroundClick(overlay::hide);
        overlay.show(box);
    }

    private Region sleeveTile(final forge.neo.look.LookItem item, final String caption,
                              final String current) {
        final StackPane face = new StackPane();
        face.getStyleClass().add("look-tile");
        face.setPrefSize(UiScale.px(72), UiScale.px(100));
        face.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        face.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        face.pseudoClassStateChanged(PICKED,
                item == null ? current == null : item.getId().equals(current));

        final javafx.scene.image.Image image = item == null ? null : item.image();
        if (image != null) {
            final javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(image);
            view.setFitWidth(UiScale.px(66));
            view.setFitHeight(UiScale.px(94));
            view.setPreserveRatio(false);
            view.setSmooth(true);
            face.getChildren().add(view);
        } else {
            final Label label = new Label(caption == null ? "?" : caption);
            label.getStyleClass().add("home-subtitle");
            label.setWrapText(true);
            label.setMaxWidth(UiScale.px(62));
            label.setMinHeight(Region.USE_PREF_SIZE);
            label.setAlignment(Pos.CENTER);
            face.getChildren().add(label);
        }

        face.setCursor(javafx.scene.Cursor.HAND);
        face.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            editor.setSleeve(item == null ? null : item.getId());
            refreshSleeveButton();
            refreshDeck();
            overlay.hide();
        });
        return face;
    }

    private void message(final String title, final String body) {
        final Label heading = new Label(title);
        heading.getStyleClass().add("dialog-title");

        final Label text = new Label(body);
        text.getStyleClass().add("dialog-text");
        text.setWrapText(true);
        text.setMaxWidth(UiScale.px(460));
        text.setMinHeight(Region.USE_PREF_SIZE);

        final Button ok = new Button(NeoText.get("banner.understood"));
        ok.getStyleClass().add("btn-primary");
        ok.setOnAction(e -> overlay.hide());

        final HBox footer = new HBox(ok);
        footer.setAlignment(Pos.CENTER_RIGHT);

        final VBox box = new VBox(12, heading, text, footer);
        box.getStyleClass().add("dialog");
        box.setPadding(new Insets(22, 26, 20, 26));
        box.setMaxWidth(Region.USE_PREF_SIZE);
        box.setMaxHeight(Region.USE_PREF_SIZE);
        overlay.setOnBackgroundClick(null);
        overlay.show(box);
    }

    public void refreshArt() {

        CardNode.refreshAllIn(this);
        for (final javafx.scene.Node node : lookupAll(".deck-art-strip")) {
            if (node instanceof DeckArtStrip strip) strip.refresh();
        }
    }

    public void saveLeaveForTest(final String name) {
        editor.setName(name);
        save();
        System.out.println("[mazo] guardado " + editor.getName() + " (" + editor.mainCount() + " cartas)");
        leave();
    }

    public DeckEditor editorForTest() {
        return editor;
    }

    public void generateForTest() {
        generate.fire();
    }

    public void showSleevePicker() {
        askSleeve();
    }

    public void toggleCommanderModeForTest() {
        commanderButton.fire();
    }

    public void pickFirstCandidateForTest() {
        if (!commanderMode || catalogueHits.isEmpty()) {
            System.out.println("[mazo] no hay selector abierto");
            return;
        }
        final PaperCard card = catalogueHits.get(0);
        if (!editor.setCommander(card)) {
            System.out.println("[mazo] rechazada: " + card.getName());
            return;
        }
        System.out.printf("[mazo] elegida %s para el hueco %s%n",
                card.getName(), pickingSpell ? "del hechizo" : "del comandante");
        refreshDeck();
        if (!pickingSpell && editor.usesSignatureSpell()
                && editor.signatureSpell() == null) {
            openPicker(true, true);
            System.out.println("[mazo] encadena al hechizo insignia");
            return;
        }
        setCommanderMode(false);
    }

    public void showFirstCardMenu() {
        for (final Map.Entry<String, Integer> group : editor.typeCounts().entrySet()) {
            final List<Map.Entry<PaperCard, Integer>> cards =
                    editor.cardsInGroup(group.getKey());
            if (!cards.isEmpty()) {
                cardMenu(cards.get(0).getKey(), this, 0, 0);
                return;
            }
        }
    }

    public void zoomFirstCardForTest() {
        for (final Map.Entry<String, Integer> group : editor.typeCounts().entrySet()) {
            final List<Map.Entry<PaperCard, Integer>> cards =
                    editor.cardsInGroup(group.getKey());
            if (!cards.isEmpty()) {
                zoom(cards.get(0).getKey());
                return;
            }
        }
    }

    public void choosePrintingForTest() {
        for (final Map.Entry<String, Integer> group : editor.typeCounts().entrySet()) {
            for (final Map.Entry<PaperCard, Integer> e : editor.cardsInGroup(group.getKey())) {
                if (e.getValue() > 1 && (editor.printingsOf(e.getKey()).size() > 1
                        || (editor.allowsArtWishes() && !e.getKey().isVeryBasicLand()))) {
                    choosePrinting(e.getKey(), Boolean.getBoolean("neo.printing.all"));
                    return;
                }
            }
        }
    }

    public void searchFor(final String query) {
        search.setText(query);
        refreshCatalogue();
    }

    public void pasteList(final String text) {
        applyImport(text);
    }

    public void showVisualView() {
        if (visualButton != null) {
            visualButton.fire();
        }
    }

    public Deck getDeck() {
        return editor.getDeck();
    }

    private static final javafx.css.PseudoClass SELECTED =
            javafx.css.PseudoClass.getPseudoClass("selected");
    private static final javafx.css.PseudoClass INVALID =
            javafx.css.PseudoClass.getPseudoClass("invalid");
    
    private static final javafx.css.PseudoClass PICKED =
            javafx.css.PseudoClass.getPseudoClass("picked");
}
