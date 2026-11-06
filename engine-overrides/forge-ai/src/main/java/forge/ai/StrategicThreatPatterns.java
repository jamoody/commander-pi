package forge.ai;

import java.util.*;
import forge.game.CardTraitBase;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/** Shared script families. Estimates use public objects or explicitly bounded priors,
 * never resolve abilities, inspect hidden identities, or attach temporary game effects. */
final class StrategicThreatPatterns {
    private StrategicThreatPatterns() { }
    private static double number(Card h, CardTraitBase t, String key, double fallback) {
        return t.hasParam(key) ? StrategicThreatSupport.number(h,t,t.getParam(key)) : fallback;
    }
    static double playerEvents(Card h, Trigger t, String key, boolean spells) {
        String filter=t.getParamOrDefault(key, "Player");
        if (!Set.of("", "Player", "You", "Player.You", "Opponent", "Player.Opponent", "Player.Other", "Player.NonActive").contains(filter)) { return 0; }
        double total=0;
        for (Player p:h.getGame().getPlayers()) {
            if ((filter.equals("You") || filter.equals("Player.You")) && p!=h.getController()) { continue; }
            if ((filter.contains("Opponent") || filter.equals("Player.Other")) && !p.isOpponentOf(h.getController())) { continue; }
            // NonActive is a timing restriction, not a permanent exclusion of today's active player.
            double supply=spells ? Math.min(p==h.getController()?2:1,p.getCardsIn(ZoneType.Hand).size()*.5) : 1;
            total+=supply*(filter.equals("Player.NonActive")?.35:1);
        }
        return Math.min(4,total);
    }
    static double counterEvents(Card h, Trigger t) {
        String type=t.getParamOrDefault("CounterType", "");
        if (type.equals("LORE") && t.hasParam("Chapter")) {
            // Only the next chapter: previous chapters are spent, not recurring production.
            double chapter=number(h,t,"Chapter",0);
            return chapter==h.getCounters(CounterType.getType("LORE"))+1 ? 1 : 0;
        }
        // Threshold-crossing triggers require the resulting counter total, not just an event.
        if (t.hasParam("CounterAmount")) { return 0; }
        if (!Set.of("P1P1","M1M1","CHARGE").contains(type)) { return 0; }
        double events=0; int scanned=0;
        for (Card source:h.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (++scanned>96) { break; }
            if (source.isFaceDown() || source.isPhasedOut() || !t.matchesValidParam("ValidSource",source.getController())) { continue; }
            for (SpellAbility sa:source.getSpellAbilities()) {
                if (!sa.isActivatedAbility() || sa.isSuppressed() || StrategicThreatSupport.blocked(sa)
                        || sa.getApi()!=ApiType.PutCounter && sa.getApi()!=ApiType.PutCounterAll
                        || !type.equals(sa.getParam("CounterType")) || StrategicThreatSupport.budget(source,sa).rate()<1) { continue; }
                int targetsScanned=0;
                for (Card target:h.getGame().getCardsIn(ZoneType.Battlefield)) {
                    if (++targetsScanned>96) { break; }
                    if (!StrategicThreatSupport.matches(target,t.getParamOrDefault("ValidCard","Card"),h,t)) { continue; }
                    boolean eligible=sa.usesTargeting()?StrategicThreatSupport.canTarget(sa,target,source)
                            :sa.getApi()==ApiType.PutCounterAll?StrategicThreatSupport.matches(target,sa.getParamOrDefault("ValidCards","Card"),source,sa)
                            :"Self".equals(sa.getParamOrDefault("Defined","Self")) && target==source;
                    if (eligible) { events++; break; }
                }
            }
            // Independent production triggers only: avoid recursively feeding a counter engine into itself.
            for (Trigger producer:source.getTriggers()) {
                if (producer.isSuppressed() || StrategicThreatSupport.blocked(producer)
                        || !"Battlefield".equals(producer.getParamOrDefault("TriggerZones","Battlefield"))
                        || !Set.of("Phase","Attacks","SpellCast").contains(producer.getMode().toString())) { continue; }
                SpellAbility sa=producer.ensureAbility();
                if (sa==null || sa.isSuppressed() || StrategicThreatSupport.blocked(sa)
                        || sa.getApi()!=ApiType.PutCounter || !type.equals(sa.getParam("CounterType"))
                        || !"Self".equals(sa.getParamOrDefault("Defined","Self"))
                        || !StrategicThreatSupport.matches(source,t.getParamOrDefault("ValidCard","Card"),h,t)) { continue; }
                events+=Math.min(1,StrategicThreatEvaluator.opportunities(source,producer));
            }
        }
        if (t.hasParam("FirstTime") || t.hasParam("ResolvedLimit")) { events=Math.min(1,events); }
        return Math.min(2,events);
    }
    static double activationEvents(Card h, Trigger t) {
        double total=0; int scanned=0;
        for (Card c:h.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (++scanned>96) { break; }
            if (c.isFaceDown() || c.isPhasedOut() || !t.matchesValidParam("ValidActivatingPlayer",c.getController())
                    || !StrategicThreatSupport.matches(c,t.getParamOrDefault("ValidCard","Card"),h,t)) { continue; }
            for (SpellAbility sa:c.getSpellAbilities()) {
                if (!sa.isActivatedAbility() || sa.isManaAbility() || sa.isSuppressed() || StrategicThreatSupport.blocked(sa)
                        || !t.matchesValidParam("ValidSA",sa)) { continue; }
                total+=Math.min(1,StrategicThreatSupport.budget(c,sa).rate());
                if (total>=2) { return 2; }
            }
        }
        return total;
    }
    static double zoneEvents(Card h, Trigger t) {
        String origin=t.getParamOrDefault("Origin", "");
        String dest=t.getParamOrDefault("Destination", "");
        String valid=t.getParamOrDefault("ValidCards", "Card");
        if (origin.equals("Battlefield") && dest.equals("Graveyard")) {
            long n=h.getGame().getCardsIn(ZoneType.Battlefield).stream().filter(c->c!=h && c.isCreature()
                    && StrategicThreatSupport.matches(c,valid,h,t)).count();
            return Math.min(1,n*.25); // batch event, not one trigger per dying card
        }
        if (!origin.equals("Graveyard")) { return 0; }
        boolean fodder=h.getGame().getCardsIn(ZoneType.Graveyard).stream().anyMatch(c->StrategicThreatSupport.matches(c,valid,h,t));
        if (!fodder) { return 0; }
        // A graveyard full of cards is not itself evidence of repeated graveyard exits.
        int scanned=0;
        for (Card source:h.getController().getCardsIn(ZoneType.Battlefield)) {
            if (++scanned>48) { break; }
            for (StaticAbility st:source.getStaticAbilities()) {
                if (!st.isSuppressed() && !StrategicThreatSupport.blocked(st) && "Graveyard".equals(st.getParam("AffectedZone"))
                        && permission(source,st)>0 && (dest.equals("Any") || dest.equals("Battlefield") || dest.equals("Stack"))) { return .5; }
            }
            for (SpellAbility sa:source.getSpellAbilities()) {
                if (!sa.isActivatedAbility() || sa.isSuppressed() || StrategicThreatSupport.blocked(sa)
                        || sa.getApi()!=ApiType.ChangeZone || !"Graveyard".equals(sa.getParam("Origin"))
                        || !dest.equals("Any") && !dest.equals(sa.getParam("Destination"))
                        || StrategicThreatSupport.budget(source,sa).rate()<1) { continue; }
                if (sa.usesTargeting() && h.getGame().getCardsIn(ZoneType.Graveyard).stream().anyMatch(c->
                        StrategicThreatSupport.matches(c,valid,h,t) && StrategicThreatSupport.canTarget(sa,c,source))) { return .5; }
            }
        }
        return 0;
    }
    static double damageAll(Card h, SpellAbility sa) {
        double n=number(h,sa,"NumDmg",0), net=0;
        String players=sa.getParamOrDefault("ValidPlayers", "");
        if (Set.of("Player", "You", "Opponent", "Player.Opponent").contains(players)) {
            for (Player p:h.getGame().getPlayers()) {
                if (players.equals("You") && p!=h.getController() || players.contains("Opponent") && !p.isOpponentOf(h.getController())) { continue; }
                net+=(p.isOpponentOf(h.getController())?1:-1)*18*n;
            }
        }
        String valid=sa.getParamOrDefault("ValidCards", "");
        if (!valid.isEmpty()) {
            int scanned=0;
            for (Card c:h.getGame().getCardsIn(ZoneType.Battlefield)) {
                if (++scanned>96) { break; }
                if (!c.isCreature() || !StrategicThreatSupport.matches(c,valid,h,sa)) { continue; }
                double loss=Math.min(150,18*n);
                if (n>=c.getNetToughness() && !c.hasKeyword(forge.game.keyword.Keyword.INDESTRUCTIBLE)) { loss=150; }
                net+=(c.getController().isOpponentOf(h.getController())?1:-1)*loss;
            }
        }
        return Math.max(0,Math.min(400,net));
    }
    static double sacrifice(Card h, SpellAbility sa, Set<SpellAbility> visited,int depth) {
        String who=sa.getParamOrDefault("Defined", "You"), valid=sa.getParamOrDefault("SacValid", "Self");
        double count=number(h,sa,"Amount",1), benefit=0, expense=0;
        if (count<=0 || sa.hasParam("Random") || valid.contains("Remembered")) { return 0; }
        if (!sa.usesTargeting() && !Set.of("You","Player.You","Player","Opponent","Player.Opponent","EachOpponent").contains(who)) { return 0; }
        boolean own=false;
        for (Player p:h.getGame().getPlayers()) {
            if (sa.usesTargeting()) { if (!StrategicThreatSupport.canTarget(sa,p,h)) { continue; } }
            else if ((who.equals("You") || who.equals("Player.You")) && p!=h.getController()
                    || (who.contains("Opponent")) && !p.isOpponentOf(h.getController())) { continue; }
            List<Double> losses=new ArrayList<>();
            for (Card c:p.getCardsIn(ZoneType.Battlefield)) {
                if (!StrategicThreatSupport.matches(c,valid,h,sa)) { continue; }
                // Removing a source doesn't prevent its own sacrifice/death payoff reliably.
                if (c==h && p==h.getController()) { continue; }
                losses.add(c.isToken()?30.0:c.isCreature()?Math.max(35,Math.min(150,ComputerUtilCard.evaluateCreature(c)*.45)):65.0);
            }
            Collections.sort(losses);
            double loss=0;
            for (int i=0;i<Math.min(count,losses.size());i++) { loss+=losses.get(i); }
            if (p==h.getController()) {
                own=true;
                if (losses.size()<count) { return 0; }
                expense=loss;
            } else if (p.isOpponentOf(h.getController())) { benefit=sa.usesTargeting()?Math.max(benefit,loss):benefit+loss; }
        }
        // Opponent's optional sacrifice is not guaranteed. Conditional refusal paths remain unsupported.
        if (!own && sa.hasParam("Optional")) { benefit*=.35; }
        double tail=StrategicThreatEvaluator.effectValue(h,sa.getSubAbility(),visited,depth+1);
        return Math.max(0,Math.min(400,benefit+tail-expense));
    }
    static double spellCopy(Card h, SpellAbility sa) {
        if (!Set.of("You","Player.You").contains(sa.getParamOrDefault("Controller","You"))) { return 0; }
        double copies=Math.min(3,number(h,sa,"Amount",1));
        if (copies<=0) { return 0; }
        Trigger t=sa.getTrigger();
        boolean triggered="TriggeredSpellAbility".equals(sa.getParam("Defined")) && t!=null;
        if (triggered && Set.of("SpellCast","SpellCastOrCopy","AbilityCast").contains(t.getMode().toString())) {
            // Explicit per-event prior. The outer trigger supplies the bounded event rate.
            if (sa.hasParam("CopyForEachCanTarget")) {
                long targets=h.getGame().getCardsIn(ZoneType.Battlefield).stream().filter(c->c!=h
                        && StrategicThreatSupport.matches(c,sa.getParam("CopyForEachCanTarget"),h,sa)).count();
                copies*=sa.hasParam("ChooseOnlyOne")?Math.min(1,targets):Math.min(3,targets);
            }
            if (sa.hasParam("DefinedTarget")) { return 0; }
            return 90*copies;
        }
        if (!sa.usesTargeting()) { return 0; }
        // Activated copying is valued only against an actual public, legal stack target.
        for (var entry:h.getGame().getStack()) {
            SpellAbility target=entry.getSpellAbility();
            if (target.cantBeCopied()) { continue; }
            try { if (sa.copy(h.getController()).canTarget(target)) { return 90*copies; } }
            catch (RuntimeException unsupported) { return 0; }
        }
        return 0;
    }
    static double peek(Card h, SpellAbility sa) {
        if (!"Library".equals(sa.getParamOrDefault("SourceZone","Library"))
                || !Set.of("You","Player.You").contains(sa.getParamOrDefault("Defined","You"))
                || sa.usesTargeting() || sa.hasParam("NoReveal") || !"True".equals(sa.getParam("RememberRevealed"))) { return 0; }
        int count=(int)Math.min(10,number(h,sa,"PeekAmount",1));
        String valid=sa.getParamOrDefault("RevealValid","Card");
        SpellAbility next=sa.getSubAbility();
        if (next==null || next.getApi()!=ApiType.ChangeZone || !"Library".equals(next.getParam("Origin"))) { return 0; }
        String defined=next.getParamOrDefault("Defined","");
        if (!defined.equals("Remembered") && !defined.equals("TopOfLibrary")) { return 0; }
        if (defined.equals("TopOfLibrary") && (count!=1 || !valid.equals(next.getParam("ConditionPresent"))
                || !"Remembered".equals(next.getParam("ConditionDefined")) || !"EQ1".equals(next.getParam("ConditionCompare")))) { return 0; }
        for (String k:next.getMapParams().keySet()) {
            if ((k.startsWith("Condition") && !Set.of("ConditionPresent","ConditionDefined","ConditionCompare").contains(k))
                    || k.startsWith("CheckSVar")) { return 0; }
        }
        if (defined.equals("Remembered") && StrategicThreatSupport.blocked(next)) { return 0; }
        double unit="Hand".equals(next.getParam("Destination"))?90:"Battlefield".equals(next.getParam("Destination"))?150:0;
        // Expected matching cards, not the identities of any library cards. Other branches not summed.
        return Math.min(400,unit*count*StrategicThreatEvaluator.libraryHitChance(h,valid,1,sa));
    }
    static double restriction(Card h, StaticAbility st) {
        boolean cast="CantBeCast".equals(st.getParam("Mode"));
        String who=st.getParamOrDefault(cast?"Caster":"Activator", "Player");
        String valid=st.getParamOrDefault("ValidCard", "Card");
        // Preserve the existing bounded forecast for explicitly one-sided restrictions.
        if (Set.of("Opponent","Player.Opponent").contains(who)) { return cast?75:45; }
        if (!Set.of("Player","Opponent","Player.Opponent","You","Player.You").contains(who)) { return 0; }
        if (cast && (valid.equals("Card.Self") || valid.equals("Self"))) { return 0; }
        if (!cast && !"Battlefield".equals(st.getParamOrDefault("AffectedZone","Battlefield"))) { return 0; }
        double opponents=0,own=0;
        for (Player p:h.getGame().getPlayers()) {
            if (who.contains("Opponent") && !p.isOpponentOf(h.getController())
                    || (who.equals("You") || who.equals("Player.You")) && p!=h.getController()) { continue; }
            double impact=0;
            if (cast) { impact=25*Math.min(2,p.getCardsIn(ZoneType.Hand).size()*.5); }
            else {
                for (Card c:p.getCardsIn(ZoneType.Battlefield)) {
                    if (!StrategicThreatSupport.matches(c,valid,h,st)) { continue; }
                    if (c.getSpellAbilities().stream().anyMatch(a->a.isActivatedAbility() && !a.isSuppressed()
                            && st.matchesValidParam("ValidSA",a)
                            && (!"True".equals(st.getParam("NonMana")) || !a.isManaAbility()))) { impact+=30; }
                }
            }
            if (p.isOpponentOf(h.getController())) { opponents+=impact; } else { own+=impact; }
        }
        return Math.min(150,Math.max(0,opponents-own));
    }
    static double permission(Card h, StaticAbility st) {
        if (!"True".equals(st.getParam("MayPlay"))) { return 0; }
        if (!"Battlefield".equals(st.getParamOrDefault("EffectZone","Battlefield"))) { return 0; }
        String zone=st.getParamOrDefault("AffectedZone",""), filter=st.getParamOrDefault("Affected","");
        if (st.hasParam("MayPlayAltManaCost") || filter.contains("Remembered") || filter.contains("Imprinted")
                || filter.contains("ExiledWith") || filter.contains("Chosen")) { return 0; }
        double limit=Math.min(3,number(h,st,"MayPlayLimit",1));
        if (zone.equals("Library")) {
            // Top-card permissions only. Never inspect or evaluate the top card's identity.
            if (!filter.contains("TopLibrary") || !filter.contains("YouCtrl")) { return 0; }
            String base=filter.split("\\.")[0];
            double unit=base.equals("Land")?25:65;
            if (!base.equals("Land") && h.getController().getCardsIn(ZoneType.Battlefield).stream().noneMatch(c->!c.getManaAbilities().isEmpty())
                    && !"True".equals(st.getParam("MayPlayWithoutManaCost"))) { return 0; }
            return unit*limit*StrategicThreatEvaluator.libraryHitChance(h,base,1,st);
        }
        if (!Set.of("Graveyard","Exile").contains(zone)) { return 0; }
        if (!filter.contains("YouOwn") && !filter.contains("YouCtrl") && !filter.contains("OppOwn") && !filter.contains("OppCtrl")) { return 0; }
        List<Double> values=new ArrayList<>();int scanned=0;
        for (Card c:h.getGame().getCardsIn(ZoneType.valueOf(zone))) {
            if (++scanned>96) { break; }
            if (c.isFaceDown() || !StrategicThreatSupport.matches(c,filter,h,st)) { continue; }
            if (c.isLand()) { values.add(25.0); }
            else if ("True".equals(st.getParam("MayPlayWithoutManaCost")) || StrategicThreatSupport.canPayPrintedCost(h,c)) {
                values.add(c.isCreature()?Math.min(120,ComputerUtilCard.evaluateCreature(c)*.4):65.0);
            }
        }
        if (filter.startsWith("Land")) { limit=Math.min(3,h.getController().getMaxLandPlays()); }
        values.sort(Collections.reverseOrder());double result=0;
        for (int i=0;i<Math.min(limit,values.size());i++) { result+=values.get(i); }
        return result;
    }
    static double extraLands(Card h, StaticAbility st) {
        if (!st.hasParam("AdjustLandPlays") || !Set.of("You","Player.You").contains(st.getParamOrDefault("Affected",""))) { return 0; }
        double supply=h.getController().getCardsIn(ZoneType.Hand).size()*.35;
        int scanned=0;
        for (Card source:h.getController().getCardsIn(ZoneType.Battlefield)) {
            if (++scanned>48) { break; }
            boolean replay=source.getStaticAbilities().stream().anyMatch(a->!a.isSuppressed() && !StrategicThreatSupport.blocked(a)
                    && "Graveyard".equals(a.getParam("AffectedZone")) && a.getParamOrDefault("Affected", "").startsWith("Land") && permission(source,a)>0);
            if (replay) { supply+=h.getController().getCardsIn(ZoneType.Graveyard).stream().filter(Card::isLand).count();break; }
        }
        return 35*Math.min(3,Math.min(number(h,st,"AdjustLandPlays",0),supply));
    }
}
