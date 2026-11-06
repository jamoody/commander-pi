"""Evidence-based triage, not a win-rate estimate or universal strategic oracle."""
import argparse,collections,csv,json,hashlib,re
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True)
p.add_argument('--regular',type=Path,required=True);p.add_argument('--jar',type=Path,required=True)
a=p.parse_args();root=a.root
def read(path,delimiter='\t'):
    with path.open(encoding='utf-8') as f:return list(csv.DictReader(f,delimiter=delimiter))
def key(r):return r['card'],r['face']
standard=read(root/'standard/cards.tsv');rich=read(root/'rich/cards.tsv')
assert not read(root/'standard/errors.tsv') and not read(root/'rich/errors.tsv')
assert {key(c) for c in standard}=={key(c) for c in rich}
rich={key(c):c for c in rich};traits=collections.defaultdict(list)
for t in read(root/'standard/traits.tsv'):traits[key(t)].append(t)
regular={key(c) for c in read(a.regular,',')}
BODY={'FLYING','FIRST_STRIKE','DOUBLE_STRIKE','DEATHTOUCH','LIFELINK','TRAMPLE','VIGILANCE',
      'REACH','DEFENDER','HASTE','FLASH','FEAR','INTIMIDATE','MENACE','SKULK','HORSEMANSHIP',
      'INDESTRUCTIBLE','HEXPROOF','SHROUD','WARD','PROTECTION','INFECT','WITHER','TOXIC',
      'AFFLICT','RAMPAGE','ABSORB','BUSHIDO','FLANKING'}
SUPPORTED={'Draw','Token','Mana','ManaReflected','CopyPermanent','Dig','ChangeZone','Pump',
           'Cleanup','GainLife','LoseLife','DealDamage','Destroy','PutCounter','Untap','Shuffle'}
SUPPORTED_MODES={'Attacks','SpellCast','Drawn','SearchedLibrary','DamageDone','DamageDoneOnce','ChangesZone','Phase','TapsForMana'}
def classify(t):
    q=json.loads(t['parameters_json']);e=json.loads(t['effect_parameters_json'])
    kind=t['kind'];mode=t['mode'];api=t['root_api']
    if t['keyword'] in BODY:return 'explained','baseline-combat-keyword:'+t['keyword']
    if kind=='trigger' and mode=='ChangesZone' and q.get('Destination')=='Battlefield' and re.fullmatch(r'(Card\.|Creature\.)?Self(?:\+[^,]+)?',q.get('ValidCard','')):
        return 'explained','spent-self-entry-trigger'
    if kind=='activated' and q.get('ActivationZone','Battlefield') not in {'Battlefield','Any'}:
        return 'explained','ability-in-other-zone'
    if kind=='trigger' and 'Battlefield' not in q.get('TriggerZones','Battlefield').split(','):
        return 'explained','trigger-in-other-zone'
    if kind=='replacement' and mode=='Moved' and q.get('Destination')=='Battlefield' and q.get('ValidCard') in {'Card.Self','Creature.Self','Self'} and api=='PutCounter' and e.get('Defined')=='Self' and e.get('ETB')=='True':
        return 'explained','entry-counter-replacement-already-applied'
    if kind=='static' and mode=='Continuous' and q.get('Affected',q.get('AffectedDefined')) in {'Card.Self','Creature.Self','Self'}:
        if set(q)<= {'Mode','Affected','AffectedDefined','CharacteristicDefining','SetPower','SetToughness','AddPower','AddToughness','Description','Secondary'}:
            return 'explained','self-stats-in-baseline' # current characteristic value, not all future scaling
    # Evidence of skipped code paths; relevance/importance still needs contextual review.
    if kind=='trigger' and mode not in SUPPORTED_MODES:
        return 'gap','unsupported-trigger:'+mode
    if kind=='replacement' and mode not in {'CreateToken','AddCounter'}:
        return 'gap','unsupported-replacement:'+mode
    if kind=='static' and mode in {'Panharmonicon','DisableTriggers','ReduceCost','CantBeCast','CantBeActivated','UntapOtherPlayer','Continuous'}:
        if mode=='Continuous' and any(k in q for k in ['ControlOpponentsSearchingLibrary','MayLookAt','MayPlay','MayPlayWithoutManaCost']):
            if not (q.get('MayPlay')=='True' and q.get('AffectedZone')=='Graveyard' and q.get('Affected')=='Land.YouOwn'):
                return 'gap','unsupported-static-permission-or-control'
        if mode=='CantBeActivated' and q.get('Activator')=='Player':return 'gap','symmetric-activation-restriction'
    if api and api not in SUPPORTED:return 'gap','unsupported-root-effect:'+api
    if api in {'DealDamage','LoseLife'} and e.get('Defined')=='Player.Opponent':
        return 'gap','opponent-recipient-alias'
    if api=='Token' and e.get('TokenOwner','You')!='You':return 'gap','token-recipient-reference'
    if api=='PutCounter' and e.get('CounterType')=='P1P1' and 'ValidTgts' not in e and 'Defined' not in e:
        return 'gap','implicit-self-counter-recipient'
    if api=='DealDamage' and e.get('NumDmg') not in {None,'0','1','2','3','4','5','6'} and float(t['isolated_effect_value'])==0:
        return 'unresolved','event-dependent-quantity-or-target'
    if t['gate']=='contributes':return 'recognized','some-effect-value-recognized'
    return 'unresolved',t['gate']

