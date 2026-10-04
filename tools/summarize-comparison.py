"""Summarize actual server CSV; never infer N/A or turn errors into fast success."""
import argparse
import csv
import json
import math
from pathlib import Path
import statistics

SEEDS = {789123, 1, 42, 20241002, 987654321}
TARGETS = ('minecraft:village_plains', 'minecraft:desert_pyramid', 'minecraft:mansion', 'minecraft:plains', 'minecraft:lush_caves')
ENGINES = ('unified105', 'unified110', 'enhance160')

def percentile(values, fraction):
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * fraction) - 1)] if values else None

def summary(rows, field):
    values = [float(row[field]) for row in rows if row.get(field, 'N/A') != 'N/A']
    return {'median': statistics.median(values), 'p95': percentile(values, .95), 'max': max(values)} if values else None

def analyze(directory):
    rows = []
    for path in sorted(directory.glob('*.csv')):
        with path.open(encoding='utf-8-sig', newline='') as stream:
            rows.extend(csv.DictReader(stream))
    identities = [(row['engine'], int(row['seed']), row['target'], row['cache'], int(row['repeat'])) for row in rows]
    expected = {(engine, seed, target, cache, repeat) for engine in ENGINES for seed in SEEDS
                for target in TARGETS for cache in ('cold', 'warm') for repeat in range(5)}
    complete = len(identities) == len(set(identities)) and set(identities) == expected
    failures = [identity for identity, row in zip(identities, rows) if row['result'] != 'found' or row['verified'] != 'true']
    results = {}
    for engine in ENGINES:
        for target in TARGETS:
            for cache in ('cold', 'warm'):
                group = [row for row in rows if row['engine'] == engine and row['target'] == target and row['cache'] == cache
                         and row['result'] == 'found' and row['verified'] == 'true']
                results[f'{engine}:{target}:{cache}'] = {'count': len(group), **{field: summary(group, field)
                    for field in ('wall_ms', 'main_ms', 'search_tick_p95_ms', 'search_tick_max_ms', 'request_main_ms',
                                  'worker_ms', 'preparation_ms', 'confirmation_ms', 'waiting_ms', 'samples',
                                  'candidates', 'checks', 'chunk_requests')}}
    mismatches = []
    for seed in SEEDS:
        for target in TARGETS:
            answers = {}
            for engine in ('unified105', 'unified110'):
                selected = [row for row in rows if row['engine'] == engine and int(row['seed']) == seed and row['target'] == target
                            and row['verified'] == 'true' and row['result'] == 'found']
                answers[engine] = {(int(row['x']), int(row['y']) if target in TARGETS[3:] else 0, int(row['z'])) for row in selected}
            if all(answers.values()) and (answers['unified105'] != answers['unified110'] or len(answers['unified110']) != 1):
                mismatches.append({'seed': seed, 'target': target, 'positions': {engine: sorted(points) for engine, points in answers.items()}})
    ratios, regressions = [], []
    for target in TARGETS:
        old, new = results[f'unified105:{target}:warm'], results[f'unified110:{target}:warm']
        if old['count'] == new['count'] == 25:
            ratios.append(new['wall_ms']['median'] / old['wall_ms']['median'])
            for field, statistic in (('wall_ms', 'p95'), ('main_ms', 'median'), ('search_tick_p95_ms', 'p95')):
                before, after = old[field][statistic], new[field][statistic]
                ratio = after / before if before else math.inf if after else 1
                if ratio > 1.1: regressions.append({'target': target, 'metric': field, 'statistic': statistic, 'ratio': ratio})
    improvement = 1 - math.exp(sum(math.log(ratio) for ratio in ratios) / len(ratios)) if len(ratios) == 5 else None
    passed = complete and not failures and not mismatches and improvement is not None and improvement >= .15 and not regressions
    return {'complete': complete, 'requests': len(rows), 'failures': failures, 'mismatches': mismatches,
            'warm_geometric_improvement': improvement, 'regressions_requiring_reproduction': regressions,
            'gate_passed': passed, 'results': results}

def render(report):
    lines = ['# 三版本真实服务器搜索对比', '', f"记录：{report['requests']} / 750；完整矩阵：{report['complete']}。",
             f"未经核实／失败记录：{len(report['failures'])}；统一版坐标不一致组：{len(report['mismatches'])}。", '',
             '| 目标 | 1.0.5 热中位 ms | 1.1.0 热中位 ms | Enhance 热中位 ms |', '|---|---:|---:|---:|']
    for target in TARGETS:
        values = [report['results'][f'{engine}:{target}:warm']['wall_ms'] for engine in ENGINES]
        lines.append('| ' + target + ' | ' + ' | '.join('N/A' if value is None else f"{value['median']:.3f}" for value in values) + ' |')
    improvement = report['warm_geometric_improvement']
    lines.extend(['', '等权几何平均改善：' + ('尚未完成' if improvement is None else f'{improvement:.2%}') + '。',
                  f"按当前完整数据检查的发布门槛：{'通过' if report['gate_passed'] else '未通过／未完成'}。", '',
                  '超过 10% 的指标需在同参数独立复测中检查是否可复现；初轮超限不能直接忽略。原始 N/A 保持 N/A，失败和未经核实的预测不计为快速成功。', '',
                  '## 需复测指标', '', '```json', json.dumps(report['regressions_requiring_reproduction'], ensure_ascii=False, indent=2), '```', '',
                  '所有统计详情（中位数、P95、最大值、冷场景、样本和核对计数）见同名 JSON。此表使用服务端数据，不能推断客户端帧率。'])
    return '\n'.join(lines) + '\n'

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('directory', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    report = analyze(args.directory)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.with_suffix('.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    args.output.with_suffix('.md').write_text(render(report), encoding='utf-8')
    print(json.dumps({key: value for key, value in report.items() if key != 'results'}, ensure_ascii=False))
