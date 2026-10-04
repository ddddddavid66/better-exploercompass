"""Validate the full component matrix and export every measured fork/iteration."""
import argparse
import csv
import json
from pathlib import Path

ENGINES = ('unified105', 'unified110', 'enhance160')
SECONDARY = ('gc.alloc.rate.norm', 'gc.alloc.rate', 'gc.count', 'gc.time')


def validate(entries):
    expected = {(name, engine, size) for name, sizes in
                (('SchedulingBenchmark.dispatch', ('1', '4')),
                 ('TraversalBenchmark.traversal', ('64', '1250')))
                for engine in ENGINES for size in sizes}
    actual = []
    for entry in entries:
        name = '.'.join(entry['benchmark'].split('.')[-2:])
        params = entry['params']
        actual.append((name, params['engine'], params.get('lanes', params.get('radius'))))
        # 不把预热、缺失 fork 或部分运行的数据标为完整结果。
        assert entry['forks'] == 3 and entry['warmupIterations'] == 5
        assert entry['measurementIterations'] == 10
        assert entry['warmupTime'] == entry['measurementTime'] == '1 s'
        data = entry['primaryMetric']['rawData']
        assert len(data) == 3 and all(len(fork) == 10 for fork in data)
        assert 'gc.alloc.rate.norm' in entry['secondaryMetrics']
    assert len(actual) == len(expected) and set(actual) == expected


def secondary_value(entry, metric, fork, iteration):
    data = entry['secondaryMetrics'].get(metric, {}).get('rawData', [])
    return data[fork][iteration] if fork < len(data) and iteration < len(data[fork]) else ''


def export(entries, output):
    validate(entries)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.with_suffix('.csv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(('benchmark', 'engine', 'params', 'unit', 'fork', 'iteration', 'value', *SECONDARY))
        for entry in entries:
            for fork, measurements in enumerate(entry['primaryMetric']['rawData']):
                for iteration, value in enumerate(measurements):
                    writer.writerow((entry['benchmark'], entry['params']['engine'],
                                     json.dumps(entry['params'], sort_keys=True),
                                     entry['primaryMetric']['scoreUnit'], fork + 1, iteration + 1, value,
                                     *(secondary_value(entry, metric, fork, iteration) for metric in SECONDARY)))
    lines = ['# JMH 组件测量', '', '12 个场景，3 个 fork、5 轮预热、10 轮测量，每轮 1 秒；360 条正式测量。', '',
             '| 组件 | 参数 | 版本 | 平均值 | 单位 | 分配 B/op |', '|---|---|---|---:|---|---:|']
    for entry in entries:
        params = dict(entry['params']); engine = params.pop('engine')
        primary = entry['primaryMetric']
        allocation = entry['secondaryMetrics']['gc.alloc.rate.norm']['score']
        lines.append(f"| {'.'.join(entry['benchmark'].split('.')[-2:])} | {params} | {engine} | "
                     f"{primary['score']:.3f} | {primary['scoreUnit']} | {allocation:.1f} |")
    lines.extend(['', '平均值和误差以原始 JMH JSON 为准；CSV 保存逐 fork、逐测量轮的原始值。',
                  '这些操作不包含世界生成、区块就绪等待或客户端绘制，不能代替服务器请求比较或推断帧率。'])
    output.with_suffix('.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('input', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    export(json.loads(args.input.read_text(encoding='utf-8')), args.output)
    print('Validated: 12 cases, 360 measurements; exported CSV and summary.')
