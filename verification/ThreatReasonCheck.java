import forge.ai.*;
import forge.game.ability.AbilityFactory;
import forge.game.card.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.neo.*;
import forge.neo.platform.*;
import java.lang.reflect.Method;
import java.util.*;
import java.nio.file.*;

/** Audit probes deliberately assert both supported behavior and currently observed gaps. */
public final class ThreatReasonCheck {
    static Method effect;static List<String> results=new ArrayList<>();
    static void check(boolean pass,String group,String label,String evidence) {
        if(!pass)throw new AssertionError(label+": "+evidence);
        results.add(group+"\t"+label+"\t"+evidence);System.out.println("PASS "+label+" "+evidence);
    }
    static Card board(StrategicThreatCheck.Fixture f,String name){return StrategicThreatCheck.board(f,name);}
    static int score(StrategicThreatCheck.Fixture f,Card c){return StrategicThreatCheck.score(f,c);}
    static double value(Card c,SpellAbility sa)throws Exception{return (double)effect.invoke(null,c,sa,Collections.newSetFromMap(new IdentityHashMap<SpellAbility,Boolean>()),0);}
    static SpellAbility root(Card c){return c.getTriggers().get(0).ensureAbility();}
    public static void main(String[] args)throws Exception {
        NeoPortable.apply();GuiBase.setInterface(new NeoGuiBase(new FxUiDispatcher()));NeoBoot.loadEngine(null);
        effect=StrategicThreatEvaluator.class.getDeclaredMethod("effectValue",Card.class,SpellAbility.class,Set.class,int.class);effect.setAccessible(true);
        try {run();results.add(0,"category\tcheck\tevidence");Files.write(Path.of(args[0]),results);
            System.out.println("ALL "+(results.size()-1)+" REASON CHECKS PASSED");System.exit(0);
        }catch(Throwable t){t.printStackTrace();System.exit(1);}
    }
    static void run()throws Exception {
        var f=StrategicThreatCheck.fixture("Plains");
        for(String name:List.of("Grizzly Bears","Colossal Dreadmaw","Mulldrifter","Ravenous Chupacabra")) {
            Card c=board(f,name);check(score(f,c)==0,"proper-zero",name,"no future-production bonus for body/spent entry");
        }
        Card mage=board(f,"Azure Mage");for(int i=0;i<4;i++)board(f,"Forest");
        int a=score(f,mage);board(f,"Island");int b=score(f,mage);
        check(a==0&&b>0,"conditional","Azure Mage colored mana",a+" -> "+b);
        var g=StrategicThreatCheck.fixture("Plains");Card keeper=board(g,"Hell's Caretaker");board(g,"Grizzly Bears");
        a=score(g,keeper);StrategicThreatCheck.add(g,"Colossal Dreadmaw",ZoneType.Graveyard);b=score(g,keeper);
        check(a==0&&b>0,"conditional","Hell's Caretaker graveyard supply",a+" -> "+b);
        var h=StrategicThreatCheck.fixture("Plains");Card mondrak=board(h,"Mondrak, Glory Dominus");
        a=score(h,mondrak);board(h,"Krenko, Mob Boss");b=score(h,mondrak);
        check(a==0&&b>0,"conditional","Mondrak visible token producer",a+" -> "+b);
        Card ballista=board(h,"Walking Ballista");a=score(h,ballista);ballista.setCounters(CounterType.getType("P1P1"),2);b=score(h,ballista);
        check(a==0&&b>0,"conditional-and-partial","Walking Ballista counter supply",a+" -> "+b+"; counter generation separately unsupported");
        SpellAbility counterAbility=ballista.getSpellAbilities().stream().filter(sa->sa.isActivatedAbility() && sa.getApi()==forge.game.ability.ApiType.PutCounter).findFirst().orElseThrow();
        double implicit=value(ballista,counterAbility);
        double explicit=value(ballista,AbilityFactory.getAbility("AB$ PutCounter | Cost$ 4 | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1",ballista));
        check(implicit==0&&explicit>0,"confirmed-family","Implicit self counter recipient",implicit+" versus explicit Self "+explicit);
        var q=StrategicThreatCheck.fixture("Plains");
        for(int i=0;i<3;i++)for(String land:List.of("Plains","Island","Swamp","Mountain","Forest"))board(q,land);
        board(q,"Elvish Warrior");board(q,"Mulldrifter");StrategicThreatCheck.add(q,"Plains",ZoneType.Hand);
        var enemy=new StrategicThreatCheck.Fixture(q.game(),q.opponent(),q.ai());board(enemy,"Sol Ring");board(enemy,"Grizzly Bears");board(enemy,"Howling Mine");
        for(String name:List.of("Hullbreaker Horror","Korvold, Fae-Cursed King","Notion Thief","Collector Ouphe")) {
            Card c=board(q,name);check(score(q,c)==0,"confirmed-example",name,"zero despite relevant public setup; unsupported script path verified in source");
        }
        Card naj=board(q,"Najeela, the Blade-Blossom");double x=value(naj,root(naj));
        SpellAbility direct=AbilityFactory.getAbility("DB$ Token | TokenScript$ w_1_1_warrior | TokenOwner$ You",naj);
        double y=value(naj,direct);check(x==0&&y>0,"confirmed-family","Triggered token recipient",x+" versus equivalent own-recipient "+y);
        Card purphoros=board(q,"Purphoros, God of the Forge");x=value(purphoros,root(purphoros));
        direct=AbilityFactory.getAbility("DB$ DealDamage | NumDmg$ 2 | Defined$ Opponent",purphoros);y=value(purphoros,direct);
        check(x==0&&y>0,"confirmed-family","Player.Opponent damage recipient",x+" versus accepted alias "+y);
        Card cut=board(q,"Zulaport Cutthroat");x=value(cut,root(cut));
        direct=AbilityFactory.getAbility("DB$ LoseLife | LifeAmount$ 1 | Defined$ Opponent",cut);y=value(cut,direct);
        check(x==8&&y>0&&score(q,cut)>0,"partial","Zulaport counts gain but misses drain","chain value "+x+"; missing drain alone "+y);
        Card terror=board(q,"Terror of the Peaks");x=value(terror,root(terror));
        direct=AbilityFactory.getAbility("DB$ DealDamage | NumDmg$ 3 | ValidTgts$ Any",terror);y=value(terror,direct);
        check(x==0&&y>0,"confirmed-family","Event-derived creature power",x+" versus known numeric power "+y);
        Card muld=board(q,"Muldrotha, the Gravetide");StrategicThreatCheck.add(q,"Forest",ZoneType.Graveyard);a=score(q,muld);
        StrategicThreatCheck.add(q,"Colossal Dreadmaw",ZoneType.Graveyard);b=score(q,muld);
        check(a>0&&a==b,"partial","Muldrotha nonland graveyard permission",a+" unchanged at "+b+" after creature added");
    }
}
