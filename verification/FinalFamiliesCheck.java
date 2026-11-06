import forge.ai.*;
import forge.game.ability.*;
import forge.game.card.*;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.*;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.neo.*;
import forge.neo.platform.*;
import java.util.*;
import java.lang.reflect.Method;

/** Real engine fixtures for the last broad family pass, including adverse/hidden-state controls. */
public final class FinalFamiliesCheck {
    static int checks; static Method effect, opportunities;
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
    static Card board(StrategicThreatCheck.Fixture f,String n){return StrategicThreatCheck.board(f,n);}
    static int score(StrategicThreatCheck.Fixture f,Card c){return StrategicThreatCheck.score(f,c);}
    static SpellAbility make(Card c,String s){return AbilityFactory.getAbility(s,c);}
    static double value(Card c,SpellAbility sa)throws Exception{return (double)effect.invoke(null,c,sa,Collections.newSetFromMap(new IdentityHashMap<SpellAbility,Boolean>()),0);}
    static double rate(Card c,String script)throws Exception{return (double)opportunities.invoke(null,c,TriggerHandler.parseTrigger(script,c,false,null));}
    public static void main(String[] args)throws Exception{
        NeoPortable.apply();GuiBase.setInterface(new NeoGuiBase(new FxUiDispatcher()));NeoBoot.loadEngine(null);
        effect=StrategicThreatEvaluator.class.getDeclaredMethod("effectValue",Card.class,SpellAbility.class,Set.class,int.class);effect.setAccessible(true);
        opportunities=StrategicThreatEvaluator.class.getDeclaredMethod("opportunities",Card.class,Trigger.class);opportunities.setAccessible(true);
        try{run();System.out.println("ALL "+checks+" FINAL FAMILY CHECKS PASSED");System.exit(0);}catch(Throwable t){t.printStackTrace();System.exit(1);}
    }
    static void run()throws Exception{
        var f=StrategicThreatCheck.fixture("Plains"); Card h=board(f,"Grizzly Bears");
        check(rate(h,"Mode$ Phase | Phase$ Main1 | ValidPlayer$ You")==1,"first main phase opportunity");
        check(rate(h,"Mode$ Phase | Phase$ Upkeep | ValidPlayer$ Player")==4,"each player upkeep opportunity");
        check(rate(h,"Mode$ Phase | Phase$ Upkeep | ValidPlayer$ Opponent")==3,"opponent upkeep opportunity");
        check(rate(h,"Mode$ Phase | Phase$ Main1 | ValidPlayer$ You.unknown")==0,"unknown player predicate stays conservative");
        Card azusa=board(f,"Azusa, Lost but Seeking"); check(score(f,azusa)==0,"extra land allowance without supply has no invented payoff");
        StrategicThreatCheck.add(f,"Plains",ZoneType.Hand);StrategicThreatCheck.add(f,"Plains",ZoneType.Hand);
        check(score(f,azusa)>0,"extra land allowance with hand-count supply");
        Card mage=board(f,"Archmage Emeritus");check(score(f,mage)>0,"magecraft draw recognized");
        Card echo=board(f,"Archmage of Echoes");check(score(f,echo)>0,"triggered permanent spell copying recognized");
        check(value(h,make(h,"AB$ CopySpellAbility | Cost$ T | ValidTgts$ Spell"))==0,"activated copier without stack target gets no invented copy");
        check(value(h,make(h,"DB$ CopySpellAbility | Defined$ TriggeredSpellAbility"))==0,"unbound triggered copy has no invented event");
        Card dryad=board(f,"Dryad Greenseeker");check(score(f,dryad)>0,"conditional peek-to-hand uses a library prior");
        var hidden=StrategicThreatCheck.fixture("Colossal Dreadmaw");Card dryad2=board(hidden,"Dryad Greenseeker");
        check(score(f,dryad)==score(hidden,dryad2),"peek score ignores hidden library identities");
        check(value(h,make(h,"DB$ PeekAndReveal | PeekAmount$ 1 | RememberRevealed$ True"))==0,"peeking without resource payoff is not a draw");
        h.setSVar("Gift","DB$ Draw | NumCards$ 2 | Defined$ You");
        check(value(h,make(h,"DB$ ImmediateTrigger | Execute$ Gift"))==180,"immediate wrapper follows Execute");
        Card shrine=board(f,"Go-Shintai of Ancient Wars");check(score(f,shrine)==0,"immediate trigger cannot ignore its payment");
        board(f,"Mountain");check(score(f,shrine)>0,"affordable immediate-trigger payment unlocks payoff");
        var enemy=new StrategicThreatCheck.Fixture(f.game(),f.opponent(),f.ai());
        check(value(h,make(h,"DB$ Sacrifice | Defined$ Opponent | SacValid$ Creature"))==0,"edict without opposing fodder has no value");
        board(enemy,"Colossal Dreadmaw");
        check(value(h,make(h,"DB$ Sacrifice | Defined$ Opponent | SacValid$ Creature"))>0,"edict recognizes opposing fodder");
        check(value(h,make(h,"DB$ Sacrifice | Defined$ You | SacValid$ Creature"))==0,"own sacrifice alone is not production");
        check(value(h,make(h,"DB$ DamageAll | ValidPlayers$ Opponent | NumDmg$ 2"))==108,"damage-all counts three opponents");
        check(value(h,make(h,"DB$ DamageAll | ValidPlayers$ You | NumDmg$ 2"))==0,"own damage is not a threat bonus");
        check(value(h,make(h,"DB$ DamageAll | ValidCards$ Creature.YouCtrl | NumDmg$ 3"))==0,"friendly fire is not credited");
        Card opportunist=board(f,"Morbid Opportunist");check(score(f,opportunist)>0,"batch death trigger recognized");
        check(rate(h,"Mode$ ChangesZoneAll | Origin$ Battlefield | Destination$ Graveyard | ValidCards$ Card.Self")==0,"own death is not preventable recurring production");
        Card brood=board(f,"Basking Broodscale");check(score(f,brood)==0,"counter payoff without counter supply stays zero");
        h.addSpellAbility(make(h,"AB$ PutCounter | Cost$ 0 | CounterType$ P1P1 | CounterNum$ 1 | ValidTgts$ Creature.YouCtrl"));
        check(score(f,brood)>0,"public counter producer unlocks counter payoff");
        check(rate(h,"Mode$ CounterAdded | ValidCard$ Card.Self | CounterType$ P1P1 | CounterAmount$ EQ10")==0,"counter event does not assume a distant threshold is reached");
        Card green=board(f,"Ancient Greenwarden");StrategicThreatCheck.add(f,"Forest",ZoneType.Graveyard);
        check(score(f,green)>0,"graveyard YouCtrl land permission recognized");
        Card augur=board(f,"Augur of Autumn");check(score(f,augur)>0,"top-library land permission recognized without hidden lookup");
        Card permit=board(f,"Elite Vanguard");permit.addStaticAbility("Mode$ Continuous | Affected$ Creature.YouOwn | AffectedZone$ Exile | MayPlay$ True | MayPlayWithoutManaCost$ True");
        check(score(f,permit)==0,"exile permission without exiled cards has no payoff");
        StrategicThreatCheck.add(f,"Colossal Dreadmaw",ZoneType.Exile);check(score(f,permit)>0,"free-cast permission uses public exiled card");
        Card lock=board(f,"Grizzly Bears");lock.addStaticAbility("Mode$ CantBeActivated | ValidCard$ Creature | Activator$ Player");
        check(score(f,lock)==0,"symmetric lock with only own activated resources is not credited");
        for(int i=0;i<4;i++)board(enemy,"Llanowar Elves");check(score(f,lock)>0,"symmetric lock considers opposing public resources");
        Card nonManaLock=board(f,"Elite Vanguard");nonManaLock.addStaticAbility("Mode$ CantBeActivated | ValidCard$ Creature.OppCtrl | Activator$ Player | ValidSA$ Activated.!ManaAbility");
        check(score(f,nonManaLock)==0,"nonmana restriction does not claim denial of mana abilities");
        Card spellLock=board(f,"Grizzly Bears");spellLock.addStaticAbility("Mode$ CantBeCast | Caster$ Player | ValidCard$ Card");
        check(score(f,spellLock)==0,"symmetric cast restriction with only own hand is not credited");
        for(var p:f.game().getPlayers())if(p!=f.opponent()){
            var pf=new StrategicThreatCheck.Fixture(f.game(),f.opponent(),p);
            for(int i=0;i<4;i++)StrategicThreatCheck.add(pf,"Plains",ZoneType.Hand);
        }
        check(score(f,spellLock)>0,"symmetric cast restriction considers opposing hand counts");
        int first=score(f,brood);check(score(f,brood)==first,"family evaluation stable across repeated calls");
    }
}
