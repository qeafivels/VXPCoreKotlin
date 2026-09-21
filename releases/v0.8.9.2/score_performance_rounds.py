#!/usr/bin/env python3
import argparse
import csv
import json
import math
import statistics
from collections import defaultdict
from pathlib import Path


def percentile_nearest_rank(values, p):
    if not values:
        raise ValueError("empty sample")
    ordered = sorted(values)
    rank = max(1, math.ceil(p * len(ordered)))
    return ordered[min(rank - 1, len(ordered) - 1)]


def summarize(rows, min_speedup, min_win_ratio, max_mad, min_rounds):
    grouped = defaultdict(list)
    signatures = defaultdict(set)
    for row in rows:
        name = row["candidate"]
        grouped[name].append(float(row["speedup_pct"]))
        signatures[name].add(row["signature"])

    summaries = []
    for name, values in grouped.items():
        med = statistics.median(values)
        mad = statistics.median(abs(v - med) for v in values)
        wins = sum(v > 0.0 for v in values)
        win_ratio = wins / len(values)
        p10 = percentile_nearest_rank(values, 0.10)
        stable_signature = len(signatures[name]) == 1
        enough_rounds = len(values) >= min_rounds
        passed = (
            stable_signature
            and enough_rounds
            and med >= min_speedup
            and win_ratio >= min_win_ratio
            and mad <= max_mad
        )
        summaries.append({
            "candidate": name,
            "rounds": len(values),
            "median_speedup_pct": med,
            "mad_pct": mad,
            "p10_speedup_pct": p10,
            "win_ratio": win_ratio,
            "stable_signature": stable_signature,
            "signature": next(iter(signatures[name])) if stable_signature else "DRIFT",
            "pass": passed,
        })

    summaries.sort(key=lambda x: (
        x["pass"],
        x["median_speedup_pct"],
        x["win_ratio"],
        -x["mad_pct"],
    ), reverse=True)
    return summaries


def main():
    ap = argparse.ArgumentParser(description="Score VXP-Core repeated performance rounds")
    ap.add_argument("tsv", type=Path)
    ap.add_argument("--min-speedup", type=float, default=1.0)
    ap.add_argument("--min-win-ratio", type=float, default=0.60)
    ap.add_argument("--max-mad", type=float, default=2.50)
    ap.add_argument("--min-rounds", type=int, default=3)
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--require-pass", action="store_true")
    args = ap.parse_args()

    if not args.tsv.is_file():
        raise SystemExit(f"missing score file: {args.tsv}")

    with args.tsv.open(newline="", encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh, delimiter="\t"))
    if not rows:
        raise SystemExit("no performance rounds recorded")

    required = {"round", "candidate", "baseline_mips", "candidate_mips", "speedup_pct", "signature"}
    if not required.issubset(rows[0]):
        raise SystemExit(f"invalid TSV columns; expected {sorted(required)}")

    summaries = summarize(
        rows,
        min_speedup=args.min_speedup,
        min_win_ratio=args.min_win_ratio,
        max_mad=args.max_mad,
        min_rounds=args.min_rounds,
    )

    if args.json:
        print(json.dumps({"candidates": summaries}, indent=2, sort_keys=True))
    else:
        print("AUTOTUNE_SCOREBOARD")
        for rank, item in enumerate(summaries, 1):
            status = "PASS" if item["pass"] else "HOLD"
            print(
                f"{rank:02d} {status} candidate={item['candidate']} rounds={item['rounds']} "
                f"medianSpeedupPct={item['median_speedup_pct']:.3f} "
                f"madPct={item['mad_pct']:.3f} p10Pct={item['p10_speedup_pct']:.3f} "
                f"winRatio={item['win_ratio']:.3f} signature={item['signature']}"
            )
        winner = next((x for x in summaries if x["pass"]), None)
        if winner:
            print(
                f"AUTOTUNE_WINNER candidate={winner['candidate']} "
                f"medianSpeedupPct={winner['median_speedup_pct']:.3f} "
                f"winRatio={winner['win_ratio']:.3f} madPct={winner['mad_pct']:.3f}"
            )
        else:
            print("AUTOTUNE_WINNER none")

    if args.require_pass and not any(x["pass"] for x in summaries):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
