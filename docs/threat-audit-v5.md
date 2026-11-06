# Final broad threat-family pass (v5, 2026-11-05)

Implemented on the local `ai-experiments` branch and deployed to Commander-AI-Windows.
All proposed candidate families now have general handling, with explicit limits below.
This is support for common script patterns, not a promise that every card in each family
now receives a bonus. No card-name rules, hidden-card identity reads, game-rule changes,
or GitHub pushes. Stable Commander-Windows is unchanged.

## Scope

| Family | Handling |
|---|---|
| Phase/player and spell-caster variants | Additional phases, each-player/opponent filters and bounded hand-count spell forecasts. Unknown player predicates remain unsupported. |
| Extra land plays | `AdjustLandPlays`, bounded by public hand-count priors and visible graveyard land access. |
| Magecraft | `SpellCastOrCopy`, using the existing cast opportunity prior without assuming infinite copies. |
| Group damage | `DamageAll`, signed opposing versus friendly damage and bounded public creature losses. |
| Counter payoffs | `CounterAdded`/`CounterAddedOnce`, visible counter producers and next Saga chapter. Unknown thresholds are not assumed reached. |
| Batch zone changes | Other-creature deaths plus selected graveyard exits with visible enablers. Library-to-graveyard and arbitrary leave-battlefield events remain unsupported. |
| Casting/activation restrictions | Symmetric restrictions consider opposing resources minus own cost. Non-mana filters are respected. Existing simple one-sided priors retained. |
| Play permissions | Owned/controller graveyard aliases, public exile cards, free casting and top-library composition priors. Unknown alternative costs and remembered/imprinted bindings remain unsupported. |
| Spell/ability copying | Triggered spell-copy production prior and actual legal public stack targets for activated copying. Selected per-target copies bounded; no combo simulation. AbilityCast events can use public activations. |
| Sacrifice | Opposing edicts use eligible public fodder, opponent choice and own sacrifice expenses. Complicated refusal/remembered branches remain unsupported. |
| Immediate triggers | Follow `Execute` without registering or resolving a game trigger; apply the root's optional payment budget. |
| Peek/reveal | Recognize direct revealed/remembered library-to-hand or battlefield payoff, including the one-card conditional pattern; no hidden identity lookup. More complex branches remain unsupported. |

The payment correction also stops previously optimistic bonuses for optional triggers
whose costs are unavailable or unsupported. A trigger can retain a payment even when
Forge no longer reports its root as an activated ability. This changes valuation only.

## What actually improved

Of the original **990** flagged names:

- **166** now have at least one formerly missed payoff exercised.
- **143** have all their original flagged paths observed, without a remaining original uncertain path.
- **574** still have a confirmed original gap (some also gained recognition).
- **273** have no remaining proven original gap, but still have unexercised/uncertain paths. These are **not claimed fixed**.

The final three bullets partition the original cohort. The first overlaps them.
This does not establish complete understanding of every other ability on those cards.

The current whole-catalog screen flags **576** names with a
confirmed meaningful gap, including **454** classified as
wholly unrecognized strategically. The reduction from 990 is not all successful fixes:
some formerly unsupported roots now require context-specific verification instead.
Static recognition is now measured individually for the added families rather than
inferred from a card's aggregate score. Other unknown static paths stay conservative.

| Creature-face observation | Standard fixture | Rich fixture |
|---|---:|---:|
| Previously positive | 2298 | 2686 |
| Now positive | 2402 | 2845 |
| Newly positive | 171 | 209 |
| Previously positive, now zero | 67 | 50 |

Zeros can result from stricter optional-payment checks or unsupported restriction
predicates. They are not automatically regressions or correctly understood cards.
The standard fixture uses six Forests; it cannot pay every colored optional cost.
These counts measure exercised recognition, not decision accuracy or stronger play.

## Verification

- Full Maven package succeeded with upstream tests skipped and UI Checkstyle skipped:
  the unchanged UI module has 22 pre-existing unused/redundant import errors.
- Final Forge AI reactor build succeeded **with Checkstyle enabled**.
- **183 packaged runtime checks passed**, including 37 new family checks,
  original real removal selection checks, hidden-library invariance, friendly-fire and
  payment controls, and a seeded 1,000-creature exception/bounds sweep.
- Two final packaged catalog runs: 26,921 permanent faces each, zero exceptions.
- Original and new audit census assertions passed. The older v4 audit is preserved.
- Overrides verified for installation, idempotence and refusal of unexpected source.
- No full-match balance or Pi performance test. The bounded model still uses approximate
  rates and production values; broader coverage does not validate those weights.

Only the AI JAR was replaced in the experimental package. No UI JAR was replaced.
Packaged AI SHA-256: `eee64be00d36f55c4bcdb9c280522c044734f37c09692d08c390ab01681b1fb7`.
Stable UI SHA-256: `e43823242ff840e1e06487f87004e63fda92e16517ba2a317e2efe4489665aac`.

## Artifacts and launch

- Launch `outputs/Commander-AI-Windows/Start Commander.cmd`.
- Baseline comparison launcher remains beside it.
- `outputs/AI-Meaningful-Coverage-v5/report.md`: full current census and rubric.
- `original-cohort.csv`: all 990 original names and their measured outcomes.
- `new-recognition-families.csv`: observed gains grouped by old missing path.
- `comparison.json`, `summary.json`, raw standard/rich data and verification logs.
- `outputs/Commander-AI-source.zip`: refreshed source, tests and documentation.

Run verification JVMs sequentially when sharing assets; the ordinary experimental
profile pointer is restored after verification. Future work should focus on decision
quality and playtesting rather than interpreting this audit as a target of zero misses.
