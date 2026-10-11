"""汇总真实 tick 专项 CSV；不将后台累计墙钟时间解释为 CPU 时间。"""
import argparse
import csv
import statistics
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("data", type=Path)
    parser.add_argument("--rounds", nargs="+", type=int, default=[30, 31, 32])
    args = parser.parse_args()
    rows = []
    for version in ("1.1.0", "1.1.1"):
        for round_id in args.rounds:
            path = args.data / f"structures-111-{version}-{round_id}.csv"
            with path.open(encoding="utf-8-sig", newline="") as stream:
                for row in csv.DictReader(stream):
                    if row["result"] != "found":
                        raise ValueError(f"Unverified result in {path}: {row}")
                    rows.append(dict(row, version=version, round=str(round_id)))
    targets = sorted({row["target"] for row in rows})
    print("| 目标 | 旧首次 ms | 新首次 ms | 旧重复 ms | 新重复 ms | 新后台累计 ms | 新等待 ms |")
    print("|---|---:|---:|---:|---:|---:|---:|")
    for target in targets:
        selected = [row for row in rows if row["target"] == target and row["async"] == "true"]
        # 每轮独立世界：首次查询与第一次重复查询分别取三轮中位数；第二次重复保留在原始数据。
        def median(version, repeat, field="elapsed_ms"):
            values = [float(row[field]) for row in selected
                      if row["version"] == version and row["repeat"] == repeat]
            if len(values) != len(args.rounds):
                raise ValueError(f"Missing rounds: {target}/{version}/{repeat}")
            return statistics.median(values)
        positions = {(row["x"], row["y"], row["z"]) for row in selected}
        if len(positions) != 1:
            raise ValueError(f"Result coordinates differ: {target}: {positions}")
        new_modes = {row["mode"] for row in selected if row["version"] == "1.1.1"}
        if new_modes != {"async"}:
            raise ValueError(f"Unexpected compatibility path: {target}: {new_modes}")
        values = [median("1.1.0", "0"), median("1.1.1", "0"),
                  median("1.1.0", "1"), median("1.1.1", "1"),
                  median("1.1.1", "0", "worker_ms"), median("1.1.1", "0", "waiting_ms")]
        print("| " + target + " | " + " | ".join(f"{value:.3f}" for value in values) + " |")
    print("\n首次查询依次执行于各轮独立新世界，目标之间可能共享已生成区块；严格独立冷区块 benchmark 未运行。")
    print("后台累计是各分片执行区间之和，包含调度/GC影响，可超过端到端耗时；不等于物理CPU时间。")


if __name__ == "__main__":
    main()
