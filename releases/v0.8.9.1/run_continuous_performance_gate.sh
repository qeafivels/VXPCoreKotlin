#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASELINE_JAR="${1:?baseline jar required}"
CANDIDATE_JAR="${2:?candidate jar required}"
VXP_FILE="${3:?VXP file required}"

ROUNDS="${PERF_CONTINUOUS_ROUNDS:-3}"
TRIALS="${PERF_CONTINUOUS_TRIALS:-3}"
FRAMES="${PERF_FRAMES:-40}"
CALLBACK_BUDGET="${PERF_CALLBACK_BUDGET:-20000000}"
MIN_MEDIAN_SPEEDUP_PCT="${PERF_CONTINUOUS_MIN_SPEEDUP_PCT:-1.0}"
JVM_XMS="${PERF_JVM_XMS:-512m}"
JVM_XMX="${PERF_JVM_XMX:-512m}"

if [[ "$ROUNDS" -lt 0 || "$TRIALS" -lt 3 ]]; then
  echo "invalid PERF_CONTINUOUS_ROUNDS/TRIALS" >&2
  exit 2
fi

BUILD="$ROOT/build/continuous-perf-gate"
mkdir -p "$BUILD"
BENCH_JAR="$BUILD/fixed-frame-performance.jar"

kotlinc -language-version 2.0 -Xskip-prerelease-check -Xallow-unstable-dependencies \
  -cp "$CANDIDATE_JAR" "$ROOT/tests/jvm/FixedFramePerformanceBenchmark.kt" \
  -include-runtime -d "$BENCH_JAR"

run_one() {
  local jar="$1" out="$2"
  java -Xms"$JVM_XMS" -Xmx"$JVM_XMX" -cp "$jar:$BENCH_JAR" \
    vxpcore.FixedFramePerformanceBenchmarkKt \
    "$VXP_FILE" "$FRAMES" "$TRIALS" "$CALLBACK_BUDGET" | tee "$out"
}

round=1
while :; do
  if [[ "$ROUNDS" -ne 0 && "$round" -gt "$ROUNDS" ]]; then break; fi
  echo "=== PERF ROUND $round ==="
  base="$BUILD/round_${round}_baseline.txt"
  cand="$BUILD/round_${round}_candidate.txt"
  if (( round % 2 )); then
    run_one "$BASELINE_JAR" "$base"
    run_one "$CANDIDATE_JAR" "$cand"
  else
    run_one "$CANDIDATE_JAR" "$cand"
    run_one "$BASELINE_JAR" "$base"
  fi

  python3 - "$base" "$cand" <<'PY'
import re, sys

def parse(path):
    lines=[x for x in open(path,encoding='utf-8') if x.startswith('RESULT ')]
    if len(lines)!=1: raise SystemExit(f'FAIL missing RESULT in {path}')
    line=lines[0]
    def get(k):
        m=re.search(rf'\b{k}=([^\s]+)', line)
        if not m: raise SystemExit(f'FAIL missing {k} in {path}')
        return m.group(1)
    return float(get('medianWarmMips')), get('signature')
b,sb=parse(sys.argv[1]); c,sc=parse(sys.argv[2])
if sb!=sc: raise SystemExit(f'FAIL semantic signature changed: {sb} != {sc}')
print(f'ROUND_GATE baselineMips={b:.3f} candidateMips={c:.3f} speedupPct={(c/b-1)*100:.2f} signature={sb}')
PY

  if [[ "$ROUNDS" -eq 0 ]]; then
    round=$((round+1))
    continue
  fi
  round=$((round+1))
done

python3 - "$BUILD" "$ROUNDS" "$MIN_MEDIAN_SPEEDUP_PCT" <<'PY'
import pathlib,re,statistics,sys
root=pathlib.Path(sys.argv[1]); rounds=int(sys.argv[2]); minimum=float(sys.argv[3])
ratios=[]; signatures=[]
def parse(path):
    line=next(x for x in path.read_text().splitlines() if x.startswith('RESULT '))
    mips=float(re.search(r'medianWarmMips=([0-9.]+)',line).group(1))
    sig=re.search(r'signature=([^\s]+)',line).group(1)
    return mips,sig
for i in range(1,rounds+1):
    b,sb=parse(root/f'round_{i}_baseline.txt'); c,sc=parse(root/f'round_{i}_candidate.txt')
    if sb!=sc: raise SystemExit(f'FAIL round {i} signature changed')
    signatures.append(sb); ratios.append((c/b-1.0)*100.0)
if len(set(signatures))!=1: raise SystemExit(f'FAIL signature drift across rounds: {signatures}')
median=statistics.median(ratios)
print('CONTINUOUS_PERF_GATE rounds=%d medianRoundSpeedupPct=%.2f minPct=%.2f signature=%s' % (rounds,median,minimum,signatures[0]))
if median + 1e-9 < minimum: raise SystemExit('FAIL candidate did not meet median repeated speedup')
print('PASS repeated alternating-order semantic + performance gate')
PY
