# NFA initialization policy

This experiment checks issue #525's flattened NFA against the initialization
policy in PR #1204. Ordinary paths reuse the flat program already prepared by
`Pattern`; NFA selection never constructs another program. Literal search and
DFA-disabled paths retain graph execution when no flat program is ready.
Progress and grapheme programs retain graph semantics even when a DFA program
exists. `NfaProgramPreparationTest` checks these gates and concurrent publication.

Definitions and input recipes live in `benchmark-data.json`. The ordinary generic
runner measures warmed compilation, compilation plus the first Boolean search,
inner-group text access or `$1` replacement, and steady replacement. Short word
captures exercise OnePass/BitState alternatives; long ambiguous fruit captures
reach the NFA; nullable and grapheme cases cover mixed-feature fallbacks.
Literal whole-input operations cover an API that bypasses the default literal
search runner. Rejection controls avoid inner capture extraction.

The cold runner defers compilation and validation until measurement and teardown,
respectively. It measures fresh-JVM compilation plus the first replacement or
whole-input operation, excluding process launch, input materialization, encoding,
and harness loading. It does not precompile a validation pattern. Warm compound
operations compile a fresh pattern each invocation; preparation of that pattern
and capture consumption are inside timing. Corpus tests compare full captures and
replacement outputs with the JDK outside timing, including long inputs. Other
engines are excluded; this investigation makes no cross-engine capture claim.
The UTF-8 adapter supports replacement but excludes group-text operations.

Examples (wrappers own timing settings and the shared machine reservation):

```bash
./run-java-benchmarks.sh --fastbuild --investigation nfa-initialization-policy \
  CrossEngineBenchmark.run -- \
  -p crossEngineTrial=NfaInitializationBenchmark.fruitLong.compileAndFindGroup@safere-string
./run-java-benchmarks.sh --first-compile --fastbuild \
  --investigation nfa-initialization-policy CrossEngineColdStartBenchmark.run -- \
  -p crossEngineColdStartTrial=NfaInitializationBenchmark.fruitShort.coldFirst@safere-string
```

Use standard mode for the panel, long mode for important comparisons, and a
separately labeled sample-time run for warm first-use latency distributions.
Cold results have only ten fresh forks: report spread without claiming reliable
p99 estimates. Focused raw evidence stays under ignored `benchmark-results/`.
