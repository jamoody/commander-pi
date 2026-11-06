"""Track original proven gaps without confusing reclassified uncertainty with fixes."""
import argparse, collections, csv, json
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--before',type=Path,required=True)
p.add_argument('--after',type=Path,required=True)
a=p.parse_args()
def read(path,delimiter=','):
    with path.open(encoding='utf-8-sig') as f:return list(csv.DictReader(f,delimiter=delimiter))
def key(r):return r['card'],r['face'],r['kind'],r['mode'],r['parameters']
names={r['card'] for r in read(a.before/'card-names.csv') if r['any_confirmed_core_gap']=='True'}
old={key(r):r for r in read(a.before/'traits.csv')}
observed=set();still=set();uncertain=set();families=collections.defaultdict(set)
for r in read(a.after/'traits.csv'):
    previous=old.get(key(r))
    if r['card'] not in names or previous is None or previous['coverage']!='miss':continue
    if r['coverage']=='new-observed':
        observed.add(r['card']);families[previous['evidence']].add(r['card'])
    elif r['coverage']=='miss':still.add(r['card'])
    elif r['coverage']=='uncertain':uncertain.add(r['card'])
closed=names-still-uncertain
uncertain_only=names-still-closed
assert len(closed)+len(still)+len(uncertain_only)==len(names)
summary={'original_gap_names':len(names),'newly_observed_names':len(observed),
         'original_gaps_all_observed':len(closed),'remaining_confirmed_original_gaps':len(still),
         'remaining_original_gaps_uncertain_only':len(uncertain_only),'fixtures':{}}
for fixture in ['standard','rich']:
    before={(r['card'],r['face']):r for r in read(a.before/fixture/'cards.tsv','\t')}
    after={(r['card'],r['face']):r for r in read(a.after/fixture/'cards.tsv','\t')}
    assert before.keys()==after.keys()
    creatures=[k for k in before if before[k]['creature']=='true']
    summary['fixtures'][fixture]={'creature_faces':len(creatures),
        'before_positive':sum(int(before[k]['score'])>0 for k in creatures),
        'after_positive':sum(int(after[k]['score'])>0 for k in creatures),
        'new_positive':sum(int(before[k]['score'])==0 and int(after[k]['score'])>0 for k in creatures),
        'lost_positive':sum(int(before[k]['score'])>0 and int(after[k]['score'])==0 for k in creatures)}
with (a.after/'original-cohort.csv').open('w',newline='',encoding='utf-8') as f:
    w=csv.writer(f);w.writerow(['card','newly_observed_payoff','remaining_confirmed_gap','remaining_uncertain_gap','outcome'])
    for n in sorted(names):w.writerow([n,n in observed,n in still,n in uncertain,
        'original gaps observed' if n in closed else 'remaining confirmed gap' if n in still else 'uncertain; not claimed fixed'])
with (a.after/'new-recognition-families.csv').open('w',newline='',encoding='utf-8') as f:
    w=csv.writer(f);w.writerow(['previous_gap','distinct_names','examples'])
    for k,ns in sorted(families.items(),key=lambda kv:-len(kv[1])):w.writerow([k,len(ns),'; '.join(sorted(ns)[:12])])
(a.after/'comparison.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
print(json.dumps(summary,indent=2))
