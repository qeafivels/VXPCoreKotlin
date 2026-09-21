# VXP-Core v0.8.9 patch manifest

The source delta is stored as a gzip-compressed unified patch:

- file: `VXP-Core-v0.8.9-ARM-JIT-FASTPATH.patch.gz`
- gzip SHA-256: `927fdc678c974b145f69a26fe7cc14fcc89975907c3e126dd3b453f2ca8382d3`
- decompressed patch SHA-256: `7c32ec508755c755375edd69403aaa38b210fd93e17cc0708716291b60c4e164`
- base: v0.8.8.3 THUMB-JIT-FASTPATH
- target: v0.8.9 ARM-JIT-FASTPATH

Reconstruct the reviewable patch with:

```bash
gzip -dc VXP-Core-v0.8.9-ARM-JIT-FASTPATH.patch.gz > VXP-Core-v0.8.9-ARM-JIT-FASTPATH.patch
sha256sum VXP-Core-v0.8.9-ARM-JIT-FASTPATH.patch
```

Expected decompressed SHA-256:

```
7c32ec508755c755375edd69403aaa38b210fd93e17cc0708716291b60c4e164
```

The user-supplied Chetaslua VXP used for performance validation is not included in this repository.
