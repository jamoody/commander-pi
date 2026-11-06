package forge.ai;

import java.util.*;
import forge.game.card.Card;
import forge.game.ability.ApiType;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/** Deterministic public-board assessment. All caches are confined to one decision. */
public final class OpponentAssessment {
    private OpponentAssessment() { }
    public record Position(int engines, int resources, int combat, int immediateDanger) {
        public int total() { return engines+resources+combat+immediateDanger; }
    }
    public record Attack(Player defender, List<Card> attackers, double value, int damage,
                         boolean lethal, boolean exposed) { }
    private record Forecast(Combat combat, int damage, boolean lethal, double losses, double gains) { }

    private static final class Decision {
        final Player viewer;
        final Map<Player,Position> positions=new IdentityHashMap<>();
        final Map<Card,Integer> values=new IdentityHashMap<>();
        Decision(Player viewer) { this.viewer=viewer; }
        int value(Card c) {
            return values.computeIfAbsent(c,card -> {
                Player observer=card.getController().isOpponentOf(viewer)?viewer:
                        viewer.getOpponents().stream().findFirst().orElse(viewer);
                return Math.max(20,ComputerUtilCard.evaluateCreature(card))
                        +Math.min(300,StrategicThreatEvaluator.score(observer,card,null));
            });
        }
        Position position(Player p) { return positions.computeIfAbsent(p,x->measure(viewer,x)); }
    }
    public static Position assess(Player viewer, Player opponent) { return measure(viewer,opponent); }
    private static Position measure(Player viewer,Player p) {
        List<Integer> engines=new ArrayList<>();int resources=0,combat=0,scanned=0;
        Player observer=viewer!=null && p.isOpponentOf(viewer)?viewer:p.getOpponents().stream().findFirst().orElse(p);
        for (Card c:p.getCardsIn(ZoneType.Battlefield)) {
            if (++scanned>96) { break; }
            if (c.isPhasedOut() || c.isFaceDown()) { continue; }
            int production=StrategicThreatEvaluator.score(observer,c,null);
            if (production>0) { engines.add(Math.min(400,production)); }
            if (!c.getManaAbilities().isEmpty()) { resources+=c.isTapped()?3:8; }
            if (c.isCreature()) { combat+=Math.max(0,ComputerUtilCard.evaluateCreature(c))/6; }
            else if (c.isPlaneswalker()) { resources+=30+10*c.getCMC(); }
            else if (!c.isLand() && production==0) { resources+=10+5*c.getCMC(); }
        }
        engines.sort(Collections.reverseOrder());double engine=0;
        for(int i=0;i<engines.size();i++) { engine+=engines.get(i)*(i==0?1:i<3?.5:.2); }
        int danger=0;
        if(viewer!=null && p.isOpponentOf(viewer)) {
            Decision d=new Decision(viewer);
            Forecast f=forecast(d,p,viewer,eligible(p,viewer,true),Set.of());
            danger=f.lethal?450:Math.min(150,f.damage*6);
            int stackScanned=0;
            for(var entry:p.getGame().getStack()) {
                if(++stackScanned>24) { break; }
                SpellAbility sa=entry.getSpellAbility();
                if(sa.getActivatingPlayer()!=p || StrategicThreatSupport.blocked(sa)) { continue; }
                if(sa.getApi()==ApiType.WinsGame && Set.of("You","Player.You").contains(sa.getParamOrDefault("Defined","You"))) { danger+=600; }
                if(sa.getApi()==ApiType.DealDamage && sa.getTargets().contains(viewer)) {
                    int amount=(int)StrategicThreatSupport.number(sa.getHostCard(),sa,sa.getParamOrDefault("NumDmg","0"));
                    if(!viewer.cantLose() && !viewer.cantLoseForZeroOrLessLife() && viewer.canLoseLife()
                            && ComputerUtilCombat.predictDamageTo(viewer,amount,sa.getHostCard(),false)>=viewer.getLife()) { danger+=600; }
                }
            }
        }
        return new Position((int)Math.min(800,engine),Math.min(250,resources),Math.min(400,combat),Math.min(1000,danger));
    }
    /** Shared ranking; no standalone hand-count or low-life bonus. Stable seat-order ties. */
    public static List<Player> rank(Player ai, Collection<Player> candidates) {
        if(Boolean.getBoolean("forge.ai.disableOpponentAssessment")) { return new ArrayList<>(candidates); }
        Decision d=new Decision(ai);List<Player> result=new ArrayList<>(candidates);
        result.sort(Comparator.<Player>comparingInt(p->d.position(p).total()).reversed()
                .thenComparingInt(p->ai.getGame().getPlayers().indexOf(p)));
        return result;
    }
    public static Player preferred(Player ai,boolean combat) {
        if(ai.getOpponents().isEmpty()) { return null; }
        if(!combat) { return rank(ai,ai.getOpponents()).get(0); }
        Decision d=new Decision(ai);Attack best=null;
        for(Player p:rank(ai,ai.getOpponents())) {
            Attack candidate=plan(d,p);
            if(best==null || candidate.value>best.value) { best=candidate; }
        }
        return best.defender;
    }
    public static Attack plan(Player ai,Player defender) { return plan(new Decision(ai),defender); }
    private static Attack plan(Decision d,Player defender) {
        List<Card> all=eligible(d.viewer,defender,false);
        all.sort(Comparator.<Card>comparingInt(c->-d.value(c)).thenComparingInt(Card::getId));
        var restrictions=new Combat(d.viewer).getAttackConstraints().getGlobalRestrictions();
        int limit=Math.min(restrictions.getMax()==null?32:restrictions.getMax(),
                restrictions.getDefenderMax().getOrDefault(defender,32));
        if(all.size()>limit) { all=new ArrayList<>(all.subList(0,Math.max(0,limit))); }
        List<List<Card>> choices=new ArrayList<>();choices.add(List.of());choices.add(all);
        List<Card> safe=new ArrayList<>();
        for(Card c:all) {
            Forecast f=forecast(d,d.viewer,defender,List.of(c),Set.of());
            if(f.losses==0 || f.gains+reward(d,c,f.combat)>f.losses) { safe.add(c); }
        }
        choices.add(safe);
        // Preserve up to four high-value defenders. This bounds search instead of enumerating subsets.
        List<Card> reserved=new ArrayList<>(all);
        for(int i=0;i<Math.min(4,all.size());i++) { reserved=new ArrayList<>(reserved);reserved.remove(all.get(i));choices.add(reserved); }
        Attack best=new Attack(defender,List.of(),0,0,false,false);
        Map<Player,Forecast> baseline=new IdentityHashMap<>();
        for(Player p:d.viewer.getOpponents()) {
            baseline.put(p,forecast(d,p,d.viewer,eligible(p,d.viewer,true),Set.of()));
        }
        for(List<Card> candidate:choices) {
            if(candidate.isEmpty()) { continue; }
            Forecast attack=forecast(d,d.viewer,defender,candidate,Set.of());
            Set<Card> unavailable=new HashSet<>();double rewards=0;
            for(Card c:candidate) {
                if(!c.hasKeyword(Keyword.VIGILANCE) || ComputerUtilCombat.combatantWouldBeDestroyed(d.viewer,c,attack.combat)) { unavailable.add(c); }
                rewards+=reward(d,c,attack.combat);
            }
            double exposure=0;boolean exposed=false;
            for(Player p:d.viewer.getOpponents()) {
                if(p==defender && attack.lethal) { continue; }
                Forecast before=baseline.get(p),after=forecast(d,p,d.viewer,eligible(p,d.viewer,true),unavailable);
                if(after.lethal) { exposed=true;exposure+=before.lethal?200:1500; }
                else {
                    // Worst-case lethal remains protected; ordinary attacks are discounted when another player is visibly ahead.
                    int ours=d.position(d.viewer).total(),other=0;
                    for(Player rival:p.getOpponents()) if(rival!=d.viewer) { other=Math.max(other,d.position(rival).total()); }
                    double likelihood=other>ours+150?.35:.7;
                    exposure+=Math.max(0,after.damage-before.damage)*12*likelihood;
                }
            }
            double strategic=1+Math.min(1,d.position(defender).total()/800.0);
            double pressure=attack.damage*8*strategic;
            double value=pressure+attack.gains-attack.losses+Math.min(250,rewards)-exposure;
            if(attack.damage>0 && (defender.isMonarch() || defender.hasInitiative())) { value+=70; }
            if(attack.lethal) { value+=1200; }
            if(attack.lethal && d.viewer.getOpponents().size()==1) { value+=10000; }
            if(value>best.value) { best=new Attack(defender,List.copyOf(candidate),value,attack.damage,attack.lethal,exposed); }
        }
        return best;
    }
    private static List<Card> eligible(Player p,Player defender,boolean future) {
        List<Card> result=new ArrayList<>();
        for(Card c:p.getCreaturesInPlay()) {
            if(result.size()>=32) { break; }
            if((future?CombatUtil.canAttackNextTurn(c,defender):CombatUtil.canAttack(c,defender))
                    && CombatUtil.getAttackCost(p.getGame(),c,defender)==null) { result.add(c); }
        }
        result.sort(Comparator.<Card>comparingInt(Card::getNetPower).reversed().thenComparingInt(Card::getId));
        return result;
    }
    /** Detached combat with deterministic plausible blocks; never registers attackers in the live game. */
    private static Forecast forecast(Decision d,Player attacker,Player defender,List<Card> cards,Set<Card> unavailable) {
        Combat combat=new Combat(attacker);
        for(Card c:cards) { combat.addAttacker(c,defender); }
        List<Card> blockers=new ArrayList<>();
        for(Card c:defender.getCreaturesInPlay()) {
            if(blockers.size()>=64) { break; }
            if(!unavailable.contains(c) && CombatUtil.canBlock(c,combat)) { blockers.add(c); }
        }
        blockers.sort(Comparator.<Card>comparingInt(d::value).thenComparingInt(Card::getId));
        for(Card a:cards) {
            List<Card> legal=new ArrayList<>();
            for(Card b:blockers) if(CombatUtil.canBlock(a,b,combat)) { legal.add(b);if(legal.size()>=12) { break; } }
            List<Card> chosen=List.of();double best=0;
            for(int n=1;n<=Math.min(3,legal.size());n++) {
                if(!CombatUtil.canAttackerBeBlockedWithAmount(a,n,combat)) { continue; }
                for(int start=0;start+n<=legal.size();start++) {
                    List<Card> group=new ArrayList<>(legal.subList(start,start+n));
                    for(Card b:group) { combat.addBlocker(a,b); }
                    combat.setBlocked(a,true);
                    double loss=0;
                    for(Card b:group) if(ComputerUtilCombat.combatantWouldBeDestroyed(defender,b,combat)) { loss+=d.value(b); }
                    double kill=ComputerUtilCombat.combatantWouldBeDestroyed(attacker,a,combat)?d.value(a):0;
                    double prevented=Math.max(0,ComputerUtilCombat.damageIfUnblocked(a,defender,null,true))*12;
                    // Defend a credible life/commander/poison elimination even at a material cost.
                    boolean danger=ComputerUtilCombat.damageIfUnblocked(a,defender,null,true)>=defender.getLife()
                            || a.isCommander() && defender.getCommanderDamage(a)+Math.max(0,a.getNetPower())>=21;
                    double utility=kill-loss+prevented+(danger?1000:0);
                    for(Card b:group) { combat.removeFromCombat(b); }
                    combat.setBlocked(a,false);
                    if(utility>best) { best=utility;chosen=group; }
                }
                // Multiple blockers are considered where required, or when they improve the exchange.
            }
            for(Card b:chosen) { combat.addBlocker(a,b);blockers.remove(b); }
            combat.setBlocked(a,!chosen.isEmpty());
        }
        int damage=Math.max(0,defender.getLife()-ComputerUtilCombat.lifeThatWouldRemain(defender,combat));
        boolean lethal=!defender.cantLose() && ((!defender.cantLoseForZeroOrLessLife() && damage>=defender.getLife())
                || !ComputerUtilCombat.getLifeThreateningCommanders(defender,combat).isEmpty()
                || ComputerUtilCombat.resultingPoison(defender,combat)>=defender.getGame().getRules().getPoisonCountersToLose());
        double losses=0,gains=0;
        for(Card a:cards) if(ComputerUtilCombat.combatantWouldBeDestroyed(attacker,a,combat)) { losses+=d.value(a); }
        for(Card b:combat.getAllBlockers()) if(ComputerUtilCombat.combatantWouldBeDestroyed(defender,b,combat)) { gains+=d.value(b); }
        return new Forecast(combat,damage,lethal,losses,gains);
    }
    private static double reward(Decision d,Card attacker,Combat combat) {
        double result=0;int scanned=0;
        for(Card source:attacker.getController().getCardsIn(ZoneType.Battlefield)) {
            if(++scanned>48) { break; }
            if(source.isPhasedOut() || source.isFaceDown()) { continue; }
            for(var t:source.getTriggers()) {
                if(t.isSuppressed() || StrategicThreatSupport.blocked(t)
                        || !"Battlefield".equals(t.getParamOrDefault("TriggerZones","Battlefield"))) { continue; }
                boolean attack=t.getMode()==TriggerType.Attacks;
                boolean declared=t.getMode()==TriggerType.AttackersDeclared;
                boolean damage=t.getMode()==TriggerType.DamageDone || t.getMode()==TriggerType.DamageDoneOnce;
                if(!attack && !declared && !damage) { continue; }
                if(attack && !t.matchesValidParam("ValidCard",attacker)) { continue; }
                if(declared && combat.getAttackers().get(0)!=attacker) { continue; }
                if(damage && (combat.isBlocked(attacker) || !t.matchesValidParam("ValidSource",attacker)
                        || !t.matchesValidParam("ValidTarget",combat.getDefenderByAttacker(attacker))
                        || ComputerUtilCombat.damageIfUnblocked(attacker,combat.getDefenderByAttacker(attacker),combat,true)<=0)) { continue; }
                if((t.hasParam("ActivationLimit") || t.hasParam("ResolvedLimit") || t.getMode()==TriggerType.DamageDoneOnce)
                        && combat.getAttackers().get(0)!=attacker) { continue; }
                if(StrategicThreatEvaluator.opportunities(source,t)<=0) { continue; }
                SpellAbility sa=t.ensureAbility();
                double output=StrategicThreatEvaluator.effectValue(source,sa,Collections.newSetFromMap(new IdentityHashMap<SpellAbility,Boolean>()),0);
                if(sa!=null && sa.getPayCosts()!=null) {
                    var budget=StrategicThreatSupport.budget(source,sa);
                    output=budget.rate()>=1?Math.max(0,output-budget.opportunityCost()):0;
                }
                result+=output;
            }
        }
        return Math.min(180,result);
    }
    public static Player damageTarget(Player ai,Collection<Player> candidates,SpellAbility sa,int damage) {
        List<Player> ordered=rank(ai,candidates);
        for(Player p:ordered) if(!p.cantLose() && !p.cantLoseForZeroOrLessLife() && p.canLoseLife()
                && ComputerUtilCombat.predictDamageTo(p,damage,sa.getHostCard(),false)>=p.getLife()) { return p; }
        return ordered.isEmpty()?null:ordered.get(0);
    }
}
