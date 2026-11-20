# Capture-recovery follow-up to #1075

Stack-document extraction takes roughly 14–15% less time on String and 15–17%
less on UTF-8 with the candidate. The gain holds across both the complete long
run and its reversed-order confirmation. Successful UTF-8 scheme extraction
and rejection take about 4% longer in the confirmation on this system. The
[reviewer's JDK 27 measurements](https://github.com/safere/safere/pull/1175#issuecomment-6952661824)
found essentially flat rejection results on a separate x86 host. The size of
the scheme cost across runtimes and architectures remains uncertain.

At 512 units, early capture production uses roughly
36–47% less time when every hit consumes text, but rejection takes
4.7–5.0× as long as disabled preference.

Keep the 512-unit capture-demand limit to bound rejected-search work and the
BitState bitmap. The extended-versus-disabled ratios at 512 and 1024 units are
similar for each consumption profile. Hit rate and capture consumption drive
the tradeoff in this range. Any more selective policy would need a signal for
those conditions and representative workload weighting.

## What changed

The implementation fuses simple greedy character loops inside BitState. A helper
walks the loop without returning to the interpreter switch for each character.
It still saves each possible loop exit in the same order, checks the same visited
states, and charges the same logical instructions against the work budget.
Capture recovery and exact fallback benefit from this general mechanism. It adds
bounded shared program metadata; cold-start and memory effects were not measured.
The existing engine-selection rules, including DFA-first unanchored searches
and the anchored capture preference, remain in place.

Baseline String stack-document profiles sampled capture replay more often than the
reverse-DFA pass. This motivated work on BitState's interpreter. The reverse-DFA
implementation itself is unchanged. Sampling classifications are approximate,
include inlining effects, and are not precise wall-time attribution.

The benchmark adapter now decodes requested UTF-8 capture ranges during timing,
retaining native byte bounds and borrowed-view offsets. This enables UTF-8 variants
of existing capture-text declarations. The standing additions cover ASCII and
multibyte extraction, primed rejection, mixed participation queries, and anchored
extraction at 511/512/513 units. The existing
`ApplicationBenchmark.stackTraceExtract` covers the same four-frame document,
reading the class name and line number at each match. The adapter change enables
that workload on pre-encoded SafeRE UTF-8 as well as String. The larger cutoff
protocol remains opt-in, separate from release collections.

## Recovery results against pinned main

Each ratio is candidate time / main time; below one means less time. The baseline
is `4ead777e87f9209c2d9dc06fb958a23bd01349be` (commit UTC `2026-11-11T04:40:44Z`), which already includes DFA-first.
The `greedy-*` artifact filenames refer to character-loop fusion.

| Workload | String ratio | UTF-8 ratio |
|---|---:|---:|
| Stack, every-hit text | 0.908× | 0.922× |
| Stack, one-in-four text | 0.942× | 0.975× |
| Stack, all misses | 0.984× | 0.989× |
| Stack, four-frame document | 0.848× | 0.833× |
| Scheme, every-hit text | 0.985× | 1.038× |
| Scheme, one-in-four text | 0.995× | 1.020× |
| Scheme, all misses | 1.061× | 1.046× |
| Scheme, one hit in sixteen, rotating cursor | 0.951× | 0.993× |

The full long cohort has 16 equally weighted rows: eight scenarios in each native
representation. Its geometric mean is 0.966×, nearest-rank P90 is
1.046×, and largest observed ratio is 1.061×. These are
fixture summaries. The small aggregate shift is within the variation of controls
that changed little or reversed direction across runs. Standard and long results
are reported separately. The reversed-order pair confirms the stack gain and
the UTF-8 scheme cost on this host and remains a separate cohort.

The two patterns come from the existing stack-trace and custom-scheme extraction
benchmarks. Inputs are constructed ASCII prose, valid/invalid frames or links,
and an existing four-frame example document. Stack text reads the fully qualified
class name; the document case reads class names and line numbers for each frame.
Scheme text reads the custom-scheme payload. Each invocation scans three phases
of 512 documents. Ordinary cases retain one cursor per input. The rare scheme
rotation resets one String cursor to new inputs; UTF-8 rotates regions over prepared
bytes. Positive documents have one match except the four-frame document case.
Text is read on every hit or one in four hits as labeled. All-miss cases consume
no capture text. String preparation and UTF-8 encoding are outside timing;
requested UTF-8 capture decoding is inside timing.

[Full scores, JMH errors, and both measurement modes](recovery-table.md),
[reversed-order confirmation](recovery-confirm-rows.json),
[baseline sampling summary](baseline-profile-summary.txt).

## Cutoff experiments and assessment

The counterfactual executable sources differ only in
`Matcher.CAPTURE_DEMAND_TEXT_LIMIT`. A value of 0 disables early capture
preference on these nonempty inputs. A value of 2048 extends it beyond the
current 512-unit limit. Other capacity and eligibility guards remain. Fast
paths such as OnePass still apply. Both counterfactuals include the same
character-loop optimization.

The cutoff fixtures use a start-anchored stack parser and a deterministic
whitespace/key/integer parser. End anchors are omitted so end-anchored reverse
probing cannot bypass the decision at larger sizes. Whitespace padding gives exact
ASCII lengths in both native representations. Rejections keep required literals
and digits but fail inside the expression. A separate post-timing JVM records
public operation diagnostics: the stack comparison switches DFA to BitState;
the deterministic control uses the prepared OnePass runner for all three operations
at these sizes. None of these audited cases uses a reverse DFA.

Each invocation resets retained positive/rejection cursors and scans 16 documents
sharing a Pattern. Actual capture text is consumed before timing to establish demand,
including for rejection controls. `text` reads group 1 on every hit; `mixed` reads
it on one in four hits; `rare` has one hit in sixteen and reads it; `reject` has no
hits. These measure warm steady state after capture consumption.

The standard screen covers stack lengths 512/1024 with all four profiles, the
OnePass control with text/rejection, and current-limit boundaries at 511/512/513:
180 measurements. The long confirmation below covers 512 units, both native
representations, and all three operations, with extended preference measured first.
Ratios are extended preference / disabled preference.

| Input | Operation | Every-hit text | One-in-four text | Rejection |
|---|---|---:|---:|---:|
| safere-string | matches | 0.631× | 1.092× | 4.967× |
| safere-string | lookingAt | 0.614× | 1.121× | 5.001× |
| safere-string | find | 0.636× | 1.126× | 5.004× |
| safere-utf8 | matches | 0.529× | 0.929× | 4.709× |
| safere-utf8 | lookingAt | 0.546× | 0.902× | 4.709× |
| safere-utf8 | find | 0.538× | 0.895× | 4.721× |

The current-limit profiles show BitState instruction/stack work on 512-unit
rejections; 1024-unit rejection samples predominantly show forward DFA work.
Capture-heavy 1024-unit profiles contain both DFA and submatch work. This agrees
with the extra-pass versus eager-backtracking tradeoff in the measurements.

Frequent extraction benefits from early capture production; rejection pays several
times as much. Mixed consumption lies closer to the crossover and depends on
representation and size. Control timings with unchanged paths also varied, limiting
precision near 1×. The existing boundary visibly trades faster extraction below
512 for cheaper rejection above it. This supports retaining a bounded preference. The retained 512 guard remains a
conservative default rather than a measured optimum.

[Complete standard cutoff table and boundary times](cutoff-table.md),
[long cutoff scores and JMH errors](cutoff-long-rows.json),
[exact resolved input corpus](resolved-cutoff-corpus/manifest.json),
[cutoff sampling summary](cutoff-profile-summary.txt),
[profile commands and sources](cutoff-512-profile/command.json).

## Reproduction and scope

Measurements use the project wrappers, sequentially, on 11th Gen Intel(R) Core(TM) i7-11700K @ 3.60GHz, OpenJDK
26.0.2, JMH 1.37, Linux/WSL2. Standard mode uses two forks, two 500 ms warmups and
five 500 ms measurements; long mode uses two forks, three 1 s warmups and five
1 s measurements. JMH errors are its reported 99.9% confidence half-widths.
Recovery times are per 1,536-document cycle; cutoff times are per 16-document batch.
Boundary tables divide batch scores by 16. Smoke and JFR timings are excluded
from comparison evidence.

To reconstruct a cutoff snapshot, apply the shared overlay to the pinned
baseline. It includes the measured harness, data and matching benchmark-test
sources. The shared overlay uses the 512 limit. For the 0 and 2048 cohorts,
apply the corresponding second patch to restore the exact capture-time sources.
Each second patch changes the limit and restores that cohort's archived README
and plan. Before running, compare the resulting files with `sourceSha256` in the
cohort's `command.json` and the entries in `benchmark-test-support.json`. Run
these commands from this evidence directory:

```bash
repro_dir=/tmp/capture-cutoff-repro
git worktree add --detach "$repro_dir" 4ead777e87f9209c2d9dc06fb958a23bd01349be
git -C "$repro_dir" apply "$PWD/cutoff-sources.patch"
git -C "$repro_dir" apply "$PWD/cutoff-0.patch"
cd "$repro_dir"
python3 safere-benchmarks/investigations/capture-recovery/run.py /tmp/capture-cutoff-rerun --family stack --sizes 512 1024 --profiles text mixed rare reject
```

Each completed cohort preserves commands, source identities, raw JSON/logs and
exit statuses. Cutoff cohorts also preserve separate path audits.
`tools/analyze.py --check` verifies the generated tables against the raw data.
Sampling summaries retain the profile findings. Raw recordings live outside the
repository. `SHA256SUMS` covers the checked-in evidence.

The Maven `benchmark-investigation` profile adds investigation sources only
when `-Dbenchmark.investigation=capture-recovery` is set. To verify the regular
JAR boundary, build without that property, list the archive with `jar tf`, and
check that it contains neither `CaptureCutoffBenchmark` nor `CaptureCutoffPaths`.
This is a focused single-thread study with warm Patterns and mostly constructed
inputs. The external OpenJDK-derived checkout was unavailable.
This investigation layer preserves the candidate as a prototype patch. Runtime
adoption is proposed in the next PR; the 512-unit limit is unchanged in both layers.

[Artifact map and capture-time documentation notes](ARTIFACTS.md).
