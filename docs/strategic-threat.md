# Experimental removal assessment

Branch: `ai-experiments`, based on stable commit `0fbd146bffe110f72d3a6ba14e64f9a4a6e7fdd4`.
UI updates belong on main and can be merged into this branch. Do not copy this branch's
engine overrides or compiled JARs into the stable build without deliberate review.

## Model

### Shared event/effect interpretation (fourth iteration)

Recipient normalization accepts Player.Opponent alongside Opponent, recognizes the default
Self for untargeted +1/+1 counters, and accepts triggered attacker/source token recipients
only with matching own-attacker/damage trigger context. New opportunities include attack
declarations, sacrifices with visible enablers and fodder, life gain with visible lifelink or
gain-life sources, tapping, targeted creatures with visible targeting enablers, and a limited
friendly-entry batch pattern. ActivationLimit bounds the added trigger estimate.

Group PumpAll/PutCounterAll effects use matching public creatures and beneficiary direction.
Single-choice Charm takes the largest recognized choice rather than summing alternatives.
RepeatEach supports bounded per-opponent repetition. Multi-choice combinations, general
Branch/Repeat, and delayed/reflexive trigger interpretation remain unsupported.

Interaction support includes targeted bounce, tapping, theft, hand-count-bounded discard,
and counter/stack bounce against currently legal opposing stack objects. Empty hands/stack
do not supply imaginary victims. This is not a full legality/removal-consequence simulator.

Graveyard MayPlay permissions recognize filtered public nonland cards when approximate
colored printed-cost resources exist. Alternate costs, free-cast permissions and hidden-zone
permissions are not guessed. The existing graveyard land behavior remains. Multiple type
permissions can overestimate jointly affordable plays; scores remain bounded.

Continuous AddTrigger/AddAbility follows SVar references for matching friendly public
beneficiaries, without inserting abilities into their live lists. Permanent Effect containers
follow supported triggers, abilities and graveyard-play static permissions. Temporary effect
lifetimes, remembered-object bindings and unsupported granted syntax conservatively contribute
zero. Granted/visible synergy estimates are capped and do not solve shared-resource optimization
or infinite combos. Public enabler rates are heuristics, not event simulations.

Nadu's filtered Dig-to-battlefield with a hand fallback values either destination. This avoids
losing the fallback when ChangeNum is All. Other complex multi-destination digging remains
partial. All handlers retain existing recursion, score and local iteration bounds.

`ThreatReasonCheck.java` and `reason_threat_audit.py` document the earlier v3 audit and its
then-observed defects; do not treat their historical defect assertions as current regressions.
Current repaired behavior is covered by `SharedPatternsCheck.java` plus the three earlier
regression harnesses. The archived reason-audit classifications are not recomputed v4 coverage.

### Mana, copying and trigger modifiers (third iteration)

`TapsForMana` now counts eligible public producers with an affordable tap mana ability,
capped at ten ordinary uses per cycle. `ManaReflected` with `ReflectProperty=Produced`
values the scripted additional amount. This distinguishes Kinnan's one extra mana from
the amount the original producer made. Nonland/other script filters remain applicable.

Targeted `CopyPermanent` effects inspect up to 48 friendly public creature targets. The
best legal nonlegendary target supplies a body value plus bounded recognized ETB/recurring
production; temporary copies are discounted and added haste changes their immediate value.
The evaluator does not instantiate tokens, search combo lines or recursively score an
unlimited chain of copy engines. Defined/imprinted targets, opposing copies, legendary
exceptions and many copy modifications remain unsupported. Copied static support is not
yet valued. These restrictions deliberately favor undercounting unsupported variants.

`Panharmonicon` and `DisableTriggers` interpret a bounded subset of static parameters,
including trigger-source ownership, cause types, zones and event modes. Supported visible
entry/attack engines contribute their estimated production. Denial subtracts friendly
production and adds opposing production; multiplication uses the opposite direction.
Spent self-ETBs receive no recurring credit. A separate modest future-ETB prior uses only
public hand counts (35 units per estimated entry, at most two per player, discounted for
narrow cause types). This is explicitly uncertain, not knowledge of hidden ETB cards.
No hand identities are inspected. Unsupported modifier conditions receive no bonus.

