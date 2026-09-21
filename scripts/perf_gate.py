#!/usr/bin/env python3
import argparse, hashlib, json, os, shlex, statistics, subprocess, sys
from pathlib import Path

def load_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def run_trial(cmd_template, title_path, seconds, index):
    cmd = cmd_template.format(
        vxp=shlex.quote(str(title_path)),
        seconds=seconds,
        trial=index,
    )
    proc = subprocess.run(cmd, shell=True, text=True, capture_output=True)
    if proc.returncode != 0:
        raise RuntimeError(f"trial {index} failed ({proc.returncode}):\n{proc.stdout}\n{proc.stderr}")
    lines = [x.strip() for x in proc.stdout.splitlines() if x.strip()]
    for line in reversed(lines):
        try:
            obj = json.loads(line)
            if isinstance(obj, dict) and "frames" in obj:
                return obj
        except json.JSONDecodeError:
            pass
    raise RuntimeError(f"trial {index} did not emit a JSON metrics object")

def median(values):
    return statistics.median(values) if values else 0.0

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--profile", required=True)
    ap.add_argument("--runner-cmd", required=True,
                    help="Command template. Use {vxp}, {seconds}, {trial}. Last JSON line must contain frames; mips/instructions are optional.")
    ap.add_argument("--trials", type=int, default=7)
    ap.add_argument("--allowed-regression-pct", type=float, default=3.0)
    ap.add_argument("--output", required=True)
    args = ap.parse_args()

    profile = load_json(args.profile)
    title = Path(profile["file"])
    if not title.is_file():
        raise SystemExit(f"missing benchmark title: {title}")
    actual = sha256(title)
    if actual.lower() != profile["sha256"].lower():
        raise SystemExit(f"SHA-256 mismatch: expected {profile['sha256']}, got {actual}")

    warmups = int(profile["workload"].get("warmup_trials", 2))
    seconds = float(profile["workload"].get("measurement_seconds", 1.8))
    for i in range(warmups):
        run_trial(args.runner_cmd, title, seconds, -(i + 1))

    results = [run_trial(args.runner_cmd, title, seconds, i) for i in range(args.trials)]
    fps = [float(r.get("fps", float(r["frames"]) / seconds)) for r in results]
    mips = [float(r["mips"]) for r in results if "mips" in r]
    insn = [int(r["instructions"]) for r in results if "instructions" in r]

    baseline_frames = float(profile["baseline"]["frames"])
    baseline_fps = baseline_frames / seconds
    med_fps = median(fps)
    floor = baseline_fps * (1.0 - args.allowed_regression_pct / 100.0)

    report = {
        "profile": profile["name"],
        "sha256": actual,
        "trials": results,
        "summary": {
            "median_fps": med_fps,
            "p95_fps": sorted(fps)[max(0, min(len(fps)-1, int(round(0.95 * (len(fps)-1)))))],
            "median_mips": median(mips) if mips else None,
            "median_instructions": int(median(insn)) if insn else None,
            "baseline_fps": baseline_fps,
            "allowed_regression_pct": args.allowed_regression_pct,
            "pass_floor_fps": floor,
            "pass": med_fps >= floor
        }
    }
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    Path(args.output).write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report["summary"], sort_keys=True))
    if not report["summary"]["pass"]:
        raise SystemExit(2)

if __name__ == "__main__":
    main()
