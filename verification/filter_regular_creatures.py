"""Conservative audit-only filter. Never changes card scripts or AI scores."""
import argparse, collections, csv, json, re, zipfile
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--audit',type=Path,required=True)
p.add_argument('--package',type=Path,required=True)
p.add_argument('--out',type=Path,required=True)
a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
# Deliberately excludes resource engines, death/ETB keywords, anthem keywords,
# annihilator and unknown mechanics even if baseline gives them some generic points.
BODY_KEYWORDS={
    'Flying','First strike','Double strike','Deathtouch','Lifelink','Trample',
    'Vigilance','Reach','Defender','Haste','Flash','Fear','Intimidate','Menace',
    'Skulk','Horsemanship','Indestructible','Hexproof','Shroud','Ward','Protection',
    'Infect','Wither','Toxic','Afflict','Rampage','Absorb','Bushido','Flanking',
}
BODY_KEYWORDS={k.casefold() for k in BODY_KEYWORDS}
def classification(script):
    if script is None:return False,'script mapping unavailable'
    # All faces of the script must qualify: no hiding a strategic reverse face.
    reasons=[];keywords=[]
    for line in script.splitlines():
        line=line.strip()
        if not line or line.startswith('#'):continue
        if line.startswith('Variant:') or line=='Oracle:<Unsupported Variant>':
            reasons.append('variant-specific abilities require review')
        elif line.startswith(('A:','T:','S:','R:')):
            reasons.append('explicit '+line[:1]+' ability')
        elif line.startswith('K:'):
            keyword=line[2:].split(':',1)[0].strip()
            if keyword.casefold() not in BODY_KEYWORDS:reasons.append('keyword requires review: '+keyword)
            else:keywords.append(keyword)
        elif line.startswith('SVar:'):
            if line.split(':',2)[1] in {'PlayMain1','BuffedBy'}:continue # AI hints, not abilities
            # Even an apparently auxiliary variable can alter evaluation/behavior.
            reasons.append('script variable requires review')
        elif line.startswith('Types:') and 'Land' in line[6:].split():
            reasons.append('land type may grant implicit mana abilities')
    if reasons:return False,'; '.join(sorted(set(reasons)))
    return True,'body and conventional keywords only: '+(', '.join(sorted(set(keywords))) or 'vanilla')

scripts={}
with zipfile.ZipFile(a.package/'res/cardsfolder/cardsfolder.zip') as z:
    for name in z.namelist():
        if not name.endswith('.txt'):continue
        script=z.read(name).decode('utf-8-sig')
        names=re.findall(r'^Name:(.+)$',script,re.M)
        if names:scripts.setdefault(names[0].strip(),[]).append(script)
def rows(name):
    with (a.audit/name).open(encoding='utf-8') as f:return list(csv.DictReader(f,delimiter='\t'))
creatures=[c for c in rows('cards.tsv') if c['creature']=='true']
regular=[];review=[]
for c in creatures:
    matches=scripts.get(c['card'],[])
    ok,why=classification('\n'.join(matches) if matches else None)
    result=dict(c,classification='regular' if ok else 'review',classification_reason=why)
    (regular if ok else review).append(result)
for name,data in [('regular-excluded.csv',regular),('strategic-review.csv',review),
                  ('strategic-zero-bonus.csv',[c for c in review if int(c['score'])==0])]:
    with (a.out/name).open('w',newline='',encoding='utf-8') as f:
        w=csv.DictWriter(f,fieldnames=list(creatures[0])+['classification','classification_reason'])
        w.writeheader();w.writerows(data)
classified={c['card']:c['classification'] for c in regular+review}
for name in ['Grizzly Bears','Colossal Dreadmaw','Serra Angel']:
    assert classified[name]=='regular',name
for name in ['Kinnan, Bonder Prodigy','Elesh Norn, Mother of Machines','Kiki-Jiki, Mirror Breaker',
             'Winota, Joiner of Forces','Esper Sentinel','Llanowar Elves','Dryad Arbor','Notion Thief','Hullbreaker Horror']:
    assert classified[name]=='review',name
assert len(regular)+len(review)==len(creatures)
assert not any(int(c['score'])>0 for c in regular), 'positive-score simple creature requires manual review'
summary=dict(total_creature_faces=len(creatures),regular_excluded=len(regular),review_faces=len(review),
             review_positive_bonus=sum(int(c['score'])>0 for c in review),
             review_zero_bonus=sum(int(c['score'])==0 for c in review))
(a.out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
keys={(c['card'],c['face']) for c in review if int(c['score'])==0}
groups=collections.defaultdict(set)
for t in rows('traits.tsv'):
    key=t['card'],t['face']
    if key not in keys:continue
    if t['kind']=='static' and 'Secondary=True' in t['parameters'] and t['mode'] in {
        'CantBlockBy','AttackVigilance','CantAttack','CantBlock','CanBlockIfShadow',
        'AssignCombatDamageAsUnblocked','Hexproof','Shroud','Protection'}:
        continue # Generated conventional keyword machinery, not a new strategic gap.
    gate=t['gate']
    if gate.startswith(('trigger-mode:','trigger-pattern:')):groups[gate].add(key)
    if gate=='static-unhandled':groups['static-mode:'+t['mode']].add(key)
    if gate=='replacement-unhandled':groups['replacement-event:'+t['mode']].add(key)
    for blocker in t['effect_blockers'].split(';'):
        if blocker.startswith('effect:'):groups[blocker].add(key)
with (a.out/'remaining-patterns.csv').open('w',newline='',encoding='utf-8') as f:
    w=csv.writer(f);w.writerow(['pattern','distinct_zero_bonus_review_faces','examples'])
    for kind,found in sorted(groups.items(),key=lambda x:-len(x[1])):
        w.writerow([kind,len(found),'; '.join(n for n,face in sorted(found)[:8])])
report=f'''# Creature audit with ordinary bodies removed

Source: {a.audit.name}. No game code or card data changed.

| Bucket | Creature faces |
|---|---:|
| Original total | {len(creatures):,} |
| Ordinary bodies/conventional keywords excluded | {len(regular):,} |
| Remaining review pool | {len(review):,} |
| Review pool with a positive added bonus | {summary['review_positive_bonus']:,} |
| Review pool with zero added bonus | {summary['review_zero_bonus']:,} |

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
'''
(a.out/'report.md').write_text(report,encoding='utf-8')
print(json.dumps(summary,indent=2))
