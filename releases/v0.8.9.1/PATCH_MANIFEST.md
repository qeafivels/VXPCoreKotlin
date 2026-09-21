# VXP-Core v0.8.9.1 patch manifest

- base: v0.8.9 ARM-JIT-FASTPATH
- target: v0.8.9.1 ARM-DECODE-TUNE
- branch: `chatgpt/6aaf544d-f54c-83ec-827c-110a4da7136f`
- parent commit: `b35932955bf28f7029b4a688d04b96b535525967`
- validation target SHA-256: `9e7ce08d33f5f6caccf446a1ca6db4ee91c39eacd38b05f0fbf234b9cb041b85`
- source delta: `VXP-Core-v0.8.9.1-ARM-DECODE-TUNE.patch.gz`
- patch gzip SHA-256: `8c8c082866f6fb6c71f2c0870cbcd773ca22a3c3afaf4ce48bf5d8a954801b03`

Accepted runtime changes:
- decode cache capacity 512 -> 1024 slots per mode;
- dedicated ARM SUB cached fast path with reference/trace parity;
- repeated alternating-order performance gate.

Rejected after measurement:
- 2048 decode-cache slots;
- packed opcode/kind/write metadata;
- cached condition metadata;
- BX/BLX-register specialization.

The user-supplied VXP test binary, prebuilt JAR and full source ZIP are not committed into Git history. Their checksums are recorded in `VXP-Core-v0.8.9.1-SHA256.txt`.
