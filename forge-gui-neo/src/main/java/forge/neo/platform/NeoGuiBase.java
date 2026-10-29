package forge.neo.platform;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import org.jupnp.UpnpServiceConfiguration;

import forge.gamemodes.match.HostedMatch;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;

public class NeoGuiBase implements IGuiBase {

    private static final String ASSETS_DIR =
            System.getProperty("forge.assetsDir", "../forge-gui/");

    private static java.util.function.Supplier<IGuiGame> guiGameFactory;

    public static void setGuiGameFactory(final java.util.function.Supplier<IGuiGame> factory) {
        guiGameFactory = factory;
    }

    private final UiDispatcher dispatcher;

    public NeoGuiBase() {
        this(new ConsoleUiDispatcher());
    }

    public NeoGuiBase(final UiDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public UiDispatcher getDispatcher() {
        return dispatcher;
    }

    @Override
    public String getAssetsDir() {
        return ASSETS_DIR;
    }

    @Override
    public boolean isRunningOnDesktop() {
        return true;
    }

    @Override
    public boolean isLibgdxPort() {
        return false;
    }

    @Override
    public String getCurrentVersion() {
        
        return "neo-dev-git";
    }

    @Override
    public String getUserAgent() {
        return NeoUserAgent.get();
    }

    @Override
    public void invokeInEdtNow(final Runnable runnable) {
        dispatcher.runAndWait(runnable);
    }

    @Override
    public void invokeInEdtLater(final Runnable runnable) {
        dispatcher.runLater(runnable);
    }

    @Override
    public void invokeInEdtAndWait(final Runnable proc) {
        dispatcher.runAndWait(proc);
    }

    @Override
    public boolean isGuiThread() {
        return dispatcher.isUiThread();
    }

    @Override
    public void runBackgroundTask(final String message, final Runnable task) {

        final Thread t = new Thread(task, "Game-neo-bg");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public float getScreenScale() {
        return 1f;
    }

    @Override
    public boolean hasNetGame() {
        return false;
    }

    @Override
    public HostedMatch hostMatch() {

        final HostedMatch match = new HostedMatch();

        match.setStartGameHook(forge.neo.match.SafeAi.hook(match, null));

        final java.util.function.UnaryOperator<HostedMatch> hook = onMatchCreated;
        return hook == null ? match : hook.apply(match);
    }

    private static volatile java.util.function.UnaryOperator<HostedMatch> onMatchCreated;

    public static void setOnMatchCreated(
            final java.util.function.UnaryOperator<HostedMatch> hook) {
        onMatchCreated = hook;
    }

    private static UnsupportedOperationException todo(final String what) {
        return new UnsupportedOperationException(
                "NeoGuiBase: '" + what + "' aun no implementado. "
                        + "Ver las notas de diseño para saber en que fase toca.");
    }

    private final ImageFetcher imageFetcher = new NeoImageFetcher();

    @Override
    public ImageFetcher getImageFetcher() {
        return imageFetcher;
    }

    private static final ISkinImage NO_ICON = new ISkinImage() { };

    @Override public ISkinImage getSkinIcon(FSkinProp p) { return NO_ICON; }
    @Override public ISkinImage getUnskinnedIcon(String path) { return NO_ICON; }
    @Override public ISkinImage getCardArt(PaperCard c, boolean backFace) { return NO_ICON; }
    @Override public ISkinImage createLayeredImage(PaperCard c, FSkinProp bg, String overlay, float opacity) { return NO_ICON; }
    @Override public void clearImageCache() {  }

    @Override
    public String encodeSymbols(final String str, final boolean formatReminderText) {
        
        return str;
    }

    @Override public int getAvatarCount() { return forge.neo.look.NeoLook.builtInAvatarCount(); }
    @Override public int getSleevesCount() { return forge.neo.look.NeoLook.builtInSleeveCount(); }
    @Override public void preventSystemSleep(boolean prevent) {  }
    
    @Override
    public void download(final GuiDownloadService service, final Consumer<Boolean> callback) {
        NeoDownloads.run(service, callback);
    }

    @Override
    public void copyToClipboard(final String text) {
        if (text == null) {
            return;
        }
        try {
            if (javafx.application.Platform.isFxApplicationThread()) {
                final javafx.scene.input.ClipboardContent content =
                        new javafx.scene.input.ClipboardContent();
                content.putString(text);
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                return;
            }
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(text), null);
        } catch (final RuntimeException e) {
            System.err.println("[neo] could not copy to the clipboard: " + e);
        }
    }

    @Override
    public void browseToUrl(final String url) {
        final long now = System.currentTimeMillis();
        final boolean again = url != null && url.equals(lastUrl) && now - lastOpened < REOPEN_MS;
        if (!again) {
            lastUrl = url;
            lastOpened = now;
            openInBrowser(url);
        }
        forge.neo.ui.Toast.show(forge.neo.NeoText.get(again ? "browser.again" : "browser.opened"));
    }

    private static final long REOPEN_MS = 8000L;
    private String lastUrl;
    private long lastOpened;

    private static void openInBrowser(final String url) {
        
        if (Boolean.getBoolean("neo.browser.dry")) {
            System.out.println("[navegador] abriria " + url);
            return;
        }
        if (NeoOs.openOnMac(url)) {
            return;
        }
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
            }
        } catch (final Exception e) {
            System.err.println("[neo] could not open " + url + ": " + e);
        }
    }

    @Override
    public void showCardList(final String title, final String msg, final List<PaperCard> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        getChoices(header(title, msg), -1, -1, list, null, null);
    }

    @Override
    public boolean showBoxedProduct(final String title, final String msg, final List<PaperCard> list) {
        showCardList(title, msg, list);
        return false;
    }

    private static String header(final String title, final String msg) {
        if (title == null || title.isBlank()) {
            return msg;
        }
        if (msg == null || msg.isBlank()) {
            return title;
        }
        return title + " — " + msg;
    }
    
    @Override
    public void showBugReportDialog(final String title, final String text, final boolean exitBtn) {
        System.err.println("[neo] " + title);
        System.err.println(text);

        if (Thread.currentThread().getName().startsWith("Game-")) {
            final java.util.function.Consumer<String> hook = engineCrash;
            if (hook != null && CRASH_TOLD.compareAndSet(false, true)) {
                try {
                    hook.accept(title);
                } catch (final RuntimeException e) {

                    System.err.println("[neo] could not report the error: " + e);
                }
            }
        }
    }

    private static volatile java.util.function.Consumer<String> engineCrash;

    private static final java.util.concurrent.atomic.AtomicBoolean CRASH_TOLD =
            new java.util.concurrent.atomic.AtomicBoolean();

    public static void setOnEngineCrash(final java.util.function.Consumer<String> hook) {
        engineCrash = hook;
    }

    public static void clearEngineCrash() {
        CRASH_TOLD.set(false);
    }
    
    @Override
    public void showImageDialog(final ISkinImage img, final String msg, final String title) {
        final String line = (title == null || title.isBlank() ? "" : title + ": ")
                + (msg == null ? "" : msg);
        if (!line.isBlank()) {
            synchronized (NOTICES) {
                NOTICES.add(line);
            }
            System.out.println("  [achievement] " + line);
        }
    }

    private static final java.util.List<String> NOTICES = new java.util.ArrayList<>();

    public static java.util.List<String> drainNotices() {
        synchronized (NOTICES) {
            final java.util.List<String> out = new java.util.ArrayList<>(NOTICES);
            NOTICES.clear();
            return out;
        }
    }
    
    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon,
                                final List<String> options, final int defaultOption) {
        if (options == null || options.isEmpty()) {
            return defaultOption;
        }
        final int def = defaultOption >= 0 && defaultOption < options.size() ? defaultOption : 0;
        final List<String> picked = getChoices(header(title, message), 1, 1, options,
                List.of(options.get(def)), null);
        if (picked.isEmpty()) {
            return def;
        }
        final int index = options.indexOf(picked.get(0));
        return index < 0 ? def : index;
    }

    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon,
                                  final String initialInput, final List<String> inputOptions,
                                  final boolean isNumeric) {
        
        if (inputOptions != null && !inputOptions.isEmpty()) {
            final int i = showOptionDialog(message, title, icon, inputOptions, 0);
            return inputOptions.get(Math.max(0, Math.min(i, inputOptions.size() - 1)));
        }
        final Asker a = asker;
        if (a != null) {
            return a.ask(header(title, message), initialInput, isNumeric);
        }
        System.out.println("[neo] no hay quien conteste a: " + header(title, message));
        return null;
    }

    public interface Asker {
        String ask(String message, String initial, boolean numeric);
    }

    private static volatile Asker asker;

    public static void setAsker(final Asker a) {
        asker = a;
    }

    @Override
    public String showFileDialog(final String title, final String defaultDir) {
        System.out.println("[neo] no hay selector de ficheros: " + title);
        return null;
    }

    @Override
    public File getSaveFile(final File defaultFile) {
        return defaultFile;
    }

    @Override
    public <T> List<T> order(final String title, final String top, final int min, final int max,
                             final List<T> sourceChoices, final List<T> destChoices) {
        final List<T> out = new java.util.ArrayList<>();
        if (destChoices != null) {
            out.addAll(destChoices);
        }
        if (sourceChoices != null) {
            out.addAll(sourceChoices);
        }
        System.out.println("[neo] orden por defecto para: " + title);
        return out;
    }

    public interface Chooser {
        <T> List<T> choose(String message, int min, int max,
                           List<T> options, List<T> preselected,
                           java.util.function.Function<T, String> display);
    }

    private static volatile Chooser chooser;

    public static void setChooser(final Chooser c) {
        chooser = c;
    }

    public static Chooser getChooser() {
        return chooser;
    }

    @Override
    public <T> List<T> getChoices(final String msg, final int min, final int max,
                                  final Collection<T> choices, final Collection<T> selected,
                                  final FSerializableFunction<T, String> display) {

        final List<T> options = choices == null ? List.of()
                : new java.util.ArrayList<>(choices);
        final List<T> pre = selected == null ? List.<T>of() : new java.util.ArrayList<>(selected);
        if (options.isEmpty()) {
            return List.of();
        }
        final String question = inSpanish(msg);
        final Chooser c = chooser;
        if (c != null) {
            final List<T> answer = c.choose(question, min, max, options, pre,
                    display == null ? null : display::apply);
            if (answer != null && (min <= 0 || !answer.isEmpty())) {
                return answer;
            }
        }

        if (min <= 0) {
            return List.of();
        }
        final List<T> fallback = new java.util.ArrayList<>(
                pre.isEmpty() ? List.of(options.get(0)) : pre);
        System.out.println("[neo] elijo por ti: " + msg + " -> " + fallback.get(0));
        return fallback.subList(0, Math.min(fallback.size(), Math.max(1, max)));
    }

    private static String inSpanish(final String msg) {
        if (msg == null) {
            return null;
        }
        switch (msg.trim()) {
            case "Choose bonus booster format":
                return forge.neo.NeoText.get("prize.format");
            case "Choose bonus booster set":
                return forge.neo.NeoText.get("prize.set");
            case "Which booster type do you choose?":
                return forge.neo.NeoText.get("prize.type");
            default:
                return msg;
        }
    }

    @Override
    public PaperCard chooseCard(final String title, final String msg, final List<PaperCard> list) {
        final List<PaperCard> picked = getChoices(
                msg == null || msg.isBlank() ? title : msg, 1, 1, list, null, null);
        return picked.isEmpty() ? null : picked.get(0);
    }

    private static final boolean AUDIO_DEBUG = Boolean.getBoolean("neo.audio.debug");

    @Override
    public boolean isSupportedAudioFormat(final File f) {
        if (f == null) {
            return false;
        }
        final String name = f.getName().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".aiff")
                || name.endsWith(".aif") || name.endsWith(".m4a");
    }

    @Override
    public IAudioClip createAudioClip(final String filename) {

        final File file = forge.sound.SoundSystem.instance.getSoundResource(filename);
        IAudioClip forgeClip = null;
        if (file != null && file.exists()) {
            try {
                if (AUDIO_DEBUG) {
                    System.out.println("[audio] " + filename + " -> " + file.getAbsolutePath());
                }
                
                forgeClip = JavaSound.clip(file);
            } catch (final RuntimeException e) {
                System.err.println("[neo] no se ha podido cargar el sonido " + filename);
            }
        }

        if (forge.neo.sound.NeoSounds.has(filename)) {
            return new NeoPooledClip(filename, forgeClip);
        }
        return forgeClip;
    }

    @Override
    public IAudioMusic createAudioMusic(final String filename) {
        
        return JavaSound.music(filename);
    }

    @Override public void startAltSoundSystem(String filename, boolean sync) { }

    @Override public void showSpellShop() { System.out.println("[neo] la tienda se abre desde la aventura"); }
    @Override public void showBazaar() { System.out.println("[neo] el bazar todavia no esta"); }

    @Override
    public IGuiGame getNewGuiGame() {
        if (guiGameFactory == null) {
            throw todo("getNewGuiGame (sin fabrica registrada: llama a NeoGuiBase.setGuiGameFactory)");
        }
        return guiGameFactory.get();
    }
    
    @Override
    public UpnpServiceConfiguration getUpnpPlatformService() {
        return new org.jupnp.DefaultUpnpServiceConfiguration();
    }
}
