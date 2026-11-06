package forge.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import forge.game.CardTraitBase;
import forge.game.ability.ApiType;
import forge.game.ability.AbilityFactory;
import forge.game.cost.CostSacrifice;
import forge.game.trigger.TriggerHandler;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/**
 * Bounded, public-information estimate of preventable production over one turn cycle.
 * Units are compatible with CreatureEvaluator, but this is ONLY a removal bonus.
 * No card-name rules, hidden-card inspection, gameplay simulation, or persistent cache.
 * Unknown timing, costs, conditions and effects contribute zero, preserving the baseline.
 */
public final class StrategicThreatEvaluator {
    private StrategicThreatEvaluator() { }
    private static final int MAX_BONUS = 1200;
    private static final int MAX_NODES = 48;
    private static final double CARD_VALUE = 90;

    public record Assessment(int score, List<String> reasons) { }

    public static int score(Player ai, Card card, SpellAbility removal) {
        return assess(ai, card, removal).score();
    }

    public static Assessment assess(Player ai, Card card, SpellAbility removal) {
        List<String> reasons = new ArrayList<>();
        if (Boolean.getBoolean("forge.ai.disableStrategicThreat") || ai == null || !card.isInPlay() || card.isPhasedOut()
                || !card.getController().isOpponentOf(ai)) {
            return new Assessment(0, List.of());
        }
        double total = 0;
        for (Trigger trigger : card.getTriggers()) {
            if (trigger.isSuppressed() || conditional(trigger)
                    || !"Battlefield".equals(trigger.getParamOrDefault("TriggerZones", "Battlefield"))) {
                continue;
            }
            double rate = opportunities(card, trigger);
            if (trigger.hasParam("ActivationLimit")) { rate = Math.min(rate, amount(card, trigger, "ActivationLimit", 0)); }
            if (rate <= 0) { continue; }
            SpellAbility effect = trigger.ensureAbility();
            double output = effectValue(card, effect, newIdentitySet(), 0);
            if (effect != null && effect.getPayCosts() != null) {
                var budget = StrategicThreatSupport.budget(card, effect);
                rate = Math.min(rate, budget.rate());
                output = Math.max(0, output - budget.opportunityCost());
            }
            double contribution = rate * output;
            if (contribution > 0) {
                total += contribution;
                reasons.add(trigger.getMode() + ": " + Math.round(contribution)
                        + " (opportunities=" + String.format(java.util.Locale.ROOT, "%.2f", rate) + ")");
            }
        }
        double bestActivated = 0;
        for (SpellAbility ability : card.getSpellAbilities()) {
            if (!ability.isActivatedAbility() || ability.isSuppressed() || conditional(ability)
                    || ability.getPayCosts() == null
                    || !"Battlefield".equals(ability.getParamOrDefault("ActivationZone", "Battlefield"))) { continue; }
            var budget = StrategicThreatSupport.budget(card, ability);
            double value = budget.rate() * Math.max(0, effectValue(card, ability, newIdentitySet(), 0) - budget.opportunityCost());
            bestActivated = Math.max(bestActivated, value); // alternate uses compete for the same resources
            if (value > 0) { reasons.add("Activated: " + Math.round(value)); }
        }
        total += bestActivated;
        double continuous = continuousValue(card);
        continuous += replacementValue(card);
        total += continuous;
        if (continuous > 0) { reasons.add("Continuous support: " + Math.round(continuous)); }
        double retention = 1;
        if (removal != null && removal.getApi() == ApiType.ChangeZone
                && "Hand".equals(removal.getParam("Destination")) && !card.isToken()) {
            retention = 0.35; // temporary denial; not a full cycle of permanent removal
            reasons.add("Temporary removal discount");
        }
        return new Assessment((int)Math.min(MAX_BONUS, Math.max(0, total * retention)), List.copyOf(reasons));
    }

    private static Set<SpellAbility> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static boolean conditional(CardTraitBase trait) {
        return StrategicThreatSupport.blocked(trait);
    }

