# Coverage audit (2026-11-04)

Auditor: verification/ThreatCoverageAudit.java, with verification/StrategicThreatCheck.java
as fixture support and verification/summarize_threat_audit.py as report generation.
No production evaluator changes were made during this audit.

The current build's common-card catalog contains 33,609 unique entries. Constructing their
non-facedown permanent states produced 26,920 successful face evaluations and one scoring
exception. 48,562 parsed trigger/activated/static/replacement traits were inspected. In the
representative supported-resource fixture, 2,547 permanent faces and 873 of 19,194 creature
faces received a positive strategic bonus. These are not accuracy or universal coverage
percentages: some zeros are appropriate, some reflect the fixture, and positive values can
still be incomplete. Original combat/body evaluation remains separate.

Critical finding: Alena, Kessig Trapper throws in StrategicThreatEvaluator.amount because
Count$Valid Creature.YouCtrl+ThisTurnEntered$GreatestCardPower is mistaken for a plain
validity predicate. Unknown numeric expression forms must fall back safely. This needs
fixing before further reliance on the experimental build.

Major reusable coverage gaps: colored/variable activation costs (2,622 trait objects),
non-mana/tap costs (4,270), conditions/activation limits, targeted effects, combat damage,
death events, static abilities, and replacement effects. These counts include keyword-
generated machinery and are not all abilities that should receive positive removal value.

Recognized examples include Winota, Esper Sentinel, Mole Man, Consecrated Sphinx, Smothering
Tithe and Krenko. Current misses include Yawgmoth, Sheoldred, Edric, Tymna, Urza, Seedborn
Muse, Grand Abolisher and Archivist of Oghma. Dockside's spent self-ETB payoff correctly adds
no future-production bonus. Bloom Tender/Faeburrow Elder receive a generic one-mana score
instead of evaluating their public color-dependent output: recognition is not correctness.

Full report and per-face/trait TSVs: outputs/AI-Threat-Audit in the enclosing workspace.
Packaged report for sharing: outputs/AI-Threat-Audit.zip. The report records the exact JAR
fingerprint, fixture, limitations, all errors, raw-script inventory differences and repair
order. Runtime enumeration excludes special card databases outside common cards; the
unmatched script-name inventory includes special formats and combined-card half names.

Next work: safe expression fallback plus full-catalog regression; shared public-condition
and cost handling; event/beneficiary normalization; additional effect families; then bounded
branch/linked-effect interpretation. Require behavioral tests, not merely higher scores.
