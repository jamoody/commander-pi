package forge.neo;

import java.util.Locale;

import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.neo.match.NeoGame;
import forge.neo.match.NeoMatchUI;
import forge.neo.platform.ConsoleUiDispatcher;
import forge.neo.platform.FxUiDispatcher;
import forge.neo.platform.NeoGuiBase;

public final class NeoMain {

    private NeoMain() {
    }

    public static void main(final String[] args) {

        applySystemProperties(args);

        NeoPortable.apply();

        forge.neo.platform.NeoFonts.beforeJavaFx();

        forge.neo.platform.PrismGuard.beforeJavaFx();

        final String cmd = args.length > 0 && !args[0].startsWith("-") ? args[0].toLowerCase(Locale.ROOT) : "list";

        final boolean windowed = "ui".equals(cmd);
        if (windowed && wantsSingleInstance(args) && !NeoLock.acquire()) {
            System.exit(0);
        }

        NeoLog.start();

        if (windowed && forge.neo.data.NeoBackup.hasPending(forge.neo.data.DataPlaces.desktop().root)) {
            System.out.println("[neo] importando los datos preparados...");
            forge.neo.data.NeoBackup.applyPending(forge.neo.data.DataPlaces.desktop());
        }

        final boolean verbose = has(args, "-v");
        final String deckName = opt(args, "--deck", null);
        final int opponents = Integer.parseInt(opt(args, "--opponents", "1"));
        final int timeout = Integer.parseInt(opt(args, "--timeout", "300"));

        banner("NeoForge");

        GuiBase.setInterface(new NeoGuiBase(
                windowed ? new FxUiDispatcher() : new ConsoleUiDispatcher()));
        System.out.println("[1/2] IGuiBase registered -> NeoGuiBase ("
                + (windowed ? "JavaFX thread" : "dedicated thread") + ")");

        if (!windowed) {
            NeoBoot.loadEngine(null);
        }

        switch (cmd) {
            case "play":
                runGame(deckName, opponents, NeoMatchUI.Mode.AUTO_PLAY, timeout, verbose);
                break;
            case "watch":
                runGame(deckName, opponents, NeoMatchUI.Mode.OBSERVE, timeout, verbose);
                break;
            case "ui":
                banner("PHASE 2 - JavaFX interface");

                NeoBoot.prepareTexts();

                javafx.application.Application.launch(NeoApp.class, args);
                break;
            case "import":
                importDeck(opt(args, "--file", null), opt(args, "--name", null));
                break;
            case "diag":
                diagnoseImages(deckName);
                break;

            default:
                listDecks();
                System.out.println();
                System.out.println("  Comandos: play | watch     (--deck=nombre --opponents=N --timeout=S -v)");
                break;
        }

        System.exit(0);
    }

    private static void applySystemProperties(final String[] args) {
        for (final String arg : args) {
            if (arg == null || !arg.startsWith("-D") || arg.length() <= 2) {
                continue;
            }
            final String body = arg.substring(2);
            final int eq = body.indexOf('=');
            final String key = eq < 0 ? body : body.substring(0, eq);
            final String value = eq < 0 ? "true" : body.substring(eq + 1);
            if (!key.isEmpty()) {
                System.setProperty(key, value);
            }
        }
    }

