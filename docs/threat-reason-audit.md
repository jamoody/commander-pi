# Reason-based creature threat audit

This audits the extra removal-production bonus, not whether Forge assigns a creature
any value. It does not equate every zero with a missing threat.

## Census

| Exclusive bucket | Creature faces |
|---|---:|
| Explained zero: baseline, spent entry, other-zone or entry-counter behavior | 3,958 |
| Zero on standard board, positive on resource-rich board | 248 |
| Zero on both boards, with an identifiable unsupported path | 6,430 |
| Zero on both boards, insufficient evidence to classify | 6,599 |
| Positive bonus, with an additional unsupported path | 402 |
| Positive bonus, no gap identified by this classifier | 1,558 |
| **Total** | **19,195** |

**The unsupported-path bucket is not a confirmed missed-threat count.** It includes
combat effects, drawbacks, defensive effects and conditional mechanics whose
strategic relevance still needs judgment. The unresolved bucket is not a failure count.
Of the 248 context-sensitive faces, 47 also have unsupported-path evidence;
their positive resource-rich score does not establish complete understanding.

Explained zeros are appropriate within the current future-production scope, assuming
ordinary on-battlefield state after entry resolves. They do not prove correct removal
timing, blink/recursion interactions, or that baseline combat weights are perfect.
Self-death payoffs are not automatically dismissed: destruction and exile differ.

## Verified examples and reusable next steps

| Family | Faces with structural evidence | Example validated by focused probe |
|---|---:|---|
| opponent-recipient-alias | 143 | Purphoros and Zulaport: Player.Opponent skipped, Opponent works |
| token-recipient-reference | 53 | Najeela: triggered-controller recipient skipped, own-recipient works |
| implicit-self-counter-recipient | 235 | Walking Ballista: omitted Defined skipped, explicit Self works |
| unsupported-trigger:Sacrificed | 89 | Korvold: sacrifice trigger never reaches its payoff |
| unsupported-root-effect:Charm | 105 | Hullbreaker Horror: modal root stops effect traversal |
| unsupported-replacement:Draw | 23 | Notion Thief: draw-replacement event unsupported |

Counts overlap. These are structural candidate populations, not promised numbers
of high-value cards fixed. In particular many self-counter effects are ordinary combat
growth. Recipient normalization is the clearest small shared fix; sacrifice events
and modal interpretation are larger additions. Never sum modal alternatives blindly.

Other focused results: Terror of the Peaks loses its event-derived power quantity;
a numeric damage amount is recognized. Muldrotha values graveyard lands but adding
a public graveyard creature does not raise its bonus. Collector Ouphe remains zero
despite opposing mana artifacts because the symmetric activation restriction is skipped.

Azure Mage, Hell's Caretaker and Mondrak demonstrate valid resource-dependent zeros.
Walking Ballista demonstrates both categories at once: counter payment works when
counters exist, but its counter-generation ability has a separate recipient gap.

## Method and limits

- Same packaged AI JAR as the prior implementation; no gameplay changes.
- Two full audits: 26,921 permanent faces each, zero scoring/inspection exceptions.
- Standard fixture exactly reproduces all scores in the previous v3 audit.
- Rich fixture adds four each of Plains/Island/Swamp/Mountain, friendly artifacts,
  creatures and a graveyard target, plus opposing creatures and a mana artifact.
- 18 focused assertions validate controls, resource changes and observed gaps.
  These tests intentionally assert current defects; they are audit evidence, not fixes.
- Runtime keyword provenance and exact effect/trait parameters distinguish generated
  combat machinery from explicit strategic abilities. Whole-face buckets require
  every exported trait to be accounted for before treating a zero as explained.
- Classification rules are in verification/reason_threat_audit.py. Per-trait and
  per-face evidence is preserved in CSVs; gap-family counts are overlapping.
- One earlier ordinary-card filter bug was corrected: script variants were ignored.
  The strict regular count is now 1,028 instead of 1,031. Three variant-bearing faces
  were restored for review; the broader explained-zero bucket is independent of that
  simple filter and includes spent ETBs and other accounted-for abilities.
- Common-catalog faces include variants, alternate faces and non-Commander-legal cards.
  These are not counts of unique Commander-legal creature names.
- Two boards do not cover all phases, tribal requirements, tokens, counters, activation
  history, timing, conditions, static recalculation, replacement interactions or combos.
  Board perturbation proves some context sensitivity, not why every remaining zero exists.
- No complete match simulation, win-rate experiment or Pi measurement was performed.

There is still no defensible percentage of strategically important creatures covered.
The useful output is the tested shared gaps above and an explicit unresolved remainder.
