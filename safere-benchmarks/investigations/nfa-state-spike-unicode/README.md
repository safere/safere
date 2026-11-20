# Unicode controls for the NFA state-storage spike

Supplement the main investigation with ordinary non-ASCII consumers. The main
panel's grapheme cases deliberately bypass scalar admission filtering, so they
cannot reveal its decoding cost on multibyte input.

The rare-write input repeats `é😀` before its literal capture suffix. The
frequent-write input repeats `é` through overlapping alternatives. String cases
consume every inner capture's text; UTF-8 cases consume `$1` replacement output.
They are separate observations, not cross-representation timing comparisons.
Both use the ordinary runner with expected results validated outside timing.

The common declarations and focused protocol test are applied identically to
all compared source snapshots. Evidence is local under
`benchmark-results/issue-525-spike/`. Run with `--investigation nfa-state-spike-unicode`; no specialized matching adapter is used.
