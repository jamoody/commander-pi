package forge.deck;

import com.google.gson.Gson;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Offline regression checks against the actual importer, without loading card assets. */
public final class ArchidektImportCheck {
    private static int checks;

    private static String imported(Map<?, ?> root) throws Exception {
        Method method = ArchidektDeckUrlProvider.class.getDeclaredMethod(
                "addCards", DeckUrlImportTextBuilder.class, Map.class);
        method.setAccessible(true);
        DeckUrlImportTextBuilder builder = new DeckUrlImportTextBuilder();
        method.invoke(null, builder, root);
        return builder.toString();
    }

    private static void check(String label, Object categories, boolean companion, String expected) throws Exception {
        Map<String, Object> card = new java.util.HashMap<>();
        if (categories != null) card.put("categories", categories);
        card.put("companion", companion);
        card.put("quantity", 2);
        card.put("card", Map.of("displayName", "Sol Ring"));
        String result = imported(Map.of("cards", List.of(card), "categories", List.of(
                Map.of("name", "Maybeboard", "includedInDeck", false),
                Map.of("name", "Sideboard", "includedInDeck", false),
                Map.of("name", "Wishlist", "includedInDeck", false))));
        String text = expected.isEmpty() ? "" : expected + "\n2 Sol Ring\n";
        if (!text.equals(result)) throw new AssertionError(label + ": expected " + text + ", got " + result);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        check("inactive maybeboard", List.of("Ramp", "Maybeboard"), false, "Main");
        check("inactive sideboard", List.of("Ramp", "Sideboard"), false, "Main");
        check("both inactive", List.of("Ramp", "Maybeboard", "Sideboard"), false, "Main");
        check("active maybeboard", List.of("Maybeboard", "Ramp"), false, "");
        check("active sideboard", List.of("Sideboard", "Maybeboard", "Commander"), false, "Sideboard");
        check("active commander", List.of("Commander", "Sideboard", "Maybeboard"), false, "Commander");
        check("inactive commander", List.of("Creature", "Commander"), false, "Main");
        check("active custom exclusion", List.of("Wishlist", "Ramp"), false, "");
        check("inactive custom exclusion", List.of("Ramp", "Wishlist"), false, "Main");
        check("companion", List.of("Creature", "Sideboard"), true, "Sideboard");
        check("excluded companion", List.of("Maybeboard", "Creature"), true, "");
        check("missing categories", null, false, "Main");
        check("empty categories", List.of(), false, "Main");
        check("malformed categories", "Sideboard", false, "Main");
        check("malformed primary", List.of(123, "Sideboard"), false, "Main");
        check("case insensitive section", List.of("commander", "Sideboard"), false, "Commander");
        System.out.println("PASS: " + checks + " category regression cases");
        if (args.length > 0) {
            Map<?, ?> root = new Gson().fromJson(Files.readString(Path.of(args[0])), Map.class);
            String text = imported(root);
            Files.writeString(Path.of(args[1]), text);
            System.out.println("Imported supplied deck snapshot to " + args[1]);
        }
    }
}
