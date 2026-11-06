"""Summarize ThreatCoverageAudit TSVs. Counts are fixture observations, not correctness rates."""
import argparse, collections, csv, hashlib, json, re, zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--audit',type=Path,required=True);p.add_argument('--package',type=Path,required=True)
a=p.parse_args();root=a.audit
def rows(name):
    with (root/name).open(encoding='utf-8') as f:return list(csv.DictReader(f,delimiter='\t'))
cards=rows('cards.tsv');traits=rows('traits.tsv');errors=rows('errors.tsv')
catalog=(root/'catalog.txt').read_text(encoding='utf-8').splitlines()
assert len(catalog)==len(set(catalog)), 'Catalog entries must be unique'
assert len({(c['card'],c['face']) for c in cards})==len(cards)
gates=collections.Counter(t['gate'] for t in traits)
kinds=collections.Counter(t['kind'] for t in traits)
blockers=collections.defaultdict(set);gate_cards=collections.defaultdict(set)
for t in traits:
    key=(t['card'],t['face']);gate_cards[t['gate']].add(key)
    for b in t['effect_blockers'].split(';'):
        if b:blockers[b].add(key)
with (root/'gaps.csv').open('w',newline='',encoding='utf-8') as f:
    w=csv.writer(f);w.writerow(['category','measure','trait_objects','distinct_card_faces','examples'])
    for gate,n in gates.most_common():
        w.writerow([gate,'first gate',n,len(gate_cards[gate]),'; '.join(sorted({x[0] for x in gate_cards[gate]})[:6])])
    for b,faces in sorted(blockers.items(),key=lambda x:-len(x[1])):
        w.writerow([b,'secondary blocker (overlapping)','',len(faces),'; '.join(sorted({x[0] for x in faces})[:6])])
creatures=[c for c in cards if c['creature']=='true']
positive=[c for c in cards if int(c['score'])>0]
positive_creatures=[c for c in creatures if int(c['score'])>0]
script_names=[]
with zipfile.ZipFile(a.package/'res/cardsfolder/cardsfolder.zip') as z:
    for name in z.namelist():
        if name.endswith('.txt'):
            content=z.read(name).decode('utf-8-sig')
            match=re.search(r'^Name:(.*)$',content,re.M)
            if match:script_names.append(match.group(1).strip())
unloaded=sorted(set(script_names)-set(catalog))
(root/'script-names-not-in-common-catalog.txt').write_text('\n'.join(unloaded)+'\n',encoding='utf-8')
jar=a.package/'app/forge-ai-2.0.16-SNAPSHOT.jar'
summary=dict(unique_loaded_catalog_entries=len(catalog),successful_permanent_faces=len(cards),
             scoring_or_inspection_errors=errors,creature_faces=len(creatures),positive_permanent_faces=len(positive),
             positive_creature_faces=len(positive_creatures),parsed_trait_objects=len(traits),trait_kinds=dict(kinds),
             primary_gates=dict(gates),secondary_blocker_distinct_faces={k:len(v) for k,v in blockers.items()},
             card_scripts_with_name=len(script_names),script_primary_names_not_in_common_catalog=len(unloaded),
             forge_ai_jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest())
