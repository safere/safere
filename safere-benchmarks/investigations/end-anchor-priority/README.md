# End-anchor priority

These fixtures define the synthetic workloads used for the
[#1214 end-anchor priority change](https://github.com/safere/safere/pull/1229).
Each workload lists its pattern, input recipe, operation, and matcher lifecycle.

The warm multibyte UTF-8 rows use `(?Us).*(?m:^)\bkey=.+?\Z`.
`Issue1214Followup.multibyte.crlf` ends at CRLF.
`Issue1214LineTail.unicodeLineStart.32768` ends at U+2029.
Both use a 32,768-unit fixture with a repeated `é😀 worker ` prefix.
The absolute-end control changes the final anchor to `\z`.

Run the two multibyte rows with the default provider:

```bash
./run-java-benchmarks.sh --fastbuild --long --provider default \
  --investigation end-anchor-priority CrossEngineBenchmark.run -- \
  -p crossEngineTrial=Issue1214Followup.multibyte.crlf@safere-utf8,Issue1214LineTail.unicodeLineStart.32768@safere-utf8
```

Run `Issue1214FirstSearch.*` workloads with `--first-compile`,
`CrossEngineColdStartBenchmark.run`, and `crossEngineColdStartTrial`.
Use the same workload definitions on both revisions. The named
`EndAnchoredFindBenchmark`, `EndAnchoredReplaceBenchmark`, and
`EndAnchoredSplitBenchmark` controls use the regular corpus.
