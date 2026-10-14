"""把已核对研究目录压成发布资源。事实和schema不变，来源编号缩短并独立保存映射。"""
from pathlib import Path
import argparse, hashlib, json
from validate_catalog import load, validate

def prepare(input_path, output_path, evidence_path, mapping_path):
    rows = load(input_path)
    evidence = load(evidence_path)
    errors = validate(rows, evidence=evidence)
    if errors:
        raise ValueError("目录未通过校验：" + "; ".join(errors[:10]))
    references = sorted({s for row in rows for rule in row["appearances"] for s in rule["sources"]})
    short = {source: "s" + format(i, "x") for i, source in enumerate(references)}
    # 长哈希在每条出现规则中重复会放大jar，使用确定性短编号；完整证据留在交付包。
    mapping = {"research_sha256": hashlib.sha256(Path(input_path).read_bytes()).hexdigest(),
               "evidence_sha256": hashlib.sha256(Path(evidence_path).read_bytes()).hexdigest(),
               "sources": {short[s]: {"research_source": s} for s in references}}
    for row in rows:
        for rule in row["appearances"]:
            rule["sources"] = [short[s] for s in rule["sources"]]
    errors = validate(rows, evidence=mapping)
    if errors: raise ValueError(errors)
    for destination, text in [(output_path, json.dumps(rows, ensure_ascii=False, separators=(",", ":"))),
                              (mapping_path, json.dumps(mapping, ensure_ascii=False, indent=2))]:
        p = Path(destination); p.parent.mkdir(parents=True, exist_ok=True)
        temp = p.with_suffix(p.suffix + ".tmp")
        temp.write_text(text + "\n", "utf-8"); temp.replace(p)
    print(json.dumps({"records": len(rows), "source_ids": len(references), "bytes": Path(output_path).stat().st_size}, ensure_ascii=False))

if __name__ == "__main__":
    p = argparse.ArgumentParser(); p.add_argument("input"); p.add_argument("output"); p.add_argument("--evidence", required=True); p.add_argument("--mapping", required=True)
    a = p.parse_args(); prepare(a.input, a.output, a.evidence, a.mapping)
