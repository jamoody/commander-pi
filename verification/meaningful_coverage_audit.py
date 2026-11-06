"""Conservative, adjustable rubric for semantic removal-ability coverage in BOTH evaluators.

This is a lower-bound census of provable gaps, not a strategic-quality score. Every face
and trait retains the evidence used; no production code or game scores are modified.
"""
import argparse,collections,csv,hashlib,json,re
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True)
p.add_argument('--evaluator',type=Path,required=True);p.add_argument('--jar',type=Path,required=True)
a=p.parse_args();root=a.root
def read(path):
    with path.open(encoding='utf-8') as f:return list(csv.DictReader(f,delimiter='\t'))
def ck(r):return r['card'],r['face']
def tk(r):return (*ck(r),r['kind'],r['mode'],r['keyword'],r['parameters_json'])
standard=read(root/'standard/cards.tsv');rich=read(root/'rich/cards.tsv')
assert not read(root/'standard/errors.tsv') and not read(root/'rich/errors.tsv')
richcards={ck(r):r for r in rich};richtraits=collections.defaultdict(list)
for t in read(root/'rich/traits.tsv'):richtraits[tk(t)].append(t)
traits=collections.defaultdict(list)
for t in read(root/'standard/traits.tsv'):traits[ck(t)].append(t)
source=a.evaluator.read_text(encoding='utf-8')
effects=set(re.findall(r'case (\w+):',source))
opportunity=source.split('static double opportunities',1)[1].split('static double effectValue',1)[0]
events=set(re.findall(r'TriggerType\.(\w+)',opportunity))
# Specific baseline scoring, verified against CreatureEvaluator, not the generic +10 ability fallback.
OLD_KEYWORDS=set('FLYING HORSEMANSHIP FEAR INTIMIDATE MENACE SKULK DOUBLE_STRIKE FIRST_STRIKE DEATHTOUCH LIFELINK TRAMPLE VIGILANCE INFECT WITHER TOXIC AFFLICT RAMPAGE ANNIHILATOR ABSORB OUTLAST BUSHIDO FLANKING EXALTED MELEE PROWESS REACH INDESTRUCTIBLE HEXPROOF SHROUD WARD PROTECTION UNDYING PERSIST DEFENDER PHASING DAYBOUND CUMULATIVE_UPKEEP ECHO FADING VANISHING'.split())
CORE_OLD={'ANNIHILATOR','EXALTED','MELEE','UNDYING','PERSIST'}
def params(t):return json.loads(t['parameters_json'])
def self_only(s):return bool(re.fullmatch(r'(?:Card\.|Creature\.)?Self(?:\+[^,]+)?',s or ''))
def scope(t):
    q=params(t);kind=t['kind'];mode=t['mode']
    if t['keyword'] in OLD_KEYWORDS:
        return ('core','baseline-specific-keyword') if t['keyword'] in CORE_OLD else ('baseline','baseline-combat-or-drawback')
    if kind=='trigger':
        if 'Battlefield' not in q.get('TriggerZones','Battlefield').split(','):return 'excluded','other-zone trigger'
        if mode=='ChangesZone' and q.get('Destination')=='Battlefield' and self_only(q.get('ValidCard')):return 'excluded','spent self ETB'
        if mode=='SpellCast' and self_only(q.get('ValidCard')):return 'excluded','self cast trigger already fired'
        if mode=='Exploited' and self_only(q.get('ValidSource')):return 'excluded','own entry/exploit sequence already fired'
        if mode=='TurnFaceUp' and self_only(q.get('ValidCard')):return 'excluded','own face-up transition already fired in audited face'
        if mode=='Cycled' and self_only(q.get('ValidCard')):return 'excluded','self cycling payoff belongs to hand/discard event'
        if mode=='Specializes' and self_only(q.get('ValidCard')):return 'excluded','own specialize transition already fired in audited face'
        if mode=='ChangesZone' and q.get('Destination')=='Graveyard' and self_only(q.get('ValidCard')):return 'borderline','own death/removal-consequence dependent'
    if kind=='activated' and q.get('ActivationZone','Battlefield') not in {'Battlefield','Any'}:return 'excluded','other-zone activation'
    if kind=='replacement' and mode=='Moved' and q.get('Destination')=='Battlefield' and self_only(q.get('ValidCard')):return 'excluded','own entry replacement'
    if kind=='replacement' and 'Battlefield' not in q.get('ActiveZones','Battlefield').split(','):return 'borderline','replacement in another zone'
    return None

