# Architecture

SafeRE compiles a Java-oriented regex language to finite automata. The design
preserves linear matching work for a fixed compiled pattern while exposing
Java-style match selection, captures, and matcher state. See
[semantic invariants](INVARIANTS.md) for the contracts each stage must preserve.

## Compilation pipeline

```text
Pattern string → RegexLexer → Parser → Regexp AST → Simplifier → Compiler → Prog → Execution
```

The streaming lexer privately owns the source and cursor. It recognizes complete escapes,
quantifiers and their modifiers, and group headers, and returns reusable tokens with original
UTF-16 source spans. Quoting is interpreted within lexical constructs rather than creating an
AST operand; empty quotes therefore cannot split a quantifier from its modifier. Quoted
punctuation retains escaped meaning. Trivia handling is context dependent: for example,
COMMENTS-mode trivia can precede a property name, but trailing property-name whitespace is
significant. Ignored comments cannot open or close quoting on subsequent lines.

The parser owns flag scope and supplies the current lexical flags before requesting another
token. Group-header recognition stops at its delimiter, and the lexer never scans the body
before the parser commits the new mode or restores an enclosing mode. Character-class grammar
uses the same escape and quotation reader. Logical-character lookahead has a fixed bound;
quoted contents and comments are streamed without per-fragment tail-sized buffers.

The parser uses explicit stacks to handle nested expressions without consuming
the Java call stack. It records source semantics such as scoped flags, group
numbering, alternation priority, and greedy or reluctant repetition. Unsupported
constructs are rejected before execution; accepted syntax is documented in
[syntax and compatibility](SYNTAX.md).

Simplification reduces the AST and lowers counted repetitions. Rewrites must
preserve observable captures and ordered match selection, not only the set of
matching strings. Every repeat copy keeps its capture writes; engines retain
earlier values only when a later accepted copy omits a group, and restore writes
when abandoning a path. Public capture access must not repair results by reinterpreting the AST.
Enumerating candidate repeat partitions after a match is chosen could make
capture access superlinear in the input length.

Exact counts and nonnullable operands use ordinary copies. General nullable
finite repeats with optional iterations retain their bounds through
simplification.
The compiler emits distinct copies sharing source capture identities, with one
invocation-local progress register starting at the final required iteration.
An empty iteration then exits once the minimum is met; required earlier empty
iterations still count. The same progress instructions and register rollback
used by unbounded loops handle these finite boundaries. DFA copies without
progress guards preserve full-match existence: unnecessary empty iterations can
be removed without changing input positions or violating the minimum. `matches()`
can therefore publish the fixed whole-region bounds and defer captures to the
guarded program. Prefix/search bounds and captures still require exact progress
semantics; the unguarded DFA cannot select their prioritized result.
Proved capture-preserving equivalences, such as compact statically zero-width
repetition, can avoid these copies and guards.

The compiler uses Thompson construction to connect instruction fragments.
`Prog` holds the resulting program and analysis metadata. Instructions express
ordered alternatives, character consumption, captures, assertions, repetition
progress, and acceptance. Capture registers store start/end pairs, including
group zero for the complete match. Grapheme consumption has a distinct
instruction from the zero-width grapheme-boundary assertion.

AST and program traversal use worklists or iterative walkers. Pattern nesting
must not turn into unbounded Java recursion during parsing, simplification,
compilation, or analysis.

## Compilation and initialization policy

