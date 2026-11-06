import forge.ai.*;
import forge.ai.ability.DestroyAi;
import forge.ai.ability.ChangeZoneAi;
import forge.ai.ability.DamageDealAi;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.AbilityFactory;
import forge.game.card.*;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.neo.NeoBoot;
import forge.neo.NeoPortable;
import forge.neo.platform.*;
import java.util.*;

/** Engine-backed deterministic fixtures; does not drive a complete match or bypass target selection. */
public final class StrategicThreatCheck {
    private static int passed;
    record Fixture(Game game, Player ai, Player opponent) { }
    static Fixture fixture(String hidden) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            players.add(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("AI" + i, null)));
        }
        GameRules rules = new GameRules(GameType.Commander);
        Game game = new Game(players, rules, new Match(rules, players, "Strategic threat check"));
        game.setAge(GameStage.Play);
        Player ai = game.getPlayers().get(0), opp = game.getPlayers().get(1);
        for (Player p : game.getPlayers()) { p.setLife(40, null); }
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        Fixture f = new Fixture(game, ai, opp);
        for (int i = 0; i < 80; i++) { add(f, hidden, ZoneType.Library); }
        return f;
    }
    static Card add(Fixture f, String name, ZoneType zone) {
        Card c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), f.opponent());
        f.opponent().getZone(zone).add(c);
        c.setSickness(false);
        return c;
    }
    static Card board(Fixture f, String name) { return add(f, name, ZoneType.Battlefield); }
    static int score(Fixture f, Card c) { return StrategicThreatEvaluator.score(f.ai(), c, null); }
    static void check(boolean condition, String label) {
        if (!condition) { throw new AssertionError(label); }
        passed++;
        System.out.println("PASS " + label);
    }
    static SpellAbility removal(Fixture f, String script) {
        Card host = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard("Murder"), f.ai());
        f.ai().getZone(ZoneType.Hand).add(host);
        SpellAbility sa = AbilityFactory.getAbility(script, host);
        sa.setActivatingPlayer(f.ai());
        return sa;
    }
    public static void main(String[] args) throws Exception {
        NeoPortable.apply();
        GuiBase.setInterface(new NeoGuiBase(new FxUiDispatcher()));
        NeoBoot.loadEngine(null);
        try {
            run();
            System.out.println("ALL " + passed + " CHECKS PASSED");
            System.exit(0);
        } catch (Throwable t) { t.printStackTrace(); System.exit(1); }
    }
    static void run() {
        Fixture f = fixture("Plains");
        Card winota = board(f, "Winota, Joiner of Forces");
        Card dreadmaw = board(f, "Colossal Dreadmaw");
        Card sentinel = board(f, "Esper Sentinel");
        Card bears = board(f, "Grizzly Bears");
        check(ComputerUtilCard.evaluateCreature(winota) == 221 && ComputerUtilCard.evaluateCreature(dreadmaw) == 306
                && ComputerUtilCard.evaluateCreature(sentinel) == 131 && ComputerUtilCard.evaluateCreature(bears) == 161,
                "original combat/sacrifice scores unchanged");
        CardCollection candidates = new CardCollection(List.of(winota, dreadmaw, sentinel, bears));
        check(ComputerUtilCard.getBestRemovalTargetAI(f.ai(), candidates) == winota, "Winota first with public enablers");
        System.setProperty("forge.ai.disableStrategicThreat", "true");
        check(ComputerUtilCard.getBestRemovalTargetAI(f.ai(), candidates) == dreadmaw, "rollback switch restores original removal ranking");
        System.clearProperty("forge.ai.disableStrategicThreat");
        check(ComputerUtilCard.getBestRemovalTargetAI(f.ai(), new CardCollection(List.of(dreadmaw, sentinel, bears))) == sentinel,
                "recurring taxed draw outranks ordinary body in four-player game");
        check(score(f, dreadmaw) == 0 && score(f, bears) == 0, "ordinary bodies gain no strategic bonus");
        Fixture targeted = fixture("Plains");
        Card unusual = board(targeted, "Grizzly Bears");
        unusual.addSpellAbility(AbilityFactory.getAbility("AB$ Draw | Cost$ T | ValidTgts$ Opponent | NumCards$ 3", unusual));
        check(score(targeted, unusual) == 0, "target-dependent output is not assumed to benefit its controller");
        int initial = score(f, winota);
        for (int i = 0; i < 7; i++) { add(f, "Elite Vanguard", ZoneType.Graveyard); }
        int depleted = score(f, winota);
        check(depleted < initial && depleted > 0, "seven visible eligible payoffs reduce estimated supply without assuming exhaustion");
        Card returned = f.opponent().getCardsIn(ZoneType.Graveyard).get(0);
        f.opponent().getZone(ZoneType.Graveyard).remove(returned);
        f.opponent().getZone(ZoneType.Library).add(returned);
        check(score(f, winota) > depleted, "returning a known payoff to library restores estimated capacity");
        System.out.println("WINOTA initial=" + initial + " seven-consumed=" + depleted);
        Fixture hidden = fixture("Elite Vanguard");
        Card hiddenWinota = board(hidden, "Winota, Joiner of Forces");
        board(hidden, "Colossal Dreadmaw"); board(hidden, "Esper Sentinel"); board(hidden, "Grizzly Bears");
        check(score(hidden, hiddenWinota) == initial, "hidden library identities do not affect assessment");
        int current = score(f, winota);
        f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, f.opponent());
        check(score(f, winota) < current / 2, "after attack declaration only discounted future combat is counted");
        Combat combat = new Combat(f.opponent());
        f.game().getPhaseHandler().setCombat(combat);
        combat.addAttacker(dreadmaw, f.ai());
        f.ai().setLife(5, null);
        check(ComputerUtilCard.getBestRemovalTargetAI(f.ai(), candidates) == dreadmaw, "directly lethal unblocked attacker outranks engines");
        f.ai().setLife(40, null);
        f.game().getPhaseHandler().setCombat(null);
        f.game().getPhaseHandler().devModeSet(PhaseType.MAIN1, f.opponent());
        Fixture alone = fixture("Plains");
        Card lonely = board(alone, "Winota, Joiner of Forces");
        check(score(alone, lonely) == 0, "attack engine without eligible attackers receives no current production bonus");
        Card enabler = board(alone, "Grizzly Bears");
        check(score(alone, lonely) > 0, "adding eligible attacker enables generic trigger estimate");
        int readyScore = score(alone, lonely);
        enabler.setTapped(true);
        check(score(alone, lonely) < readyScore, "tapped enabler is discounted before imminent combat");
        enabler.setTapped(false);
        lonely.getTriggers().forEach(t -> t.setSuppressed(true));
        check(score(alone, lonely) == 0, "suppressed trigger contributes nothing");
        Card mulldrifter = board(alone, "Mulldrifter");
        check(score(alone, mulldrifter) == 0, "already-realized self-ETB draw is not preventable production");
        int taxed = score(f, sentinel);
        sentinel.setBasePower(0);
        check(score(f, sentinel) == 0, "zero-power draw tax has no expected unpaid payoff");
        sentinel.setBasePower(3);
        check(score(f, sentinel) > taxed, "higher public tax increases expected payoff");
        sentinel.setBasePower(1);
        Card whisperer = board(alone, "Beast Whisperer");
        check(score(alone, whisperer) == 0, "own-cast engine with empty hand has no assumed spell supply");
        add(alone, "Plains", ZoneType.Hand); add(alone, "Plains", ZoneType.Hand);
        check(score(alone, whisperer) > 0, "same draw effect recognizes controller-cast opportunities without reading hand");
        Fixture land = fixture("Plains");
        Card mole = board(land, "Mole Man, Moloid Master");
        check(score(land, mole) == 0, "landfall engine needs a public or estimated land supply");
        add(land, "Forest", ZoneType.Graveyard);
        int sustainable = score(land, mole);
        check(sustainable > 0, "static graveyard land permission enables recurring token production");
        for (int i = 0; i < 8; i++) { add(land, "Forest", ZoneType.Graveyard); }
        check(score(land, mole) == sustainable, "land-play limit caps recursion estimate despite many graveyard lands");
        Card archdruid = board(land, "Elvish Archdruid");
        int before = score(land, archdruid);
        board(land, "Llanowar Elves");
        check(score(land, archdruid) > before, "generic affected-permanent count values anthem and scalable mana support");
        SpellAbility destroy = removal(hidden, "SP$ Destroy | Cost$ 1 B B | ValidTgts$ Creature | TgtPrompt$ Select creature");
        SpellAbility bounce = removal(hidden, "SP$ ChangeZone | Cost$ U | Origin$ Battlefield | Destination$ Hand | ValidTgts$ Creature");
        check(StrategicThreatEvaluator.score(hidden.ai(), hiddenWinota, bounce)
                < StrategicThreatEvaluator.score(hidden.ai(), hiddenWinota, destroy), "bounce receives temporary-denial discount");
        board(hidden, "Grizzly Bears"); board(hidden, "Grizzly Bears");
        var decision = new DestroyAi().chkDrawback(hidden.ai(), destroy);
        check(decision.willingToPlay(destroy) && destroy.getTargetCard() == hiddenWinota,
                "real DestroyAi path chooses Winota and passes spend-removal gate");
        SpellAbility exile = removal(hidden, "SP$ ChangeZone | Cost$ 1 W | Origin$ Battlefield | Destination$ Exile | ValidTgts$ Creature");
        new ChangeZoneAi().chkDrawback(hidden.ai(), exile);
        check(exile.getTargetCard() == hiddenWinota, "real exile target-selection path chooses engine");
        SpellAbility burn = removal(hidden, "SP$ DealDamage | Cost$ 1 R | NumDmg$ 4 | ValidTgts$ Creature");
        new DamageDealAi().chkDrawback(hidden.ai(), burn);
        check(burn.getTargetCard() == hiddenWinota, "real damage target-selection path chooses killable engine");
        for (int i = 0; i < 20; i++) {
            check(ComputerUtilCard.useRemovalNow(destroy, hiddenWinota, 0, ZoneType.Graveyard),
                    "urgent engine removal is not rejected by random combat-only heuristic " + i);
        }
        for (String name : List.of("Rhystic Study", "Consecrated Sphinx", "Solemn Simulacrum", "Avenger of Zendikar",
                "Tireless Tracker", "Tireless Provisioner", "Blood Artist", "Soul Warden", "Krenko, Mob Boss")) {
            Card card = board(land, name);
            int value = score(land, card);
            check(value >= 0 && value <= 1200, "bounded fallback/classification: " + name);
        }
        long start = System.nanoTime();
        for (int i = 0; i < 500; i++) { ComputerUtilCard.getBestRemovalTargetAI(hidden.ai(), hidden.opponent().getCreaturesInPlay(), destroy); }
        System.out.printf(java.util.Locale.ROOT, "PERFORMANCE 500 complete candidate rankings: %.1f ms%n", (System.nanoTime() - start) / 1e6);
        System.out.println("EXPLANATION " + StrategicThreatEvaluator.assess(hidden.ai(), hiddenWinota, destroy));
        var catalog = new ArrayList<>(FModel.getMagicDb().getCommonCards().getUniqueCards());
        Collections.shuffle(catalog, new Random(20261104));
        Fixture sweep = fixture("Plains");
        int sampled = 0;
        for (var paper : catalog) {
            if (!paper.getRules().getType().isCreature()) { continue; }
            Card card = Card.fromPaperCard(paper, sweep.opponent());
            sweep.opponent().getZone(ZoneType.Battlefield).add(card);
            try {
                int value = score(sweep, card);
                if (value < 0 || value > 1200) { throw new AssertionError("unbounded score " + value); }
            } catch (Throwable e) { throw new AssertionError("catalog evaluation: " + paper.getName(), e); }
            finally { sweep.opponent().getZone(ZoneType.Battlefield).remove(card); }
            if (++sampled == 1000) { break; }
        }
        check(sampled == 1000, "1000 seeded catalog creatures evaluate without exceptions or unbounded scores");
    }
}