This iteration does not solve infinite combinations, redundancy among multiple suppressors,
or every modifier's interactions with trigger limits. An empty resource board can still
correctly yield zero. Validation lives in `verification/EngineFamiliesCheck.java`.

`StrategicThreatEvaluator` adds a bounded estimate of preventable production over roughly
one turn cycle to the existing removal ranking. It does not change CreatureEvaluator,
card scripts, game rules, attack selection, or sacrifice valuation. Existing legality,
regeneration, must-target and already-dying filters still run. Existing player-strength
weighting is retained; this is not a multiplayer politics overhaul.

The evaluator uses trigger modes, effect APIs, quantities, filters, costs and destinations.
There are no card-name comparisons. Winota, Sentinel and Mole Man are regression fixtures,
not rules in the implementation. Contributions are bounded to 1,200 points and recursion
to eight levels / 48 distinct ability nodes. No game-state simulation or library inspection
is performed. Scores are recalculated per decision; there is no stale persistent cache.

Effect coverage includes drawing, tokens, mana, library digging/tutoring, targeted own-graveyard
creature recursion, life gain/drain, damage, destruction, selected counters and untapping.
Recurring events include attacks, combat damage, other creatures dying, opponent searches,
spell casting, drawing, land/creature entry and selected phases. Continuous support recognizes
friendly buffs/protection, cost reduction, extra untaps, opponent restrictions and graveyard
land permission. Token/counter multipliers receive a bounded bonus with visible producers.

Activation budgets recognize colored mana, tapping, sacrificing other permanents, life,
generic discards and source-counter payments. Public resources constrain recurrence;
sacrifices/discards/life/mana carry opportunity costs. Alternative activated abilities use
the largest contribution rather than spending the same resources on every ability.
Numeric expressions support selected public aggregates and arithmetic, including greatest
creature power and colors among permanents. Unsupported aggregate syntax safely yields zero.

These are shared interpretations of Forge script fields, not per-card tags or exceptions.
Complex conditions, modal branches/repeats, arbitrary combos and unrecognized APIs retain
baseline evaluation. Coverage remains partial; recognizing a family does not imply that
every member's restrictions or strategic interactions are understood.

Approximations: combat-hit/death/search rates are priors, not simulated outcomes. Mana
availability approximates each public producer as one source and does not solve floating,
restricted or multi-output mana. Cost reduction uses hand size, not hidden identities or
known spell types. Multipliers currently examine direct token/counter producers only.
Effect values are rough common weights, not lethal/combo solvers. Target legality checks use
copies of abilities; public quantities avoid arbitrary AbilityUtils evaluation. The new
families require playtesting and Pi measurements before claiming stronger overall play.

The new bonus also contributes to `useRemovalNow` after its combat timing discounts.
Destroy, exile/bounce and damage callers pass the removal ability to the common ranking.
Bounce discounts future denial to 35%. Other removal consequences, death payoffs, commander
recasting and replacement interactions are not yet comprehensively modeled; existing
caller safeguards remain responsible for those. A directly lethal unblocked attacker at
declare-blockers receives priority over speculative engines; this is not a complete lethal
solver for poison, combined attackers, commander damage or every damage-step interaction.

## Rational-deck prior and public depletion

Library effects estimate a hit probability using three starting-supply assumptions:

| Filter | Starting eligible supply assumptions (Commander) |
|---|---|
| Any card | 99 |
| Any creature | 24 / 30 / 36 |
| Any land | 32 / 37 / 42 |
| Other restricted package | 10 / 16 / 22 |

These are tunable heuristics, not measured deckbuilding statistics. The final estimate
averages the three hit probabilities. Visible, nontoken, noncommander eligible cards owned
by the controller in battlefield/graveyard/face-up exile reduce estimated remaining supply.
Library size is public; library card identities/order, hidden hands and original deck lists
are never examined. A 5% supply floor reflects uncertainty about decks outside the prior.
An empty library always yields zero. Returning eligible cards to the library removes them
from the public depletion count. Cards are counted once in their current zone.

