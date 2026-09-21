#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SOURCE_ROOT="${PERF_SOURCE_ROOT:-$PWD}"
BENCHMARK_SOURCE="${PERF_BENCHMARK_SOURCE:-$SOURCE_ROOT/tests/jvm/FixedFramePerformanceBenchmark.kt}"
BASELINE_JAR="${1:?usage: run_autotune_performance_gate.sh <baseline.jar> <file.vxp> <candidate.jar> [candidate2.jar ...]}"
VXP_FILE="${2:?VXP file required}"
shift 2
if [[ "$#" -lt 1 ]]; then
  echo "at least one candidate jar is required" >&2
  exit 2
fi
CANDIDATES=("$@")

ROUNDS="${PERF_AUTOTUNE_ROUNDS:-5}"          # 0 = run until interrupted
TRIALS="${PERF_AUTOTUNE_TRIALS:-3}"
FRAMES="${PERF_FRAMES:-40}"
CALLBACK_BUDGET="${PERF_CALLBACK_BUDGET:-20000000}"
MIN_SPEEDUP_PCT="${PERF_AUTOTUNE_MIN_SPEEDUP_PCT:-1.0}"
MIN_WIN_RATIO="${PERF_AUTOTUNE_MIN_WIN_RATIO:-0.60}"
MAX_MAD_PCT="${PERF_AUTOTUNE_MAX_MAD_PCT:-2.50}"
MIN_ROUNDS="${PERF_AUTOTUNE_MIN_ROUNDS:-3}"
JVM_XMS="${PERF_JVM_XMS:-512m}"
JVM_XMX="${PERF_JVM_XMX:-512m}"
SLEEP_SECONDS="${PERF_AUTOTUNE_SLEEP_SECONDS:-0}"
EXPECTED_VXP_SHA256="${PERF_EXPECT_VXP_SHA256:-9e7ce08d33f5f6caccf446a1ca6db4ee91c39eacd38b05f0fbf234b9cb041b85}"

if [[ "$ROUNDS" -lt 0 || "$TRIALS" -lt 3 || "$MIN_ROUNDS" -lt 1 ]]; then
  echo "invalid rounds/trials/min-rounds" >&2
  exit 2
fi
for file in "$BASELINE_JAR" "$VXP_FILE" "${CANDIDATES[@]}"; do
  [[ -f "$file" ]] || { echo "missing file: $file" >&2; exit 2; }
done

if [[ "$EXPECTED_VXP_SHA256" != "any" ]]; then
  actual_sha="$(sha256sum "$VXP_FILE" | awk '{print $1}')"
  if [[ "$actual_sha" != "$EXPECTED_VXP_SHA256" ]]; then
    echo "FAIL target VXP SHA-256 mismatch" >&2
    echo "expected=$EXPECTED_VXP_SHA256" >&2
    echo "actual=$actual_sha" >&2
    exit 3
  fi
fi

BUILD="${PERF_AUTOTUNE_BUILD_DIR:-$PWD/build/autotune-perf-gate}"
mkdir -p "$BUILD"
BENCH_JAR="$BUILD/fixed-frame-performance.jar"
SCORES="$BUILD/rounds.tsv"
SCORE_SCRIPT="$SCRIPT_DIR/score_performance_rounds.py"

printf 'round\tcandidate\tbaseline_mips\tcandidate_mips\tspeedup_pct\tsignature\n' > "$SCORES"

[[ -f "$BENCHMARK_SOURCE" ]] || { echo "missing benchmark source: $BENCHMARK_SOURCE" >&2; echo "set PERF_BENCHMARK_SOURCE or PERF_SOURCE_ROOT" >&2; exit 2; }

kotlinc -language-version 2.0 -Xskip-prerelease-check -Xallow-unstable-dependencies \
  -cp "$BASELINE_JAR" "$BENCHMARK_SOURCE" \
  -include-runtime -d "$BENCH_JAR"

candidate_id() {
  local jar="$1" base hash
  base="$(basename "$jar")"
  hash="$(sha256sum "$jar" | awk '{print substr($1,1,12)}')"
  printf '%s@%s' "$base" "$hash"
}

