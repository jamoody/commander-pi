package forge.ai;

import forge.game.CardTraitBase;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.cost.*;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.*;

/** Public-state, bounded helpers. Unknown syntax is deliberately not evaluated by AbilityUtils. */
final class StrategicThreatSupport {
    private StrategicThreatSupport() { }
    static boolean matches(Card c, String valid, Card host, CardTraitBase trait) {
        if (c.isFaceDown() || valid == null || valid.matches("(?i).*(library|hand|chosen).*")) { return false; }
        try { return c.isValid(valid.split(","), host.getController(), host, trait); }
        catch (RuntimeException unsupported) { return false; }
    }
    static double number(Card host, CardTraitBase trait, String expression) {
        return number(host, trait, expression, new HashSet<>(), 0);
    }
    private static double number(Card host, CardTraitBase trait, String expression, Set<String> seen, int depth) {
        if (expression == null || depth > 5 || !seen.add(expression)) { return 0; }
        if (expression.matches("-?[0-9]+")) {
            try { return Math.max(0, Math.min(40, Double.parseDouble(expression))); }
            catch (NumberFormatException ex) { return 0; }
        }
        if (expression.equals("Count$CardPower")) { return Math.max(0, host.getNetPower()); }
        if (expression.equals("Count$CardToughness")) { return Math.max(0, host.getNetToughness()); }
        int slash = expression.lastIndexOf('/');
        if (slash > 0) {
            double base = number(host, trait, expression.substring(0, slash), seen, depth + 1);
            String operation = expression.substring(slash + 1);
            if (operation.equals("Twice")) { return Math.min(40, 2 * base); }
            if (operation.equals("HalfUp")) { return Math.ceil(base / 2); }
            if (operation.equals("HalfDown")) { return Math.floor(base / 2); }
            return 0;
        }
        if (expression.startsWith("Count$Valid ")) {
            String[] parts = expression.substring(12).split("\\$", -1);
            if (parts.length > 2) { return 0; }
            List<Card> eligible = new ArrayList<>();
            for (Card c : host.getGame().getCardsIn(ZoneType.Battlefield)) {
                if (matches(c, parts[0], host, trait)) { eligible.add(c); }
            }
            String aggregate = parts.length == 1 ? "Count" : parts[1];
            return switch (aggregate) {
                case "Count" -> Math.min(40, eligible.size());
                case "GreatestCardPower" -> eligible.stream().mapToInt(Card::getNetPower).max().orElse(0);
                case "GreatestCardToughness" -> eligible.stream().mapToInt(Card::getNetToughness).max().orElse(0);
                case "TotalCardPower" -> Math.min(40, eligible.stream().mapToInt(Card::getNetPower).sum());
                case "Colors" -> colors(eligible);
                default -> 0;
            };
        }
        if (expression.equals("PlayerCountRegisteredOpponents$HasPropertywasDealtCombatDamageThisTurn")) {
            long observed = host.getController().getOpponents().stream()
                    .filter(p -> p.hasProperty("wasDealtCombatDamageThisTurn", host.getController(), host, trait)).count();
            if (host.getGame().getPhaseHandler().getPhase().ordinal() >= forge.game.phase.PhaseType.COMBAT_DAMAGE.ordinal()) { return observed; }
            return Math.max(observed, Math.min(host.getController().getOpponents().size(), host.getController().getCreaturesInPlay().size() * .35));
        }
        if (expression.matches("[A-Za-z][A-Za-z0-9_]*")) {
            String next = trait.getSVar(expression);
            if (!next.isEmpty()) { return number(host, trait, next, seen, depth + 1); }
        }
        return 0;
    }
    static int colors(Iterable<Card> cards) {
        int mask = 0;
        for (Card c : cards) { if (!c.isFaceDown()) { mask |= c.getColor().getColor(); } }
        return Integer.bitCount(mask & 31);
    }
    static double manaOutput(Card card, SpellAbility ability) {
        String produced = ability.getParamOrDefault("Produced", "");
        String prefix = "Special EachColorAmong_Valid ";
        if (produced.startsWith(prefix)) {
            List<Card> eligible = new ArrayList<>();
            for (Card c : card.getController().getCardsIn(ZoneType.Battlefield)) {
                if (matches(c, produced.substring(prefix.length()), card, ability)) { eligible.add(c); }
            }
            return colors(eligible);
        }
        if (produced.startsWith("Special")) { return 0; }
        double multiplier = produced.matches("[WUBRGC]( [WUBRGC])+") ? produced.split(" ").length : 1;
        return multiplier * (ability.hasParam("Amount") ? number(card, ability, ability.getParam("Amount")) : 1);
    }