This first version uses current public accounting, not a persistent event history: it does
not remember a revealed card after it returns to a hidden hand, infer remaining hit quality,
track bottom-of-library ordering, or learn initial composition from observed samples.
It therefore lowers the estimate after seven visible Human deployments without claiming
to know exactly how many or how strong the remaining Humans are.

Attack opportunities use eligible public creatures and attack restrictions; tapped/sick
creatures before imminent combat are discounted. After attackers are declared, only a
discounted next combat is credited: existing stack triggers are not counted as stopped.
Opposing spell engines assume one qualifying spell per opponent per cycle, with an
approximate unpaid-tax probability based on the tax amount. They do not yet track every
opponent's first spell or calculate exact mana-payment decisions. Landfall supply uses hand
count as an estimate or public graveyard lands plus land-play permission, capped by land plays.
Token production currently uses a generic token value, not a full token-template evaluator.

## Build and verification

Use the ordinary repository build instructions and `scripts/apply_engine_overrides.py`.
The override manifest now supports new files with `originalSha256: null`; it refuses to
overwrite any unknown existing content and validates all inputs before changing files.

Compile `verification/StrategicThreatCheck.java` against a packaged application's `app/*`.
Run it from that package directory using `@app/launch.args`, a disposable `neo.dataDir`, and
a classpath containing the compiled checks plus `app/*`. It exercises real Forge objects
and DestroyAi, ChangeZoneAi and DamageDealAi target selection. It also verifies unchanged
body scores, depletion, hidden-information invariance, suppression, timing, lethal combat,
tax scaling, resource availability, graveyard land access and land-play limits.

`StrategicThreatEvaluator.assess` returns the score and reasons for diagnostic tools; normal
games do not print a log entry for every evaluation. Set JVM property
`-Dforge.ai.disableStrategicThreat=true` to restore the original removal rankings and timing
contributions for comparison. This disables the extra lethal priority too.

The Windows experimental package uses its own directory and profile. The stable package
is not updated. Keep GitHub changes batched until the user requests an upload.

## Verification result (2026-11-04)

Fourth iteration: full Maven package succeeded (upstream suite skipped). Packaged JARs passed
58 original + 27 expanded + 17 family + 33 shared-pattern checks = 135 checks. The full audit
evaluated 26,921 permanent faces without exceptions. Positive creature bonuses increased
from 1,960 to 2,298: 338 newly positive, none lost. Positive permanent bonuses rose from
3,896 to 4,354. These are fixture coverage results, not accuracy or win-rate estimates.
Hullbreaker and Nadu remain zero in this fixture because relevant public targets/enablers
are absent; resource-appropriate focused tests pass. The original seven-candidate Windows
benchmark took 281.8 ms for 500 calls; it is not a broad performance or Pi benchmark.
See `docs/threat-audit-v4.md`, `outputs/AI-Threat-Audit-v4`, and `.build/v4-*.log`.

Third iteration: full Maven package succeeded with the upstream suite skipped. Tests against
the packaged JARs passed 58 original, 27 expanded and 17 new family checks (102 total).
The common-catalog audit again evaluated 26,921 permanent faces with zero exceptions.
In the unchanged fixture, Kinnan's additional score is 22, Elesh Norn's 61, and Kiki-Jiki's
112 (all previously zero). Positive creature-face bonuses increased from 1,946 to 1,960;
positive permanent-face bonuses from 3,876 to 3,896. Many copy/modifier variants still lack
resources or supported parameters in that fixture; family population is not coverage.
The 500-ranking Windows timing was 310.5 ms, not a Pi benchmark. Reports are in
`docs/threat-audit-v3.md` and `outputs/AI-Threat-Audit-v3`. Logs use `.build/v3-*.log` and
`.build/audit-v3.log`. No UI changes were made in this iteration.