    private static void runGame(final String deckName, final int opponents,
                                final NeoMatchUI.Mode mode, final int timeout, final boolean verbose) {

        final Deck deck = deckName == null ? NeoGame.firstCommanderDeck() : NeoGame.commanderDeck(deckName);
        if (deck == null) {
            System.out.println();
            System.out.println("  No se ha encontrado ningun mazo de Commander"
                    + (deckName == null ? "" : " llamado '" + deckName + "'") + ".");
            System.out.println("  Buscados en %APPDATA%\\Forge\\decks\\commander\\");
            return;
        }

        banner("FASE 1 - partida de Commander");
        final long t0 = System.currentTimeMillis();
        final NeoGame.Result r = NeoGame.play(deck, opponents, mode, timeout, verbose);
        final long secs = (System.currentTimeMillis() - t0) / 1000;

        System.out.println();
        if (r.completed) {
            banner("OK - partida completada");
            System.out.printf(Locale.ROOT,
                    "  Turnos: %d | decisiones contestadas: %d | duracion: %ds%n",
                    r.turns, r.decisions, secs);
            if (r.winner != null && !r.winner.isEmpty()) {
                System.out.println("  Ganador: " + r.winner);
            }
            System.out.println();
            System.out.println("  El motor ha jugado una partida entera conducida por NeoMatchUI,");
            System.out.println("  sin Swing. Camino de lectura y de escritura validados.");
            System.out.println("  Siguiente: FASE 2 - ventana JavaFX y render de carta.");
        } else {
            banner("FALLO - la partida no termino");
            System.out.printf(Locale.ROOT, "  Turnos alcanzados: %d | decisiones: %d%n",
                    r.turns, r.decisions);
        }
    }

