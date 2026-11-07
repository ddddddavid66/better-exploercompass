"""Assemble two products from the Forge-reobfuscated integration build."""
import hashlib
import io
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path


CORE = "com/david/compass/runtime/"
CREATURE = "com/chaosthedude/explorerscompass/unified/creature/"
EXPLORER = "com/chaosthedude/explorerscompass/"
PRODUCTS = {
    "runtime": ("compass_runtime", "1.0.2", "Compass Runtime (Internal)", "compass-runtime"),
    "creature": ("creaturecompass", "1.0.2", "生物定位指南针 / Creature Compass", "creaturecompass"),
    "explorer": ("explorerscompass", "1.20.1-1.4.0-unified-1.3.4", "探险者指南针 统一 1.3.4", "explorerscompass-unified"),
}
CORE_PATH = "META-INF/jarjar/compass-runtime-1.0.2.jar"


def owner(path):
    for prefix, group in [(CORE, "runtime"), (CREATURE, "creature"), (EXPLORER, "explorer")]:
        if path.startswith(prefix):
            return group
    raise ValueError(f"Unknown class: {path}")


def check_graph(source, jdeps):
    result = subprocess.run([jdeps, "--ignore-missing-deps", "-verbose:class", "-filter:none", str(source)],
                            capture_output=True, check=True)
    edges = re.findall(rb"^\s+([\w.$]+)\s+->\s+([\w.$]+)", result.stdout, re.M)
    if not edges:
        raise RuntimeError("jdeps returned no class edges")
    bad = set()
    for left, right in edges:
        a, b = (part.decode().replace(".", "/") for part in (left, right))
        if a.startswith((CORE, EXPLORER)) and b.startswith((CORE, EXPLORER)):
            src, dst = owner(a), owner(b)
            if src != dst and dst != "runtime":
                bad.add(f"{a} -> {b}")
    if bad:
        raise RuntimeError("Cross-product bytecode dependency:\n" + "\n".join(sorted(bad)))


def metadata(group):
    mod, version, title, _ = PRODUCTS[group]
    result = ('modLoader="javafml"\nloaderVersion="[47,)"\nlicense="CC BY-NC-SA 4.0"\n'
              f'[[mods]]\nmodId="{mod}"\nversion="{version}"\ndisplayName="{title}"\n'
              'authors="ChaosTheDude, David"\n')
    dependencies = [("forge", "[47,)", True), ("minecraft", "[1.20.1,1.20.2)", True)]
    if group != "runtime":
        dependencies.append(("compass_runtime", "[1.0.2]", True))
    if group == "explorer":
        dependencies.append(("explorerscompassfilters", "[0]", False))
    for dep, versions, required in dependencies:
        result += (f'[[dependencies.{mod}]]\nmodId="{dep}"\nmandatory={str(required).lower()}\n'
                   f'versionRange="{versions}"\nordering="NONE"\nside="BOTH"\n')
    return result.encode()


def encode_json(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode()


def archive(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for name, data in sorted(entries.items()):
            output.writestr(name if isinstance(name, zipfile.ZipInfo) else zipfile.ZipInfo(name),
                            data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    return buffer.getvalue()


def is_shared_translation(key):
    return key.startswith(("filters.dimension_", "filters.landing_", "filters.teleport_")) or key in {
        "filters.overworld", "filters.nether", "filters.end"
    }


def partition(source):
    groups = {key: {} for key in PRODUCTS}
    refmap = json.loads(source.read("explorerscompass-unified.refmap.json"))
    packages = {}
    for name in source.namelist():
        if name.endswith(".class"):
            group = owner(name)
            package = name.rsplit("/", 1)[0]
            previous = packages.setdefault(package, group)
            if previous != group:
                raise RuntimeError(f"Split Java package: {package}")
            groups[group][name] = source.read(name)
        elif name == "pack.mcmeta" or name.startswith("META-INF/licenses/") and not name.endswith("/"):
            for group in groups.values():
                group[name] = source.read(name)
        elif name.endswith(".bin"):
            groups["runtime"][name] = source.read(name)
        elif name.startswith(("assets/creaturecompass/", "data/creaturecompass/")) and not name.endswith("/"):
            groups["creature"][name] = source.read(name)
        elif name.startswith("assets/explorerscompass/lang/") and name.endswith(".json"):
            language = json.loads(source.read(name))
            locale = name.rsplit("/", 1)[-1]
            shared = {k: v for k, v in language.items() if is_shared_translation(k)}
            creature = {k: v for k, v in language.items() if k.startswith("item.creaturecompass.")}
            groups["runtime"][f"assets/compass_runtime/lang/{locale}"] = encode_json(shared)
            groups["creature"][f"assets/creaturecompass/lang/{locale}"] = encode_json(creature)
            groups["explorer"][name] = encode_json({k: v for k, v in language.items() if k not in shared and k not in creature})
        elif not name.endswith("/") and name.startswith(("assets/explorerscompass/", "data/", "META-INF/accesstransformer.cfg")):
            groups["explorer"][name] = source.read(name)
    for group, (_, _, _, prefix) in PRODUCTS.items():
        files = groups[group]
        mixin = f"{prefix}.mixins.json"
        config = json.loads(source.read(mixin))
        config["refmap"] = f"{prefix}.refmap.json"
        files[mixin] = encode_json(config)
        selected = {config["package"].replace(".", "/") + "/" + name for name in config["mixins"]}
        if any(name + ".class" not in files for name in selected):
            raise RuntimeError(f"Mixin class assigned to the wrong product: {group}")
        pick = lambda mapping: {k: v for k, v in mapping.items() if k in selected}
        files[config["refmap"]] = encode_json({"mappings": pick(refmap["mappings"]),
                                               "data": {key: pick(value) for key, value in refmap["data"].items()}})
        files["META-INF/mods.toml"] = metadata(group)
        files["META-INF/MANIFEST.MF"] = (f"Manifest-Version: 1.0\r\nAutomatic-Module-Name: com.david.compass.{group}\r\n"
                                        f"MixinConfigs: {mixin}\r\n\r\n").encode()
    return groups


def main(source, destination, jdeps):
    check_graph(source, jdeps)
    with zipfile.ZipFile(source) as original:
        groups = partition(original)
    core = archive(groups["runtime"])
    descriptor = encode_json({"jars": [{"identifier": {"group": "com.david.compass", "artifact": "compass-runtime"},
                                       "version": {"range": "[1.0.2]", "artifactVersion": "1.0.2"},
                                       "path": CORE_PATH, "isObfuscated": True}]})
    destination.mkdir(parents=True, exist_ok=True)
    for group, filename in [("explorer", "ExplorersCompassUnified-1.20.1-forge-1.3.4.jar"),
                            ("creature", "CreatureCompass-1.20.1-forge-1.0.2.jar")]:
        files = groups[group]
        files[CORE_PATH] = core
        files["META-INF/jarjar/metadata.json"] = descriptor
        data = archive(files)
        temporary = destination / (filename + ".tmp")
        temporary.write_bytes(data)
        temporary.replace(destination / filename)
        print(f"{filename}: {len(data)} bytes, SHA-256 {hashlib.sha256(data).hexdigest()}")
    print(f"Identical embedded core: {len(core)} bytes, SHA-256 {hashlib.sha256(core).hexdigest()}")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3])
