# Expanded strategic threat audit

The same four-player public-board fixture was used before and after the change.
Scores are additional removal bonuses, not complete creature values. Positive scores
show exercised coverage; they do not prove correct weights or stronger play.

| Observation | Before | After |
|---|---:|---:|
| permanent faces | 26,920 | 26,921 |
| creature faces | 19,194 | 19,195 |
| positive permanent faces | 2,547 | 3,876 |
| positive creature faces | 873 | 1,946 |
| errors | 1 | 0 |

## Examples

| Card | Before | After |
|---|---:|---:|
| Winota, Joiner of Forces | 341 | 341 |
| Esper Sentinel | 189 | 189 |
| Yawgmoth, Thran Physician | 0 | 146 |
| Sheoldred, the Apocalypse | 0 | 112 |
| Edric, Spymaster of Trest | 0 | 225 |
| Tymna the Weaver | 0 | 157 |
| Urza, Lord High Artificer | 0 | 22 |
| Seedborn Muse | 0 | 252 |
| Grand Abolisher | 0 | 120 |
| Archivist of Oghma | 0 | 102 |
| Bloom Tender | 22 | 44 |
| Faeburrow Elder | 22 | 44 |
| Alena, Kessig Trapper | error | 88 |

The Alena aggregate-expression exception is fixed. Correct zeros remain expected
for ordinary bodies, already-realized effects and unavailable resources. Unknown
conditions, effect branches and unsupported mechanics still leave genuine misses.
The fixture has six Forests and is not representative of every deck or board.
Trait diagnostics are review hints: isolated effect values exclude cost penalties,
and family recognition does not establish support for every parameter combination.
This audit covers common-catalog permanent faces, not every card script or game state.
No full-match balance or Raspberry Pi performance validation has been performed.
