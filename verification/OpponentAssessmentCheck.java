import forge.ai.*;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.neo.*;
import forge.neo.platform.*;
import java.util.*;

public final class OpponentAssessmentCheck {
    static int checks;
    static void check(boolean b,String text){if(!b)throw new AssertionError(text);checks++;System.out.println("PASS "+text);}
    static Card card(StrategicThreatCheck.Fixture f,Player p,String name){return StrategicThreatCheck.board(new StrategicThreatCheck.Fixture(f.game(),f.ai(),p),name);}
    public static void main(String[] args)throws Exception{
        NeoPortable.apply();GuiBase.setInterface(new NeoGuiBase(new FxUiDispatcher()));NeoBoot.loadEngine(null);
        try{run();System.out.println("ALL "+checks+" OPPONENT CHECKS PASSED");System.exit(0);}catch(Throwable t){t.printStackTrace();System.exit(1);}
    }
    static void run()throws Exception{
        var f=StrategicThreatCheck.fixture("Plains");Player ai=f.ai(),a=f.opponent(),b=f.game().getPlayers().get(2),c=f.game().getPlayers().get(3);
        f.game().getPhaseHandler().devModeSet(PhaseType.MAIN1,ai);
        var before=OpponentAssessment.assess(ai,a);
        for(int i=0;i<20;i++)StrategicThreatCheck.add(f,"Plains",ZoneType.Hand);
        a.setLife(5,null);check(before.equals(OpponentAssessment.assess(ai,a)),"hand size and low life give no independent threat bonus");
        Card engine=card(f,b,"Grizzly Bears");engine.addSpellAbility(AbilityFactory.getAbility("AB$ Draw | Cost$ T | NumCards$ 5 | Defined$ You",engine));
        card(f,c,"Colossal Dreadmaw");
        check(OpponentAssessment.assess(ai,b).engines()>OpponentAssessment.assess(ai,c).engines(),"productive engine distinguished from combat body");
        check(OpponentAssessment.preferred(ai,false)==b,"productive engine outranks empty low-life player");
        for(int i=0;i<10;i++)check(OpponentAssessment.preferred(ai,false)==b,"opponent selection deterministic "+i);
        Card murder=card(f,ai,"Grizzly Bears");var burn=AbilityFactory.getAbility("AB$ DealDamage | Cost$ 0 | ValidTgts$ Player | NumDmg$ 3",murder);burn.setActivatingPlayer(ai);
        check(OpponentAssessment.damageTarget(ai,List.of(a,b,c),burn,3)==b,"nonlethal burn follows strategic threat");
        a.setLife(3,null);check(OpponentAssessment.damageTarget(ai,List.of(a,b,c),burn,3)==a,"credible burn elimination overrides strategic threat");
        new forge.ai.ability.DamageDealAi().chkDrawback(ai,burn);
        check(burn.getTargets().contains(a),"actual damage handler selects the achievable elimination");
        var discard=AbilityFactory.getAbility("AB$ Discard | Cost$ 0 | ValidTgts$ Opponent | NumCards$ 1",murder);discard.setActivatingPlayer(ai);
        StrategicThreatCheck.add(new StrategicThreatCheck.Fixture(f.game(),ai,b),"Plains",ZoneType.Hand);
        var discardMethod=forge.ai.ability.DiscardAi.class.getDeclaredMethod("discardTargetAI",Player.class,forge.game.spellability.SpellAbility.class);discardMethod.setAccessible(true);
        check((boolean)discardMethod.invoke(new forge.ai.ability.DiscardAi(),ai,discard) && discard.getTargets().contains(b),"actual discard handler targets the stronger player rather than larger hand");
        a.setLife(40,null);
        var blocked=StrategicThreatCheck.fixture("Plains");blocked.game().getPhaseHandler().devModeSet(PhaseType.MAIN1,blocked.ai());
        Card bear=card(blocked,blocked.ai(),"Grizzly Bears");card(blocked,blocked.opponent(),"Colossal Dreadmaw");
        check(OpponentAssessment.plan(blocked.ai(),blocked.opponent()).attackers().isEmpty(),"declines an attack that only loses a creature");
        var open=StrategicThreatCheck.fixture("Plains");open.game().getPhaseHandler().devModeSet(PhaseType.MAIN1,open.ai());
        Card attacker=card(open,open.ai(),"Grizzly Bears");
        var hit=OpponentAssessment.plan(open.ai(),open.opponent());
        check(hit.damage()==2 && hit.attackers().contains(attacker),"safe meaningful damage produces an attack plan");
        open.opponent().setLife(2,null);check(OpponentAssessment.plan(open.ai(),open.opponent()).lethal(),"realistic combat elimination recognized");
        open.opponent().setLife(40,null);attacker.setCommander(true);attacker.setBasePower(21);
        check(OpponentAssessment.plan(open.ai(),open.opponent()).lethal(),"commander damage elimination recognized");
        attacker.setCommander(false);attacker.setBasePower(2);
        var live=open.game().getCombat();boolean tapped=attacker.isTapped();int triggers=attacker.getTriggers().size();
        OpponentAssessment.plan(open.ai(),open.opponent());
        check(open.game().getCombat()==live && attacker.isTapped()==tapped && attacker.getTriggers().size()==triggers,"forecast does not replace live combat or mutate tapped/trigger state");
        var defence=StrategicThreatCheck.fixture("Plains");defence.game().getPhaseHandler().devModeSet(PhaseType.MAIN1,defence.ai());defence.ai().setLife(4,null);
        Card guard=card(defence,defence.ai(),"Colossal Dreadmaw");
        Player rival=defence.game().getPlayers().get(2);card(defence,rival,"Colossal Dreadmaw");
        check(OpponentAssessment.plan(defence.ai(),defence.opponent()).attackers().isEmpty(),"retains blocker instead of exposing itself to lethal for incidental damage");
        var menace=StrategicThreatCheck.fixture("Plains");menace.game().getPhaseHandler().devModeSet(PhaseType.MAIN1,menace.ai());
        Card evasive=card(menace,menace.ai(),"Boggart Brute");card(menace,menace.opponent(),"Grizzly Bears");
        check(OpponentAssessment.plan(menace.ai(),menace.opponent()).damage()==3,"single blocker cannot block menace");
        var rewards=StrategicThreatCheck.fixture("Plains");rewards.game().getPhaseHandler().devModeSet(PhaseType.MAIN1,rewards.ai());
        Card rewarded=card(rewards,rewards.ai(),"Grizzly Bears");rewarded.setSVar("Payoff","DB$ Draw | Defined$ You | NumCards$ 3");
        rewarded.addTrigger(forge.game.trigger.TriggerHandler.parseTrigger("Mode$ Attacks | ValidCard$ Card.Self | Execute$ Payoff",rewarded,false,null));
        card(rewards,rewards.opponent(),"Wall of Denial");
        var rewardPlan=OpponentAssessment.plan(rewards.ai(),rewards.opponent());
        check(rewardPlan.damage()==0 && rewardPlan.attackers().contains(rewarded),"valuable attack trigger can justify an attack with no player damage");
        var winota=StrategicThreatCheck.fixture("Plains");winota.game().getPhaseHandler().devModeSet(PhaseType.MAIN1,winota.ai());
        Card leader=card(winota,winota.ai(),"Winota, Joiner of Forces");leader.setSickness(true);
        Card nonhuman=card(winota,winota.ai(),"Grizzly Bears");card(winota,winota.opponent(),"Wall of Denial");
        StrategicThreatCheck.add(new StrategicThreatCheck.Fixture(winota.game(),winota.opponent(),winota.ai()),"Elite Vanguard",ZoneType.Library);
        check(OpponentAssessment.plan(winota.ai(),winota.opponent()).attackers().contains(nonhuman),"nonattacking Winota rewards a qualifying creature's attack");
        var pending=StrategicThreatCheck.fixture("Plains");Card source=card(pending,pending.opponent(),"Grizzly Bears");
        int dangerBefore=OpponentAssessment.assess(pending.ai(),pending.opponent()).immediateDanger();
        var lethal=AbilityFactory.getAbility("AB$ DealDamage | Cost$ 0 | ValidTgts$ Player | NumDmg$ 40",source);lethal.setActivatingPlayer(pending.opponent());lethal.getTargets().add(pending.ai());pending.game().getStack().add(lethal);
        check(OpponentAssessment.assess(pending.ai(),pending.opponent()).immediateDanger()>dangerBefore,"visible lethal damage on stack raises immediate danger");
        var tie=StrategicThreatCheck.fixture("Plains");
        check(OpponentAssessment.preferred(tie.ai(),false)==tie.opponent(),"equal threats use stable seat order");
        // Run real attack-controller integration on a simple position, with original legality enforcement.
        open.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS,open.ai());
        Combat combat=new Combat(open.ai());new AiAttackController(open.ai()).declareAttackers(combat);
        check(combat.getAttackers().contains(attacker),"real attack controller executes a useful safe attack");
        long start=System.nanoTime();for(int i=0;i<20;i++)OpponentAssessment.preferred(f.ai(),true);
        System.out.printf(java.util.Locale.ROOT,"PERFORMANCE 20 four-player decisions %.1f ms%n",(System.nanoTime()-start)/1e6);
        var catalog=new ArrayList<>(forge.model.FModel.getMagicDb().getCommonCards().getUniqueCards());
        Collections.shuffle(catalog,new Random(20261106));
        var sweep=StrategicThreatCheck.fixture("Plains");sweep.game().getPhaseHandler().devModeSet(PhaseType.MAIN1,sweep.ai());
        card(sweep,sweep.ai(),"Grizzly Bears");card(sweep,sweep.opponent(),"Grizzly Bears");
        int sampled=0;
        for(var paper:catalog){
            if(!paper.getRules().getType().isPermanent())continue;
            Card sample=Card.fromPaperCard(paper,sweep.opponent());sweep.opponent().getZone(ZoneType.Battlefield).add(sample);
            try{
                var position=OpponentAssessment.assess(sweep.ai(),sweep.opponent());
                var plan=OpponentAssessment.plan(sweep.ai(),sweep.opponent());
                if(position.total()<0 || position.total()>2450 || !Double.isFinite(plan.value()))throw new AssertionError("invalid assessment");
            }catch(Throwable e){throw new AssertionError("opponent catalog sweep: "+paper.getName(),e);}
            finally{sweep.opponent().getZone(ZoneType.Battlefield).remove(sample);}
            if(++sampled==150)break;
        }
        check(sampled==150,"150 seeded permanent boards assess and plan without exceptions or unbounded scores");
    }
}
