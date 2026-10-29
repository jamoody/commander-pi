package forge.neo.deck;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


import forge.deck.CardRelationMatrixGenerator;
import forge.deck.DeckFormat;


import forge.deck.io.CardThemedMatrixIO;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;



public final class FastDeckGen {

    private FastDeckGen() {
    }

    
    private static volatile boolean deferred;

    
    public static void skipInEngine(final ForgePreferences prefs) {
        
        try {
            if (prefs.getPrefBoolean(FPref.DECKGEN_CARDBASED)) {
                prefs.setPref(FPref.DECKGEN_CARDBASED, false);
                deferred = true;
            }
        } catch (final RuntimeException e) {
            deferred = false;
        }
    }

    
    public static void loadNow() {
        if (!deferred) {
            return;
        }
        deferred = false;
        try {
            FModel.getPreferences().setPref(FPref.DECKGEN_CARDBASED, true);
        } catch (final RuntimeException e) {
            System.err.println("[neo] deckgen: no se ha podido reponer la preferencia: " + e);
        }
        final long t0 = System.currentTimeMillis();
        try {
            for (final DeckFormat f : new DeckFormat[] {DeckFormat.Commander}) {
                final String name = f.toString();
                final HashMap<String, List<Map.Entry<PaperCard, Integer>>> map =
                        read(CardThemedMatrixIO.getMatrixFile(name));
                if (map != null) {
                    CardRelationMatrixGenerator.cardPools.put(name, map);
                } else {
                    CardRelationMatrixGenerator.initializeFormat(f);
                }
            }
        } catch (final RuntimeException e) {
            System.err.println("[neo] deckgen: " + e);
        }
        System.out.println("        deck generator: " + (System.currentTimeMillis() - t0) + " ms");
    }

    @SuppressWarnings("unchecked")
    private static <T> T read(final File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try (ObjectInputStream in = new ObjectInputStream(
                new BufferedInputStream(new FileInputStream(file), 1 << 16))) {
            return (T) in.readObject();
        } catch (final Exception e) {
            System.err.println("[neo] deckgen: no se lee " + file.getName() + ": " + e);
            return null;
        }
    }
}
