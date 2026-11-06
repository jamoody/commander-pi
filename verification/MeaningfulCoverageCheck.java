import forge.ai.*;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.neo.*;
import forge.neo.platform.*;
import java.util.*;
import java.nio.file.*;

/** Meaningful-coverage controls: contrasts specific old recognition with generic points. */
public final class MeaningfulCoverageCheck {
    static List<String> results=new ArrayList<>();
    static void check(boolean ok,String message) {
        if(!ok)throw new AssertionError(message);results.add(message);System.out.println("PASS "+message);
    }
    static Map<String,Integer> legacy(Card card) {
        Map<String,Integer> labels=new LinkedHashMap<>();
        new CreatureEvaluator(){@Override protected int addValue(int v,String label){if(v!=0)labels.put(label,v);return v;}}.evaluateCreature(card);
        return labels;
    }
    public static void main(String[] args)throws Exception {
        NeoPortable.apply();GuiBase.setInterface(new NeoGuiBase(new FxUiDispatcher()));NeoBoot.loadEngine(null);
        try {
            var f=StrategicThreatCheck.fixture("Plains");
            Card one=StrategicThreatCheck.board(f,"Grizzly Bears"),five=StrategicThreatCheck.board(f,"Grizzly Bears");
            int base=ComputerUtilCard.evaluateCreature(one);
            one.addSpellAbility(AbilityFactory.getAbility("AB$ Draw | Cost$ T | NumCards$ 1 | Defined$ You",one));
            five.addSpellAbility(AbilityFactory.getAbility("AB$ Draw | Cost$ T | NumCards$ 5 | Defined$ You",five));
            check(ComputerUtilCard.evaluateCreature(one)==base+10 && ComputerUtilCard.evaluateCreature(five)==base+10,"legacy activated draw gets same generic ten points for one or five cards");
            check(StrategicThreatCheck.score(f,five)>StrategicThreatCheck.score(f,one),"new evaluator actually distinguishes draw production");
            Card crusher=StrategicThreatCheck.board(f,"Ulamog's Crusher");
            check(legacy(crusher).getOrDefault("eldrazi",0)==100,"legacy explicitly recognizes annihilator two");
            Card elf=StrategicThreatCheck.board(f,"Llanowar Elves");
            check(legacy(elf).getOrDefault("manadork",0)==10,"legacy gives specific albeit coarse mana-role recognition");
            Card cobra=StrategicThreatCheck.board(f,"Lotus Cobra");
            check(legacy(cobra).getOrDefault("landfall",0)==10,"legacy gives coarse landfall recognition");
            for(String name:List.of("Notion Thief","Dauthi Voidwalker","Opposition Agent","Vilis, Broker of Blood")) {
                Card card=StrategicThreatCheck.board(f,name);
                check(StrategicThreatCheck.score(f,card)==0 && !card.hasSVar("AIEvaluationModifier"),name+": no new bonus or custom legacy evaluation override");
            }
            Card sentinel=StrategicThreatCheck.board(f,"Esper Sentinel");
            check(StrategicThreatCheck.score(f,sentinel)>0,"recognized engine control remains positive");
            Card mulldrifter=StrategicThreatCheck.board(f,"Mulldrifter");
            check(StrategicThreatCheck.score(f,mulldrifter)==0,"spent entry draw is a correct zero for present removal production");
            Path root=Path.of(args[0]);Files.write(root.resolve("behavioral-controls.txt"),results);
            var commander=FModel.getFormats().get("Commander").getFilterRules();
            List<String> legality=new ArrayList<>();legality.add("card\tlocal_commander_allowed");
            for(var paper:FModel.getMagicDb().getCommonCards().getUniqueCards()) {
                legality.add(paper.getName()+"\t"+commander.test(paper));
            }
            Files.write(root.resolve("local-commander-filter.tsv"),legality);
            System.out.println("ALL "+results.size()+" MEANINGFUL COVERAGE CONTROLS PASSED");System.exit(0);
        }catch(Throwable t){t.printStackTrace();System.exit(1);}
    }
}