Expanded-family update: full Maven package succeeded (upstream suite skipped). The packaged
JARs passed the original 58 checks plus 27 new resource/direction/coverage checks. The complete
common-catalog audit scored 26,921 permanent faces with zero exceptions, fixing the Alena
aggregate-expression crash. Positive creature-face bonuses increased from 873 to 1,946 in
the same fixture; positive permanent-face bonuses increased from 2,547 to 3,876. These are
coverage observations, not an accuracy or win-rate claim. Detailed before/after results are
in `docs/threat-audit-v2.md` and the external `outputs/AI-Threat-Audit-v2` artifacts.

The updated seven-candidate fixture took 302.8 ms for 500 rankings on Windows. This single
run is not a controlled speed comparison and does not establish Raspberry Pi performance.
Logs: `.build/packaged-v2-strategic.log`, `.build/packaged-v2-expanded.log`, `.build/audit-v2.log`.
The packaged JavaFX smoke test also passed: Mulligan, zero-card bottom confirmation,
Keep, normal human play and clean return to menu. Art downloads were unavailable in the
restricted test environment; this validates controls, not image loading. UI log:
`.build/packaged-v2-ui.log`.

### Original prototype verification

Full Maven reactor package succeeded with JDK 17 (upstream test suite skipped).
The packaged JARs passed 58 assertions/checks, including real destroy/exile/damage target
selection and a seeded 1,000-creature catalog exception/bounds sweep. The original four
body scores remain Winota 221, Dreadmaw 306, Sentinel 131, Bears 161. With two eligible
attackers, Winota's new production bonus was 227; seven additional publicly accounted-for
Humans reduced it to 133. With four eligible attackers, the bonus was 455.

The real JavaFX Keep button test reached a human turn and returned to the menu cleanly.
Art download attempts failed in the network-restricted test environment; this check
establishes control behavior, not image quality. No full match-to-victory or Pi performance
test was run. A seven-creature ranking fixture took 424 ms for 500 calls on Windows
(about 0.85 ms/ranking); this is not a bound for arbitrary boards or Pi hardware.

Run engine/UI checks sequentially when sharing an assets directory: NeoPortable writes
`forge.profile.properties` there even when each process specifies a different dataDir.
The test runner must use disposable profiles and restore the ordinary package profile.

## Final broad threat-family pass (v5, 2026-11-05)

All proposed candidate families now have bounded general handling; complex variants
remain conservative. Final packaged checks: 183 passed. Two catalog runs of
26,921 permanent faces each completed without exceptions. 166 of the original 990
flagged names have newly observed payoff recognition; 143 have all original flagged
paths observed. Current confirmed-gap screen: 576 names; reclassified uncertain cases
are NOT counted as fixes. Optional trigger costs are now checked consistently.

Only the experimental AI JAR was updated; stable app and UI JARs are unchanged.
Full package succeeded with unrelated UI style errors bypassed; final AI build passed
with Checkstyle enabled. Full-match balance and Pi validation remain outstanding.
Details: docs/threat-audit-v5.md and outputs/AI-Meaningful-Coverage-v5/change-report.md.
Source ZIP refreshed. All changes local on ai-experiments; no GitHub push.

## Opponent assessment and meaningful attacks (v6, 2026-11-06)

The AI experiment now uses visible engine/resource/combat/danger components, with no
standalone hand-count or low-life threat bonus. Preferred opponents use deterministic
comparisons. Bounded attack plans account for likely blocks, material losses, rewards
(including nonattacking Winota), achievable eliminations and exposure from losing blockers.
Main burn/discard paths share the assessment; removal context stays deliberately small.
Some specialist legacy targeting and simulation paths remain outside this integration.

Final AI build passed with Checkstyle; upstream suite skipped. All 213 packaged checks
passed, including 30 opponent checks and a 150-permanent assessment/planning sweep.
Small Windows fixtures do not establish full-match strength or Pi performance.
Only experimental AI JAR updated; stable app and UI JARs unchanged. Comparison launcher
now disables both experimental evaluators. Source ZIP refreshed. No GitHub push.
Details: docs/opponent-assessment.md and outputs/AI-Opponent-Assessment-v6/report.md.
