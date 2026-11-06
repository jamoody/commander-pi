import forge.ai.*;
import forge.game.ability.*;
import forge.game.card.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.neo.*;
import forge.neo.platform.*;
import java.util.*;
import java.lang.reflect.Method;

public final class SharedPatternsCheck {
    static int checks;static Method effect;
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
    static Card board(StrategicThreatCheck.Fixture f,String n){return StrategicThreatCheck.board(f,n);}
    static int score(StrategicThreatCheck.Fixture f,Card c){return StrategicThreatCheck.score(f,c);}
    static double value(Card c,SpellAbility sa)throws Exception{return (double)effect.invoke(null,c,sa,Collections.newSetFromMap(new IdentityHashMap<SpellAbility,Boolean>()),0);}
    static SpellAbility make(Card c,String s){return AbilityFactory.getAbility(s,c);}
    public static void main(String[] args)throws Exception{
        NeoPortable.apply();GuiBase.setInterface(new NeoGuiBase(new FxUiDispatcher()));NeoBoot.loadEngine(null);
        effect=StrategicThreatEvaluator.class.getDeclaredMethod("effectValue",Card.class,SpellAbility.class,Set.class,int.class);effect.setAccessible(true);
        try{run();System.out.println("ALL "+checks+" SHARED PATTERN CHECKS PASSED");System.exit(0);}catch(Throwable t){t.printStackTrace();System.exit(1);}
    }
    static void run()throws Exception{
        var f=StrategicThreatCheck.fixture("Plains");
        Card host=board(f,"Grizzly Bears");
        double alias=value(host,make(host,"DB$ LoseLife | Defined$ Player.Opponent | LifeAmount$ 1"));
        check(alias==48 && alias==value(host,make(host,"DB$ LoseLife | Defined$ Opponent | LifeAmount$ 1")),"opponent aliases agree across three opponents");
        check(value(host,make(host,"DB$ LoseLife | Defined$ You | LifeAmount$ 1"))==0,"own life loss not credited as hostile damage");
        check(value(host,make(host,"AB$ PutCounter | Cost$ 4 | CounterType$ P1P1 | CounterNum$ 1"))==20,"omitted counter recipient means self");
        check(value(host,make(host,"DB$ Token | TokenScript$ w_1_1_warrior | TokenOwner$ TriggeredAttackerController"))==0,"triggered recipient without matching event has no invented owner");
        Card naj=board(f,"Najeela, the Blade-Blossom");check(score(f,naj)>0,"Najeela token recipient resolved from own attack opportunities");
        Card kor=board(f,"Korvold, Fae-Cursed King");check(score(f,kor)>0,"sacrifice engine recognizes visible sacrifice enabler and fodder");
        Card magda=board(f,"Magda, Brazen Outlaw");check(score(f,magda)>0,"tap trigger recognizes creature's attack opportunity");
        Card arch=board(f,"Archangel of Thune");check(score(f,arch)>0,"life gain from public lifelink enables group counters");
        var enemy=new StrategicThreatCheck.Fixture(f.game(),f.opponent(),f.ai());
        double own=value(host,make(host,"DB$ PutCounterAll | CounterType$ P1P1 | CounterNum$ 1 | ValidCards$ Creature.YouCtrl"));
        board(enemy,"Colossal Dreadmaw");
        check(value(host,make(host,"DB$ PutCounterAll | CounterType$ P1P1 | CounterNum$ 1 | ValidCards$ Creature.YouCtrl"))==own,"friendly group buffs ignore opposing creatures");
        check(value(host,make(host,"DB$ PumpAll | NumAtt$ 2 | NumDef$ 2 | ValidCards$ Creature.OppCtrl"))==0,"opponent-only buff does not become a threat bonus");
        Card hull=board(f,"Hullbreaker Horror");check(score(f,hull)==0,"spell trigger needs some hand supply");
        StrategicThreatCheck.add(f,"Plains",ZoneType.Hand);check(score(f,hull)>0,"Hullbreaker modal bounce recognizes a public opponent target");
        host.setSVar("Choice1","DB$ Draw | NumCards$ 1 | Defined$ You");
        host.setSVar("Choice3","DB$ Draw | NumCards$ 3 | Defined$ You");
        check(value(host,make(host,"DB$ Charm | Choices$ Choice1,Choice3"))==270,"modal choices use best payoff rather than sum");
        check(value(host,make(host,"DB$ Charm | Choices$ Choice1,Choice3 | CharmNum$ 2"))==0,"unsupported multiple-choice combination stays conservative");
        var g=StrategicThreatCheck.fixture("Plains");Card muld=board(g,"Muldrotha, the Gravetide");
        StrategicThreatCheck.add(g,"Grizzly Bears",ZoneType.Graveyard);check(score(g,muld)==0,"graveyard permission requires mana to cast nonland");
        board(g,"Forest");board(g,"Forest");check(score(g,muld)>0,"nonland graveyard permission values affordable public target");
        Card nadu=board(g,"Nadu, Winged Wisdom");check(score(g,nadu)==0,"granted target trigger without targeting enabler has no invented events");
        int triggerCount=nadu.getTriggers().size();board(g,"Shuko");
        check(score(g,nadu)>0,"granted trigger follows static SVar references and visible targeting enabler");
        check(nadu.getTriggers().size()==triggerCount,"grant analysis does not mutate live trigger list");
        int first=score(g,nadu);check(score(g,nadu)==first,"repeat evaluation stable without accumulating granted abilities");
        Card cut=board(f,"Zulaport Cutthroat");
        check(value(cut,cut.getTriggers().get(0).ensureAbility())==56,"Zulaport includes both drain and life gain");
        Card pur=board(f,"Purphoros, God of the Forge");check(score(f,pur)>0,"Purphoros opposing-player damage recognized");
        Card adeline=board(f,"Adeline, Resplendent Cathar");check(score(f,adeline)>0,"attack-declaration event and bounded per-opponent repetition recognized");
        check(value(host,make(host,"DB$ Counter | ValidTgts$ Spell"))==0,"no counterspell value with empty stack");
        check(value(host,make(host,"DB$ Discard | Defined$ Player.Opponent | NumCards$ 1"))==0,"discard has no value against empty hands");
        StrategicThreatCheck.add(enemy,"Plains",ZoneType.Hand);
        check(value(host,make(host,"DB$ Discard | Defined$ Player.Opponent | NumCards$ 1"))>0,"discard recognizes public opposing hand supply");
        check(value(host,make(host,"DB$ GainControl | ValidTgts$ Creature.OppCtrl"))>0,"repeatable theft sees legal public target");
        check(value(host,make(host,"DB$ Tap | ValidTgts$ Creature.OppCtrl"))>0,"repeatable tap sees legal public target");
        var grants=StrategicThreatCheck.fixture("Plains");Card grantor=board(grants,"Grizzly Bears");
        grantor.setSVar("GiftDraw","AB$ Draw | Cost$ 3 U | NumCards$ 1 | Defined$ You");
        grantor.addStaticAbility("Mode$ Continuous | Affected$ Creature.Other+YouCtrl | AddAbility$ GiftDraw");
        board(grants,"Elite Vanguard");check(score(grants,grantor)==0,"granted activation requires beneficiary resources");
        for(int i=0;i<4;i++)board(grants,"Island");
        check(score(grants,grantor)>0,"granted activated ability is attributed to grantor");
        grantor.setSVar("TickDraw","DB$ Draw | NumCards$ 1 | Defined$ You");
        grantor.setSVar("Tick","Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | Execute$ TickDraw");
        check(value(grantor,make(grantor,"DB$ Effect | Triggers$ Tick"))==90,"permanent generated effect follows trigger references");
        check(value(grantor,make(grantor,"DB$ Effect | Triggers$ Tick | Duration$ UntilEndOfTurn"))==0,"temporary generated effect does not invent a later upkeep");
        check(value(grantor,make(grantor,"DB$ Effect | Abilities$ GiftDraw"))>0,"permanent generated effect follows ability references");
    }
}
