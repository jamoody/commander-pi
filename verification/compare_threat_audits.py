"""Compare observed scores; increased coverage is not a strategic accuracy metric."""
import argparse, csv, hashlib, json
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--before', type=Path, required=True)
p.add_argument('--after', type=Path, required=True)
p.add_argument('--jar', type=Path, required=True)
a = p.parse_args()

def rows(root, name):
    with (root / name).open(encoding='utf-8') as f:
        return list(csv.DictReader(f, delimiter='\t'))

def stats(root):
    cards = rows(root, 'cards.tsv')
    assert len({(c['card'], c['face']) for c in cards}) == len(cards)
    return dict(permanent_faces=len(cards), creature_faces=sum(c['creature']=='true' for c in cards),
                positive_permanent_faces=sum(int(c['score'])>0 for c in cards),
                positive_creature_faces=sum(c['creature']=='true' and int(c['score'])>0 for c in cards),
                errors=len(rows(root, 'errors.tsv')))

before, after = stats(a.before), stats(a.after)
summary = dict(before=before, after=after, forge_ai_sha256=hashlib.sha256(a.jar.read_bytes()).hexdigest())
(a.after / 'comparison.json').write_text(json.dumps(summary, indent=2)+'\n', encoding='utf-8')
old = {(c['card'],c['face']):c for c in rows(a.before,'cards.tsv')}
new = {(c['card'],c['face']):c for c in rows(a.after,'cards.tsv')}
with (a.after / 'changed-scores.csv').open('w', newline='', encoding='utf-8') as f:
    w=csv.writer(f); w.writerow(['card','face','before','after','explanation'])
    for key,c in sorted(new.items()):
        prev=old.get(key,{}).get('score','unavailable')
        if prev!=c['score']: w.writerow([*key,prev,c['score'],c['explanation']])
lines=['# Expanded strategic threat audit', '',
       'The same four-player public-board fixture was used before and after the change.',
       'Scores are additional removal bonuses, not complete creature values. Positive scores',
       'show exercised coverage; they do not prove correct weights or stronger play.', '',
       '| Observation | Before | After |','|---|---:|---:|']
for key in before: lines.append(f'| {key.replace("_"," ")} | {before[key]:,} | {after[key]:,} |')
lines += ['', '## Examples', '', '| Card | Before | After |', '|---|---:|---:|']
for name in ['Kinnan, Bonder Prodigy','Elesh Norn, Mother of Machines','Kiki-Jiki, Mirror Breaker',
             'Korvold, Fae-Cursed King','Najeela, the Blade-Blossom','Purphoros, God of the Forge',
             'Zulaport Cutthroat','Hullbreaker Horror','Archangel of Thune','Adeline, Resplendent Cathar',
             'Muldrotha, the Gravetide','Nadu, Winged Wisdom',
             'Winota, Joiner of Forces','Esper Sentinel','Yawgmoth, Thran Physician',
             'Sheoldred, the Apocalypse','Edric, Spymaster of Trest','Tymna the Weaver',
             'Urza, Lord High Artificer','Seedborn Muse','Grand Abolisher','Archivist of Oghma',
             'Bloom Tender','Faeburrow Elder','Alena, Kessig Trapper']:
    key=(name,'Original')
    lines.append(f"| {name} | {old.get(key,{}).get('score','error')} | {new.get(key,{}).get('score','absent')} |")
lines += ['', 'The Alena aggregate-expression exception is fixed. Correct zeros remain expected',
          'for ordinary bodies, already-realized effects and unavailable resources. Unknown',
          'conditions, effect branches and unsupported mechanics still leave genuine misses.',
          'The fixture has six Forests and is not representative of every deck or board.',
          'Trait diagnostics are review hints: isolated effect values exclude cost penalties,',
          'and family recognition does not establish support for every parameter combination.',
          'This audit covers common-catalog permanent faces, not every card script or game state.',
          'No full-match balance or Raspberry Pi performance validation has been performed.', '']
(a.after/'report.md').write_text('\n'.join(lines),encoding='utf-8')
print(json.dumps(summary,indent=2))
