# Small ASCII class scanning

Focused controls for [opportunity 8 in #1185](https://github.com/safere/safere/issues/1185).
The regular corpus supplies the mixed-Unicode size grid, pair/triple scans, early
hits, dense iteration, and sparse iteration. This investigation adds ASCII misses
and late hits, nearby nonmembers, alternate narrow and wide triples, tiny setup,
capture-bearing and suffix-verification callers, repeated finds, and replacement.
These exercise filter acceptance, rejection, and fallback without changing the
operation or result consumption of the motivating workload.

The `dfaTriple` workloads match a DFA loop with three ASCII escapes. They cover
long and short multibyte runs, frequent escapes, and alternating ASCII and
multibyte characters. The gap sweep places 6 to 192 `é` characters before
each of 4096 `:1` escapes. [Issue #1264](https://github.com/safere/safere/issues/1264)

The `dfaTripleUnmasked` controls measure the long run and 12-character gap with
three escape bytes that differ by at least two bits per pair. The optimized
scanner usually finds the escape at the first ASCII byte and returns from the
single-byte check.

Use the shared runner with the default provider, for example:

```bash
./run-java-benchmarks.sh --fastbuild --provider default \
  --investigation small-ascii-class CrossEngineBenchmark.run -- \
  -p crossEngineTrial=AsciiClassScan.nearMiss.262144@safere-utf8
```

Run each selected trial on both revisions with identical declarations. The
patterns and pre-existing inputs are prepared outside measurement; the shared
runner validates and consumes each operation's result. Sizes name UTF-16 units;
inspect the resolved manifest for UTF-8 byte sizes. These are diagnostic controls,
not additions to the regular reporting corpus. Keep timing results and profiles
local.
