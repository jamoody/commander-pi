import forge.ai.*;
import forge.game.ability.AbilityFactory;
import forge.game.card.*;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.neo.*;
import forge.neo.platform.*;
import java.util.List;

/** Behavioral checks for shared interpretation, including correct zero and negative cases. */
public final class ExpandedThreatCheck {
    static int checks;
    static void check(boolean ok, String label) {
        if (!ok) { throw new AssertionError(label); }
        System.out.println("PASS " + label); checks++;
    }
    static int score(StrategicThreatCheck.Fixture f, Card c) { return StrategicThreatEvaluator.score(f.ai(), c, null); }
    static Card add(StrategicThreatCheck.Fixture f, String name) { return StrategicThreatCheck.board(f, name); }
    public static void main(String[] args) throws Exception {
        NeoPortable.apply(); GuiBase.setInterface(new NeoGuiBase(new FxUiDispatcher())); NeoBoot.loadEngine(null);
        try { run(); System.out.println("ALL " + checks + " EXPANDED CHECKS PASSED"); System.exit(0); }
        catch (Throwable t) { t.printStackTrace(); System.exit(1); }
    }
    static void run() {
        var f = StrategicThreatCheck.fixture("Plains");
        Card alena = add(f, "Alena, Kessig Trapper");
        check(score(f, alena) >= 0, "Alena aggregate expression does not throw");
        Card generic = add(f, "Grizzly Bears");
        generic.setSVar("X", "Count$Valid Creature.YouCtrl$GreatestCardPower");
        generic.addSpellAbility(AbilityFactory.getAbility("AB$ Mana | Cost$ T | Produced$ R | Amount$ X", generic));
        int small = score(f, generic);
        add(f, "Colossal Dreadmaw");
        check(score(f, generic) > small, "public greatest-power aggregate scales with board");
        generic.setSVar("X", "Count$Valid Creature.YouCtrl$UnsupportedAggregate");
        check(score(f, generic) == 0, "unknown aggregate safely yields no speculative bonus");
        var costs = StrategicThreatCheck.fixture("Plains");
        Card mage = add(costs, "Azure Mage");
        for (int i=0;i<4;i++) { add(costs,"Forest"); }
        check(score(costs, mage) == 0, "colored activation requires matching public mana source");
        add(costs,"Island");
        check(score(costs, mage) > 0, "colored activation recognized with matching source and enough mana");
        Card yawg = add(costs, "Yawgmoth, Thran Physician");
        int before = score(costs, yawg);
        Card fodder = add(costs, "Grizzly Bears");
        check(score(costs, yawg) > before, "sacrifice draw engine scales with eligible public fodder");
        costs.opponent().setLife(1, null);
        check(score(costs, yawg) == 0, "life payment stops being assumed when controller cannot afford it safely");
        costs.opponent().setLife(40, null);
        var empty = StrategicThreatCheck.fixture("Plains");
        Card loneYawg = add(empty,"Yawgmoth, Thran Physician");
        check(score(empty,loneYawg)==0,"another-creature sacrifice cannot use source or nonexistent fodder");
        Card keeper = add(empty,"Hell's Caretaker");
        check(score(empty,keeper)==0,"recursion requires an eligible graveyard target");
        StrategicThreatCheck.add(empty,"Colossal Dreadmaw",ZoneType.Graveyard);
        check(score(empty,keeper)>0,"targeted graveyard recursion values public recoverable creature");
        Card bloom = add(empty,"Bloom Tender");
        int mono = score(empty,bloom);
        add(empty,"Elite Vanguard"); add(empty,"Goblin Guide");
        check(score(empty,bloom)>mono,"special mana output scales with public permanent colors");
        var protection=StrategicThreatCheck.fixture("Plains");
        check(score(protection,add(protection,"Grand Abolisher"))>0,"recurring own-turn opponent restrictions recognized");
        Card muse=add(protection,"Seedborn Muse");
        check(score(protection,muse)==0,"untap support has no invented resources");
        add(protection,"Forest");
        check(score(protection,muse)>0,"untap support values actual public mana resources");
        Card elect=add(protection,"Goblin Electromancer");
        check(score(protection,elect)==0,"cost reduction has no assumed spells with empty hand");
        StrategicThreatCheck.add(protection,"Plains",ZoneType.Hand);
        check(score(protection,elect)>0,"cost reduction uses hand size without inspecting identities");
        var triggers=StrategicThreatCheck.fixture("Plains");
        for(String name:List.of("Sheoldred, the Apocalypse","Edric, Spymaster of Trest","Tymna the Weaver","Archivist of Oghma")) {
            Card c=add(triggers,name);check(score(triggers,c)>0,"new recurring trigger/effect recognized: "+name);
        }
        Card artist=add(triggers,"Blood Artist");
        check(score(triggers,artist)>0,"other-creature death production recognized");
        var selfDeath=StrategicThreatCheck.fixture("Plains");
        check(score(selfDeath,add(selfDeath,"Blood Artist"))==0,"source's own death payoff is not credited as prevented");
        Card doubling=add(selfDeath,"Doubling Season");
        check(score(selfDeath,doubling)==0,"token/counter multiplier has no invented producer");
        add(selfDeath,"Krenko, Mob Boss");
        check(score(selfDeath,doubling)>0,"replacement multiplier values visible token producer");
        var targets=StrategicThreatCheck.fixture("Plains");
        Card hostile=add(targets,"Grizzly Bears");
        hostile.addSpellAbility(AbilityFactory.getAbility("AB$ Draw | Cost$ T | ValidTgts$ Opponent | NumCards$ 3",hostile));
        check(score(targets,hostile)==0,"opponent-only draw is not misclassified as controller's advantage");
        Card limited=add(targets,"Elite Vanguard");
        limited.addSpellAbility(AbilityFactory.getAbility("AB$ Draw | Cost$ 0 | Defined$ You | NumCards$ 1 | ActivationLimit$ 1",limited));
        check(score(targets,limited)==90,"once-per-turn limit bounds free draw engine");
        Card counters=add(targets,"Llanowar Elves");
        var ability=AbilityFactory.getAbility("AB$ Draw | Cost$ SubCounter<1/CHARGE> | Defined$ You | NumCards$ 1",counters);
        counters.addSpellAbility(ability);
        int noCounters=score(targets,counters);
        counters.setCounters(CounterType.getType("CHARGE"),2);
        check(score(targets,counters)>noCounters,"counter-payment output requires actual counters");
    }
}