def significance(t):
    initial=scope(t)
    if initial:return initial
    q=params(t);kind=t['kind'];mode=t['mode'];roles=[]
    if kind=='static':
        if mode=='CantChangeLife' and q.get('ValidPlayer') in {'You','Player.You'}:return 'core','life-total protection'
        if mode in {'CantGainLife','CantDraw'} and 'Opponent' in q.get('ValidPlayer',''):return 'core','opposing resource restriction'
        if mode in {'CantBeCast','CantBeActivated','DisableTriggers','Panharmonicon','ReduceCost','UntapOtherPlayer','ManaBurn'}:
            if any(self_only(q.get(k)) for k in ['Affected','ValidCard']):return 'borderline','self-only restriction/protection'
            return 'core','restriction or amplification'
        if any(k in q for k in ['MayPlay','ControlOpponentsSearchingLibrary','AddTrigger','AddAbility','AdjustLandPlays']):return 'core','permission or granted engine'
        if mode=='Continuous' and q.get('Affected','').find('YouCtrl')>=0 and any(k in q for k in ['AddPower','AddToughness','AddKeyword']):return 'core','friendly board support'
        if mode=='Continuous' and any(k in q.get('Affected','') for k in ['OppCtrl','YouDontCtrl']) and any(q.get(k,'').startswith('-') for k in ['AddPower','AddToughness']):return 'core','opposing board suppression'
        return 'borderline','static semantics need judgment'
    if kind=='replacement' and mode=='Moved' and q.get('Destination')=='Graveyard' and 'OppOwn' in q.get('ValidCard',''):
        return 'core','opponent graveyard denial'
    if kind=='replacement' and q.get('Layer')=='CantHappen':
        if mode=='GameLoss' and q.get('ValidPlayer') in {'You','Player.You'} or mode=='GameWin' and q.get('ValidPlayer') in {'Opponent','Player.Opponent'}:
            return 'core','win/loss protection'
        if mode in {'GameLoss','GameWin'}:return 'excluded','win/loss restriction benefits opponent or harms controller'
    nodes=json.loads(t['effect_nodes_json'])
    for node in nodes:
        api=node['api'];e=node['parameters'];defined=e.get('Defined','You')
        friendly=defined in {'You','Player.You','TriggeredSourceController'} and 'Opponent' not in e.get('ValidTgts','')
        hostile=defined in {'Opponent','Player.Opponent','EachOpponent','TriggeredTarget','TriggeredCardController','TriggeredPlayer'}
        target=e.get('ValidTgts','');opposing_target=bool(target) and 'YouCtrl' not in target and 'YouOwn' not in target
        if api=='Draw' and friendly and e.get('NumCards','1')!='0':roles.append('card production')
        if api=='Token' and e.get('TokenOwner','You') in {'You','Player.You','TriggeredAttackerController','TriggeredSourceController'}:roles.append('token production')
        if api in {'Mana','ManaReflected'} and friendly:roles.append('mana production')
        if api=='Dig' and e.get('DestinationZone','Hand') in {'Hand','Battlefield'} and e.get('SourceZone','Library')=='Library' and friendly:roles.append('library resource access')
        if api=='ChangeZone':
            if e.get('Origin') in {'Library','Graveyard'} and e.get('Destination') in {'Hand','Battlefield'} and e.get('DefinedPlayer','You') in {'You','Player.You'} and ('Opp' not in target or e.get('GainControl')=='True'):roles.append('tutoring or recursion')
            elif opposing_target and e.get('Origin') in {'Battlefield','Stack'}:roles.append('repeatable interaction')
        if api in {'Destroy','Exile','GainControl','Counter','Tap'} and opposing_target:roles.append('repeatable interaction')
        if api=='Counter' and e.get('Defined')=='TriggeredSpellAbility' and q.get('ValidActivatingPlayer') in {'Opponent','Player.Opponent'}:roles.append('opposing spell denial')
        if api=='CopyPermanent' and (target or 'Defined' in e):roles.append('permanent copying')
        if api=='CopySpellAbility':roles.append('spell/ability amplification')
        if api=='Discard' and (hostile or opposing_target):roles.append('opposing resource denial')
        if api=='Sacrifice' and (hostile or opposing_target):roles.append('opposing resource denial')
        if api in {'DealDamage','LoseLife','DamageAll'} and (hostile or opposing_target or 'Opp' in e.get('ValidPlayers','')):
            # One point can be relevant if recurring or multi-opponent; self-damage is excluded.
            amount=e.get('NumDmg',e.get('LifeAmount','1'))
            if amount!='0' and not (mode=='Phase' and amount=='1'):roles.append('recurring damage/drain')
        if api in {'PumpAll','PutCounterAll'} and 'YouCtrl' in e.get('ValidCards',''):roles.append('friendly board support')
        if api in {'AddPhase','AddTurn','WinGame'} and friendly or api=='LoseGame' and hostile:roles.append('extra turns/combat or game outcome')
    if roles:
        # Very large or undefined mana costs are meaningful only under additional assumptions.
        cost=q.get('Cost','')
        if re.fullmatch(r'\d+',cost) and int(cost)>7:return 'borderline','high activation cost; '+', '.join(sorted(set(roles)))
        if set(roles)=={'card production'} and 'Discard<' in cost:return 'borderline','discard-to-draw selection needs net-value judgment'
        if re.search(r'PayLife<(?:[89]|[1-9]\d+)\b',cost):return 'borderline','large life payment needs cost-benefit judgment'
        return 'core',', '.join(sorted(set(roles)))
    return 'borderline','no clear core payoff (small life/filtering/self-growth, drawback, or unknown)'