    private static String stripExtension(final String fileName) {
        final int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static void importDeck(final String file, final String name) {
        if (file == null) {
            System.out.println("  Uso: import --file=lista.txt [--name=nombre]");
            return;
        }
        final java.io.File f = new java.io.File(file);
        if (!f.exists()) {
            System.out.println("  No existe el fichero: " + f.getAbsolutePath());
            return;
        }
        banner("IMPORTAR MAZO");
        try {
            final String text = forge.neo.deck.DeckImporter.readFile(f);
            final String deckName = name != null ? name : stripExtension(f.getName());

            final var result = forge.neo.deck.DeckImporter.importCommander(text, deckName);
            final Deck deck = result.deck;
            if (deck == null) {
                System.out.println("  El importador no ha devuelto ningun mazo.");
                return;
            }

            System.out.printf(Locale.ROOT, "  Reconocidas: %d cartas%n", result.accepted);
            if (result.unknown > 0) {
                System.out.printf(Locale.ROOT, "  NO reconocidas: %d%n", result.unknown);
                for (final String p : result.problems) {
                    System.out.println("     - " + p);
                }
            }

            System.out.printf(Locale.ROOT, "  Mazo principal: %d%n", deck.getMain().countAll());
            if (deck.has(DeckSection.Commander)) {
                for (final java.util.Map.Entry<PaperCard, Integer> e : deck.get(DeckSection.Commander)) {
                    System.out.println("  Comandante: " + e.getKey().getName()
                            + " [" + e.getKey().getEdition() + "]");
                }
            } else {
                System.out.println("  AVISO: el mazo no tiene comandante.");
            }

            forge.neo.deck.DeckImporter.save(deck);
            System.out.println();
            System.out.println("  Guardado como '" + deck.getName() + "'.");
        } catch (final Exception e) {
            System.out.println("  Fallo al importar: " + e);
            e.printStackTrace();
        }
    }

    private static void diagnoseImages(final String deckName) {
        final Deck deck = deckName == null ? NeoGame.firstCommanderDeck() : NeoGame.commanderDeck(deckName);
        if (deck == null) {
            System.out.println("  No hay mazo.");
            return;
        }
        banner("DIAGNOSTICO DE IMAGENES");
        System.out.println("  CACHE_CARD_PICS_DIR = "
                + forge.localinstance.properties.ForgeConstants.CACHE_CARD_PICS_DIR);
        System.out.println();

        int n = 0;
        for (final java.util.Map.Entry<PaperCard, Integer> e : deck.getMain()) {
            final PaperCard pc = e.getKey();
            final forge.game.card.CardView cv = forge.game.card.CardView.getCardForUi(pc);
            final String key = cv.getCurrentState().getImageKey();
            final java.io.File f = forge.ImageKeys.getImageFile(key);

            System.out.println("  " + pc.getName() + " [" + pc.getEdition() + "]");
            System.out.println("     clave  : " + key);
            System.out.println("     fichero: " + (f == null ? "(no encontrado en disco)" : f.getAbsolutePath()));
            System.out.println("     hasImage: " + forge.ImageKeys.hasImage(pc));

            if (f == null) {
                final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                
                GuiBase.getInterface().invokeInEdtLater(
                        () -> GuiBase.getInterface().getImageFetcher().fetchImage(key, done::countDown));
                try {
                    final boolean ok = done.await(25, java.util.concurrent.TimeUnit.SECONDS);
                    forge.ImageKeys.clearMissingCards();
                    final java.io.File after = forge.ImageKeys.getImageFile(key);
                    System.out.println("     descarga: " + (ok ? "callback OK" : "TIMEOUT")
                            + " | ahora en disco: " + (after != null));
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            System.out.println();
            if (++n >= 3) {
                break;
            }
        }
    }

    private static void listDecks() {
        System.out.println();
        System.out.println("  Mazos de Commander:");
        int count = 0;
        for (final Deck deck : FModel.getDecks().getCommander()) {
            count++;
            System.out.println();
            System.out.println("  -- " + deck.getName() + "  (" + deck.getMain().countAll() + " cartas)");
            if (deck.has(DeckSection.Commander)) {
                for (final java.util.Map.Entry<PaperCard, Integer> e : deck.get(DeckSection.Commander)) {
                    final PaperCard c = e.getKey();
                    System.out.println("     Comandante: " + c.getName() + " [" + c.getEdition() + "]"
                            + "  " + c.getRules().getManaCost());
                }
            }
        }
        if (count == 0) {
            System.out.println("     (ninguno)");
        }
    }

    private static boolean wantsSingleInstance(final String[] args) {
        if ("false".equalsIgnoreCase(System.getProperty("neo.singleInstance"))) {
            return false;
        }
        for (final String a : args) {
            if (a.equals("--lobby") || a.startsWith("--lobby-join")) {
                return false;
            }
        }
        return true;
    }

    private static boolean has(final String[] args, final String flag) {
        for (final String a : args) {
            if (a.equals(flag)) {
                return true;
            }
        }
        return false;
    }

    private static String opt(final String[] args, final String key, final String def) {
        for (final String a : args) {
            if (a.startsWith(key + "=")) {
                return a.substring(key.length() + 1);
            }
        }
        return def;
    }

    private static void checkOfflineArt() {
        final java.io.File dir = forge.neo.card.OfflineArt.dir();
        System.out.println("  carpeta: " + dir.getAbsolutePath() + (dir.isDirectory() ? "" : "  (NO EXISTE)"));
        int caras = 0;
        int conArte = 0;
        int conRecorte = 0;
        final java.util.List<String> faltan = new java.util.ArrayList<>();
        for (final PaperCard pc : forge.StaticData.instance().getCommonCards().getUniqueCards()) {
            for (final boolean atras : new boolean[] {false, true}) {
                if (atras && !pc.hasBackFace()) {
                    continue;
                }
                final String key = pc.getImageKey(atras);
                caras++;
                if (forge.neo.card.OfflineArt.find(key, false) != null) {
                    conArte++;
                } else if (!pc.getName().startsWith("A-")) {
                    faltan.add(key);
                }
                if (forge.neo.card.OfflineArt.find(key, true) != null) {
                    conRecorte++;
                }
            }
        }
        System.out.printf("  caras: %d   con arte: %d (%.1f%%)   con recorte: %d%n",
                caras, conArte, 100.0 * conArte / Math.max(1, caras), conRecorte);
        System.out.println("  sin arte (sin contar Alchemy): " + faltan.size());
        faltan.stream().limit(25).forEach(k -> System.out.println("     " + k));
        checkArtDownloadTargets();
        checkArtDownloadFilters();
        if (Integer.getInteger("neo.art.testLimit", 0) > 0) {
            checkArtDownloadRuns();
            checkEveryPrintingRuns();
        }
        if (Integer.getInteger("neo.tokens.testLimit", 0) > 0) {
            checkTokenArtRuns();
        }
    }

    private static void checkTokenArtRuns() {
        final java.util.Map<String, String[]> all = forge.neo.card.TokenArtDownload.newestPrintingsForTest();
        System.out.println("  fichas distintas: " + all.size() + " (Gingerbrute: "
                + java.util.Arrays.toString(all.get("gingerbrute")) + ")");
        final boolean ok = forge.neo.platform.NeoDownloads.runAndWait(
                new forge.neo.card.TokenArtDownload(), 120);
        forge.neo.card.TokenArtDownload.afterDownload();
        int found = 0;
        int tried = 0;
        for (final String name : all.keySet()) {
            final java.io.File f = new java.io.File(
                    forge.localinstance.properties.ForgeConstants.CACHE_TOKEN_PICS_DIR, name + ".jpg");
            if (!f.exists()) {
                continue;
            }
            tried++;
            
            if (forge.ImageKeys.getImageFile("t:" + name + "|ZZZ|999") != null) {
                found++;
            }
            if (tried >= 10) {
                break;
            }
        }
        System.out.println("  descarga de fichas terminada: " + ok + " | el juego encuentra " + found + " de " + tried);
    }

    private static void checkArtDownloadRuns() {
        final forge.neo.card.ArtDownload service =
                new forge.neo.card.ArtDownload(forge.neo.card.ArtDownload.Scope.ALL);
        System.out.println("  bajando de verdad " + Integer.getInteger("neo.art.testLimit", 0)
                + " cartas de Scryfall...");
        final boolean ok = forge.neo.platform.NeoDownloads.runAndWait(service, 120);
        System.out.println("  descarga terminada: " + ok + " (" + service.getCount() + " pedidas)");

        forge.neo.card.ArtDownload.afterDownload();

        final java.util.List<PaperCard> pedidas = service.getQueued();
        int encontradas = 0;
        final java.util.List<String> sin = new java.util.ArrayList<>();
        for (final PaperCard pc : pedidas) {
            if (forge.neo.card.OfflineArt.find(pc.getImageKey(false), false) != null) {
                encontradas++;
            } else {
                sin.add(pc.getName());
            }
        }
        System.out.println("  y el lector las encuentra: " + encontradas + " de " + pedidas.size());
        sin.stream().limit(10).forEach(n -> System.out.println("     sin foto: " + n));

        final int permitidos = Math.max(2, pedidas.size() / 10);
        if (pedidas.isEmpty() || pedidas.size() - encontradas > permitidos) {
            throw new IllegalStateException("se pidieron " + pedidas.size()
                    + " artes y OfflineArt solo encuentra " + encontradas);
        }
    }

    private static void checkEveryPrintingRuns() {
        final String name = System.getProperty("neo.art.everyCard", "Skullclamp");

        final forge.gui.download.GuiDownloadService service =
                forge.neo.card.ArtDownload.everyPrinting(c -> name.equals(c.getName()));
        System.out.println("  todas las impresiones de " + name + " (descargador de Forge)...");
        final boolean ok = forge.neo.platform.NeoDownloads.runAndWait(service, 300);
        forge.neo.card.ArtDownload.afterDownload();
        int total = 0;
        int found = 0;

        final forge.neo.card.ImageQuality quality = forge.neo.card.ImageQuality.current();
        for (final PaperCard pc : forge.model.FModel.getMagicDb().getCommonCards().getAllCards(name)) {
            final String key = forge.util.ImageUtil.getImageKey(pc, "", true);
            if (key == null) {
                continue;
            }
            total++;
            if (quality != forge.neo.card.ImageQuality.NORMAL) {
                final java.io.File f = quality.exactFile(key);
                if (f != null) {
                    found++;
                    try {
                        final java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f);
                        System.out.println("     " + f.getName() + ": "
                                + (img == null ? "?" : img.getWidth() + "x" + img.getHeight()));
                        if (img != null && img.getWidth() <= 488) {
                            throw new IllegalStateException(f + " es de " + img.getWidth()
                                    + " de ancho: no es la calidad " + quality.scryfall);
                        }
                    } catch (final java.io.IOException e) {
                        throw new IllegalStateException("no se lee " + f, e);
                    }
                }
                continue;
            }
            final java.io.File full = new java.io.File(
                    forge.localinstance.properties.ForgeConstants.CACHE_CARD_PICS_DIR, key + ".jpg");
            final String fb = key.contains(".full") ? key.replace(".full", ".fullborder") : key + ".fullborder";
            final java.io.File border = new java.io.File(
                    forge.localinstance.properties.ForgeConstants.CACHE_CARD_PICS_DIR, fb + ".jpg");
            if (full.exists() || border.exists()) {
                found++;
            }
        }
        System.out.println("  descarga terminada: " + ok + " | impresiones en disco (" + quality.scryfall + "): "
                + found + " de " + total);
        if (total == 0 || total - found > Math.max(2, total / 10)) {
            throw new IllegalStateException("de " + total + " impresiones de " + name
                    + " solo hay " + found + " en disco");
        }
    }

    private static void checkArtDownloadTargets() {
        int mirados = 0;
        int mal = 0;
        for (final PaperCard pc : forge.neo.card.ArtDownload.sample(400)) {
            final String nombre = pc.getRules().getMainPart().getName();
            final java.io.File destino = forge.neo.card.ArtDownload.destination(nombre);
            if (destino == null) {
                continue;
            }
            mirados++;

            final String limpio = forge.neo.card.OfflineArt.fileName(nombre);
            final java.io.File esperado = new java.io.File(
                    new java.io.File(forge.neo.card.ImageQuality.current().artDir(),
                            forge.neo.card.OfflineArt.folder(limpio)), limpio + ".jpg");
            if (!destino.getAbsolutePath().equals(esperado.getAbsolutePath())) {
                mal++;
                if (mal <= 5) {
                    System.out.println("     DESALINEADO: " + nombre);
                    System.out.println("       baja a: " + destino.getAbsolutePath());
                    System.out.println("       lee de: " + esperado.getAbsolutePath());
                }
            }
        }
        System.out.printf("  destino de la descarga: %d cartas miradas, %d desalineadas%n",
                mirados, mal);
        if (mal > 0) {
            throw new IllegalStateException(mal + " carta(s) se bajarian donde nadie las lee");
        }
    }

    private static void checkArtDownloadFilters() {
        final java.util.List<forge.card.CardEdition> eds = forge.neo.card.ArtDownload.editions();
        boolean ordered = true;
        for (int i = 1; i < eds.size(); i++) {
            final java.util.Date a = eds.get(i - 1).getDate();
            final java.util.Date b = eds.get(i).getDate();
            if (a != null && b != null && a.before(b)) {
                ordered = false;
            }
        }
        forge.game.GameFormat pauper = null;
        for (final forge.game.GameFormat f : forge.neo.card.ArtDownload.formats()) {
            if ("Pauper".equals(f.getName())) {
                pauper = f;
            }
        }
        if (eds.isEmpty() || !ordered || pauper == null) {
            throw new IllegalStateException("listas de la descarga: " + eds.size() + " expansiones, ordenadas="
                    + ordered + ", Pauper=" + (pauper != null));
        }
        final forge.neo.card.ArtDownload byFormat = forge.neo.card.ArtDownload.forFormat(pauper);
        final int pending = byFormat.countPending();
        int illegal = 0;
        for (final PaperCard c : byFormat.getQueued()) {
            if (!pauper.getFilterRules().test(c)) {
                illegal++;
            }
        }
        final int all = forge.neo.card.ArtDownload.missing(forge.neo.card.ArtDownload.Scope.ALL);
        System.out.printf("  por formato: Pauper pide %d (de %d sin filtro), %d ilegales; %d expansiones, la primera %s%n",
                pending, all, illegal, eds.size(), eds.get(0).getCode());

        if (illegal > 0 || (all > 0 && pending >= all && Integer.getInteger("neo.art.testLimit", 0) <= 0)) {
            throw new IllegalStateException("el filtro por formato no filtra: " + illegal + " ilegales");
        }
    }

    private static void banner(final String text) {
        final String line = "=".repeat(Math.max(60, text.length() + 4));
        System.out.println();
        System.out.println(line);
        System.out.println("  " + text);
        System.out.println(line);
    }
}
