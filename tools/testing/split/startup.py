"""Run the installed candidate jars, not the development source set."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import time
from pathlib import Path


def client_arguments(original, instance):
    replace = {"--gameDir": str(instance), "--username": "CompassSplitProbe",
               "--uuid": "00000000-0000-0000-0000-000000001300", "--accessToken": "0",
               "--clientId": "0", "--xuid": "0", "--userProperties": "{}"}
    removed = {"--quickPlaySingleplayer", "--quickPlayMultiplayer", "--quickPlayRealms", "--quickPlayPath"}
    args = iter(original)
    output = []
    for arg in args:
        if arg in removed:
            next(args, None)
        elif arg in replace:
            next(args, None)
            output += [arg, replace[arg]]
        else:
            output.append(arg)
    return output


def write_arguments(path, values):
    quote = lambda arg: '"' + arg.replace("\\", "/").replace('"', '\\"') + '"'
    path.write_text("\n".join(map(quote, values)) + "\n", encoding="gbk" if os.name == "nt" else "utf-8")


def run(launcher, candidates, probe, folder, mode, timeout):
    instance = folder / mode
    instance.mkdir()
    mods = instance / "mods"
    mods.mkdir()
    jars = [probe]
    if mode in ("explorer", "both"):
        jars.append(candidates / "ExplorersCompassUnified-1.20.1-forge-1.3.0.jar")
    if mode in ("creature", "both"):
        jars.append(candidates / "CreatureCompass-1.20.1-forge-1.0.0.jar")
    for jar in jars:
        shutil.copy2(jar, mods / jar.name)
    (instance / "options.txt").write_text("onboardAccessibility:false\nfullscreen:false\n", encoding="utf-8")
    receipt = instance / "split-startup.txt"
    args = instance / "launch.args"
    write_arguments(args, [f"-Dcompass.split.expected={mode}", "-Dcompass.split.report=split-startup.txt"]
                    + client_arguments(launcher["args"], instance))
    start = time.monotonic()
    with (instance / "console.log").open("wb") as log:
        process = subprocess.Popen([launcher["java"], "@" + str(args)], cwd=instance, stdout=log,
                                   stderr=subprocess.STDOUT, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        print(f"START {mode} pid={process.pid}", flush=True)
        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            process.terminate()
            process.wait(timeout=20)
    text = receipt.read_text(encoding="utf-8") if receipt.exists() else ""
    result = {"mode": mode, "side": "client", "pass": process.returncode == 0 and text.startswith("PASS\n"),
              "receipt": text, "exit_code": process.returncode, "wall_seconds": round(time.monotonic() - start, 2),
              "sha256": {jar.name: hashlib.sha256(jar.read_bytes()).hexdigest() for jar in jars}}
    (instance / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result), flush=True)
    return result["pass"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("launcher", type=Path)
    parser.add_argument("candidates", type=Path)
    parser.add_argument("probe", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("modes", nargs="+", choices=["explorer", "creature", "both"])
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    launcher = json.loads(args.launcher.read_text(encoding="utf-8"))
    folder = args.output.resolve()
    folder.mkdir(parents=True, exist_ok=False)
    success = [run(launcher, args.candidates.resolve(), args.probe.resolve(), folder, mode, args.timeout)
               for mode in args.modes]
    return 0 if all(success) else 1


if __name__ == "__main__":
    raise SystemExit(main())