def coverage(t,card):
    q=params(t);e=json.loads(t['effect_parameters_json']);kind=t['kind'];mode=t['mode'];api=t['root_api']
    other=richtraits[tk(t)];variants=[t]+other
    if t['keyword'] in OLD_KEYWORDS:return 'old-specific','explicit keyword valuation'
    if api=='Mana' and any(s.startswith('manadork:') for s in json.loads(card['legacy_labels'])):return 'old-coarse','baseline recognizes mana role (not necessarily its quantity)'
    if kind=='trigger' and q.get('TriggerDescription','').startswith('Landfall'):
        # Preserve this as coarse recognition unless the new evaluator provides fuller evidence.
        old_landfall=True
    else:old_landfall=False
    if kind in {'activated','trigger'}:
        if any(v['gate']=='contributes' and float(v['opportunities'])>0 and float(v['isolated_effect_value'])>0 for v in variants):
            return 'new-observed','payoff and event/resource path exercised'
        if old_landfall:return 'old-coarse','baseline identifies landfall, not its payoff'
        if kind=='trigger' and mode not in events:return 'miss','new evaluator skips trigger '+mode+'; baseline has no specific handler'
        if api and api not in effects:return 'miss','new evaluator stops at '+api+'; baseline gives at most generic ability points'
        if kind=='trigger' and mode in {'SpellCast','SpellCastOrCopy'} and q.get('ValidActivatingPlayer','') not in {'','Player','You','Player.You','Opponent','Player.Opponent','Player.Other','Player.NonActive'}:
            return 'miss','unsupported spell-caster filter'
        if kind=='trigger' and mode=='Phase' and (q.get('Phase') not in {'Upkeep','End of Turn','Main2','Main1','Main','Draw','BeginCombat','EndCombat'} or q.get('ValidPlayer','') not in {'','Player','You','Player.You','Opponent','Player.Opponent','Player.Other','Player.NonActive'}):
            return 'miss','unsupported phase/player pattern'
        if kind=='trigger' and mode=='ChangesZoneAll' and q.get('Destination')!='Battlefield' and q.get('Origin')!='Graveyard' and not (q.get('Origin')=='Battlefield' and q.get('Destination')=='Graveyard'):return 'miss','batch zone-change destination unsupported'
        if api=='Counter' and 'ValidTgts' not in e:return 'miss','defined counter target unsupported'
        return 'uncertain','recognized API but cost, condition, target, quantity or event supply not established'
    if kind=='replacement':
        if mode not in {'CreateToken','AddCounter'}:return 'miss','replacement event '+mode+' absent from production valuation'
        return 'uncertain','replacement-family score not isolated to this trait'
    if kind=='static':
        if any(v['gate']=='static-contributes' and float(v['isolated_effect_value'])>0 for v in variants):return 'new-observed','isolated static family contribution exercised'
        if mode in {'CantChangeLife','CantGainLife','CantDraw'}:return 'miss','life/draw restriction not valued by source-removal evaluator'
        if mode=='CantBeActivated' and q.get('Activator','Player') not in {'Player','Opponent','Player.Opponent','You','Player.You'}:return 'miss','restriction activator filter not handled'
        if mode=='CantBeCast' and q.get('Caster','Player') not in {'Player','Opponent','Player.Opponent','You','Player.You'}:return 'miss','restriction caster filter not handled'
        if mode=='Continuous' and q.get('ControlOpponentsSearchingLibrary'):return 'miss','search control field not handled'
        if mode=='Continuous' and 'AdjustLandPlays' in q and q.get('Affected') not in {'You','Player.You'}:return 'miss','extra-land player filter unsupported'
        if mode=='Continuous' and any(k in q.get('Affected','') for k in ['OppCtrl','YouDontCtrl']) and any(q.get(k,'').startswith('-') for k in ['AddPower','AddToughness']):return 'miss','opposing continuous stat suppression not attributed to source'
        if q.get('MayPlay')=='True':
            filt=q.get('Affected','');zone=q.get('AffectedZone','')
            if 'MayPlayAltManaCost' in q or any(k in filt for k in ['Remembered','Imprinted','ExiledWith','Chosen']):return 'miss','permission binding or alternate payment unsupported'
            if zone=='Library':
                if 'TopLibrary' not in filt or 'YouCtrl' not in filt:return 'miss','library permission filter unsupported'
            elif zone not in {'Graveyard','Exile'} or not any(k in filt for k in ['YouOwn','YouCtrl','OppOwn','OppCtrl']):return 'miss','permission zone/owner filter unsupported'
        return 'uncertain','static contribution not isolated; aggregate positive is not proof for this trait'
    return 'uncertain','no verified classification'

