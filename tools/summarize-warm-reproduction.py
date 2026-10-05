"""Independent warm-tail check; warmups remain recorded and are never relabeled."""
import argparse, csv, json, runpy
from pathlib import Path
helpers=runpy.run_path(str(Path(__file__).with_name('summarize-comparison.py')))
SEEDS,TARGETS,summary=(helpers[name] for name in ('SEEDS','TARGETS','summary'))

def analyze(directory):
    rows=[]
    for path in sorted(directory.glob('*.csv')):
        with path.open(encoding='utf-8-sig',newline='') as stream: rows.extend(csv.DictReader(stream))
    measured=[r for r in rows if r['cache']=='warm']
    expected={(e,s,t,n) for e in ('unified105','unified110') for s in SEEDS for t in TARGETS for n in range(20)}
    identities=[(r['engine'],int(r['seed']),r['target'],int(r['repeat'])) for r in measured]
    complete=len(identities)==len(set(identities)) and set(identities)==expected
    failures=[r for r in rows if r['result']!='found' or r['verified']!='true']
    results={}
    regressions=[]
    mismatches=[]
    for target in TARGETS:
        for engine in ('unified105','unified110'):
            selected=[r for r in measured if r['engine']==engine and r['target']==target and r['result']=='found' and r['verified']=='true']
            results[f'{engine}:{target}']={'count':len(selected),**{field:summary(selected,field) for field in ('wall_ms','main_ms','search_tick_p95_ms')}}
        old,new=(results[f'{e}:{target}'] for e in ('unified105','unified110'))
        if old['count']==new['count']==100:
            for field,statistic in (('wall_ms','p95'),('main_ms','median'),('main_ms','p95'),('search_tick_p95_ms','p95')):
                before,after=old[field][statistic],new[field][statistic]
                if after>before*1.1:regressions.append({'target':target,'metric':field,'statistic':statistic,'ratio':after/before})
        for seed in SEEDS:
            points=[]
            for engine in ('unified105','unified110'):
                points.append({(int(r['x']),int(r['y']) if target in TARGETS[3:] else 0,int(r['z'])) for r in measured if r['engine']==engine and r['target']==target and int(r['seed'])==seed})
            if all(points) and (points[0]!=points[1] or len(points[1])!=1):mismatches.append({'target':target,'seed':seed})
    return {'complete':complete,'measured':len(measured),'warmups':len(rows)-len(measured),'failures':len(failures),
            'mismatches':mismatches,'regressions':regressions,'passed':complete and not failures and not mismatches and not regressions,'results':results}

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('directory',type=Path);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();report=analyze(args.directory)
    args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='results'},ensure_ascii=False))
