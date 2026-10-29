package forge.neo.ui;

import forge.neo.MenuLayout;
import forge.neo.NeoText;
import forge.neo.match.NeoFormat;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public class MainMenu extends BorderPane {

    public interface Actions {

        void play(NeoFormat format);

        void library();

        void look();

        default void replays() {
        }

        void settings();

        void quit();
    }

    public MainMenu(final Actions actions) {
        getStyleClass().addAll("table-root", "home");

        final Label title = new Label(NeoText.get("menu.title"));
        title.getStyleClass().add("home-title");
        final Label subtitle = new Label(NeoText.get("menu.subtitle"));
        subtitle.getStyleClass().add("home-subtitle");

        final VBox words = new VBox(2, title, subtitle);
        words.setAlignment(Pos.CENTER_LEFT);

        final javafx.scene.image.ImageView mark = forge.neo.NeoLogo.view(76);
        final HBox header = mark == null ? new HBox(words) : new HBox(16, mark, words);
        header.setAlignment(Pos.CENTER);

        final javafx.scene.layout.GridPane top = new javafx.scene.layout.GridPane();
        for (final double pct : new double[] {30, 40, 30}) {
            final javafx.scene.layout.ColumnConstraints c =
                    new javafx.scene.layout.ColumnConstraints();
            c.setPercentWidth(pct);
            top.getColumnConstraints().add(c);
        }
        final Region language = languagePicker();

        final Button itchButton = itchButton();
        final javafx.scene.shape.Circle itchDot = new javafx.scene.shape.Circle(5);
        itchDot.getStyleClass().add("itch-dot");
        itchDot.setMouseTransparent(true);
        itchDot.setTranslateX(4);
        itchDot.setTranslateY(-4);
        final javafx.scene.layout.StackPane itch =
                new javafx.scene.layout.StackPane(itchButton, itchDot);
        javafx.scene.layout.StackPane.setAlignment(itchDot, Pos.TOP_RIGHT);

        itch.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        final HBox discord = new HBox(8, itch, discordButton(), kofiButton());
        discord.setAlignment(Pos.CENTER_RIGHT);
        top.add(language, 0, 0);
        top.add(header, 1, 0);
        top.add(discord, 2, 0);
        javafx.scene.layout.GridPane.setValignment(language, javafx.geometry.VPos.CENTER);
        top.setPadding(new Insets(12, 30, 8, 30));

        modes = new FlowPane(16, 16);
        modes.setAlignment(Pos.CENTER);
        modes.setPadding(new Insets(10, 40, 10, 40));

        modes.setPrefWrapLength(UiScale.px(3 * MAX_CARD + 32));

        tile("commander", formatTile(NeoFormat.COMMANDER, actions));

        tile("library", modeCard(ModeArt.Mode.LIBRARY, NeoText.get("menu.library"),
                NeoText.get("menu.library.desc"),
                NeoText.get("menu.library.note",
                        forge.model.FModel.getMagicDb().getCommonCards().getUniqueCards().size()),
                true, actions::library));

        tile("look", modeCard(ModeArt.Mode.LOOK, NeoText.get("menu.look"),
                NeoText.get("menu.look.desc"),
                lookNote(), true, actions::look));

        for (final java.util.Map.Entry<String, Region> t : tiles.entrySet()) {
            t.getValue().setOnContextMenuRequested(e -> {
                tileMenu(t.getKey(), t.getValue(), e.getScreenX(), e.getScreenY());
                e.consume();
            });
        }

        editHint.getStyleClass().add("menu-edit-hint");
        editHint.setWrapText(true);
        editHint.setMaxWidth(Double.MAX_VALUE);
        editHint.setAlignment(Pos.CENTER);
        VBox.setMargin(editHint, new Insets(4, 40, 0, 40));

        final VBox centre = new VBox(top, editHint, modes);
        centre.setAlignment(Pos.CENTER);

        final ScrollPane scroll = new ScrollPane(centre);
        scroll.getStyleClass().add("dialog-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        setCenter(scroll);

        scroll.viewportBoundsProperty().addListener((o, a, b) -> fitCards(modes, b.getWidth()));
        modes.getChildren().addListener((javafx.collections.ListChangeListener<javafx.scene.Node>) ch ->
                fitCards(modes, scroll.getViewportBounds().getWidth()));

        final Button settings = new Button(NeoText.get("common.settings"));
        settings.getStyleClass().add("btn-secondary");
        settings.setOnAction(e -> actions.settings());

        final Button replays = new Button(NeoText.get("menu.replays"));
        replays.getStyleClass().add("btn-secondary");
        replays.setOnAction(e -> actions.replays());

        final Button quit = new Button(NeoText.get("common.quit"));
        quit.getStyleClass().add("btn-secondary");
        quit.setOnAction(e -> actions.quit());

        final String engine = forge.neo.NeoVersion.label();
        final Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        final HBox footer = new HBox(10);
        if (engine != null) {

            final javafx.scene.control.TextField copiable =
                    new javafx.scene.control.TextField(engine);
            copiable.setEditable(false);
            copiable.getStyleClass().add("home-version");

            copiable.setPrefWidth(new javafx.scene.text.Text(engine)
                    .getLayoutBounds().getWidth() * UiScale.growth() + UiScale.px(18));
            footer.getChildren().add(copiable);
        }

        final HBox update = new HBox();
        update.getStyleClass().add("update-banner");
        update.setAlignment(Pos.CENTER_LEFT);
        showUpdate(update, itchButton, itchDot);
        forge.neo.update.NeoUpdate.whenKnown(() -> showUpdate(update, itchButton, itchDot));
        footer.getChildren().add(update);

        editButton.setOnAction(e -> {
            editing = !editing;
            relayout();
        });
        resetButton.getStyleClass().add("btn-secondary");
        resetButton.setOnAction(e -> {
            MenuLayout.reset();
            relayout();
        });
        footer.getChildren().addAll(gap, resetButton, editButton, replays, settings, quit);
        footer.getStyleClass().add("home-footer");
        footer.setPadding(new Insets(12, 30, 16, 30));
        footer.setAlignment(Pos.CENTER_RIGHT);
        setBottom(footer);

        editing = Boolean.getBoolean("neo.menu.edit");
        relayout();
    }

    private final FlowPane modes;

    private final java.util.LinkedHashMap<String, Region> tiles = new java.util.LinkedHashMap<>();

    private final java.util.Map<String, Region> editLayers = new java.util.HashMap<>();

    private final Label editHint = new Label(NeoText.get("menu.edit.hint"));
    private final Button editButton = new Button();
    private final Button resetButton = new Button(NeoText.get("menu.edit.reset"));

    private boolean editing;

    private String dragging;

    private void tile(final String id, final Region tile) {
        tiles.put(id, tile);

        tile.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (editing) {
                e.consume();
            }
        });
    }

    private java.util.List<String> factoryOrder() {
        return new java.util.ArrayList<>(tiles.keySet());
    }

    private void relayout() {
        final java.util.List<String> defaults = factoryOrder();
        final java.util.List<String> ids = editing
                ? MenuLayout.order(defaults) : MenuLayout.visible(defaults);
        final java.util.List<javafx.scene.Node> kids = new java.util.ArrayList<>();

        for (final java.util.Map.Entry<String, Region> t : tiles.entrySet()) {
            decorate(t.getKey(), t.getValue());
        }
        for (final String id : ids) {
            kids.add(tiles.get(id));
        }
        modes.getChildren().setAll(kids);

        editHint.setVisible(editing);
        editHint.setManaged(editing);
        editButton.setText(NeoText.get(editing ? "menu.edit.done" : "menu.edit"));
        editButton.getStyleClass().removeAll("btn-primary", "btn-secondary");
        editButton.getStyleClass().add(editing ? "btn-primary" : "btn-secondary");
        resetButton.setVisible(editing);
        resetButton.setManaged(editing);
        resetButton.setDisable(!MenuLayout.customized(defaults));
    }

    private void decorate(final String id, final Region tile) {
        if (!(tile instanceof javafx.scene.layout.StackPane box)) {
            return;
        }
        final Region layer = editLayers.computeIfAbsent(id, this::editLayer);
        final boolean dim = editing && MenuLayout.isHidden(id);
        box.getChildren().remove(layer);
        for (final javafx.scene.Node n : box.getChildren()) {
            n.setOpacity(dim ? 0.35 : 1);
        }
        if (editing) {
            box.getChildren().add(layer);
            refreshLayer(id, layer);
        }
    }

    private Region editLayer(final String id) {
        final boolean rtl = Rtl.on();
        
        final Button earlier = layerButton(rtl ? "\u2192" : "\u2190", NeoText.get("menu.edit.earlier"));
        final Button later = layerButton(rtl ? "\u2190" : "\u2192", NeoText.get("menu.edit.later"));
        final Button hide = new Button();
        hide.getStyleClass().add("menu-edit-button");
        earlier.setOnAction(e -> {
            MenuLayout.move(factoryOrder(), id, -1);
            relayout();
        });
        later.setOnAction(e -> {
            MenuLayout.move(factoryOrder(), id, 1);
            relayout();
        });
        hide.setOnAction(e -> {
            MenuLayout.setHidden(factoryOrder(), id, !MenuLayout.isHidden(id));
            relayout();
        });
        final Label hiddenMark = new Label(NeoText.get("menu.edit.hidden"));
        hiddenMark.getStyleClass().add("menu-edit-hidden");

        final HBox bar = new HBox(UiScale.px(6), hiddenMark, earlier, later, hide);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(UiScale.px(10)));
        bar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        final javafx.scene.layout.StackPane layer = new javafx.scene.layout.StackPane(bar);
        javafx.scene.layout.StackPane.setAlignment(bar, Pos.TOP_RIGHT);
        layer.getStyleClass().add("menu-edit-layer");
        layer.setUserData(new Object[] {hide, hiddenMark, earlier, later});
        layer.setCursor(javafx.scene.Cursor.MOVE);
        
        layer.setOnMouseClicked(javafx.event.Event::consume);

        layer.setOnDragDetected(e -> {
            final javafx.scene.input.Dragboard db =
                    layer.startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
            final javafx.scene.input.ClipboardContent content =
                    new javafx.scene.input.ClipboardContent();
            content.putString(id);
            db.setContent(content);
            final javafx.scene.SnapshotParameters sp = new javafx.scene.SnapshotParameters();
            sp.setFill(javafx.scene.paint.Color.TRANSPARENT);
            sp.setTransform(javafx.scene.transform.Transform.scale(0.5, 0.5));
            db.setDragView(tiles.get(id).snapshot(sp, null), e.getX() / 2, e.getY() / 2);
            dragging = id;
            e.consume();
        });
        layer.setOnDragOver(e -> {
            if (dragging != null && !dragging.equals(id)) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
            }
            e.consume();
        });
        layer.setOnDragEntered(e -> {
            if (dragging != null && !dragging.equals(id)) {
                layer.getStyleClass().add("menu-edit-drop");
            }
        });
        layer.setOnDragExited(e -> layer.getStyleClass().remove("menu-edit-drop"));
        layer.setOnDragDropped(e -> {
            final boolean ok = dragging != null && !dragging.equals(id);
            if (ok) {
                MenuLayout.moveTo(factoryOrder(), dragging, id);
            }
            e.setDropCompleted(ok);
            e.consume();

            if (ok) {
                javafx.application.Platform.runLater(this::relayout);
            }
        });
        layer.setOnDragDone(e -> dragging = null);
        return layer;
    }

    private void refreshLayer(final String id, final Region layer) {
        final Object[] parts = (Object[]) layer.getUserData();
        final Button hide = (Button) parts[0];
        final Label hiddenMark = (Label) parts[1];
        final boolean hidden = MenuLayout.isHidden(id);
        final java.util.List<String> all = MenuLayout.order(factoryOrder());
        hide.setText(NeoText.get(hidden ? "menu.edit.show" : "menu.edit.hide"));
        hide.setDisable(!MenuLayout.canHide(factoryOrder(), id));
        hiddenMark.setVisible(hidden);
        hiddenMark.setManaged(hidden);
        ((Button) parts[2]).setDisable(all.indexOf(id) <= 0);
        ((Button) parts[3]).setDisable(all.indexOf(id) >= all.size() - 1);
    }

    private static Button layerButton(final String glyph, final String tip) {
        final Button b = new Button(glyph);
        b.getStyleClass().add("menu-edit-button");
        b.setTooltip(new javafx.scene.control.Tooltip(tip));
        b.setAccessibleText(tip);
        return b;
    }

    private void tileMenu(final String id, final Region tile, final double x, final double y) {
        if (editing) {
            return;
        }
        final javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        menu.getStyleClass().add("card-menu");
        final javafx.scene.control.MenuItem hide =
                new javafx.scene.control.MenuItem(NeoText.get("menu.edit.hideThis"));
        hide.setDisable(!MenuLayout.canHide(factoryOrder(), id));
        hide.setOnAction(e -> {
            MenuLayout.setHidden(factoryOrder(), id, true);
            relayout();
        });
        final javafx.scene.control.MenuItem order =
                new javafx.scene.control.MenuItem(NeoText.get("menu.edit") + "\u2026");
        order.setOnAction(e -> {
            editing = true;
            relayout();
        });
        menu.getItems().addAll(hide, order);
        menu.show(tile, x, y);
    }

    private static Region languagePicker() {
        final java.util.List<forge.neo.NeoLanguage.Option> langs =
                forge.neo.NeoLanguage.available();
        final String current = forge.neo.NeoLanguage.current();

        final Button button = new Button();
        button.getStyleClass().add("segment");
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setGraphicTextGap(6);
        button.setTooltip(new javafx.scene.control.Tooltip(NeoText.get("menu.language")));
        button.setAccessibleText(NeoText.get("menu.language"));

        final Label note = new Label();
        note.getStyleClass().add("home-subtitle");
        note.setWrapText(true);
        note.setVisible(false);
        note.setManaged(false);

        final javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        menu.getStyleClass().add("card-menu");
        final javafx.scene.control.ToggleGroup group = new javafx.scene.control.ToggleGroup();
        for (final forge.neo.NeoLanguage.Option option : langs) {
            final javafx.scene.control.RadioMenuItem item =
                    new javafx.scene.control.RadioMenuItem(option.getLabel());

            item.setGraphic(FlagIcon.of(option.getId(), UiScale.px(12)));
            item.setToggleGroup(group);
            item.setSelected(option.getId().equals(current));
            item.setOnAction(e -> {
                forge.neo.NeoLanguage.set(option.getId());
                showLanguage(button, option);
                note.setText(NeoText.get("menu.language.restart", option.getLabel()));
                note.setVisible(true);
                note.setManaged(true);
            });
            menu.getItems().add(item);
            if (option.getId().equals(current)) {
                showLanguage(button, option);
            }
        }
        if (button.getText() == null || button.getText().isEmpty()) {
            button.setText(NeoText.get("menu.language"));
        }
        button.setOnAction(e -> menu.show(button, javafx.geometry.Side.BOTTOM, 0, 4));

        final VBox box = new VBox(6, button, note);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static void showLanguage(final Button button,
                                     final forge.neo.NeoLanguage.Option option) {
        button.setText(option.getLabel() + "  ▾");
        button.setGraphic(FlagIcon.of(option.getId(), UiScale.px(12)));
    }

    static final String DISCORD_URL = "https://discord.gg/fF5Tn7Z2pv";

    private static Button discordButton() {
        
        final javafx.scene.shape.SVGPath logo = new javafx.scene.shape.SVGPath();
        logo.setContent("M107.7,8.07A105.15,105.15,0,0,0,81.47,0a72.06,72.06,0,0,0-3.36,6.83"
                + "A97.68,97.68,0,0,0,49,6.83,72.37,72.37,0,0,0,45.64,0,105.89,105.89,0,0,0,19.39,8.09"
                + "C2.79,32.65-1.71,56.6.54,80.21h0A105.73,105.73,0,0,0,32.71,96.36,77.7,77.7,0,0,0,39.6,85.25"
                + "a68.42,68.42,0,0,1-10.85-5.18c.91-.66,1.8-1.34,2.66-2a75.57,75.57,0,0,0,64.32,0"
                + "c.87.71,1.76,1.39,2.66,2a68.68,68.68,0,0,1-10.87,5.19,77,77,0,0,0,6.89,11.1"
                + "A105.25,105.25,0,0,0,126.6,80.22h0C129.24,52.84,122.09,29.11,107.7,8.07Z"
                + "M42.45,65.69C36.18,65.69,31,60,31,53s5-12.74,11.43-12.74S54,46,53.89,53,48.84,65.69,42.45,65.69Z"
                + "m42.24,0C78.41,65.69,73.25,60,73.25,53s5-12.74,11.44-12.74S96.23,46,96.12,53,91.08,65.69,84.69,65.69Z");
        logo.getStyleClass().add("discord-logo");

        final double scale = 22 / 127.14;
        logo.setScaleX(scale);
        logo.setScaleY(scale);

        final Button b = new Button();
        b.setGraphic(new javafx.scene.Group(logo));
        b.getStyleClass().add("discord-button");
        b.setTooltip(new javafx.scene.control.Tooltip(NeoText.get("menu.discord")));
        b.setAccessibleText(NeoText.get("menu.discord"));
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setOnAction(e -> {
            try {
                forge.gui.GuiBase.getInterface().browseToUrl(DISCORD_URL);
            } catch (final Exception ex) {
                System.err.println("[neo] no se ha podido abrir Discord: " + ex);
            }
        });
        return b;
    }

    static final String KOFI_URL = "https://ko-fi.com/dokkodolabs";

    static Button kofiButton() {
        final javafx.scene.shape.SVGPath logo = new javafx.scene.shape.SVGPath();
        logo.setContent("M23.881 8.948c-.773-4.085-4.859-4.593-4.859-4.593H.723c-.604 0-.679.798-.679.798"
                + "s-.082 7.324-.022 11.822c.164 2.424 2.586 2.672 2.586 2.672s8.267-.023 11.966-.049"
                + "c2.438-.426 2.683-2.566 2.658-3.734 4.352.24 7.422-2.831 6.649-6.916zm-11.062 3.511"
                + "c-1.246 1.453-4.011 3.976-4.011 3.976s-.121.119-.31.023c-.076-.057-.108-.09-.108-.09"
                + "-.443-.441-3.368-3.049-4.034-3.954-.709-.965-1.041-2.7-.091-3.71.951-1.01 3.005-1.086 "
                + "4.363.407 0 0 1.565-1.782 3.468-.963 1.904.82 1.832 3.011.723 4.311zm6.173.478"
                + "c-.928.116-1.682.028-1.682.028V7.284h1.77s1.971.551 1.971 2.638c0 1.913-.985 2.667"
                + "-2.059 3.015z");
        logo.getStyleClass().add("kofi-logo");

        final javafx.scene.shape.SVGPath heart = new javafx.scene.shape.SVGPath();
        heart.setContent("M12.819 12.459c-1.246 1.453-4.011 3.976-4.011 3.976s-.121.119-.31.023"
                + "c-.076-.057-.108-.09-.108-.09-.443-.441-3.368-3.049-4.034-3.954-.709-.965-1.041-2.7"
                + "-.091-3.71.951-1.01 3.005-1.086 4.363.407 0 0 1.565-1.782 3.468-.963 1.904.82 1.832 "
                + "3.011.723 4.311z");
        heart.getStyleClass().add("kofi-heart");

        final javafx.scene.Group cup = new javafx.scene.Group(logo, heart);
        final double scale = (22 * 96.36 / 127.14) / 14.5;
        cup.setScaleX(scale);
        cup.setScaleY(scale);

        final Button b = new Button();
        b.setGraphic(new javafx.scene.Group(cup));
        b.getStyleClass().add("kofi-button");
        b.setTooltip(new javafx.scene.control.Tooltip(NeoText.get("menu.kofi")));
        b.setAccessibleText(NeoText.get("menu.kofi"));
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setOnAction(e -> {
            try {
                forge.gui.GuiBase.getInterface().browseToUrl(KOFI_URL);
            } catch (final Exception ex) {
                System.err.println("[neo] no se ha podido abrir Ko-fi: " + ex);
            }
        });
        return b;
    }

    static Button itchButton() {
        final javafx.scene.shape.SVGPath logo = new javafx.scene.shape.SVGPath();
        logo.setContent("M3.13 1.338C2.08 1.96.02 4.328 0 4.95v1.03c0 1.303 1.22 2.45 2.325 2.45 "
                + "1.33 0 2.436-1.102 2.436-2.41 0 1.308 1.07 2.41 2.4 2.41 1.328 0 2.362-1.102 "
                + "2.362-2.41 0 1.308 1.137 2.41 2.466 2.41h.024c1.33 0 2.466-1.102 2.466-2.41 0 "
                + "1.308 1.034 2.41 2.363 2.41 1.33 0 2.4-1.102 2.4-2.41 0 1.308 1.106 2.41 2.435 "
                + "2.41C22.78 8.43 24 7.282 24 5.98V4.95c-.02-.62-2.082-2.99-3.13-3.612-3.253-.114"
                + "-5.508-.134-8.87-.133-3.362 0-7.945.053-8.87.133zm6.376 6.477a2.74 2.74 0 0 1-.468"
                + ".602c-.5.49-1.19.795-1.947.795a2.786 2.786 0 0 1-1.95-.795c-.182-.178-.32-.37-.446"
                + "-.59-.127.222-.303.412-.486.59a2.788 2.788 0 0 1-1.95.795c-.092 0-.187-.025-.264"
                + "-.052-.107 1.113-.152 2.176-.168 2.95v.005l-.006 1.167c.02 2.334-.23 7.564 1.03 "
                + "8.85 1.952.454 5.545.662 9.15.663 3.605 0 7.198-.21 9.15-.664 1.26-1.284 1.01"
                + "-6.514 1.03-8.848l-.006-1.167v-.004c-.016-.775-.06-1.838-.168-2.95-.077.026-.172"
                + ".052-.263.052a2.788 2.788 0 0 1-1.95-.795c-.184-.178-.36-.368-.486-.59-.127.22"
                + "-.265.412-.447.59a2.786 2.786 0 0 1-1.95.794c-.76 0-1.446-.303-1.948-.793a2.74 "
                + "2.74 0 0 1-.468-.602 2.738 2.738 0 0 1-.463.602 2.787 2.787 0 0 1-1.95.794h-.16"
                + "a2.787 2.787 0 0 1-1.95-.793 2.738 2.738 0 0 1-.464-.602zm-2.004 2.59v.002c.795"
                + ".002 1.5 0 2.373.953.687-.072 1.406-.108 2.125-.107.72 0 1.438.035 2.125.107.873"
                + "-.953 1.578-.95 2.372-.953.376 0 1.876 0 2.92 2.934l1.123 4.028c.832 2.995-.266 "
                + "3.068-1.636 3.07-2.03-.075-3.156-1.55-3.156-3.025-1.124.184-2.436.276-3.748.277"
                + "-1.312 0-2.624-.093-3.748-.277 0 1.475-1.125 2.95-3.156 3.026-1.37-.004-2.468"
                + "-.077-1.636-3.072l1.122-4.027c1.045-2.934 2.545-2.934 2.92-2.934zM12 12.714c"
                + "-.002.002-2.14 1.964-2.523 2.662l1.4-.056v1.22c0 .056.56.033 1.123.007.562.026 "
                + "1.124.05 1.124-.008v-1.22l1.4.055C14.138 14.677 12 12.713 12 12.713z");
        logo.getStyleClass().add("itch-logo");

        final double scale = (22 * 96.36 / 127.14) / 21.5;
        logo.setScaleX(scale);
        logo.setScaleY(scale);

        final Button b = new Button();
        b.setGraphic(new javafx.scene.Group(logo));
        b.getStyleClass().add("itch-button");
        b.setTooltip(new javafx.scene.control.Tooltip(NeoText.get("menu.itch")));
        b.setAccessibleText(NeoText.get("menu.itch"));
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setOnAction(e -> openItch());
        return b;
    }

    private static void openItch() {
        try {
            forge.gui.GuiBase.getInterface().browseToUrl(forge.neo.update.NeoUpdate.PAGE_URL);
        } catch (final Exception ex) {
            System.err.println("[neo] no se ha podido abrir itch.io: " + ex);
        }
    }

    private static void showUpdate(final HBox box, final Button itch,
                                   final javafx.scene.shape.Circle dot) {
        final String newer = forge.neo.update.NeoUpdate.newer();
        final String offer = forge.neo.update.NeoUpdate.offer();
        dot.setVisible(newer != null);
        final String tip = newer == null ? NeoText.get("menu.itch")
                : NeoText.get("menu.itch.newer", newer);
        itch.getTooltip().setText(tip);
        itch.setAccessibleText(tip);

        box.getChildren().clear();
        box.setVisible(offer != null);
        box.setManaged(offer != null);
        if (offer == null) {
            return;
        }
        final Label text = new Label(NeoText.get("update.available", offer));
        text.getStyleClass().add("update-banner-text");
        text.setMinWidth(0);
        final Button open = new Button(NeoText.get("update.open"));
        open.getStyleClass().add("update-open");
        open.setMinWidth(Region.USE_PREF_SIZE);
        open.setOnAction(e -> openItch());
        final Button later = new Button(NeoText.get("update.later"));
        later.getStyleClass().add("update-later");
        later.setMinWidth(Region.USE_PREF_SIZE);
        later.setOnAction(e -> {
            forge.neo.update.NeoUpdate.dismiss(offer);
            showUpdate(box, itch, dot);
        });
        box.getChildren().addAll(text, open, later);
    }

    private static String lookNote() {
        final String name = forge.neo.look.NeoLook.playerName();
        return name.isBlank() ? forge.neo.NeoText.get("menu.look.note")
                : forge.neo.NeoText.get("menu.look.noteName", name);
    }

    private static Region formatTile(final NeoFormat format, final Actions actions) {
        final int count = format.decks().size();
        final ModeArt.Mode art = ModeArt.Mode.COMMANDER;
        return modeCard(art, format.getLabel(), format.getDescription(),
                count == 0 ? NeoText.get("menu.noDecks") : NeoText.get("menu.deckCount", count),
                true, () -> actions.play(format));
    }

    private static void fitCards(final FlowPane modes, final double viewport) {
        final double w = viewport - modes.getPadding().getLeft() - modes.getPadding().getRight();
        if (w <= 0) {
            return;
        }
        final double gap = modes.getHgap();
        final int cols = (int) Math.max(1, Math.min(MAX_COLS, Math.floor((w + gap) / (UiScale.px(MIN_CARD) + gap))));
        final double card = Math.min(UiScale.px(MAX_CARD), Math.floor((w - gap * (cols - 1)) / cols) - 1);
        for (final javafx.scene.Node n : modes.getChildren()) {
            if (n instanceof Region r && r.getStyleClass().contains("mode-card")) {
                r.setPrefWidth(card);
                r.setMaxWidth(card);
            }
        }
    }

    private static final double MIN_CARD = 330;
    private static final double MAX_CARD = 480;
    private static final int MAX_COLS = 4;

    private static final double MAX_ART = 150;

    private static final double RADIUS = 12;

    private static final javafx.scene.paint.Color PANEL = javafx.scene.paint.Color.web("#1B212A");

    private static Region modeCard(final ModeArt.Mode mode, final String name, final String description,
                                   final String footnote, final boolean enabled, final Runnable action) {
        final Label title = new Label(name);
        title.getStyleClass().add("mode-card-name");

        title.setWrapText(true);

        final Label desc = new Label(description);
        desc.getStyleClass().add("mode-card-desc");
        desc.setWrapText(true);

        final Label note = new Label(footnote);
        note.getStyleClass().add("mode-card-note");
        note.setWrapText(true);
        note.setStyle("-fx-text-fill: " + rgba(mode.tint(), 0.92) + ";");

        final HBox dots = new HBox(UiScale.px(4));
        for (final javafx.scene.paint.Color c : mode.colors()) {
            dots.getChildren().add(new javafx.scene.shape.Circle(UiScale.px(4.5), enabled ? c
                    : ModeArt.alpha(c, 0.4)));
        }
        dots.setAlignment(Pos.CENTER_LEFT);
        dots.setMinWidth(Region.USE_PREF_SIZE);
        final HBox foot = new HBox(UiScale.px(8), dots, note);
        foot.setAlignment(Pos.CENTER_LEFT);

        final Region gap = new Region();
        VBox.setVgrow(gap, javafx.scene.layout.Priority.ALWAYS);
        final VBox text = new VBox(UiScale.px(5), title, desc, gap, foot);
        text.setAlignment(Pos.TOP_LEFT);

        final javafx.scene.canvas.Canvas art = new javafx.scene.canvas.Canvas();
        art.setManaged(false);
        art.setMouseTransparent(true);

        final javafx.scene.layout.StackPane box = new javafx.scene.layout.StackPane(art, text);
        box.getStyleClass().add("mode-card");
        javafx.scene.layout.StackPane.setAlignment(text, Pos.TOP_LEFT);
        box.setPrefWidth(UiScale.px(MAX_CARD));
        box.setMinHeight(UiScale.px(140));
        final javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.setArcWidth(UiScale.px(RADIUS * 2));
        clip.setArcHeight(UiScale.px(RADIUS * 2));
        box.setClip(clip);

        final Runnable paint = () -> {
            final double w = box.getWidth();
            final double h = box.getHeight();
            art.setWidth(w);
            art.setHeight(h);
            clip.setWidth(w);
            clip.setHeight(h);

            final double room = Math.min(artSize(h) * 0.66, w * 0.40);
            text.setPadding(new Insets(UiScale.px(16), room, UiScale.px(14), UiScale.px(20)));
            paintCard(art, mode, enabled, box.isHover() && enabled,
                    box.getEffectiveNodeOrientation() == javafx.geometry.NodeOrientation.RIGHT_TO_LEFT,
                    panelOf(box));
        };
        box.layoutBoundsProperty().addListener((o, a, b) -> paint.run());
        box.hoverProperty().addListener((o, a, b) -> paint.run());

        if (enabled) {
            
            box.setOnMouseClicked(e -> {
                if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                    action.run();
                }
            });
        } else {
            box.setDisable(true);
        }
        return box;
    }

    private static double artSize(final double h) {
        return Math.min(h, UiScale.px(MAX_ART));
    }

    private static javafx.scene.paint.Color panelOf(final Region r) {
        final javafx.scene.layout.Background bg = r.getBackground();
        if (bg != null && !bg.getFills().isEmpty()
                && bg.getFills().get(0).getFill() instanceof javafx.scene.paint.Color c) {
            return c;
        }
        return PANEL;
    }

    private static void paintCard(final javafx.scene.canvas.Canvas c, final ModeArt.Mode mode,
                                  final boolean enabled, final boolean glow, final boolean rtl,
                                  final javafx.scene.paint.Color panel) {
        final javafx.scene.canvas.GraphicsContext g = c.getGraphicsContext2D();
        final double w = c.getWidth();
        final double h = c.getHeight();
        g.clearRect(0, 0, w, h);
        if (w <= 0 || h <= 0) {
            return;
        }

        g.save();
        if (rtl) {
            g.translate(w, 0);
            g.scale(-1, 1);
        }
        final double f = enabled ? 1 : 0.35;
        final double lit = glow ? 1 : 0;
        final double a = artSize(h);
        final double cx = w - a * 0.40;
        final double cy = h * 0.52;

        final java.util.List<javafx.scene.paint.Color> colors = mode.colors();
        final double radius = a * 0.95;
        for (int i = 0; i < colors.size(); i++) {
            final double dx = (i - (colors.size() - 1) / 2.0) * a * 0.35;
            final javafx.scene.paint.Color col = colors.get(i);
            g.setFill(new javafx.scene.paint.RadialGradient(0, 0, cx + dx, cy, radius, false,
                    javafx.scene.paint.CycleMethod.NO_CYCLE,
                    new javafx.scene.paint.Stop(0, ModeArt.alpha(col, (0.30 + 0.20 * lit) * f)),
                    new javafx.scene.paint.Stop(1, ModeArt.alpha(col, 0))));
            g.fillOval(cx + dx - radius, cy - radius, radius * 2, radius * 2);
        }
        ModeArt.draw(g, mode, cx, cy, a * 0.64, mode.ink().interpolate(javafx.scene.paint.Color.WHITE, 0.15),
                panel, f);
        
        g.setFill(new javafx.scene.paint.LinearGradient(0, 0, w, 0, false,
                javafx.scene.paint.CycleMethod.NO_CYCLE,
                new javafx.scene.paint.Stop(0, ModeArt.alpha(panel, 0.92)),
                new javafx.scene.paint.Stop(0.45, ModeArt.alpha(panel, 0.70)),
                new javafx.scene.paint.Stop(0.75, ModeArt.alpha(panel, 0))));
        g.fillRect(0, 0, w, h);

        final javafx.scene.paint.Color tint = mode.tint();
        g.setStroke(new javafx.scene.paint.LinearGradient(0, 0, w, 0, false,
                javafx.scene.paint.CycleMethod.NO_CYCLE,
                new javafx.scene.paint.Stop(0, ModeArt.alpha(tint, 0)),
                new javafx.scene.paint.Stop(0.5, ModeArt.alpha(tint, 0.6 * f)),
                new javafx.scene.paint.Stop(1, ModeArt.alpha(tint, 0))));
        g.setLineWidth(1.5);
        g.strokeLine(0, 0.75, w, 0.75);
        g.restore();

        final double lw = UiScale.px(1.5);
        final double arc = UiScale.px(RADIUS * 2) - lw;
        g.setStroke(ModeArt.alpha(tint, !enabled ? 0.25 : glow ? 0.95 : 0.55));
        g.setLineWidth(lw);
        g.strokeRoundRect(lw / 2, lw / 2, w - lw, h - lw, arc, arc);
    }

    private static String rgba(final javafx.scene.paint.Color c, final double a) {
        return String.format(java.util.Locale.ROOT, "rgba(%d,%d,%d,%.2f)", (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255), a);
    }

}
