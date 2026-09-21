# v0.8.9.2 performance-tooling manifest

- base runtime: v0.8.9.1 ARM-DECODE-TUNE
- scope: continuous autotune / robust candidate ranking
- runtime semantic delta: none in this commit
- target branch: chatgpt/6aaf544d-f54c-83ec-827c-110a4da7136f
- default target VXP SHA-256: 9e7ce08d33f5f6caccf446a1ca6db4ee91c39eacd38b05f0fbf234b9cb041b85
- user VXP included in repository: no

Files:

- run_autotune_performance_gate.sh — continuous multi-candidate runner with semantic lock and rolling scoreboard.
- score_performance_rounds.py — robust median/MAD/win-ratio scorer.
- PERFORMANCE_AUTOTUNE_v0.8.9.2.md — design, thresholds, usage and next candidate matrix.

A runtime optimization must not be promoted above v0.8.9.1 until it passes this gate plus the existing regression, malformed/unsupported, fuzz-derived, Kotlin-only and clean-room gates.