Initialization balances compilation cost with predictable first execution. Patterns stored in
`static final` fields can pay preparation costs during application initialization; dynamic or
short-lived patterns can benefit when unused preparation is avoided. See
[efficient use](EFFICIENT_USE.md#compile-each-pattern-once) for application guidance.

Decide initialization for each artifact from the operations and engine paths that can actually
reach it:

- Prepare the common search and capture paths eagerly by default. First `find()` and `group()`
  latency matter; a lower compile time alone does not justify moving their required work.
- API- or path-specific artifacts may be built on first relevant use. A conditional reverse search
  is one such path even though it is reached through `find()`. Explain which callers avoid the work
  and which callers incur it later; the presence of an eligible engine does not mean every caller
  uses it.
- Build expensive pattern-specific artifacts once per `Pattern`, including for concurrent first
  callers, and publish them safely. Cheap immutable values may use a single check of a safely
  published field when concurrent callers can harmlessly construct equivalent values. Cache an
  unsuccessful eligibility or construction result when retrying cannot change the outcome.
  Preserve the selected fallback and its eligibility for other APIs.
- Eliminate redundant or provably irrelevant preparation. Initialize optional metadata and shared
  tables only when needed. Check mixed-feature patterns too. A table unused by
  an ASCII character class may be needed by another feature in the same pattern's first search.
- A static holder class can initialize and safely publish an immutable table once per defining class
  loader. The first caller pays for initialization. If it fails, later initialization attempts in
  that loader fail with `NoClassDefFoundError`. Keep static initialization dependencies acyclic to
  prevent deadlock during concurrent first use.
- Account for intermediate preparation rebuilt after compilation, synchronization, cache reads,
  and wrapper delegation. Building an automaton only once does not prove that laziness adds no
  work. Preserve steady-state execution and measure compilation together with the first affected
  operation, including captures when relevant. See the
  [measurement requirements](../safere-benchmarks/PERFORMANCE_GUIDE.md#compilation-and-first-use-measurements).

This policy concerns preparation and initialization. DFA state discovery, per-thread execution
state, and deferred capture extraction have their own runtime costs and semantic constraints.
Class loading and linking on a first request can occur even when pattern preparation is eager.
Describe when an artifact is constructed in code comments rather than making eager or lazy
construction an unconditional API contract.

### Decision history

[PR #545](https://github.com/safere/safere/pull/545) introduced eager preparation to improve first-use
and initial-request tail latency. [PR #590 was closed](https://github.com/safere/safere/pull/590#issuecomment-5066257148)
because its compile-time gains shifted that preparation into first use. That decision was later
refined: the [concrete proposal in #970](https://github.com/safere/safere/issues/970#issuecomment-6015796070)
was [explicitly accepted](https://github.com/safere/safere/issues/970#issuecomment-6103300201), allowing
API- and path-specific laziness while retaining the common-search latency priority and requiring
compile-plus-first-use and steady-state evidence.

Accepted implementations illustrate the boundaries:

| Precedent | Decision and review lesson |
|---|---|
| [#973: Unicode case index](https://github.com/safere/safere/pull/973) | Reject provably ineligible accelerator candidates before initializing data they will never use. |
| [#980: OnePass](https://github.com/safere/safere/pull/980#pullrequestreview-5481185686) | Defer analysis for paths that cannot use it in ordinary search/capture operations; concurrent first callers share construction. |
| [#982: reverse DFA](https://github.com/safere/safere/pull/982#pullrequestreview-5497761816) | Conditional reverse preparation can be lazy; measure first searches that need it and those that do not. |
| [#987: literal runners](https://github.com/safere/safere/pull/987#pullrequestreview-5481319501) | Defer forward preparation bypassed by the literal search runner while preserving other operations and concurrency. |
| [#1011: ShiftDfa](https://github.com/safere/safere/pull/1011#pullrequestreview-5677115296) | Whole-input-only preparation can be lazy; preserve other APIs' fallbacks and check compile-plus-first-`matches()` and repeated `matches()`. |

The [ShiftDfa follow-up](https://github.com/safere/safere/pull/1011#issuecomment-6439548353) removed
measured steady-state overhead by replacing the lazy runner after resolution, including failed
construction. These decisions permit selective laziness; they do not authorize a general transfer
of preparation costs into first execution. Reversing the latency priority needs a separate design
discussion.

## Execution strategies

SafeRE selects eligible strategies according to the pattern, operation, input,
region, and required result. Selection order and size thresholds are tuning
choices in the code, not API guarantees.

| Strategy | Purpose |
|---|---|
| Literal, character-class, and keyword paths | Execute recognized pattern shapes directly |
| Prefix/start acceleration | Skip positions that cannot start a match |
| OnePass | Match an unambiguous program with captures in one pass |
| Forward DFA | Reject impossible matches and, when eligible, establish bounds |
| Reverse DFA | Narrow candidate starts or reject end-constrained searches |
| BitState | Explore paths with visited-state bookkeeping and bounded work |
| Pike VM NFA | Execute prioritized threads and captures for general supported patterns |

The lazy DFA builds and caches only encountered states. Character equivalence
classes group code points with identical transition behavior. Assertion context,
including word and line boundaries, must be represented in cache identity.
Scoped line modes and CRLF context cannot be merged into a single global flag.
DFA budgets bound cache growth; exhaustion leads to bounded cache management or
fallback rather than a change in match results.

The NFA processes prioritized threads in lockstep and carries capture registers.
BitState uses an explicit stack and visited-state information to avoid
unrestricted backtracking. Work limits and semantic eligibility checks keep
these paths within the matching contract. OnePass is available only when its
path and capture choices agree with the public semantics.

An eligible search can combine a forward DFA pass, reverse range narrowing,
and an anchored pass to establish match bounds. Capture extraction can be
postponed until captures are observed. Each pass has a separate authority:
a filter cannot publish exact bounds, and bounds do not imply resolved captures.
For unanchored searches, historical capture demand does not move a capture engine
ahead of DFA rejection. The history still enables capture collection during an
already-required exact fallback; anchored operations retain their capture preference.
If an optimization cannot establish the required semantics, dispatch uses an
eligible exact path. See [engine invariants](INVARIANTS.md#engines-agree-on-public-observations).

Use [diagnostics](DIAGNOSTICS.md) to observe actual execution. Static pattern
capabilities alone do not predict which path an operation will take.

## Input representations and regions

`InputScanner` provides shared code-point consumption and bounded search over
String and UTF-8 representations. String positions are UTF-16 indices; UTF-8
positions are byte offsets relative to the input view. Engine progress and
capture registers use the representation's coordinates without decoding the
whole UTF-8 subject into a String.

The public UTF-8 API has a separate matcher to make ownership, byte coordinates,
and scalar-boundary progress explicit. Captures expose bounds so callers can
slice their storage; replacement sinks consume borrowed ranges synchronously.
See [Direct UTF-8 matching](UTF8.md).

Consumption bounds, boundary visibility, anchoring bounds, and candidate starts
are separate execution context. A region substring is not a substitute for
that context. Grapheme segmentation caches bounded per-input information for
regional-indicator parity, pictographic/ZWJ sequences, and Indic linking rather
than rescanning arbitrary prefixes at each position. See
[graphemes and regions](GRAPHEME_REGIONS.md).

Storage adapters must define ownership, mutation, lifetime, validation,
forward/reverse progress, and window boundaries. Add them for a concrete
integration with correctness and performance evidence. Keep third-party buffer
and SQL-engine types out of the core public API.

## Unicode data

SafeRE bundles Unicode property and simple-fold tables with the library.
General categories, scripts, blocks, and binary-property membership use these
tables. General categories, scripts, blocks, and most binary properties are
generated using a maintainer-selected JDK's supported `Character` APIs.
Grapheme properties (`Grapheme_Cluster_Break`, `Indic_Conjunct_Break`, and
`Extended_Pictographic`) come from ICU4J (`UCharacter` and `UnicodeSet`), without
consulting private JDK classifiers. Generated data is checked into the repository,
and the generated source records its provenance.

Unicode upgrades are intentional maintenance changes: update the ICU4J generator
dependency and test fixture, select a JDK with the same Unicode version for the
remaining properties, regenerate and review the output, and run focused Unicode
compatibility tests. The [Unicode generator guide](../safere-unicode/README.md)
describes the workflow.

Some Unicode behavior also uses the runtime JDK: the Java case mappings
incorporated into the case-equivalence closure. Record the JDK when investigating these behaviors.
A difference caused by Unicode-version skew must be distinguished from a
matching-engine bug. Intentional case-fold and boundary differences are recorded
in the [compatibility policy](../INTENTIONAL_DIVERGENCES.md).

## Source map

Library sources are under [org/safere](../safere/src/main/java/org/safere/).

| Area | Starting points |
|---|---|
| Public API | `Pattern`, `Matcher`, `PatternSet`, `Utf8Input`, `Utf8Matcher`, `Utf8Sink` |
| Compilation | `Parser`, `Regexp`, `Simplifier`, `Compiler`, `Prog`, `Inst` |
| Execution | `Dfa`, `OnePass`, `BitState`, `Nfa` |
| Representation and boundaries | `InputScanner`, `EngineContext`, `GraphemeSupport` |
| Character semantics | `CharClass`, `UnicodeTables`, `UnicodeProperties`, `UnicodeCaseFolding` |
| Engine equivalence | `EnginePathContract`, `EnginePathOptions` |

SafeRE began as a Java port of RE2 and incorporates RE2/J code. It has since
evolved independently, with a focus on Java compatibility, JVM performance,
and direct UTF-8 matching. Its automata architecture retains that lineage;
Java-oriented syntax, public state, input coordinates, and captures follow
SafeRE's own compatibility contract.
