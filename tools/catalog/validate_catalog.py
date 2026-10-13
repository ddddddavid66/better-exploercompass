"""校验v2生物目录、来源引用和运行时注册表分区；不修改输入文件。"""
from pathlib import Path
import argparse, collections, json, re

ID = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
FIELDS = {"schema_version", "entity", "name", "english", "aliases", "dimensions", "biomes", "note", "appearances"}
RULE_FIELDS = {"kind", "dimension", "biomes", "description", "verification", "sources"}
KINDS = {"natural_spawn", "structure", "spawner", "summoned", "conversion", "breeding", "event", "unknown"}

def load(path):
    return json.loads(Path(path).read_text("utf-8-sig"))

def validate(rows, snapshot=None, evidence=None, audit=None):
    errors = []
    def check(condition, message):
        if not condition: errors.append(message)
    def ids(values, label):
        check(isinstance(values, list), label + " 必须为数组")
        if not isinstance(values, list): return
        check(all(isinstance(v, str) and ID.fullmatch(v) for v in values), label + " 存在非法ID")
        check(len(set(map(str, values))) == len(values), label + " 存在重复")
    check(isinstance(rows, list), "顶层必须为数组")
    if not isinstance(rows, list): return errors
    seen = set()
    registered = {e["entity"] for e in snapshot["entities"]} if snapshot else None
    sources = evidence.get("sources", {}) if evidence else None
    for i, row in enumerate(rows):
        label = str(row.get("entity", i))
        check(set(row) == FIELDS, label + " 字段不符合v2")
        check(row.get("schema_version") == 2, label + " 版本不是2")
        check(bool(ID.fullmatch(label)), label + " 不是完整注册ID")
        check(label not in seen, label + " 重复实体")
        seen.add(label)
        if registered is not None: check(label in registered, label + " 当前未注册")
        check(isinstance(row.get("name"), str) and bool(row["name"].strip()), label + " 缺名称")
        for key in ("english", "note"): check(isinstance(row.get(key), str), label + " 缺字符串 " + key)
        aliases = row.get("aliases", [])
        check(isinstance(aliases, list) and all(isinstance(a, str) for a in aliases), label + " 别名格式错误")
        ids(row.get("dimensions"), label + ".dimensions"); ids(row.get("biomes"), label + ".biomes")
        appearances = row.get("appearances", [])
        check(isinstance(appearances, list), label + " appearances必须为数组")
        for a in appearances:
            check(set(a) == RULE_FIELDS, label + " appearance字段错误")
            check(a.get("kind") in KINDS, label + " 出现方式错误")
            check(a.get("verification") in {"verified", "partial", "unverified"}, label + " 核实状态错误")
            dim = a.get("dimension")
            check(dim is None or isinstance(dim, str) and ID.fullmatch(dim), label + " 维度错误")
            ids(a.get("biomes"), label + ".appearance.biomes")
            check(isinstance(a.get("description"), str), label + " 缺描述")
            refs = a.get("sources", [])
            check(isinstance(refs, list) and all(isinstance(s, str) and s for s in refs), label + " 来源格式错误")
            if a.get("verification") == "verified": check(bool(refs), label + " 已核实但无来源")
            if a.get("verification") == "unverified": check(dim is None and not a.get("biomes"), label + " 未核实内容填写了范围")
            if sources is not None:
                for source in refs: check(source in sources, label + " 来源不存在：" + source)
    if snapshot and audit:
        entries = audit["entities"]
        keys = [e["entity"] for e in entries]
        check(len(keys) == len(set(keys)), "审计重复实体")
        check(set(keys) == registered, "审计没有完整分区注册全集")
        check(all(e.get("classification") in {"creature", "non_creature", "pending"} for e in entries), "审计分类非法")
        expected = {e["entity"] for e in entries if e["classification"] == "creature"}
        check(seen == expected, "生物目录与审计中的可收录集合不同")
    return errors

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("catalog"); parser.add_argument("--registry"); parser.add_argument("--evidence"); parser.add_argument("--audit")
    args = parser.parse_args()
    rows = load(args.catalog)
    errors = validate(rows, load(args.registry) if args.registry else None, load(args.evidence) if args.evidence else None, load(args.audit) if args.audit else None)
    print(json.dumps({"records": len(rows), "errors": errors, "appearance_statuses": dict(collections.Counter(a["verification"] for r in rows for a in r["appearances"]))}, ensure_ascii=False, indent=2))
    raise SystemExit(bool(errors))