rows=[];trait_rows=[];patterns=collections.defaultdict(set);buckets=collections.Counter()
for c in standard:
    if c['creature']!='true':continue
    k=key(c);ts=traits[k];findings=[classify(t) for t in ts]
    gaps=sorted({reason for state,reason in findings if state=='gap'})
    for t,(state,reason) in zip(ts,findings):
        trait_rows.append(dict(card=c['card'],face=c['face'],kind=t['kind'],mode=t['mode'],classification=state,reason=reason,gate=t['gate'],keyword=t['keyword'],root_api=t['root_api'],parameters=t['parameters_json']))
    base=int(c['score']);alt=int(rich[k]['score'])
    if base>0:bucket='positive-with-gap-evidence' if gaps else 'positive-no-confirmed-completeness'
    elif k in regular:bucket='explained-zero'
    elif ts and all(state=='explained' for state,reason in findings):bucket='explained-zero'
    elif alt>0:bucket='context-sensitive-zero'
    elif gaps:bucket='zero-with-unsupported-path'
    else:bucket='unresolved-zero'
    buckets[bucket]+=1
    for gap in gaps:patterns[gap].add(k)
    rows.append(dict(card=c['card'],face=c['face'],bucket=bucket,standard_score=base,rich_score=alt,
                     gap_families='; '.join(gaps),reasons='; '.join(sorted({r for s,r in findings})) or ('baseline-body-only' if k in regular else 'no-exported-traits; needs-script-review')))
def write(name,data):
    with (root/name).open('w',newline='',encoding='utf-8') as f:
        w=csv.DictWriter(f,fieldnames=list(data[0]));w.writeheader();w.writerows(data)
write('creature-classifications.csv',rows);write('trait-classifications.csv',trait_rows)
write('gap-families.csv',[dict(family=family,faces_with_evidence=len(found),zero_in_both=sum(r['standard_score']==0 and r['rich_score']==0 and key(r) in found for r in rows),examples='; '.join(n for n,face in sorted(found)[:10])) for family,found in sorted(patterns.items(),key=lambda x:-len(x[1]))])
summary=dict(creature_faces=len(rows),standard_positive=sum(r['standard_score']>0 for r in rows),buckets=dict(buckets),
             errors=0,forge_ai_sha256=hashlib.sha256(a.jar.read_bytes()).hexdigest())