run_one() {
  local jar="$1" out="$2"
  java -Xms"$JVM_XMS" -Xmx"$JVM_XMX" -cp "$jar:$BENCH_JAR" \
    vxpcore.FixedFramePerformanceBenchmarkKt \
    "$VXP_FILE" "$FRAMES" "$TRIALS" "$CALLBACK_BUDGET" | tee "$out"
}

parse_result() {
  python3 - "$1" <<'PY'
import re, sys
lines=[x for x in open(sys.argv[1], encoding='utf-8') if x.startswith('RESULT ')]
if len(lines) != 1:
    raise SystemExit(f'FAIL expected exactly one RESULT line in {sys.argv[1]}')
line=lines[0]
def get(k):
    m=re.search(rf'\b{k}=([^\s]+)', line)
    if not m: raise SystemExit(f'FAIL missing {k} in {sys.argv[1]}')
    return m.group(1)
print(get('medianWarmMips') + '\t' + get('signature'))
PY
}

scoreboard() {
  python3 "$SCORE_SCRIPT" "$SCORES" \
    --min-speedup "$MIN_SPEEDUP_PCT" \
    --min-win-ratio "$MIN_WIN_RATIO" \
    --max-mad "$MAX_MAD_PCT" \
    --min-rounds "$MIN_ROUNDS" || true
}

on_interrupt() {
  echo
  echo "=== AUTOTUNE INTERRUPTED: rolling scoreboard ==="
  scoreboard
  echo "results=$SCORES"
  exit 130
}
trap on_interrupt INT TERM

round=1
while :; do
  if [[ "$ROUNDS" -ne 0 && "$round" -gt "$ROUNDS" ]]; then
    break
  fi
  echo "=== AUTOTUNE ROUND $round ==="

  for index in "${!CANDIDATES[@]}"; do
    candidate="${CANDIDATES[$index]}"
    id="$(candidate_id "$candidate")"
    safe_id="$(printf '%s' "$id" | tr -c 'A-Za-z0-9._@-' '_')"
    base_out="$BUILD/round_${round}_${safe_id}_baseline.txt"
    cand_out="$BUILD/round_${round}_${safe_id}_candidate.txt"

    # Rotate order by round and candidate index to reduce systematic thermal/JIT bias.
    if (( (round + index) % 2 )); then
      run_one "$BASELINE_JAR" "$base_out"
      run_one "$candidate" "$cand_out"
    else
      run_one "$candidate" "$cand_out"
      run_one "$BASELINE_JAR" "$base_out"
    fi

    IFS=$'\t' read -r base_mips base_sig < <(parse_result "$base_out")
    IFS=$'\t' read -r cand_mips cand_sig < <(parse_result "$cand_out")
    if [[ "$base_sig" != "$cand_sig" ]]; then
      echo "FAIL semantic signature changed for $id: $base_sig != $cand_sig" >&2
      exit 4
    fi

    speedup="$(python3 - "$base_mips" "$cand_mips" <<'PY'
import sys
b=float(sys.argv[1]); c=float(sys.argv[2])
print(f'{(c/b-1.0)*100.0:.9f}')
PY
)"
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
      "$round" "$id" "$base_mips" "$cand_mips" "$speedup" "$base_sig" >> "$SCORES"
    printf 'ROUND_GATE round=%d candidate=%s baselineMips=%.3f candidateMips=%.3f speedupPct=%.3f signature=%s\n' \
      "$round" "$id" "$base_mips" "$cand_mips" "$speedup" "$base_sig"
  done

  scoreboard
  if [[ "$SLEEP_SECONDS" != "0" ]]; then sleep "$SLEEP_SECONDS"; fi
  round=$((round + 1))
done

echo "=== AUTOTUNE FINAL GATE ==="
python3 "$SCORE_SCRIPT" "$SCORES" \
  --min-speedup "$MIN_SPEEDUP_PCT" \
  --min-win-ratio "$MIN_WIN_RATIO" \
  --max-mad "$MAX_MAD_PCT" \
  --min-rounds "$MIN_ROUNDS" \
  --require-pass

echo "PASS at least one candidate met semantic + repeated-performance acceptance gates"
echo "results=$SCORES"
