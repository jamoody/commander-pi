# Opponent assessment and meaningful attacks (v6, 2026-11-06)

The experimental AI now separates a player's visible strategic position from the value
of taking a particular action against them. No rules-engine or UI changes were made.

## Shared position

`OpponentAssessment.Position` exposes engine, resource, combat and immediate-danger
components. It adds no independent hand-size or low-life bonus. The previous strategic
ability interpreter remains responsible for recognizing production; hand counts can still
limit a particular engine's opportunities, and discard still requires cards to discard.

Engine contributions are capped per permanent, sorted and discounted across additional
sources, with an overall cap. This reduces overlap; it is not an exact allocation of all
shared mana, sacrifice fodder or other activation resources. Mana producers, planeswalkers
and other support permanents contribute bounded resource scores. Ordinary combat bodies
have a smaller, separate component. A detached combat forecast estimates immediate danger.
Selected explicit win effects and targeted lethal damage already on the stack add urgency.
Unknown combos, branches and alternative-win conditions are not solved generally.

`ComputerUtil.evaluateBoardPosition` now uses this assessment. The preferred-opponent
helper uses stable seat-order ties, removing its former low-life-squared and random-near-tie
selection. Existing generic `Player.getStrongestOpponent`/`getWeakestOpponent` methods in
the rules module remain unchanged; specialist legacy callers outside the integrated paths
can still use them. This is not a rewrite of every individual card AI or simulation policy.

## Attack plans

For each opponent the planner compares no attack, an all-eligible attack, a safer subset,
and variants reserving up to four valuable defenders. It respects the global/defender
attacker cap and excludes paid-attack costs it cannot budget. It uses detached Combat
objects and Forge damage, combat-death, evasion, poison and commander-damage helpers.
Deterministic plausible blocks account for creature losses and legal blocker counts.
The search is bounded: 32 candidate attackers, 64 blockers, 12 candidate blockers per
attacker and groups of at most three. These bounds omit some legal or optimal large-board
plans. Activated defenders, exotic requirements and opponent combat tricks are not fully
simulated. Existing engine legality/required-attack processing remains in control.

The action value includes expected player damage, valuable opposing losses, own losses,
attack/combat-damage rewards and exposure from leaving attackers unavailable as blockers.
Supported triggers on other friendly permanents count too: Winota can reward a non-Human
attack while she stays back. No player damage is necessary if a useful trigger justifies
the attack. Recognized rewards and total production are capped; unsupported conditions
remain conservative. Group/once triggers are not credited independently to every attacker.

An achievable elimination is strongly preferred; a low life total by itself is not.
Immediate counterattack lethality remains a safety check. Nonlethal counterattack exposure
is discounted if another player has a visibly stronger position; this is an explicit
heuristic, not a political model or a simulation of consecutive opponents' turns.

After required attacks are processed, the new plan limits optional attackers. The original
controller retains legality checks, attack taxes, planeswalker/battle handling and other
combat safeguards. It may choose fewer attackers than the proposed plan. Random trade
chances are disabled on this path. Randomness elsewhere in legacy AI and Magic's rules is
not globally removed.

## Interaction and removal

- Main damage targeting follows strategic threat unless a legal, predicted player kill is
  available. Mandatory player-target iteration uses the same deterministic order.
- Targeted discard ranks suitable opponents by threat rather than shuffling. Discard X
  budgeting follows the actual target/defined recipients, not the lowest-life opponent.
- Creature removal retains the previous strategic bonus and directly-lethal priority.
  Its controller-context contribution is deliberately small (one thirty-second of the
  contextual score after subtracting the target's capped production). This avoids allowing
  player context to reverse the Sentinel-versus-ordinary-body regression case.
- Other ability handlers that already call the shared preferred-opponent or board-position
  routines inherit the changes; card-specific exceptions are not all replaced.

Position/card-value caches are local to one opponent decision, and removal contexts are
cached only for one candidate ranking. No new persistent cache can outlive a board change.
The existing board-position entry point retains Forge's own AiCache lifecycle.

## Verification and rollout

See `outputs/AI-Opponent-Assessment-v6` for the final packaged test logs and build evidence.
The dedicated test covers productive engines, hand/life independence, deterministic ties,
actual burn/discard handlers, bad trades, safe damage, commander lethal, retained blockers,
menace, attack rewards (including nonattacking Winota), pending lethal damage and real attack
controller integration. The previous suites must pass against the packaged JAR as well.
Full-match strength and Raspberry Pi speed have not been measured. Small Windows timings
are diagnostic observations, not a Pi performance promise or a win-rate result.

Only `Commander-AI-Windows/app/forge-ai-2.0.16-SNAPSHOT.jar` is replaced. The experimental UI
JAR and stable Commander-Windows remain unchanged. The prior AI JAR is retained locally at
`.build/forge-ai-before-opponent-assessment.jar`. No GitHub push or commit is performed.

`forge.ai.disableOpponentAssessment=true` restores the older shared board/attack selection
and primary burn selection; it also restores discard shuffling. The baseline comparison
launcher supplies that flag together with `forge.ai.disableStrategicThreat=true`.
Corrections to discard recipient/cost consistency remain; it is not a byte-for-byte old app.

## Final packaged result

213 checks passed (30 opponent-specific checks plus 183 previous checks), including a
150-permanent opponent-assessment sweep and the previous 1,000-creature regression sweep.
The final Forge AI reactor build passed with Checkstyle enabled; upstream tests were skipped.
Only the experimental AI JAR was replaced. Source and verification artifacts are saved locally.

AI SHA-256: `64557f68e29dbc1ce107206faf527f3d990c118cb81174bd1bd5e774a38bc646`.
