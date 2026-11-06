# Creature audit with ordinary bodies removed

Source: AI-Threat-Audit-v3. No game code or card data changed.

| Bucket | Creature faces |
|---|---:|
| Original total | 19,195 |
| Ordinary bodies/conventional keywords excluded | 1,028 |
| Remaining review pool | 18,167 |
| Review pool with a positive added bonus | 1,960 |
| Review pool with zero added bonus | 16,207 |

This is a conservative exclusion, not a claim that every remaining creature is an engine
or a significant missed threat. Explicit abilities, script variables, unknown keywords,
unmapped scripts, and strategic alternate faces stay in the pool. Mana creatures stay:
baseline's generic mana/ability points are not proof of adequate resource valuation.
Conventional keyword-only creatures are excluded regardless of power or mana value.
This is not a declaration that the baseline makes perfect combat decisions.

Zero means no additional bonus in one fixed fixture, not no baseline evaluation.
The review pool still includes spent ETBs, conditional abilities without resources,
combat-only scripts, and genuine strategic misses. Positive scores can be partial.
remaining-patterns.csv groups structural review leads with overlapping counts; these
are not counts of confirmed high-value misses. Full per-face decisions are in the CSVs.