(root/'summary.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
assert sum(buckets.values())==19195
controls={r['card']:r for r in rows if r['face']=='Original'}
for n in ['Grizzly Bears','Colossal Dreadmaw','Mulldrifter','Ravenous Chupacabra']:
    assert controls[n]['bucket']=='explained-zero',(n,controls[n])
for n in ['Notion Thief','Hullbreaker Horror','Korvold, Fae-Cursed King','Najeela, the Blade-Blossom','Purphoros, God of the Forge']:
    assert controls[n]['bucket']=='zero-with-unsupported-path',(n,controls[n])
assert controls['Zulaport Cutthroat']['bucket']=='positive-with-gap-evidence'
assert not any(r['bucket']=='explained-zero' and r['gap_families'] for r in rows)
context_mixed=sum(r['bucket']=='context-sensitive-zero' and bool(r['gap_families']) for r in rows)
lines=['# Reason-based creature threat audit', '',
       'This audits the extra removal-production bonus, not whether Forge assigns a creature',
       'any value. It does not equate every zero with a missing threat.', '',
       '## Census', '', '| Exclusive bucket | Creature faces |', '|---|---:|']
labels={'explained-zero':'Explained zero: baseline, spent entry, other-zone or entry-counter behavior',
        'context-sensitive-zero':'Zero on standard board, positive on resource-rich board',
        'zero-with-unsupported-path':'Zero on both boards, with an identifiable unsupported path',
        'unresolved-zero':'Zero on both boards, insufficient evidence to classify',
        'positive-with-gap-evidence':'Positive bonus, with an additional unsupported path',
        'positive-no-confirmed-completeness':'Positive bonus, no gap identified by this classifier'}
for bucket,label in labels.items():lines.append(f'| {label} | {buckets[bucket]:,} |')
lines += [f'| **Total** | **{len(rows):,}** |','',
          '**The unsupported-path bucket is not a confirmed missed-threat count.** It includes',
          'combat effects, drawbacks, defensive effects and conditional mechanics whose',
          'strategic relevance still needs judgment. The unresolved bucket is not a failure count.',
          f'Of the {buckets["context-sensitive-zero"]} context-sensitive faces, {context_mixed} also have unsupported-path evidence;',
          'their positive resource-rich score does not establish complete understanding.', '',
          'Explained zeros are appropriate within the current future-production scope, assuming',
          'ordinary on-battlefield state after entry resolves. They do not prove correct removal',
          'timing, blink/recursion interactions, or that baseline combat weights are perfect.',
          'Self-death payoffs are not automatically dismissed: destruction and exile differ.', '',
          '## Verified examples and reusable next steps', '',
          '| Family | Faces with structural evidence | Example validated by focused probe |',
          '|---|---:|---|']
examples=[('opponent-recipient-alias','Purphoros and Zulaport: Player.Opponent skipped, Opponent works'),
          ('token-recipient-reference','Najeela: triggered-controller recipient skipped, own-recipient works'),
          ('implicit-self-counter-recipient','Walking Ballista: omitted Defined skipped, explicit Self works'),
          ('unsupported-trigger:Sacrificed','Korvold: sacrifice trigger never reaches its payoff'),
          ('unsupported-root-effect:Charm','Hullbreaker Horror: modal root stops effect traversal'),
          ('unsupported-replacement:Draw','Notion Thief: draw-replacement event unsupported')]
for family,example in examples:lines.append(f'| {family} | {len(patterns[family]):,} | {example} |')
lines += ['', 'Counts overlap. These are structural candidate populations, not promised numbers',
          'of high-value cards fixed. In particular many self-counter effects are ordinary combat',
          'growth. Recipient normalization is the clearest small shared fix; sacrifice events',
          'and modal interpretation are larger additions. Never sum modal alternatives blindly.',
          '', 'Other focused results: Terror of the Peaks loses its event-derived power quantity;',
          'a numeric damage amount is recognized. Muldrotha values graveyard lands but adding',
          'a public graveyard creature does not raise its bonus. Collector Ouphe remains zero',
          'despite opposing mana artifacts because the symmetric activation restriction is skipped.',
          '', 'Azure Mage, Hell\'s Caretaker and Mondrak demonstrate valid resource-dependent zeros.',
          'Walking Ballista demonstrates both categories at once: counter payment works when',
          'counters exist, but its counter-generation ability has a separate recipient gap.',
          '', '## Method and limits', '',
          '- Same packaged AI JAR as the prior implementation; no gameplay changes.',
          '- Two full audits: 26,921 permanent faces each, zero scoring/inspection exceptions.',
          '- Standard fixture exactly reproduces all scores in the previous v3 audit.',
          '- Rich fixture adds four each of Plains/Island/Swamp/Mountain, friendly artifacts,',
          '  creatures and a graveyard target, plus opposing creatures and a mana artifact.',
          '- 18 focused assertions validate controls, resource changes and observed gaps.',
          '  These tests intentionally assert current defects; they are audit evidence, not fixes.',
          '- Runtime keyword provenance and exact effect/trait parameters distinguish generated',
          '  combat machinery from explicit strategic abilities. Whole-face buckets require',
          '  every exported trait to be accounted for before treating a zero as explained.',
          '- Classification rules are in verification/reason_threat_audit.py. Per-trait and',
          '  per-face evidence is preserved in CSVs; gap-family counts are overlapping.',
          '- One earlier ordinary-card filter bug was corrected: script variants were ignored.',
          '  The strict regular count is now 1,028 instead of 1,031. Three variant-bearing faces',
          '  were restored for review; the broader explained-zero bucket is independent of that',
          '  simple filter and includes spent ETBs and other accounted-for abilities.',
          '- Common-catalog faces include variants, alternate faces and non-Commander-legal cards.',
          '  These are not counts of unique Commander-legal creature names.',
          '- Two boards do not cover all phases, tribal requirements, tokens, counters, activation',
          '  history, timing, conditions, static recalculation, replacement interactions or combos.',
          '  Board perturbation proves some context sensitivity, not why every remaining zero exists.',
          '- No complete match simulation, win-rate experiment or Pi measurement was performed.', '',
          'There is still no defensible percentage of strategically important creatures covered.',
          'The useful output is the tested shared gaps above and an explicit unresolved remainder.', '']
(root/'report.md').write_text('\n'.join(lines),encoding='utf-8')
print(json.dumps(summary,indent=2))