faces=[];trait_rows=[];family=collections.defaultdict(set)
for card in standard:
    if card['creature']!='true':continue
    key=ck(card);ts=traits[key];records=[]
    for t in ts:
        tier,role=significance(t)
        status,why=coverage(t,card) if tier=='core' else ('not-core',role)
        row=dict(card=card['card'],face=card['face'],kind=t['kind'],mode=t['mode'],tier=tier,role=role,coverage=status,evidence=why,keyword=t['keyword'],root_api=t['root_api'],parameters=t['parameters_json'],effect_nodes=t['effect_nodes_json'])
        records.append(row);trait_rows.append(row)
    core=[r for r in records if r['tier']=='core'];missing=[r for r in core if r['coverage']=='miss'];uncertain=[r for r in core if r['coverage']=='uncertain'];recognized=[r for r in core if r['coverage'] in {'old-specific','old-coarse','new-observed'}]
    max_score=max(int(card['score']),int(richcards[key]['score']))
    bespoke=bool(card['legacy_modifier'])
    if missing and bespoke:bucket='custom-legacy-modifier-review'
    elif missing and not recognized and not uncertain and max_score==0:bucket='core-strategic-miss'
    elif missing and (recognized or max_score>0):bucket='partial-core-gap'
    elif missing:bucket='core-gap-with-other-uncertainty'
    elif core and uncertain:bucket='core-coverage-unresolved'
    elif core:bucket='core-recognition-observed'
    else:bucket='no-core-role-established'
    for r in missing:family[r['evidence']].add(key)
    faces.append(dict(card=card['card'],face=card['face'],bucket=bucket,core_traits=len(core),missed_core_traits=len(missing),recognized_core_traits=len(recognized),uncertain_core_traits=len(uncertain),standard_bonus=card['score'],rich_bonus=richcards[key]['score'],legacy_score=card['legacy_score'],legacy_labels=card['legacy_labels'],custom_legacy_modifier=card['legacy_modifier'],roles='; '.join(sorted({r['role'] for r in core})),missing_reasons='; '.join(sorted({r['evidence'] for r in missing}))))