    static double opportunities(Card card, Trigger t) {
        Player owner = card.getController();
        var phase = card.getGame().getPhaseHandler();
        if (t.getMode() == TriggerType.AttackersDeclared) {
            if (!t.matchesValidParam("AttackingPlayer", owner) || t.hasParam("AttackedTarget")) { return 0; }
            boolean attack = owner.getCreaturesInPlay().stream().anyMatch(c -> owner.getOpponents().stream().anyMatch(p -> CombatUtil.canAttackNextTurn(c,p)));
            return attack ? (phase.isPlayerTurn(owner) && phase.getPhase().ordinal() >= PhaseType.COMBAT_DECLARE_ATTACKERS.ordinal() ? .35 : 1) : 0;
        }
        if (t.getMode() == TriggerType.Sacrificed && t.matchesValidParam("ValidPlayer", owner)) {
            long fodder = owner.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c != card
                    && StrategicThreatSupport.matches(c,t.getParamOrDefault("ValidCard","Permanent"),card,t)).count();
            return Math.min(fodder, eventSupply(card, false));
        }
        if (t.getMode() == TriggerType.LifeGained && t.matchesValidParam("ValidPlayer", owner)) { return eventSupply(card, true); }
        if (t.getMode() == TriggerType.Taps) {
            double count = 0;
            for (Card c : owner.getCardsIn(ZoneType.Battlefield)) {
                if (!StrategicThreatSupport.matches(c,t.getParamOrDefault("ValidCard","Card"),card,t)) { continue; }
                boolean ability = c.getSpellAbilities().stream().anyMatch(sa -> !sa.isSuppressed() && !conditional(sa)
                        && sa.getPayCosts()!=null && sa.getPayCosts().hasTapCost() && StrategicThreatSupport.budget(c,sa).rate()>=1);
                boolean attack = c.isCreature() && owner.getOpponents().stream().anyMatch(p -> CombatUtil.canAttackNextTurn(c,p));
                if (ability || attack) { count++; }
            }
            return Math.min(4,count);
        }
        if (t.getMode() == TriggerType.BecomesTarget || t.getMode() == TriggerType.BecomesTargetOnce) {
            double targets = 0;
            for (Card c : owner.getCardsIn(ZoneType.Battlefield)) {
                if (!StrategicThreatSupport.matches(c,t.getParamOrDefault("ValidTarget","Card"),card,t)) { continue; }
                for (Card source : owner.getCardsIn(ZoneType.Battlefield)) {
                    if (source.getSpellAbilities().stream().anyMatch(sa -> sa.isActivatedAbility() && sa.usesTargeting()
                            && !sa.isSuppressed() && !conditional(sa) && StrategicThreatSupport.budget(source,sa).rate()>=1
                            && StrategicThreatSupport.canTarget(sa,c,source))) { targets++; break; }
                }
            }
            return Math.min(2,targets);
        }
        if (t.getMode() == TriggerType.ChangesZoneAll && "Battlefield".equals(t.getParam("Destination"))) {
            String valid = t.getParamOrDefault("ValidCards", "");
            if (valid.contains("YouCtrl") && !valid.contains("Self")) { return Math.min(1,owner.getCardsIn(ZoneType.Hand).size()*.35); }
        }
        if (t.getMode() == TriggerType.TapsForMana) {
            if (!t.matchesValidParam("Activator", owner) || t.hasParam("Produced")) { return 0; }
            double sources = 0;
            for (Card source : owner.getCardsIn(ZoneType.Battlefield)) {
                if (source.isPhasedOut() || !StrategicThreatSupport.matches(source, t.getParamOrDefault("ValidCard", "Permanent"), card, t)) { continue; }
                boolean usable = source.getManaAbilities().stream().anyMatch(sa -> !sa.isSuppressed() && !conditional(sa)
                        && sa.getPayCosts() != null && sa.getPayCosts().hasTapCost()
                        && StrategicThreatSupport.budget(source, sa).rate() >= 1);
                if (usable) { sources++; }
            }
            return Math.min(10, sources); // one ordinary use per public producer per cycle
        }
        if (t.getMode() == TriggerType.Attacks) {
            if (t.hasParam("Alone") || t.hasParam("Attacked") || t.hasParam("FirstAttack")) { return 0; }
            double eligible = 0;
            for (Card creature : owner.getCreaturesInPlay()) {
                if (t.matchesValidParam("ValidCard", creature)
                        && owner.getOpponents().stream().anyMatch(p -> CombatUtil.canAttackNextTurn(creature, p))) {
                    boolean imminent = phase.isPlayerTurn(owner)
                            && phase.getPhase().ordinal() < PhaseType.COMBAT_DECLARE_ATTACKERS.ordinal();
                    boolean ready = owner.getOpponents().stream().anyMatch(p -> CombatUtil.canAttack(creature, p));
                    eligible += imminent && !ready ? 0.35 : 1;
                }
            }
            // Once attackers are declared, existing triggers are independent of their source.
            // Only discounted next-combat production remains preventable.
            double timing = phase.isPlayerTurn(owner)
                    && phase.getPhase().ordinal() >= PhaseType.COMBAT_DECLARE_ATTACKERS.ordinal() ? 0.35 : 1;
            return Math.min(6, eligible) * timing;
        }
        if (t.getMode() == TriggerType.SpellCast || t.getMode() == TriggerType.SpellCastOrCopy) {
            if (t.hasParam("ActivatorThisTurnCast") && !"EQ1".equals(t.getParam("ActivatorThisTurnCast"))) { return 0; }
            String who = t.getParamOrDefault("ValidActivatingPlayer", "");
            if (Set.of("Player", "", "Player.Other", "Player.NonActive").contains(who)) {
                return StrategicThreatPatterns.playerEvents(card, t, "ValidActivatingPlayer", true);
            }
            if (who.equals("Opponent") || who.equals("Player.Opponent")) {
                // One likely qualifying spell per opponent per cycle, never hidden hand identities.
                return owner.getOpponents().size();
            }
            if (who.equals("You") || who.equals("Player.You")) {
                return Math.min(2, owner.getCardsIn(ZoneType.Hand).size() * 0.5);
            }
        }
        if (t.getMode() == TriggerType.Drawn) {
            String filter = t.getParamOrDefault("ValidCard", "");
            if (filter.equals("Card.OppOwn") || filter.equals("Card.OppCtrl")) { return owner.getOpponents().size(); }
            if (filter.equals("Card.YouOwn") || filter.equals("Card.YouCtrl")) { return 1; }
        }
        if (t.getMode() == TriggerType.SearchedLibrary
                && t.getParamOrDefault("ValidPlayer", "").contains("Opponent")) {
            return owner.getOpponents().size() * 0.35;
        }
        if (t.getMode() == TriggerType.DamageDone || t.getMode() == TriggerType.DamageDoneOnce) {
            if (!"True".equals(t.getParam("CombatDamage"))) { return 0; }
            if (!owner.getOpponents().stream().anyMatch(p -> t.matchesValidParam("ValidTarget", p))) { return 0; }
            double hits = 0;
            for (Card attacker : owner.getCreaturesInPlay()) {
                if (StrategicThreatSupport.matches(attacker, t.getParamOrDefault("ValidSource", "Card"), card, t)
                        && owner.getOpponents().stream().anyMatch(p -> CombatUtil.canAttackNextTurn(attacker, p))) { hits += .5; }
            }
            return Math.min(t.getMode() == TriggerType.DamageDoneOnce ? 1 : 3, hits);
        }
        if (t.getMode() == TriggerType.ChangesZone && "Battlefield".equals(t.getParam("Origin"))
                && "Graveyard".equals(t.getParam("Destination"))) {
            // Estimate OTHER future deaths; the source's own death trigger is not prevented by destroying it.
            long eligible = card.getGame().getCardsIn(ZoneType.Battlefield).stream()
                    .filter(c -> c != card && c.isCreature() && StrategicThreatSupport.matches(c, t.getParamOrDefault("ValidCard", "Card"), card, t)).count();
            return Math.min(2, eligible * .25);
        }
        if (t.getMode() == TriggerType.ChangesZone && "Battlefield".equals(t.getParam("Destination"))) {
            String valid = t.getParamOrDefault("ValidCard", "");
            // Self-ETB production has already happened. It is not prevented by removing the source.
            if (valid.contains("Self")) { return 0; }
            if (valid.startsWith("Land.") && valid.contains("YouCtrl")) {
                int landPlays = Math.min(3, owner.getMaxLandPlays());
                if (phase.isPlayerTurn(owner)) {
                    landPlays = Math.max(0, landPlays - owner.getLandsPlayedThisTurn());
                }
                boolean replay = hasGraveyardLandAccess(card);
                long graveLands = owner.getCardsIn(ZoneType.Graveyard).stream().filter(Card::isLand).count();
                double supply = replay && graveLands > 0 ? Math.min(landPlays, graveLands)
                        : Math.min(landPlays, owner.getCardsIn(ZoneType.Hand).size() * 0.35);
                return supply;
            }
            if (valid.startsWith("Creature.") && valid.contains("YouCtrl")) {
                return Math.min(2, owner.getCardsIn(ZoneType.Hand).size() * 0.35);
            }
        }
        if (t.getMode() == TriggerType.Phase) {
            String step = t.getParamOrDefault("Phase", "");
            if (Set.of("Upkeep", "End of Turn", "Main2", "Main1", "Main", "Draw", "BeginCombat", "EndCombat").contains(step)) {
                return StrategicThreatPatterns.playerEvents(card, t, "ValidPlayer", false);
            }
        }
        if (t.getMode() == TriggerType.AbilityCast) {
            return StrategicThreatPatterns.activationEvents(card, t);
        }
        if (t.getMode() == TriggerType.CounterAdded || t.getMode() == TriggerType.CounterAddedOnce) {
            return StrategicThreatPatterns.counterEvents(card, t);
        }
        if (t.getMode() == TriggerType.ChangesZoneAll) {
            return StrategicThreatPatterns.zoneEvents(card, t);
        }
        return 0;
    }

    static double effectValue(Card card, SpellAbility sa, Set<SpellAbility> visited, int depth) {
        if (sa == null || depth >= 8 || visited.size() >= MAX_NODES || !visited.add(sa)
                || sa.isSuppressed() || conditional(sa)) { return 0; }
        ApiType api = sa.getApi();
        if (api == null) { return 0; }
        double value = 0;
        double continuation = 1;
        switch (api) {
        case CopySpellAbility:
            value = StrategicThreatPatterns.spellCopy(card, sa);
            break;
        case DamageAll:
            value = StrategicThreatPatterns.damageAll(card, sa);
            break;
        case Sacrifice:
            // Includes the tail with the sacrifice's cost/success; never count it twice.
            return StrategicThreatPatterns.sacrifice(card, sa, visited, depth);
        case ImmediateTrigger:
            value = amount(card, sa, "TriggerAmount", 1)
                    * effectValue(card, sa.getAdditionalAbility("Execute"), visited, depth + 1);
            break;
        case PeekAndReveal:
            return StrategicThreatPatterns.peek(card, sa);
        case Draw:
            if (StrategicThreatSupport.friendly(sa, card)) {
                value = CARD_VALUE * amount(card, sa, "NumCards", 1);
            }
            break;
        case Token:
            if (friendlyTokenRecipient(sa)) {
                value = 60 * amount(card, sa, "TokenAmount", 1);
            }
            break;
        case Mana:
            value = 22 * StrategicThreatSupport.manaOutput(card, sa);
            break;
        case ManaReflected:
            if ("You".equals(sa.getParamOrDefault("Defined", "You"))
                    && "Produced".equals(sa.getParam("ReflectProperty"))) {
                value = 22 * amount(card, sa, "Amount", 1);
            }
            break;
        case CopyPermanent:
            value = copyValue(card, sa, visited, depth);
            break;
        case GainLife:
            if (StrategicThreatSupport.friendly(sa, card)) { value = 8 * amount(card, sa, "LifeAmount", 0); }
            break;
        case LoseLife:
            value = 16 * amount(card, sa, "LifeAmount", 0) * StrategicThreatSupport.hostileTargets(sa, card, false);
            break;
        case DealDamage:
            value = 18 * amount(card, sa, "NumDmg", 0) * Math.max(StrategicThreatSupport.hostileTargets(sa, card, true), StrategicThreatSupport.hostileTargets(sa, card, false));
            break;
        case Destroy:
            value = 150 * StrategicThreatSupport.hostileTargets(sa, card, true);
            break;
        case PutCounter:
            String counter = sa.getParamOrDefault("CounterType", "");
            if (counter.equals("M1M1")) { value = 30 * amount(card, sa, "CounterNum", 1) * StrategicThreatSupport.hostileTargets(sa, card, true); }
            else if (counter.equals("P1P1") && (sa.usesTargeting()
                    ? card.getController().getCreaturesInPlay().stream().anyMatch(c -> StrategicThreatSupport.canTarget(sa, c, card))
                    : "Self".equals(sa.getParamOrDefault("Defined", "Self")))) {
                value = 20 * amount(card, sa, "CounterNum", 1);
            }
            break;
        case PumpAll:
        case PutCounterAll:
            value = groupValue(card, sa);
            break;
        case Charm:
            // Exactly one choice, including optional choose-zero. Do not sum alternatives.
            if (amount(card,sa,"CharmNum",1) != 1 || amount(card,sa,"MinCharmNum",1)>1) { return 0; }
            List<forge.game.spellability.AbilitySub> choices = sa.getAdditionalAbilityList("Choices");
            if (choices != null) {
                int scanned=0;
                for (SpellAbility choice : choices) {
                    if (++scanned>8) { break; }
                    value=Math.max(value,effectValue(card,choice,visited,depth+1));
                }
            }
            break;
        case RepeatEach:
            if (!"Opponent".equals(sa.getParam("RepeatPlayers")) || sa.hasParam("RepeatCards") || sa.hasParam("RepeatTypesFrom")) { return 0; }
            value=card.getController().getOpponents().size()*effectValue(card,sa.getAdditionalAbility("RepeatSubAbility"),visited,depth+1);
            break;
        case Tap:
            value=35*StrategicThreatSupport.hostileTargets(sa,card,true);
            break;
        case Discard:
            double discarded=0;
            String discardWho=sa.getParamOrDefault("Defined","");
            for(Player opponent:card.getController().getOpponents()) {
                if(sa.usesTargeting() ? !StrategicThreatSupport.canTarget(sa,opponent,card)
                        : !Set.of("Opponent","Player.Opponent","EachOpponent").contains(discardWho)) { continue; }
                double cards=Math.min(opponent.getCardsIn(ZoneType.Hand).size(),amount(card,sa,"NumCards",1));
                discarded=sa.usesTargeting() ? Math.max(discarded,cards) : discarded+cards;
            }
            value=65*discarded;
            break;
        case GainControl:
            value=160*StrategicThreatSupport.hostileTargets(sa,card,true)*(sa.hasParam("LoseControl") ? .4 : 1);
            break;
        case Counter:
            // Only currently legal public stack targets; no imaginary spells.
            value=stackInteraction(card,sa,110);
            break;
        case Effect:
            value = grantedEffectValue(card,sa,visited,depth);
            break;
        case Untap:
            value = 20 * card.getController().getCardsIn(ZoneType.Battlefield).stream()
                    .filter(c -> c.isTapped() && (sa.usesTargeting() ? StrategicThreatSupport.canTarget(sa, c, card)
                            : c == card && "Self".equals(sa.getParam("Defined")))).limit(3).count();
            break;
        case Shuffle:
            break; // no independent value; continue to the actual payoff
        case Dig:
            if (!"You".equals(sa.getParamOrDefault("Defined", "You"))) { return 0; }
            if (!"Library".equals(sa.getParamOrDefault("SourceZone", "Library"))) { return 0; }
            String valid = sa.getParamOrDefault("ChangeValid", "Card");
            int draws = (int)amount(card, sa, "DigNum", 0);
            continuation = libraryHitChance(card, valid, draws, sa);
            double count = amount(card, sa, "ChangeNum", 1);
            String destination = sa.getParamOrDefault("DestinationZone", "Hand");
            value = continuation * count * (destination.equals("Battlefield") ? 150
                    : destination.equals("Hand") ? CARD_VALUE : 0);
            if ("Hand".equals(sa.getParam("DestinationZone2")) && "All".equals(sa.getParam("ChangeNum"))) {
                value = draws * (continuation * (destination.equals("Battlefield") ? 150 : CARD_VALUE) + (1-continuation)*CARD_VALUE);
            }
            break;
        case ChangeZone:
            if ("Stack".equals(sa.getParam("Origin")) && "Hand".equals(sa.getParam("Destination"))) {
                value=stackInteraction(card,sa,65); break;
            }
            if ("Battlefield".equals(sa.getParam("Origin")) && "Hand".equals(sa.getParam("Destination")) && sa.usesTargeting()) {
                value=65*StrategicThreatSupport.hostileTargets(sa,card,true); break;
            }
            if ("Graveyard".equals(sa.getParam("Origin")) && "Battlefield".equals(sa.getParam("Destination"))) {
                if (!sa.usesTargeting()) { return 0; } // unknown remembered/defined sets need separate interpretation
                value = card.getController().getCardsIn(ZoneType.Graveyard).stream()
                        .filter(c -> c.isCreature() && (!sa.usesTargeting() || StrategicThreatSupport.canTarget(sa, c, card)))
                        .mapToInt(c -> Math.min(220, ComputerUtilCard.evaluateCreature(c))).max().orElse(0);
                break;
            }
            if (!"You".equals(sa.getParamOrDefault("DefinedPlayer", "You")) || sa.usesTargeting()) { return 0; }
            if ("Library".equals(sa.getParam("Origin"))) {
                continuation = libraryHitChance(card, sa.getParamOrDefault("ChangeType", "Card"), 99, sa);
                value = continuation * amount(card, sa, "ChangeNum", 1)
                        * ("Battlefield".equals(sa.getParam("Destination")) ? 150 : "Hand".equals(sa.getParam("Destination")) ? CARD_VALUE : 0);
            }
            break;
        case Pump:
            // A modifier to a newly produced object belongs to the same payoff, weighted by
            // the parent effect's success. No independent value for a bookkeeping reference.
            if ("Remembered".equals(sa.getParam("Defined"))) {
                value = 8 * (amount(card, sa, "NumAtt", 0) + amount(card, sa, "NumDef", 0));
                if (sa.getParamOrDefault("KW", "").contains("Indestructible")) { value += 20; }
            }
            break;
        case Cleanup:
            break;
        default:
            // Unknown control flow (Charm/Branch/Repeat/etc.) must not sum mutually exclusive
            // choices or claim its unconditional tail is a guaranteed payoff.
            return 0;
        }
        double tail = effectValue(card, sa.getSubAbility(), visited, depth + 1);
        double tax = 1;
        if (sa.hasParam("UnlessCost")) {
            double cost = amount(card, sa, "UnlessCost", 0);
            tax = cost <= 0 ? 0 : Math.min(0.95, 0.55 + 0.15 * cost);
        }
        return tax * (value + continuation * tail);
    }

    /** Interpret only numeric/public expressions; never evaluate arbitrary hidden-zone SVars. */
    private static double amount(Card card, CardTraitBase trait, String key, double fallback) {
        String expression = trait.getParam(key);
        if (expression == null) { return fallback; }
        return StrategicThreatSupport.number(card, trait, expression);
    }

    /** Commander deck-composition priors, not access to any player's deck list. */
    static double libraryHitChance(Card host, String valid, int examined, CardTraitBase trait) {
        int size = host.getController().getCardsIn(ZoneType.Library).size();
        if (size == 0 || examined <= 0) { return 0; }
        int seen = 0;
        for (ZoneType zone : List.of(ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile)) {
            for (Card c : host.getGame().getCardsIn(zone)) {
                if (c.getOwner() == host.getController() && !c.isToken() && !c.isCommander()
                        && !c.isFaceDown() && c.isValid(valid.split(","), host.getController(), host, trait)) { seen++; }
            }
        }
        // Range expresses uncertainty: narrow payoff package vs broad creature/land/card pool.
        double[] prior = valid.equals("Card") ? new double[]{99, 99, 99}
                : valid.equals("Creature") ? new double[]{24, 30, 36}
                : valid.equals("Land") ? new double[]{32, 37, 42}
                : new double[]{10, 16, 22};
        double probability = 0;
        for (double initial : prior) {
            // Public depletion is evidence, not proof of the original deck composition.
            // Retain a small uncertainty tail for decks outside our assumed range.
            double remaining = Math.min(size, Math.max(initial * 0.05, initial - seen));
            double miss = 1;
            for (int i = 0; i < Math.min(examined, size); i++) {
                miss *= Math.max(0, (size - remaining - i) / (size - i));
            }
            probability += 1 - miss;
        }
        return probability / prior.length;
    }

    private static boolean hasGraveyardLandAccess(Card card) {
        for (Card source : card.getController().getCardsIn(ZoneType.Battlefield)) {
            for (StaticAbility st : source.getStaticAbilities()) {
                if (!st.isSuppressed() && !conditional(st) && "Continuous".equals(st.getParam("Mode"))
                        && "True".equals(st.getParam("MayPlay")) && "Graveyard".equals(st.getParam("AffectedZone"))
                        && "Land.YouOwn".equals(st.getParam("Affected"))) { return true; }
            }
        }
        return false;
    }

    private static double continuousValue(Card card) {
        double value = 0;
        for (StaticAbility st : card.getStaticAbilities()) {
            if (st.isSuppressed() || conditional(st)) { continue; }
            String mode = st.getParamOrDefault("Mode", "");
            if (mode.equals("Panharmonicon") || mode.equals("DisableTriggers")) {
                value += triggerModifierValue(card, st, mode.equals("DisableTriggers"));
            }
            if (mode.equals("ReduceCost") && "You".equals(st.getParam("Activator"))) {
                value += 20 * amount(card, st, "Amount", 1) * Math.min(2, card.getController().getCardsIn(ZoneType.Hand).size() * .5);
            }
            if (mode.equals("UntapOtherPlayer") && st.getParamOrDefault("ValidCard", "").contains("YouCtrl")) {
                long resources = card.getController().getCardsIn(ZoneType.Battlefield).stream()
                    .filter(c -> StrategicThreatSupport.matches(c, st.getParam("ValidCard"), card, st))
                    .filter(c -> !c.getManaAbilities().isEmpty() || c.getSpellAbilities().stream().anyMatch(sa -> sa.isActivatedAbility()
                                && sa.getPayCosts() != null && sa.getPayCosts().hasTapCost())).count();
                value += 12 * Math.min(10, resources) * card.getController().getOpponents().size();
            }
            if (mode.equals("CantBeCast") || mode.equals("CantBeActivated")) {
                value += StrategicThreatPatterns.restriction(card, st);
            }
            if (!mode.equals("Continuous")) { continue; }
            value += permissionValue(card,st);
            value += StrategicThreatPatterns.extraLands(card,st);
            value += grantedStaticValue(card,st);
            String valid = st.getParam("Affected");
            if (valid == null || !valid.contains("YouCtrl") || st.hasParam("AffectedZone")) { continue; }
            double buff = 12 * amount(card, st, "AddPower", 0) + 8 * amount(card, st, "AddToughness", 0);
            String keywords = st.getParamOrDefault("AddKeyword", "");
            if (keywords.contains("Hexproof") || keywords.contains("Indestructible") || keywords.contains("Shroud")) { buff += 35; }
            for (Card beneficiary : card.getController().getCreaturesInPlay()) {
                if (beneficiary != card && beneficiary.isValid(valid.split(","), card.getController(), card, st)) { value += buff; }
            }
        }
        return value;
    }

    private static boolean friendlyTokenRecipient(SpellAbility sa) {
        String owner=sa.getParamOrDefault("TokenOwner","You");
        if (owner.equals("You") || owner.equals("Player.You")) { return true; }
        Trigger t=sa.getTrigger();
        // opportunities() counts our own attackers/damage sources only.
        return t!=null && (owner.equals("TriggeredAttackerController") && t.getMode()==TriggerType.Attacks
                || owner.equals("TriggeredSourceController") && (t.getMode()==TriggerType.DamageDone || t.getMode()==TriggerType.DamageDoneOnce));
    }

    private static double stackInteraction(Card card,SpellAbility sa,double points) {
        if (!sa.usesTargeting()) { return 0; }
        for (var entry : card.getGame().getStack()) {
            SpellAbility target=entry.getSpellAbility();
            if (target.getActivatingPlayer()==null || !target.getActivatingPlayer().isOpponentOf(card.getController())) { continue; }
            try { if(sa.copy(card.getController()).canTarget(target)) { return points; } }
            catch(RuntimeException unsupported) { return 0; }
        }
        return 0;
    }

    private static double groupValue(Card card,SpellAbility sa) {
        String valid=sa.getParamOrDefault("ValidCards","Creature");
        double unit;
        boolean harm=false;
        if(sa.getApi()==ApiType.PutCounterAll) {
            String type=sa.getParamOrDefault("CounterType","");
            if (!type.equals("P1P1") && !type.equals("M1M1")) { return 0; }
            harm=type.equals("M1M1"); unit=20*amount(card,sa,"CounterNum",1);
        } else {
            unit=12*amount(card,sa,"NumAtt",0)+8*amount(card,sa,"NumDef",0);
            if(sa.getParamOrDefault("KW","").contains("Indestructible")) { unit+=35; }
        }
        double value=0; int count=0;
        for(Card c:card.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (++count>96) { break; }
            if (!c.isCreature() || !StrategicThreatSupport.matches(c,valid,card,sa)) { continue; }
            boolean own=c.getController()==card.getController();
            value += (own!=harm ? 1 : -1)*unit;
        }
        return Math.max(0,Math.min(400,value));
    }

    /** Conservative event supply from visible enablers; no arbitrary trigger simulation. */
    private static double eventSupply(Card host,boolean life) {
        double total=0;int scanned=0;
        for(Card source:host.getController().getCardsIn(ZoneType.Battlefield)) {
            if (++scanned>48) { break; }
            if(source.isPhasedOut()) { continue; }
            double contribution=0;
            if(life && source.isCreature() && source.hasKeyword(forge.game.keyword.Keyword.LIFELINK)
                    && host.getController().getOpponents().stream().anyMatch(p -> CombatUtil.canAttackNextTurn(source,p))) { contribution=.5; }
            for(SpellAbility sa:source.getSpellAbilities()) {
                if(!sa.isActivatedAbility() || sa.isSuppressed() || conditional(sa)) { continue; }
                var budget=StrategicThreatSupport.budget(source,sa);
                boolean supplies=life ? sa.getApi()==ApiType.GainLife && StrategicThreatSupport.friendly(sa,source)
                        : sa.getPayCosts()!=null && sa.getPayCosts().getCostParts().stream().anyMatch(p -> p instanceof CostSacrifice && !p.payCostFromSource());
                if(supplies) { contribution=Math.max(contribution,Math.min(2,budget.rate())); }
            }
            for(Trigger t:source.getTriggers()) {
                if(t.isSuppressed() || conditional(t) || !"Battlefield".equals(t.getParamOrDefault("TriggerZones","Battlefield"))) { continue; }
                SpellAbility sa=t.ensureAbility();if(sa==null) { continue; }
                // Only independent modes, so this scan cannot recursively amplify itself.
                if(!Set.of(TriggerType.Attacks,TriggerType.Drawn,TriggerType.Phase,TriggerType.ChangesZone).contains(t.getMode())) { continue; }
                boolean supplies=life ? sa.getApi()==ApiType.GainLife && StrategicThreatSupport.friendly(sa,source)
                        : sa.getApi()==ApiType.Sacrifice && "You".equals(sa.getParamOrDefault("Defined","You"));
                if(supplies) { contribution=Math.max(contribution,Math.min(2,opportunities(source,t))); }
            }
            total+=contribution;
        }
        return Math.min(3,total);
    }

    private static double permissionValue(Card host,StaticAbility st) {
        return StrategicThreatPatterns.permission(host, st);
    }

    private static double grantedStaticValue(Card host,StaticAbility st) {
        if((!st.hasParam("AddTrigger") && !st.hasParam("AddAbility")) || st.hasParam("AffectedZone")) { return 0; }
        double total=0;int scanned=0;
        for(Card beneficiary:host.getController().getCardsIn(ZoneType.Battlefield)) {
            if(++scanned>24) { break; }
            if(!StrategicThreatSupport.matches(beneficiary,st.getParamOrDefault("Affected",""),host,st)) { continue; }
            for(String name:st.getParamOrDefault("AddTrigger","").split(" & ")) {
                String script=st.getSVar(name);if(script.isEmpty()) { continue; }
                try {
                    Trigger t=TriggerHandler.parseTrigger(script,beneficiary,false,st);
                    if(conditional(t)) { continue; }
                    if(beneficiary==host && host.getTriggers().stream().anyMatch(existing -> existing.getParamOrDefault("Execute","").equals(t.getParamOrDefault("Execute","?")))) { continue; }
                    double rate=opportunities(beneficiary,t);
                    if(t.hasParam("ActivationLimit")) { rate=Math.min(rate,amount(beneficiary,t,"ActivationLimit",0)); }
                    total+=rate*effectValue(beneficiary,t.ensureAbility(),newIdentitySet(),2);
                } catch(RuntimeException unsupported) { /* unknown granted syntax contributes zero */ }
            }
            double bestAbility=0;
            for(String name:st.getParamOrDefault("AddAbility","").split(" & ")) {
                if(name.isEmpty()) { continue; }
                try {
                    SpellAbility ability=AbilityFactory.getAbility(beneficiary,name,st);
                    if(conditional(ability)) { continue; }
                    var budget=StrategicThreatSupport.budget(beneficiary,ability);
                    bestAbility=Math.max(bestAbility,budget.rate()*Math.max(0,effectValue(beneficiary,ability,newIdentitySet(),2)-budget.opportunityCost()));
                } catch(RuntimeException unsupported) { /* no speculative granted activation */ }
            }
            total+=bestAbility;
        }
        return Math.min(400,total);
    }

    private static double grantedEffectValue(Card host,SpellAbility sa,Set<SpellAbility> visited,int depth) {
        if(depth>=4 || sa.hasParam("RememberObjects") || sa.hasParam("ImprintCards")
                || !"Permanent".equals(sa.getParamOrDefault("Duration","Permanent"))) { return 0; }
        double total=0;int scanned=0;
        for(String name:sa.getParamOrDefault("Triggers","").split(",")) {
            if(++scanned>8 || name.isEmpty()) { break; }
            try {
                Trigger t=TriggerHandler.parseTrigger(sa.getSVar(name),host,false,sa);
                if(!conditional(t)) { total+=opportunities(host,t)*effectValue(host,t.ensureAbility(),visited,depth+1); }
            } catch(RuntimeException unsupported) { /* no speculative payoff */ }
        }
        for(String name:sa.getParamOrDefault("StaticAbilities","").split(",")) {
            if(++scanned>16 || name.isEmpty()) { break; }
            try {
                StaticAbility st=new StaticAbility(sa.getSVar(name),host,host.getCurrentState());
                if(!conditional(st)) { total+=permissionValue(host,st); }
            } catch(RuntimeException unsupported) { /* no speculative payoff */ }
        }
        double bestAbility=0;
        for(String name:sa.getParamOrDefault("Abilities","").split(",")) {
            if(++scanned>24 || name.isEmpty()) { break; }
            try {
                SpellAbility granted=AbilityFactory.getAbility(host,name,sa);
                if(conditional(granted)) { continue; }
                var budget=StrategicThreatSupport.budget(host,granted);
                bestAbility=Math.max(bestAbility,budget.rate()*Math.max(0,effectValue(host,granted,visited,depth+1)-budget.opportunityCost()));
            } catch(RuntimeException unsupported) { /* no speculative granted activation */ }
        }
        total+=bestAbility;
        return Math.min(300,total);
    }

    /** Copies are valued from legal public targets; never instantiate or simulate a token. */
    private static double copyValue(Card host, SpellAbility sa, Set<SpellAbility> visited, int depth) {
        if (depth >= 2 || !sa.usesTargeting() || sa.hasParam("Defined") || sa.hasParam("CopySource")
                || !"You".equals(sa.getParamOrDefault("Controller", "You"))) { return 0; }
        boolean temporary = sa.hasParam("AtEOT");
        boolean haste = sa.getParamOrDefault("AddKeywords", "").contains("Haste");
        double best = 0;
        int inspected = 0;
        for (Card target : host.getController().getCreaturesInPlay()) {
            if (++inspected > 48) { break; }
            if (target.isFaceDown() || target.isPhasedOut() || target.getType().isLegendary()
                    || !StrategicThreatSupport.canTarget(sa, target, host)) { continue; }
            double body = Math.max(0, ComputerUtilCard.evaluateCreature(target)) * (temporary ? (haste ? .6 : .35) : 1);
            double production = 0;
            for (Trigger trigger : target.getTriggers()) {
                if (trigger.isSuppressed() || conditional(trigger)
                        || !"Battlefield".equals(trigger.getParamOrDefault("TriggerZones", "Battlefield"))) { continue; }
                boolean selfEntry = trigger.getMode() == TriggerType.ChangesZone
                        && "Battlefield".equals(trigger.getParam("Destination"))
                        && trigger.getParamOrDefault("ValidCard", "").contains("Self")
                        && trigger.matchesValidParam("ValidCard", target);
                double rate = selfEntry ? 1 : opportunities(target, trigger) * (temporary ? .5 : 1);
                production += rate * effectValue(target, trigger.ensureAbility(), visited, depth + 1);
            }
            double activated = 0;
            for (SpellAbility ability : target.getSpellAbilities()) {
                if (!ability.isActivatedAbility() || ability.isSuppressed() || conditional(ability)
                        || !"Battlefield".equals(ability.getParamOrDefault("ActivationZone", "Battlefield"))) { continue; }
                if (temporary && !haste && ability.getPayCosts() != null && ability.getPayCosts().hasTapCost()) { continue; }
                var budget = StrategicThreatSupport.budget(target, ability);
                activated = Math.max(activated, Math.min(1, budget.rate())
                        * Math.max(0, effectValue(target, ability, visited, depth + 1) - budget.opportunityCost()));
            }
            best = Math.max(best, body + Math.min(300, production + activated));
        }
        return Math.min(600, best * amount(host, sa, "NumCopies", 1));
    }

    /** Marginal value of supported trigger modifiers, without running engine events. */
    private static double triggerModifierValue(Card host, StaticAbility st, boolean denial) {
        // Event-specific restrictions need explicit interpretation, not optimistic omission.
        for (String key : st.getMapParams().keySet()) {
            if (!Set.of("Mode", "ValidMode", "ValidCard", "ValidCause", "Origin", "Destination",
                    "Description", "Secondary", "ValidZone", "EffectZone").contains(key)) { return 0; }
        }
        if (!"Battlefield".equals(st.getParamOrDefault("EffectZone", "Battlefield"))
                || !"Battlefield".equals(st.getParamOrDefault("ValidZone", "Battlefield"))) { return 0; }
        String cause = st.getParamOrDefault("ValidCause", "Permanent");
        if (!Set.of("Permanent", "Card", "Creature", "Artifact", "Artifact,Creature").contains(cause)) { return 0; }
        double total = 0;
        int inspected = 0;
        for (Card source : host.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (++inspected > 96) { break; }
            if (source == host || source.isPhasedOut() || !StrategicThreatSupport.matches(source,
                    st.getParamOrDefault("ValidCard", "Permanent"), host, st)) { continue; }
            boolean friendly = source.getController() == host.getController();
            double sign = denial ? (friendly ? -1 : 1) : (friendly ? 1 : -1);
            for (Trigger trigger : source.getTriggers()) {
                if (trigger.isSuppressed() || conditional(trigger) || trigger.hasParam("ActivationLimit")
                        || trigger.hasParam("NumActivations") || trigger.getSpawningAbility() != null
                        || !"Battlefield".equals(trigger.getParamOrDefault("TriggerZones", "Battlefield"))) { continue; }
                if (st.hasParam("ValidMode") && !List.of(st.getParam("ValidMode").split(",")).contains(trigger.getMode().toString())) { continue; }
                if (st.hasParam("Destination") && !st.getParam("Destination").equals(trigger.getParam("Destination"))) { continue; }
                if (st.hasParam("Origin") && !st.getParam("Origin").equals(trigger.getParam("Origin"))) { continue; }
                // Restrict broad cause filters to events whose cause type is known here.
                if (trigger.getMode() != TriggerType.ChangesZone && trigger.getMode() != TriggerType.Attacks) { continue; }
                if (!cause.equals("Permanent") && !cause.equals("Card")) {
                    String eventFilter = trigger.getParamOrDefault("ValidCard", "");
                    if (!eventFilter.startsWith(cause + ".") && !eventFilter.equals(cause)) { continue; }
                }
                total += sign * opportunities(source, trigger) * effectValue(source, trigger.ensureAbility(), newIdentitySet(), 0);
            }
        }
        // Unseen future ETB cards: a modest explicit prior, based ONLY on hand counts.
        // Public self-ETB triggers are already spent and receive no recurring credit above.
        String filter = st.getParamOrDefault("ValidCard", "Permanent");
        if ("Battlefield".equals(st.getParam("Destination")) && !st.hasParam("Origin")
                && st.getParamOrDefault("ValidMode", "").contains("ChangesZone")
                && Set.of("Permanent.YouCtrl", "Permanent.OppCtrl+inZoneBattlefield", "Permanent.OppCtrl", "Permanent").contains(filter)) {
            double breadth = cause.equals("Permanent") || cause.equals("Card") ? 1 : .5;
            for (Player player : host.getGame().getPlayers()) {
                boolean friendly = player == host.getController();
                if (filter.contains("YouCtrl") && !friendly || filter.contains("OppCtrl") && !player.isOpponentOf(host.getController())) { continue; }
                double sign = denial ? (friendly ? -1 : 1) : (friendly ? 1 : -1);
                total += sign * breadth * 35 * Math.min(2, player.getCardsIn(ZoneType.Hand).size() * .35);
            }
        }
        return Math.max(0, Math.min(400, total));
    }

    private static double replacementValue(Card card) {
        double value = 0;
        for (var replacement : card.getReplacementEffects()) {
            if (replacement.isSuppressed() || conditional(replacement)
                    || !"Battlefield".equals(replacement.getParamOrDefault("ActiveZones", "Battlefield"))) { continue; }
            String event = replacement.getParamOrDefault("Event", "");
            if (!event.equals("CreateToken") && !event.equals("AddCounter")) { continue; }
            String valid = replacement.getParamOrDefault(event.equals("CreateToken") ? "ValidToken" : "ValidCard", "");
            if (!valid.contains("YouCtrl")) { continue; }
            SpellAbility effect = replacement.getOverridingAbility();
            if (effect == null) { continue; }
            boolean multiplier = event.equals("CreateToken") && effect.getApi() == ApiType.ReplaceToken && "Amount".equals(effect.getParam("Type"))
                    || event.equals("AddCounter") && effect.getApi() == ApiType.ReplaceCounter
                    && effect.getSVar(effect.getParamOrDefault("Amount", "")).equals("ReplaceCount$CounterNum/Twice");
            if (!multiplier) { continue; }
            ApiType producer = event.equals("CreateToken") ? ApiType.Token : ApiType.PutCounter;
            int sources = 0;
            for (Card source : card.getController().getCardsIn(ZoneType.Battlefield)) {
                boolean found = source.getSpellAbilities().stream().anyMatch(sa -> sa.isActivatedAbility() && sa.getApi() == producer
                        && !sa.isSuppressed() && !conditional(sa) && StrategicThreatSupport.budget(source, sa).rate() >= 1
                        && effectValue(source, sa, newIdentitySet(), 0) > 0);
                for (Trigger t : source.getTriggers()) {
                    SpellAbility sa = t.ensureAbility();
                    if (!t.isSuppressed() && !conditional(t) && sa != null && sa.getApi() == producer
                            && "Battlefield".equals(t.getParamOrDefault("TriggerZones", "Battlefield"))
                            && opportunities(source, t) > 0 && effectValue(source, sa, newIdentitySet(), 0) > 0) { found = true; }
                }
                if (found) { sources++; }
            }
            value += 60 * Math.min(4, sources);
        }
        return value;
    }

    /** Keep a directly lethal, currently unblocked attacker ahead of speculative engines. */
    static boolean directlyLethal(Player ai, Card card) {
        if (Boolean.getBoolean("forge.ai.disableStrategicThreat")) { return false; }
        Combat combat = ai.getGame().getCombat();
        PhaseType phase = ai.getGame().getPhaseHandler().getPhase();
        return combat != null && phase == PhaseType.COMBAT_DECLARE_BLOCKERS
                && combat.isAttacking(card) && combat.getDefenderByAttacker(card) == ai && !combat.isBlocked(card)
                && ComputerUtilCombat.damageIfUnblocked(card, ai, combat, true) >= ai.getLife()
                && ai.getLife() > 0;
    }
}
