package forge.neo.deck;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.deck.CardRelationMatrixGenerator;
import forge.deck.DeckFormat;
import forge.item.PaperCard;


public final class CommanderSynergy {

    private CommanderSynergy() {
    }

    
    public static List<Map.Entry<PaperCard, Integer>> poolOf(final PaperCard commander) {
        if (commander == null) {
            return List.of();
        }
        final Map<String, List<Map.Entry<PaperCard, Integer>>> m = matrix();
        if (m == null) {
            return List.of();
        }
        final List<Map.Entry<PaperCard, Integer>> pool = m.get(commander.getName());
        return pool == null ? List.of() : pool;
    }

    
    public static boolean knows(final PaperCard commander) {
        return !poolOf(commander).isEmpty();
    }

    
    public static Set<String> namesFor(final Collection<PaperCard> commanders) {
        final Set<String> out = new HashSet<>();
        if (commanders == null) {
            return out;
        }
        for (final PaperCard cmd : commanders) {
            for (final Map.Entry<PaperCard, Integer> e : poolOf(cmd)) {
                out.add(e.getKey().getName());
            }
        }
        return out;
    }

    
    public static List<PaperCard> byWeight(final PaperCard commander) {
        final List<Map.Entry<PaperCard, Integer>> pool = new ArrayList<>(poolOf(commander));
        pool.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        final List<PaperCard> out = new ArrayList<>(pool.size());
        for (final Map.Entry<PaperCard, Integer> e : pool) {
            out.add(e.getKey());
        }
        return out;
    }

    
    public static List<Map.Entry<PaperCard, Integer>> popularity() {
        List<Map.Entry<PaperCard, Integer>> p = popularity;
        if (p != null) {
            return p;
        }
        final Map<String, List<Map.Entry<PaperCard, Integer>>> m = matrix();
        if (m == null) {
            return List.of();
        }
        synchronized (CommanderSynergy.class) {
            if (popularity != null) {
                return popularity;
            }
            final Map<String, Map.Entry<PaperCard, Integer>> sum = new java.util.HashMap<>();
            for (final List<Map.Entry<PaperCard, Integer>> pool : m.values()) {
                for (final Map.Entry<PaperCard, Integer> e : pool) {
                    sum.merge(e.getKey().getName(),
                            new java.util.AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue()),
                            (a, b) -> new java.util.AbstractMap.SimpleImmutableEntry<>(
                                    a.getKey(), a.getValue() + b.getValue()));
                }
            }
            final List<Map.Entry<PaperCard, Integer>> out = new ArrayList<>(sum.values());
            out.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            popularity = java.util.Collections.unmodifiableList(out);
            return popularity;
        }
    }

    private static volatile List<Map.Entry<PaperCard, Integer>> popularity;

    
    private static Map<String, List<Map.Entry<PaperCard, Integer>>> matrix() {
        final String format = DeckFormat.Commander.toString();
        Map<String, List<Map.Entry<PaperCard, Integer>>> m =
                CardRelationMatrixGenerator.cardPools.get(format);
        if (m != null) {
            return m;
        }
        synchronized (CommanderSynergy.class) {
            m = CardRelationMatrixGenerator.cardPools.get(format);
            if (m != null) {
                return m;
            }
            if (failed) {
                return null;
            }
            try {
                CardRelationMatrixGenerator.initializeFormat(DeckFormat.Commander);
            } catch (final RuntimeException e) {
                System.out.println("[ascenso] sin matriz de sinergia: " + e);
            }
            m = CardRelationMatrixGenerator.cardPools.get(format);
            if (m == null) {
                failed = true;
                System.out.println("[ascenso] la matriz de sinergia no esta disponible;"
                        + " los mazos y los premios se sortean como antes");
            }
            return m;
        }
    }

    
    private static boolean failed;
}
