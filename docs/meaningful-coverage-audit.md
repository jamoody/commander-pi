# Meaningful strategic ability coverage — 2026-11-05

## Result

The conservative screening result is **814 distinct catalog card names**
whose core strategic role is not recognized by either removal evaluator under the rubric below.
This is a code-supported screening estimate, not hand-certified ground truth for every card.
Specific unsupported paths are verified; whether every conditional payoff is important in a
particular Commander game remains a judgment. Unresolved cases are not counted as misses.

| Distinct card names | Count |
|---|---:|
| Wholly unrecognized core strategic role | 814 |
| Some recognition, with a core ability gap | 95 |
| A core gap exists, extent of other coverage uncertain | 81 |
| **Names with at least one screened core gap** | **990** |

Names are deduplicated across creature faces. A card is not called wholly missed if another
audited creature face has meaningful recognition or unresolved meaningful coverage.
Catalog names still include digital/rebalanced/special variants and cards outside ordinary
Commander legality. This is not a current official Commander-legal-card census.

## What meaningful means here

Core: repeatable or still-future card/mana/token production, tutoring/recursion, resource
access, copying, board-wide support/suppression, repeatable interaction, extra turns/combat,
and rules-based protection or denial. Conditional engines count when the script exposes a
plausible payoff; we do not demand that the standard test board currently enables it.

Borderline, not automatically counted: small standalone life gain; scry/surveil and other
selection without a clear resource payoff; ordinary self-pump/growth and combat tricks;
discard-to-draw selection needing net-value judgment; pure generic activations costing more
than seven mana; life payments of eight or more; one-point phase-triggered damage; unknown
recipient/quantity semantics. These are adjustable judgment rules, not declarations that
such cards are always weak. Soul Warden, for example, can be important with the right engine.

Excluded from present removal-production gaps: already-fired self ETBs, self-cast triggers,
own exploit sequences, already-completed self face-up/specialize transitions, self cycling
payoffs and abilities confined to another zone. Reusing these through blink/recursion may
matter, but the external enabler is a separate source of future production. Owner-harming
game-loss restrictions are not treated as a beneficial threat engine.

The rubric is implemented in significance() and scope() in meaningful_coverage_audit.py.
Every trait has a saved role/tier, coverage decision and explanation in traits.csv.

## What recognition means

- Old: actual CreatureEvaluator labels are recorded. Explicit keyword handling, such as
  annihilator, counts. Mana-role and landfall bonuses count as coarse recognition, not
  proof that quantities or payoffs are understood.
- Generic body/CMC points and the default +10 activated-ability bonus do not establish
  semantic recognition. Runtime controls show the same old +10 for drawing one or five
  cards, whereas the new evaluator distinguishes them.
- New: actual payoff and opportunity/resource evidence from standard and richer boards
  counts as observed recognition. API-family membership alone does not.
- A miss needs a specific code path that skips a core ability: unsupported trigger/effect,
  recipient/zone/filter restriction, or absent source attribution. Zero alone never proves it.
- Wholly missed additionally requires zero aggregate new bonus on both boards, no observed
  core recognition on any creature face, no unresolved core trait, and no custom legacy
  evaluation modifier that could explain recognition.
- Some positive paths may still omit linked payoffs. Observed recognition is not completeness
  and is not evidence that weights or final target choices are correct.

This concerns the shared removal-target valuation path. Forge can know how to activate a
card in other AI modules without attributing that ability to its value as a removal target.
Combat simulation, action selection, card-specific tactical logic and final decision quality
are not exhaustively audited here. Generic opponent-board weighting does not identify which
creature supplies an engine.

## Face-level census

| Bucket | Faces |
|---|---:|
| no-core-role-established | 12,972 |
| core-coverage-unresolved | 3,289 |
| core-strategic-miss | 830 |
| core-recognition-observed | 1,938 |
| partial-core-gap | 90 |
| core-gap-with-other-uncertainty | 76 |

The no-core-role-established bucket combines baseline bodies, spent effects, borderline
abilities and unknown semantics; it must not be read as a count of fully understood cards.
Core-coverage-unresolved remains separate and is not included in the strict miss estimate.

## Verified examples

- Notion Thief: unsupported draw replacement; ordinary body value is not draw-theft recognition.
- Dauthi Voidwalker: opponent graveyard denial is not attributed to the creature.
- Opposition Agent: search control field is not valued.
- The Gitrog Monster: graveyard batch-change draw and extra land-play permission are skipped.
- Jin-Gitaxias, Progress Tyrant: spell copying and the defined triggered counter target are skipped.
- Vilis: LifeLost trigger is not recognized.
- Platinum Angel and Platinum Emperion: their loss/life-total protections lack source valuation.
- Ulamog's Crusher: correctly retained as old-system recognition through annihilator.
- Llanowar Elves and Lotus Cobra: old-system coarse mana/landfall recognition is preserved.
- Mulldrifter, Abundant Maw, Drownyard Lurker: spent ETB/cast/cycling effects do not become misses.
- Toxrill: proven suppression gap plus unresolved other coverage, not a strict whole-role miss.
- Nadu: granted-engine coverage is context-dependent, not promoted to a confirmed miss.

## Validation and limits

- Both current v4 evaluators, not the earlier v3 unsupported-path census.
- Two engine-backed catalog runs: 26,921 permanent faces each, zero exceptions.
- 19,195 creature faces, representing 18,868 distinct catalog names.
- 13 runtime controls and 22 named classification controls passed, plus census invariants.
- Spot-checking provisional misses corrected opponent-benefiting draw inference, spent
  cast/cycling/exploit/specialize handling and protection-versus-drawback direction.
- The richer board cannot cover all phases, histories, tribal requirements, counters,
  tokens, actual static recalculation and combo states. Missing observations stay uncertain.
- Replacement and static per-trait recognition is intentionally conservative because a
  positive aggregate score does not prove that a particular static ability was understood.
- No full-match or win-rate experiment, weight tuning, production changes or Pi test.
- local-commander-filter.tsv is only this installation's stored format predicate output.
  It is retained as a diagnostic, not used to claim current official card legality.

## Artifacts

- card-names.csv: deduplicated results and cross-face buckets.
- strict-strategic-misses.csv: every flagged face with old/new scores and reasons.
- partial-core-gaps.csv and faces.csv: full face-level accounting.
- traits.csv: per-ability judgment and exact structured script fields.
- gap-families.csv: reusable missing paths; counts overlap.
- standard/ and rich/: raw native-engine observations.
- behavioral-controls.txt: runtime verification results.

Packaged AI SHA-256: `613b4d1266a6b90f6be5e0a8880d5dd697cc551e9f20ac76090fecc67e779c1d`
