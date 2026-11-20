# Preserved evidence map

- `README.md`: readable outcome, methods, tradeoffs, and cutoff disposition.
- `summary.txt`: short plain-text version.
- `recovery-table.md`: complete standard/long recovery comparisons with raw scores and JMH errors.
- `recovery-confirm-rows.json`: separate reversed-order confirmation; not pooled into the main cohort.
- `cutoff-table.md`: complete standard cutoff comparison and 511/512/513 boundary measurements.
- `cutoff-long-table.md` / `cutoff-long-rows.json`: long 512-unit confirmation.
- `base-*` / `greedy-*`: raw recovery JMH JSON/logs and commands. “Greedy” names the character-loop fusion candidate.
- `cutoff-*/` contains raw JMH output, command/source identities, and public-diagnostics path audits.
- `resolved-cutoff-corpus/` contains exact generated input bytes and the resolved manifest, verified identical across source variants.
- `cutoff-sources.patch` restores shared capture-time sources and benchmark test support over the pinned baseline. `cutoff-0.patch` and `cutoff-2048.patch` restore the two alternatives.
- `benchmark-test-support.json` records the original benchmark test hashes. Maven still compiles these tests in timing builds that skip test execution.
- `profiles-derived.json` / `cutoff-profile-summary.txt`: approximate sample classifications and top frames, with inlining limitations.
- `tools/`: comparison and JFR-summary regeneration scripts; the collection driver lives one directory above.

The source patches preserve capture-time content. Their provisional README and
plan described the deterministic control's anchored `find()` as reaching the
cutoff. The actual path audits showed that the prepared OnePass runner handles all
three operations at 512/1024. The current repository documentation and `README.md`
contain the corrected interpretation. This documentation correction changes no
Java harness, input, or measurement.

Only completed comparison and profile cohorts are archived here. Smoke output,
unit-test logs, failed builds, and interrupted runs remain local and are not
performance evidence. Historical policy-study evidence is unchanged.

`SHA256SUMS` covers every other published evidence file. Raw JMH output is
authoritative; derived tables are regenerated with `python3 tools/analyze.py --check`.
Apply the patches in the order shown in the
[reproduction instructions](README.md#reproduction-and-scope). Raw JFR recordings
can be restored separately under `baseline-jfr/` and `cutoff-512-profile/jfr/` to
regenerate the checked-in sampling summaries.