def write(name,data):
    with (root/name).open('w',newline='',encoding='utf-8') as f:
        w=csv.DictWriter(f,fieldnames=list(data[0]));w.writeheader();w.writerows(data)
write('faces.csv',faces);write('traits.csv',trait_rows)
write('strict-strategic-misses.csv',[r for r in faces if r['bucket']=='core-strategic-miss'])
write('partial-core-gaps.csv',[r for r in faces if r['bucket']=='partial-core-gap'])
write('gap-families.csv',[dict(reason=k,distinct_faces=len(v),examples='; '.join(n for n,f in sorted(v)[:12])) for k,v in sorted(family.items(),key=lambda kv:-len(kv[1]))])
names=collections.defaultdict(list)
for row in faces:names[row['card']].append(row)
names_result=[]
legality={r['card']:r['local_commander_allowed']=='true' for r in read(root/'local-commander-filter.tsv')} if (root/'local-commander-filter.tsv').exists() else {}
for name,rs in sorted(names.items()):
    anymiss=any(int(r['missed_core_traits'])>0 and r['bucket']!='custom-legacy-modifier-review' for r in rs)
    strict=any(r['bucket']=='core-strategic-miss' for r in rs) and all(int(r['recognized_core_traits'])==0 and int(r['uncertain_core_traits'])==0 and int(r['standard_bonus'])==0 and int(r['rich_bonus'])==0 and not r['custom_legacy_modifier'] for r in rs)
    name_bucket='wholly-unrecognized-core' if strict else 'partial-core-gap' if anymiss and any(int(r['recognized_core_traits'])>0 or int(r['standard_bonus'])>0 or int(r['rich_bonus'])>0 for r in rs) else 'core-gap-extent-uncertain' if anymiss else 'no-proven-core-gap'
    names_result.append(dict(card=name,creature_faces=len(rs),bucket=name_bucket,strict_strategic_miss=strict,any_confirmed_core_gap=anymiss,local_commander_allowed=legality.get(name,'unknown'),face_buckets='; '.join(r['face']+':'+r['bucket'] for r in rs)))
write('card-names.csv',names_result)
summary=dict(creature_faces=len(faces),unique_catalog_creature_names=len(names),face_buckets=dict(collections.Counter(r['bucket'] for r in faces)),
             name_buckets=dict(collections.Counter(r['bucket'] for r in names_result)),
             strict_strategic_miss_names=sum(r['strict_strategic_miss'] for r in names_result),
             names_with_any_core_gap=sum(r['any_confirmed_core_gap'] for r in names_result),
             custom_legacy_review=sum(r['bucket']=='custom-legacy-modifier-review' for r in faces),
             local_commander_names=sum(r['local_commander_allowed'] is True for r in names_result),
             local_commander_strict_misses=sum(r['strict_strategic_miss'] and r['local_commander_allowed'] is True for r in names_result),
             local_commander_any_core_gap=sum(r['any_confirmed_core_gap'] and r['local_commander_allowed'] is True for r in names_result),
             forge_ai_sha256=hashlib.sha256(a.jar.read_bytes()).hexdigest())