(root/'summary.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
examples=['Winota, Joiner of Forces','Esper Sentinel','Mole Man, Moloid Master','Consecrated Sphinx',
          'Smothering Tithe','Krenko, Mob Boss','Bloom Tender','Faeburrow Elder','Yawgmoth, Thran Physician',
          'Sheoldred, the Apocalypse','Edric, Spymaster of Trest','Tymna the Weaver','Urza, Lord High Artificer',
          'Seedborn Muse','Grand Abolisher','Archivist of Oghma','Blood Artist','Dockside Extortionist']
example_rows=[]
for name in examples:
    matches=[c for c in cards if c['card']==name and c['face']=='Original']
    if not matches:continue
    c=matches[0]
    why=sorted({t['gate'] for t in traits if t['card']==name and t['face']=='Original'})
    effect_gaps=sorted({b for t in traits if t['card']==name and t['face']=='Original' for b in t['effect_blockers'].split(';') if b})
    example_rows.append(f"| {name} | {c['score']} | {', '.join(why)} |")
with (root/'examples.csv').open('w',newline='',encoding='utf-8') as f:
    w=csv.writer(f);w.writerow(['card','face','score','explanation'])
    for c in cards:
        if c['card'] in examples:w.writerow([c['card'],c['face'],c['score'],c['explanation']])
gap_table='\n'.join(f"| {gate} | {gates[gate]:,} | {len(gate_cards[gate]):,} |" for gate in [
    'colored-or-variable-mana-cost','non-mana-tap-cost','activation-condition-or-limit','trigger-condition',
    'trigger-mode:DamageDone','trigger-pattern:ChangesZone:Graveyard','static-unhandled','replacement-unhandled'])
report=f'''# Strategic threat coverage audit — 2026-11-04

## Finding

The evaluator is an early prototype with narrow structural coverage. General script data
can support many unique cards, but the current implementation skips common costs, trigger
patterns and effects. Passing a robustness sample did not establish broad strategic understanding.
This audit found a production scoring exception as well as omissions and coarse valuations.
No production evaluator or game binary was changed during this audit.

## Scope and method

Enumerated **{len(catalog):,} unique entries** from the running build's common-card catalog,
constructed real Forge Card objects and inspected their non-facedown permanent states.
**{len(cards):,} permanent faces completed**; {len(errors)} face failed during scoring.
The run inspected **{len(traits):,} parsed trait objects**, including keyword-generated traits:
{', '.join(f'{n:,} {k}' for k,n in kinds.items())}.

The packaged script ZIP contains {len(script_names):,} scripts with a primary Name line.
{len(unloaded):,} of those primary names do not appear in the loaded common catalog. They are
listed separately; this report does not claim runtime coverage of definitions the build does
not load there (or a one-to-one mapping between script names and catalog/state names).
The unmatched names include Plane/Scheme/Vanguard and other special card types, split-card
half names, Room half names, Contraptions and Attractions; they are not 648 ordinary
Commander creatures silently omitted from evaluation.
No Commander-legality filter was applied; results include special/alternate digital cards.

The auditor invokes the actual private opportunity/effect/condition functions through
reflection, plus public `assess`. Its diagnostic gate labels describe the current source;
they are not a second scoring implementation. Rebuild/update the auditor when the evaluator
changes. Scoring and diagnostics run in one representative fixture: four players, opponent
main phase one, six Forests, Bears/Elf/Human/Thopter bodies, five hidden cards, 80 library
cards and a Forest/Bears in the graveyard. Each audited permanent is added in turn and removed.
Static continuous effects are not globally recomputed as a full game would do, and special
synergies or tribal support are not assembled individually. This is a controlled coverage
probe, not an estimate of win rate or optimal gameplay.

## What the numbers do and do not mean

| Observation | Faces |
|---|---:|
| Permanent faces receiving a positive new bonus | {len(positive):,} / {len(cards):,} ({len(positive)/len(cards):.1%}) |
| Creature faces receiving a positive new bonus | {len(positive_creatures):,} / {len(creatures):,} ({len(positive_creatures)/len(creatures):.1%}) |

A zero bonus is **not** proof of a missed threat: bodies and many combat keywords already
receive baseline value; a spent self-ETB payoff should not receive a future-production bonus.
A positive bonus is **not** proof of accurate understanding: generic token/mana values can
be badly incomplete. The face denominator counts alternate states separately. These are
fixture observations, not universal coverage percentages.

## Safety defect: Alena, Kessig Trapper

The actual `assess` call throws IllegalArgumentException on:
`Count$Valid Creature.YouCtrl+ThisTurnEntered$GreatestCardPower`.
`amount` treats the entire suffix after `Count$Valid` as a card-validity filter, passing an
aggregate expression into `Card.isValid`. This can escape into removal evaluation.
Unsupported numeric expressions must safely return an unknown/fallback result; later we
can implement public aggregations through a bounded interpreter. An all-catalog regression
is needed alongside the small curated tests. The error TSV and run log record the failure.

## Biggest structural gates

These are **first-gate observations**, not counts of cards that should automatically gain
score. A face can have multiple traits. Secondary blockers in gaps.csv overlap and must not
be added to primary-gate counts.

| First gate | Trait objects | Distinct faces |
|---|---:|---:|
{gap_table}

The blanket targeting guard additionally appears on
{len(blockers['targeted-effect']):,} distinct faces' effect graphs, even where a different
earlier gate already prevents scoring. The graph walk includes nested alternatives and
records unsupported effect types independently of the first gate.

## Representative results

Scores below are only the new strategic bonus, not total removal priority.

| Card | Bonus | Diagnostic gates |
|---|---:|---|
{chr(10).join(example_rows)}

The zero for Dockside's self-ETB payoff is intentional in this model; ignoring Yawgmoth's
ongoing activated engine or Edric's repeatable combat-damage payoff is a coverage gap.
Sheoldred exposes both a missing life-loss effect and overly literal event-filter matching
(`Card.OppCtrl` versus the recognized `Card.OppOwn`). These are reusable framework gaps.
Bloom Tender/Faeburrow Elder both receive 22: their `Produced$ Special EachColorAmong_Valid`
mana output is ignored and the missing Amount defaults to one. Thus even recognized Mana
is only partially understood. Krenko alone in this fixture has one Goblin; 60 does not
establish how well arbitrary token populations/qualities are valued.

## Recommended implementation order

1. Make unknown expressions fail safely, reproduce Alena, and add this full-catalog audit
   to regression verification. Do this before relying on the current experimental build.
2. Normalize trigger filters and evaluate common public conditions/limits, rather than
   reject every conditional or once-per-turn ability. Add common combat-damage, death and
   postcombat events with explicit opportunity estimates. Not all death triggers are a
   positive reason to destroy their source: distinguish self-death consequences.
3. Model colored mana, tap, sacrifice, discard, life and counter costs from Forge Cost
   objects and available resources. This expands whole groups of unique activated engines.
4. Handle target/beneficiary selection, graveyard recursion, damage/life loss, counters and
   temporary permissions. Track what removal actually prevents, including adverse death
   effects. Do not simply remove the targeting guard or assign one weight per API.
5. Read static/replacement effects for cost reduction, protection, untapping and resource
   multipliers. Separate already-valued body/keyword properties from marginal support.
6. Interpret bounded branching/modes/linked abilities and public quantity expressions;
   improve token/mana output estimates. Preserve unknown fallbacks for unsupported paths.

Measure progress with curated behavioral expectations plus audited reachability and
runtime failures. A larger positive-score percentage alone is not the objective.

## Reproduction and files

Compile verification/StrategicThreatCheck.java and verification/ThreatCoverageAudit.java
against the experimental app JARs. Run ThreatCoverageAudit with the report directory as
its argument, @app/launch.args, a disposable neo.dataDir and the compiled checks on the
classpath. Run only one process per assets directory: NeoPortable writes a shared profile
pointer. Restore the ordinary package profile after testing.

Summarize with verification/summarize_threat_audit.py --audit REPORT_DIR --package PACKAGE_DIR.
Report identity: Forge AI JAR SHA-256 `{summary['forge_ai_jar_sha256']}`.

- cards.tsv: every successfully evaluated permanent face, bonus and explanation.
- traits.tsv: all observed trigger/activated/static/replacement objects and their gates.
- gaps.csv: aggregate primary gates and overlapping secondary effect blockers.
- examples.csv: selected card-level observations.
- errors.tsv: failed scoring/inspection cases.
- catalog.txt: all loaded unique common-card entries visited.
- script-names-not-in-common-catalog.txt: packaged script names outside that enumeration.
- summary.json: machine-readable counts and binary fingerprint.
'''
(root/'REPORT.md').write_text(report,encoding='utf-8')
print(json.dumps({k:summary[k] for k in ['unique_loaded_catalog_entries','successful_permanent_faces','creature_faces','positive_creature_faces','card_scripts_with_name','script_primary_names_not_in_common_catalog']},indent=2))
