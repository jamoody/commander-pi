import forge.ai.StrategicThreatEvaluator;
import forge.ai.CreatureEvaluator;
import forge.card.CardStateName;
import forge.game.CardTraitBase;
import forge.game.card.Card;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostTap;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.neo.NeoBoot;
import forge.neo.NeoPortable;
import forge.neo.platform.*;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Observes the real evaluator through reflection; does not change its scoring code. */
public final class ThreatCoverageAudit {
    static Method conditional, opportunities, effect, budget, budgetRate;
    static PrintWriter traits, cards, errors;
    static String cardName, faceName;
    static int traitId;
    static final Set<String> SUPPORTED = Set.of("Draw", "Token", "Mana", "ManaReflected", "CopyPermanent", "Dig", "ChangeZone", "Pump", "Cleanup", "GainLife", "LoseLife", "DealDamage", "Destroy", "PutCounter", "Untap", "Shuffle", "PumpAll", "PutCounterAll", "Charm", "RepeatEach", "Tap", "Discard", "GainControl", "Counter", "Effect", "DamageAll", "CopySpellAbility", "Sacrifice", "ImmediateTrigger", "PeekAndReveal");
    static Method method(String name, Class<?>... types) throws Exception {
        Method m = StrategicThreatEvaluator.class.getDeclaredMethod(name, types); m.setAccessible(true); return m;
    }
    static String clean(Object o) { return String.valueOf(o).replace('\t', ' ').replace('\r', ' ').replace('\n', ' '); }
    static void row(PrintWriter out, Object... cols) {
        out.println(Arrays.stream(cols).map(ThreatCoverageAudit::clean).collect(java.util.stream.Collectors.joining("\t")));
    }
    static boolean conditional(CardTraitBase t) throws Exception { return (boolean)conditional.invoke(null, t); }
    static double effect(Card c, SpellAbility sa) throws Exception {
        return (double)effect.invoke(null, c, sa, Collections.newSetFromMap(new IdentityHashMap<SpellAbility, Boolean>()), 0);
    }
    static void inspectEffects(SpellAbility sa, Set<SpellAbility> seen, Set<String> blockers, Set<String> apis, List<Map<String,Object>> nodes) throws Exception {
        if (sa == null || !seen.add(sa) || seen.size() > 128) { return; }
        String api = String.valueOf(sa.getApi()); apis.add(api);
        nodes.add(Map.of("api",api,"parameters",sa.getMapParams()));
        if (!SUPPORTED.contains(api)) { blockers.add("effect:" + api); }
        if (conditional(sa)) { blockers.add("effect-condition"); }
        for (String key : List.of("NumCards", "TokenAmount", "Amount", "DigNum", "ChangeNum", "UnlessCost", "NumAtt", "NumDef")) {
            String val = sa.getParam(key);
            if (val == null || val.matches("-?[0-9]+")) { continue; }
            String svar = sa.getSVar(val);
            if (!svar.equals("Count$CardPower") && !svar.equals("Count$CardToughness")
                    && !(svar.startsWith("Count$Valid ") && !svar.contains("/"))) {
                blockers.add("quantity-expression-review"); // diagnostic hint, not a proven unsupported expression
            }
        }
        inspectEffects(sa.getSubAbility(), seen, blockers, apis, nodes);
        for (SpellAbility child : sa.getAdditionalAbilities().values()) { inspectEffects(child, seen, blockers, apis, nodes); }
        for (var children : sa.getAdditionalAbilityLists().values()) {
            for (SpellAbility child : children) { inspectEffects(child, seen, blockers, apis, nodes); }
        }
    }
    static String pattern(Trigger t) {
        if (t.getMode() == TriggerType.Attacks) {
            return t.hasParam("Alone") || t.hasParam("Attacked") || t.hasParam("FirstAttack") ? "trigger-pattern:Attacks" : "fixture-opportunity-zero";
        }
        if (t.getMode() == TriggerType.SpellCast) { return "trigger-pattern:SpellCast"; }
        if (t.getMode() == TriggerType.Drawn) { return "trigger-pattern:Drawn"; }
        if (t.getMode() == TriggerType.ChangesZone) {
            return "trigger-pattern:ChangesZone:" + t.getParamOrDefault("Destination", "unspecified");
        }
        if (t.getMode() == TriggerType.Phase) { return "trigger-pattern:Phase:" + t.getParamOrDefault("Phase", "unspecified"); }
        return "trigger-mode:" + t.getMode();
    }
    static void trait(Card c, String kind, CardTraitBase t, SpellAbility sa, String mode, String gate, double rate) throws Exception {
        Set<String> blockers = new TreeSet<>(), apis = new TreeSet<>();
        List<Map<String,Object>> nodes=new ArrayList<>();
        inspectEffects(sa, Collections.newSetFromMap(new IdentityHashMap<SpellAbility, Boolean>()), blockers, apis,nodes);
        double output = sa == null ? 0 : effect(c, sa);
        if (t instanceof forge.game.staticability.StaticAbility st && !st.isSuppressed() && !conditional(st)) {
            String helper = "True".equals(st.getParam("MayPlay")) ? "permission"
                    : st.hasParam("AdjustLandPlays") ? "extraLands"
                    : Set.of("CantBeCast","CantBeActivated").contains(st.getParamOrDefault("Mode","")) ? "restriction" : null;
            if (helper != null) {
                Method m=Class.forName("forge.ai.StrategicThreatPatterns").getDeclaredMethod(helper,Card.class,forge.game.staticability.StaticAbility.class);
                m.setAccessible(true);output=(double)m.invoke(null,c,st);
                if (output>0) { gate="static-contributes"; }
            }
        }
        if (gate.isEmpty()) {
            gate = output > 0 ? "contributes" : blockers.isEmpty() ? "effect-zero-in-fixture" : "effect-unhandled";
        }
        row(traits, cardName, faceName, ++traitId, kind, mode, gate, rate, output,
                String.join(";", blockers), String.join(";", apis), t.getMapParams(),
                t.getKeyword() == null ? "" : t.getKeyword().getKeyword().name(),
                sa == null ? "" : String.valueOf(sa.getApi()), new com.google.gson.Gson().toJson(t.getMapParams()),
                sa == null ? "{}" : new com.google.gson.Gson().toJson(sa.getMapParams()),new com.google.gson.Gson().toJson(nodes));
    }
    static void inspect(Card c) throws Exception {
        for (Trigger t : c.getTriggers()) {
            String gate = "";
            double rate = (double)opportunities.invoke(null, c, t);
            if (t.isSuppressed()) { gate = "suppressed"; }
            else if (conditional(t)) { gate = "trigger-condition"; }
            else if (!"Battlefield".equals(t.getParamOrDefault("TriggerZones", "Battlefield"))) { gate = "outside-battlefield"; }
            else if ("Battlefield".equals(t.getParam("Destination")) && t.getParamOrDefault("ValidCard", "").contains("Self")) {
                gate = "self-ETB-already-realized";
            } else if (rate <= 0) { gate = pattern(t); }
            SpellAbility effect=t.ensureAbility();
            if (effect!=null && effect.getPayCosts()!=null) {
                rate=Math.min(rate,(double)budgetRate.invoke(budget.invoke(null,c,effect)));
                if (rate<=0 && gate.isEmpty()) { gate="cost-unhandled-or-unavailable-in-fixture"; }
            }
            trait(c, "trigger", t, effect, String.valueOf(t.getMode()), gate, rate);
        }
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (!sa.isActivatedAbility()) { continue; }
            String gate = "";
            if (sa.isSuppressed()) { gate = "suppressed"; }
            else if (conditional(sa)) { gate = "activation-condition-or-limit"; }
            else if (sa.getPayCosts() == null || !"Battlefield".equals(sa.getParamOrDefault("ActivationZone", "Battlefield"))) { gate = "outside-battlefield"; }
            double rate = (double)budgetRate.invoke(budget.invoke(null, c, sa));
            if (gate.isEmpty() && rate <= 0) { gate = "cost-unhandled-or-unavailable-in-fixture"; }
            trait(c, "activated", sa, sa, String.valueOf(sa.getApi()), gate, rate);
        }
        for (var st : c.getStaticAbilities()) {
            String mode = st.getParamOrDefault("Mode", "unknown");
            String gate = "static-unhandled";
            if (st.isSuppressed()) { gate = "suppressed"; }
            else if (conditional(st)) { gate = "static-condition"; }
            else if (Set.of("ReduceCost", "UntapOtherPlayer", "CantBeCast", "CantBeActivated", "Panharmonicon", "DisableTriggers").contains(mode)) { gate = "static-family-recognized-review-parameters"; }
            else if (mode.equals("Continuous")) {
                if ("True".equals(st.getParam("MayPlay")) && "Graveyard".equals(st.getParam("AffectedZone"))
                        && "Land.YouOwn".equals(st.getParam("Affected"))) { gate = "static-recognized-land-permission"; }
                else if (st.getParamOrDefault("Affected", "").contains("YouCtrl") && !st.hasParam("AffectedZone")
                        && (st.hasParam("AddPower") || st.hasParam("AddToughness") || st.hasParam("AddKeyword"))) { gate = "static-recognized-buff-shape"; }
            }
            trait(c, "static", st, null, mode, gate, 0);
        }
        for (var r : c.getReplacementEffects()) {
            String event = r.getParamOrDefault("Event", "unknown");
            trait(c, "replacement", r, r.getOverridingAbility(), event,
                    Set.of("CreateToken", "AddCounter").contains(event) ? "replacement-family-review-parameters-and-producers" : "replacement-unhandled", 0);
        }
    }
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]); Files.createDirectories(dir);
        conditional = method("conditional", CardTraitBase.class);
        opportunities = method("opportunities", Card.class, Trigger.class);
        effect = method("effectValue", Card.class, SpellAbility.class, Set.class, int.class);
        budget = Class.forName("forge.ai.StrategicThreatSupport").getDeclaredMethod("budget", Card.class, SpellAbility.class);
        budget.setAccessible(true);
        budgetRate = budget.getReturnType().getDeclaredMethod("rate"); budgetRate.setAccessible(true);
        NeoPortable.apply(); GuiBase.setInterface(new NeoGuiBase(new FxUiDispatcher())); NeoBoot.loadEngine(null);
        try (PrintWriter a = new PrintWriter(Files.newBufferedWriter(dir.resolve("cards.tsv"), StandardCharsets.UTF_8));
             PrintWriter b = new PrintWriter(Files.newBufferedWriter(dir.resolve("traits.tsv"), StandardCharsets.UTF_8));
             PrintWriter e = new PrintWriter(Files.newBufferedWriter(dir.resolve("errors.tsv"), StandardCharsets.UTF_8))) {
            cards = a; traits = b; errors = e;
            row(cards, "card", "face", "types", "creature", "score", "traits", "explanation", "legacy_score", "legacy_labels", "keywords", "legacy_modifier");
            row(traits, "card", "face", "id", "kind", "mode", "gate", "opportunities", "isolated_effect_value", "effect_blockers", "effect_apis", "parameters", "keyword", "root_api", "parameters_json", "effect_parameters_json", "effect_nodes_json");
            row(errors, "card", "face", "error");
            var f = StrategicThreatCheck.fixture("Plains");
            for (int i = 0; i < 6; i++) { StrategicThreatCheck.board(f, "Forest"); }
            for (String name : List.of("Grizzly Bears", "Llanowar Elves", "Elite Vanguard", "Ornithopter")) { StrategicThreatCheck.board(f, name); }
            for (int i = 0; i < 5; i++) { StrategicThreatCheck.add(f, "Plains", ZoneType.Hand); }
            StrategicThreatCheck.add(f, "Forest", ZoneType.Graveyard);
            StrategicThreatCheck.add(f, "Grizzly Bears", ZoneType.Graveyard);
            String fixtureName = args.length > 1 ? args[1] : "standard";
            if (fixtureName.equals("rich")) {
                for (String land : List.of("Plains", "Island", "Swamp", "Mountain")) {
                    for (int i=0;i<4;i++) { StrategicThreatCheck.board(f, land); }
                }
                for (String name : List.of("Sol Ring", "Ornithopter", "Ornithopter", "Mulldrifter", "Colossal Dreadmaw", "Elvish Warrior")) {
                    StrategicThreatCheck.board(f,name);
                }
                StrategicThreatCheck.add(f,"Colossal Dreadmaw",ZoneType.Graveyard);
                var opposing = new StrategicThreatCheck.Fixture(f.game(), f.opponent(), f.ai());
                for (String name : List.of("Grizzly Bears", "Colossal Dreadmaw", "Sol Ring")) { StrategicThreatCheck.board(opposing,name); }
            } else if (!fixtureName.equals("standard")) { throw new IllegalArgumentException(fixtureName); }
            Files.writeString(dir.resolve("fixture.txt"), fixtureName + "\n");
            int processed = 0, faces = 0;
            var catalog = new ArrayList<>(FModel.getMagicDb().getCommonCards().getUniqueCards());
            catalog.sort(Comparator.comparing(p -> p.getName()));
            Files.write(dir.resolve("catalog.txt"), catalog.stream().map(p -> p.getName()).toList(), StandardCharsets.UTF_8);
            for (var paper : catalog) {
                cardName = paper.getName(); faceName = "construction";
                Card c = null;
                try {
                    c = Card.fromPaperCard(paper, f.opponent());
                    f.opponent().getZone(ZoneType.Battlefield).add(c); c.setSickness(false);
                    for (CardStateName state : new ArrayList<>(c.getStates())) {
                        if (state == CardStateName.FaceDown || state == CardStateName.EmptyRoom) { continue; }
                        c.setState(state, false); faceName = state.name();
                        if (!(c.isCreature() || c.isArtifact() || c.isEnchantment() || c.isLand() || c.isPlaneswalker() || c.isBattle())) { continue; }
                        String stage = "assess";
                        try {
                            int first = traitId;
                            var result = StrategicThreatEvaluator.assess(f.ai(), c, null);
                            stage = "inspect";
                            inspect(c);
                            List<String> labels=new ArrayList<>();
                            int legacy=c.isCreature() ? new CreatureEvaluator() {
                                @Override protected int addValue(int value,String label) {
                                    if(value!=0) { labels.add((label.startsWith("sa:") ? "activated-ability-generic" : label)+":"+value); }
                                    return value;
                                }
                            }.evaluateCreature(c) : 0;
                            row(cards, cardName, faceName, c.getType(), c.isCreature(), result.score(), traitId - first, result.reasons(),legacy,
                                    new com.google.gson.Gson().toJson(labels),
                                    new com.google.gson.Gson().toJson(c.getKeywords().stream().map(k -> k.getKeyword().name()).toList()),
                                    c.getSVar("AIEvaluationModifier"));
                            faces++;
                        } catch (Throwable ex) {
                            row(errors, cardName, faceName, stage + ": " + ex + " cause=" + ex.getCause());
                            System.out.println("AUDIT ERROR " + cardName + " " + faceName + " " + stage);
                            ex.printStackTrace();
                        }
                    }
                } catch (Throwable ex) { row(errors, cardName, faceName, ex + " cause=" + ex.getCause()); }
                finally { if (c != null) { f.opponent().getZone(ZoneType.Battlefield).remove(c); } }
                if (++processed % 2000 == 0) { System.out.println("AUDIT " + processed + "/" + catalog.size() + " cards, " + faces + " permanent faces"); cards.flush(); traits.flush(); errors.flush(); }
            }
            System.out.println("AUDIT COMPLETE " + processed + " unique catalog entries; " + faces + " permanent faces; " + traitId + " traits");
        }
        System.exit(0);
    }
}