(root/'summary.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
control={r['card']:r for r in faces if r['face']=='Original'}
for name in ['Notion Thief','Dauthi Voidwalker','Opposition Agent','Vilis, Broker of Blood','Platinum Angel','Platinum Emperion']:
    assert control[name]['bucket']=='core-strategic-miss',(name,control[name])
for name in ['The Gitrog Monster','Jin-Gitaxias, Progress Tyrant']:
    assert control[name]['bucket']=='partial-core-gap',(name,control[name])
for name in ['Llanowar Elves',"Ulamog's Crusher",'Esper Sentinel','Winota, Joiner of Forces','Kinnan, Bonder Prodigy','Hullbreaker Horror']:
    assert control[name]['bucket']=='core-recognition-observed',(name,control[name])
for name in ['Mulldrifter','Grizzly Bears','Colossal Dreadmaw','Soldevi Heretic','Drownyard Lurker','Abundant Maw']:
    assert control[name]['bucket']=='no-core-role-established',(name,control[name])
assert control['Toxrill, the Corrosive']['bucket']=='core-gap-with-other-uncertainty'
assert control['Nadu, Winged Wisdom']['bucket']=='core-coverage-unresolved'
assert sum(summary['face_buckets'].values())==19195
assert sum(summary['name_buckets'].values())==18868
lines=['# Meaningful strategic ability coverage — 2026-11-05','',
       '## Result','',
       f"The conservative screening result is **{summary['strict_strategic_miss_names']:,} distinct catalog card names**",
       'whose core strategic role is not recognized by either removal evaluator under the rubric below.',
       'This is a code-supported screening estimate, not hand-certified ground truth for every card.',
       'Specific unsupported paths are verified; whether every conditional payoff is important in a',
       'particular Commander game remains a judgment. Unresolved cases are not counted as misses.', '',
       '| Distinct card names | Count |','|---|---:|',
       f"| Wholly unrecognized core strategic role | {summary['name_buckets']['wholly-unrecognized-core']:,} |",
       f"| Some recognition, with a core ability gap | {summary['name_buckets']['partial-core-gap']:,} |",
       f"| A core gap exists, extent of other coverage uncertain | {summary['name_buckets']['core-gap-extent-uncertain']:,} |",
       f"| **Names with at least one screened core gap** | **{summary['names_with_any_core_gap']:,}** |", '',
       'Names are deduplicated across creature faces. A card is not called wholly missed if another',
       'audited creature face has meaningful recognition or unresolved meaningful coverage.',
       'Catalog names still include digital/rebalanced/special variants and cards outside ordinary',
       'Commander legality. This is not a current official Commander-legal-card census.', '',
       '## What meaningful means here', '',
       'Core: repeatable or still-future card/mana/token production, tutoring/recursion, resource',
       'access, copying, board-wide support/suppression, repeatable interaction, extra turns/combat,',
       'and rules-based protection or denial. Conditional engines count when the script exposes a',
       'plausible payoff; we do not demand that the standard test board currently enables it.', '',
       'Borderline, not automatically counted: small standalone life gain; scry/surveil and other',
       'selection without a clear resource payoff; ordinary self-pump/growth and combat tricks;',
       'discard-to-draw selection needing net-value judgment; pure generic activations costing more',
       'than seven mana; life payments of eight or more; one-point phase-triggered damage; unknown',
       'recipient/quantity semantics. These are adjustable judgment rules, not declarations that',
       'such cards are always weak. Soul Warden, for example, can be important with the right engine.', '',
       'Excluded from present removal-production gaps: already-fired self ETBs, self-cast triggers,',
       'own exploit sequences, already-completed self face-up/specialize transitions, self cycling',
       'payoffs and abilities confined to another zone. Reusing these through blink/recursion may',
       'matter, but the external enabler is a separate source of future production. Owner-harming',
       'game-loss restrictions are not treated as a beneficial threat engine.', '',
       'The rubric is implemented in significance() and scope() in meaningful_coverage_audit.py.',
       'Every trait has a saved role/tier, coverage decision and explanation in traits.csv.', '',
       '## What recognition means', '',
       '- Old: actual CreatureEvaluator labels are recorded. Explicit keyword handling, such as',
       '  annihilator, counts. Mana-role and landfall bonuses count as coarse recognition, not',
       '  proof that quantities or payoffs are understood.',
       '- Generic body/CMC points and the default +10 activated-ability bonus do not establish',
       '  semantic recognition. Runtime controls show the same old +10 for drawing one or five',
       '  cards, whereas the new evaluator distinguishes them.',
       '- New: actual payoff and opportunity/resource evidence from standard and richer boards',
       '  counts as observed recognition. API-family membership alone does not.',
       '- A miss needs a specific code path that skips a core ability: unsupported trigger/effect,',
       '  recipient/zone/filter restriction, or absent source attribution. Zero alone never proves it.',
       '- Wholly missed additionally requires zero aggregate new bonus on both boards, no observed',
       '  core recognition on any creature face, no unresolved core trait, and no custom legacy',
       '  evaluation modifier that could explain recognition.',
       '- Some positive paths may still omit linked payoffs. Observed recognition is not completeness',
       '  and is not evidence that weights or final target choices are correct.', '',
       'This concerns the shared removal-target valuation path. Forge can know how to activate a',
       'card in other AI modules without attributing that ability to its value as a removal target.',
       'Combat simulation, action selection, card-specific tactical logic and final decision quality',
       'are not exhaustively audited here. Generic opponent-board weighting does not identify which',
       'creature supplies an engine.', '',
       '## Face-level census', '', '| Bucket | Faces |','|---|---:|']
for label,n in summary['face_buckets'].items():lines.append(f'| {label} | {n:,} |')
lines += ['', 'The no-core-role-established bucket combines baseline bodies, spent effects, borderline',
          'abilities and unknown semantics; it must not be read as a count of fully understood cards.',
          'Core-coverage-unresolved remains separate and is not included in the strict miss estimate.', '',
          '## Verified examples', '',
          '- Notion Thief: unsupported draw replacement; ordinary body value is not draw-theft recognition.',
          '- Dauthi Voidwalker: opponent graveyard denial is not attributed to the creature.',
          '- Opposition Agent: search control field is not valued.',
          '- The Gitrog Monster: extra land-play permission now recognized; library-to-graveyard batch draw remains unsupported.',
          '- Jin-Gitaxias, Progress Tyrant: copying now recognized; the defined triggered counter target remains unsupported.',
          '- Vilis: LifeLost trigger is not recognized.',
          '- Platinum Angel and Platinum Emperion: their loss/life-total protections lack source valuation.',
          '- Ulamog\'s Crusher: correctly retained as old-system recognition through annihilator.',
          '- Llanowar Elves and Lotus Cobra: old-system coarse mana/landfall recognition is preserved.',
          '- Mulldrifter, Abundant Maw, Drownyard Lurker: spent ETB/cast/cycling effects do not become misses.',
          '- Toxrill: proven suppression gap plus unresolved other coverage, not a strict whole-role miss.',
          '- Nadu: granted-engine coverage is context-dependent, not promoted to a confirmed miss.', '',
          '## Validation and limits', '',
          '- Current v5 strategic evaluator plus the unchanged original CreatureEvaluator.',
          '- Two engine-backed catalog runs: 26,921 permanent faces each, zero exceptions.',
          '- 19,195 creature faces, representing 18,868 distinct catalog names.',
          '- See the v5 change report for packaged runtime checks; census invariants checked below.',
          '- Spot-checking provisional misses corrected opponent-benefiting draw inference, spent',
          '  cast/cycling/exploit/specialize handling and protection-versus-drawback direction.',
          '- The richer board cannot cover all phases, histories, tribal requirements, counters,',
          '  tokens, actual static recalculation and combo states. Missing observations stay uncertain.',
          '- Replacement and static per-trait recognition is intentionally conservative because a',
          '  positive aggregate score does not prove that a particular static ability was understood.',
          '- No full-match or win-rate experiment or Pi test; see v5 change report for production changes.',
          '- local-commander-filter.tsv is only this installation\'s stored format predicate output.',
          '  It is retained as a diagnostic, not used to claim current official card legality.', '',
          '## Artifacts', '',
          '- card-names.csv: deduplicated results and cross-face buckets.',
          '- strict-strategic-misses.csv: every flagged face with old/new scores and reasons.',
          '- partial-core-gaps.csv and faces.csv: full face-level accounting.',
          '- traits.csv: per-ability judgment and exact structured script fields.',
          '- gap-families.csv: reusable missing paths; counts overlap.',
          '- standard/ and rich/: raw native-engine observations.',
          '- behavioral-controls.txt: runtime verification results.', '',
          f"Packaged AI SHA-256: `{summary['forge_ai_sha256']}`", '']
(root/'report.md').write_text('\n'.join(lines),encoding='utf-8')
print(json.dumps(summary,indent=2))
