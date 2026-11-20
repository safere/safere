# NFA state-storage spike

This investigation compares the checkpoint for issue #525 with consumer admission
filtering, shared capture records, and flat capture storage. It reuses the ordinary
cross-engine runner and existing declarations for the surrounding control panel.
No new matching behavior or benchmark adapter is introduced.

Additional declarations vary capture count, input length, and capture-write
frequency. Rare-write patterns capture a literal suffix after a greedy wildcard;
frequent-write patterns capture inside an ambiguous repeated alternative. The
ambiguity prevents deterministic OnePass capture extraction on the long controls.
Short cases retain normal engine selection. Every inner capture contains `a`, so
the consumed group-length sum equals the declared capture count. Compilation,
materialization, and expected-result validation occur outside measurement.

New capture-text cases use String input because the generic UTF-8 adapter lacks
text access. Existing UTF-8 replacement and grapheme controls remain. Ordinary
Unicode consumers are covered by the companion `nfa-state-spike-unicode` corpus.

Other engines are explicitly excluded from the new cases: this experiment does
not establish equivalent quantified-capture semantics or stack-safe execution in
those implementations. Existing controls retain their original exclusions.

Run a selected case using the normal settings and machine reservation:

```bash
./run-java-benchmarks.sh --fastbuild --investigation nfa-state-spike \
  CrossEngineBenchmark.run -- \
  -p crossEngineTrial=NfaStateSpikeBenchmark.frequent.16.32768@safere-string \
  -prof gc
```

Focused measurements, source snapshots, profiles, and comparison tables remain
local under ignored `benchmark-results/issue-525-spike/`. This is an experiment,
not a corpus-wide benchmark report or a proposed release configuration.