    record Budget(double rate, double opportunityCost) { }
    static Budget budget(Card card, SpellAbility ability) {
        if (ability.getPayCosts() == null) { return new Budget(0, 0); }
        Player p = card.getController();
        double rate = 3, penalty = 0;
        for (CostPart part : ability.getPayCosts().getCostParts()) {
            if (part instanceof CostPartMana mana) {
                var cost = mana.getMana();
                if (cost.countX() > 0) { return new Budget(0, 0); }
                List<Integer> sources = manaSources(card);
                List<Integer> available = new ArrayList<>(sources);
                // Assign colored shards to distinct public sources. This is a conservative
                // approximation: floating mana, restricted mana and multimana lands are omitted.
                for (var shard : cost) {
                    int color = shard.getColorMask();
                    if (color == 0) { continue; }
                    int match = -1;
                    for (int i = 0; i < available.size(); i++) { if ((available.get(i) & color) != 0) { match = i; break; } }
                    if (match < 0) { return new Budget(0, 0); }
                    available.remove(match);
                }
                if (cost.getCMC() > sources.size()) { return new Budget(0, 0); }
                if (cost.getCMC() > 0) {
                    rate = Math.min(rate, sources.size() / (double)cost.getCMC());
                    penalty += 12 * cost.getCMC();
                }
            } else if (part instanceof CostTap) {
                rate = Math.min(rate, 1);
            } else if (part instanceof CostSacrifice || part instanceof CostTapType) {
                if (part.payCostFromSource()) { return new Budget(0, 0); }
                double n = number(card, ability, part.getAmount());
                if (n <= 0) { return new Budget(0, 0); }
                List<Card> pool = new ArrayList<>();
                for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                    if (c != card && matches(c, part.getType(), card, ability)
                            && (!(part instanceof CostTapType) || !c.isTapped())) { pool.add(c); }
                }
                rate = Math.min(rate, Math.floor(pool.size() / n));
                if (part instanceof CostSacrifice) {
                    pool.sort(Comparator.comparingInt(c -> c.isCreature() ? ComputerUtilCard.evaluateCreature(c) : 100 + c.getCMC() * 15));
                    double expense = 0;
                    for (int i = 0; i < Math.min(pool.size(), (int)n); i++) {
                        Card c = pool.get(i);
                        expense += c.isToken() ? 20 : c.isCreature() ? Math.max(30, ComputerUtilCard.evaluateCreature(c) * .3) : 60;
                    }
                    penalty += expense;
                }
            } else if (part instanceof CostPayLife) {
                double n = number(card, ability, part.getAmount());
                if (n <= 0) { return new Budget(0, 0); }
                rate = Math.min(rate, Math.floor(Math.max(0, p.getLife() - 5) / n)); penalty += n * 5;
            } else if (part instanceof CostDiscard) {
                if (!part.getType().equals("Card")) { return new Budget(0, 0); }
                double n = number(card, ability, part.getAmount());
                if (n <= 0) { return new Budget(0, 0); }
                rate = Math.min(rate, Math.floor(p.getCardsIn(ZoneType.Hand).size() / n)); penalty += n * 65;
            } else if (part instanceof CostRemoveCounter counter) {
                if (!part.payCostFromSource() || counter.counter == null) { return new Budget(0, 0); }
                double n = number(card, ability, part.getAmount());
                if (n <= 0) { return new Budget(0, 0); }
                rate = Math.min(rate, Math.floor(card.getCounters(counter.counter) / n)); penalty += 12 * n;
            } else { return new Budget(0, 0); }
        }
        if (ability.hasParam("ActivationLimit")) {
            double limit = number(card, ability, ability.getParam("ActivationLimit"));
            rate = Math.min(rate, Math.max(0, limit - card.getAbilityActivatedThisTurn(ability)));
        }
        if (ability.hasParam("GameActivationLimit")) { return new Budget(0, 0); }
        return new Budget(Math.max(0, rate), penalty);
    }
    private static List<Integer> manaSources(Card host) {
        List<Integer> sources = new ArrayList<>();
        for (Card c : host.getController().getCardsIn(ZoneType.Battlefield)) {
            int mask = 0; boolean produces = false;
            for (SpellAbility sa : c.getManaAbilities()) {
                if (sa.isSuppressed()) { continue; }
                String produced = sa.getParamOrDefault("Produced", "");
                if (produced.equals("Any")) { mask |= 31; produces = true; }
                if (produced.matches("[WUBRGC]( [WUBRGC])*")) {
                    for (int i = 0; i < 5; i++) { if (produced.contains("WUBRG".substring(i, i + 1))) { mask |= 1 << i; } }
                    produces = true;
                }
            }
            if (produces) { sources.add(mask); }
        }
        return sources;
    }
    static boolean canPayPrintedCost(Card host, Card spell) {
        var cost=spell.getManaCost();
        List<Integer> available=manaSources(host);
        if(cost.isNoCost() || cost.countX()>0 || cost.getCMC()>available.size()) { return false; }
        for(var shard:cost) {
            int color=shard.getColorMask();if(color==0) { continue; }
            int match=-1;
            for(int i=0;i<available.size();i++) { if((available.get(i)&color)!=0) { match=i;break; } }
            if(match<0) { return false; }available.remove(match);
        }
        return true;
    }

    static boolean blocked(CardTraitBase trait) {
        Card host = trait.getHostCard(); Player p = host.getController();
        for (String key : trait.getMapParams().keySet()) {
            String val = trait.getParam(key);
            if (key.equals("ActivationLimit") || key.equals("ActivationPhases") || key.equals("PlayerTurn") || key.equals("OpponentTurn")) { continue; }
            if (key.equals("Condition") && val.equals("PlayerTurn")) { continue; } // recurring own-turn protection
            if (key.equals("Activation")) {
                boolean satisfied = switch (val) {
                    case "Threshold" -> p.getCardsIn(ZoneType.Graveyard).size() >= 7;
                    case "Metalcraft" -> p.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isArtifact).count() >= 3;
                    case "Hellbent" -> p.getCardsIn(ZoneType.Hand).isEmpty();
                    default -> false;
                };
                if (!satisfied) { return true; }
                continue;
            }
            if (key.startsWith("Condition") || key.startsWith("CheckSVar") || key.equals("IsPresent")
                    || key.equals("Metalcraft") || key.equals("Threshold") || key.equals("PresentDefined") || key.equals("GameActivationLimit")) { return true; }
        }
        return false;
    }

    static boolean friendly(SpellAbility sa, Card host) {
        if (sa.usesTargeting()) { return canTarget(sa, host.getController(), host); }
        String d = sa.getParamOrDefault("Defined", "You");
        return d.equals("You") || d.equals("Player.You") || d.equals("TriggeredSourceController");
    }
    static double hostileTargets(SpellAbility sa, Card host, boolean permanents) {
        if (sa.usesTargeting()) {
            if (permanents) {
                return host.getController().getOpponents().stream().flatMap(p -> p.getCardsIn(ZoneType.Battlefield).stream())
                        .anyMatch(c -> canTarget(sa, c, host)) ? 1 : 0;
            }
            return host.getController().getOpponents().stream().anyMatch(p -> canTarget(sa, p, host)) ? 1 : 0;
        }
        String d = sa.getParamOrDefault("Defined", "");
        if (d.equals("Opponent") || d.equals("EachOpponent") || d.equals("Player.Opponent")) { return host.getController().getOpponents().size(); }
        if (d.equals("TriggeredCardController") || d.equals("TriggeredTarget") || d.equals("TriggeredPlayer")) { return 1; }
        return 0;
    }
    static boolean canTarget(SpellAbility ability, GameObject target, Card host) {
        try { return ability.copy(host.getController()).canTarget(target); }
        catch (RuntimeException unknown) { return false; }
    }
}
