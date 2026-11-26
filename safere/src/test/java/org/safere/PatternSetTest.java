// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.function.IntConsumer;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Tests for {@link PatternSet}. */
@DisabledForCrosscheck("PatternSet is a SafeRE-only API with no java.util.regex equivalent")
class PatternSetTest {
  // ---------------------------------------------------------------------------
  // Builder
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Builder")
  class BuilderTests {
    @Test
    void addReturnsSequentialIndices() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      assertThat(b.add("foo")).isEqualTo(0);
      assertThat(b.add("bar")).isEqualTo(1);
      assertThat(b.add("baz")).isEqualTo(2);
    }

    @Test
    void addRejectsInvalidPattern() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      assertThatThrownBy(() -> b.add("[invalid")).isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void addAfterCompileThrows() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      b.compile();
      assertThatThrownBy(() -> b.add("bar"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Cannot add");
    }

    @Test
    void compileWithNoPatternsThrows() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      assertThatThrownBy(b::compile)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("No patterns");
    }

    @Test
    void doubleCompileThrows() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      b.compile();
      assertThatThrownBy(b::compile)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("already been called");
    }
  }

  // ---------------------------------------------------------------------------
  // Unanchored matching
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Unanchored")
  class UnanchoredTests {
    @Test
    void singlePatternMatches() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      PatternSet set = b.compile();

      assertThat(set.match("foo")).containsExactly(0);
      assertThat(set.match("hello foo world")).containsExactly(0);
      assertThat(set.match("bar")).isEmpty();
    }

    @Test
    void multiplePatterns() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      b.add("bar");
      b.add("baz");
      PatternSet set = b.compile();

      assertThat(set.match("foo")).containsExactly(0);
      assertThat(set.match("bar")).containsExactly(1);
      assertThat(set.match("baz")).containsExactly(2);
      assertThat(set.match("nothing")).isEmpty();
    }

    @Test
    void multipleMatchesInSameText() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      b.add("bar");
      b.add("baz");
      PatternSet set = b.compile();

      assertThat(set.match("foobar")).containsExactly(0, 1);
      assertThat(set.match("foobarbaz")).containsExactly(0, 1, 2);
    }

    @Test
    void overlappingPatterns() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("abc");
      b.add("bcd");
      PatternSet set = b.compile();

      assertThat(set.match("abcd")).containsExactly(0, 1);
    }

    @Test
    void patternWithQuantifiers() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("[0-9]+");
      b.add("[a-z]+");
      PatternSet set = b.compile();

      assertThat(set.match("abc123")).containsExactly(0, 1);
      assertThat(set.match("!!!")).isEmpty();
    }

    @Test
    void matchesConvenience() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      PatternSet set = b.compile();

      assertThat(set.matches("foo bar")).isTrue();
      assertThat(set.matches("nothing")).isFalse();
    }

    @Test
    @Tag("work-counter")
    void regionalIndicatorGraphemeBoundaryMissesStayNearLinear() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("\\b{g}z");
      PatternSet set = b.compile();

      assertFourXInputStaysNearLinear(
          "PatternSet regional-indicator boundary miss",
          length -> assertThat(set.match("\uD83C\uDDE6".repeat(length))).isEmpty());
    }

    @Test
    void graphemeClusterPatternMatches() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("\\X");
      b.add("z");
      PatternSet set = b.compile();

      assertThat(set.match("a")).containsExactly(0);
      assertThat(set.match("e\u0301")).containsExactly(0);
      assertThat(set.match("z")).containsExactly(0, 1);
    }

    @Test
    void graphemeFallbackDoesNotRecompileMembersPerMatch() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      List<String> patterns = new ArrayList<>();
      patterns.add("\\X");
      b.add(patterns.getFirst());
      for (int i = 0; i < 64; i++) {
        String pattern = "literal" + i;
        patterns.add(pattern);
        b.add(pattern);
      }
      PatternSet set = b.compile();
      AllocationTracker allocationTracker = allocationTracker();
      long threadId = Thread.currentThread().threadId();

      set.match("a");
      compileAndMatchEachPattern(patterns, "a");
      long compileEachTimeAllocated =
          allocatedFor(
              allocationTracker,
              threadId,
              () -> {
                for (int i = 0; i < 50; i++) {
                  compileAndMatchEachPattern(patterns, "a");
                }
              });
      long cachedSetAllocated =
          allocatedFor(
              allocationTracker,
              threadId,
              () -> {
                for (int i = 0; i < 50; i++) {
                  assertThat(set.match("a")).containsExactly(0);
                }
              });

      assertThat(cachedSetAllocated)
          .as("grapheme PatternSet fallback should reuse compile-time member patterns")
          .isLessThan(compileEachTimeAllocated / 2);
    }
  }

  // ---------------------------------------------------------------------------
  // Anchor start
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Anchor start")
  class AnchorStartTests {
    @Test
    void matchesAtStart() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_START);
      b.add("foo");
      b.add("bar");
      PatternSet set = b.compile();

      assertThat(set.match("foobar")).containsExactly(0);
      assertThat(set.match("barfoo")).containsExactly(1);
      assertThat(set.match("xfoo")).isEmpty();
    }

    @Test
    void prefixMatch() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_START);
      b.add("[0-9]+");
      b.add("[a-z]+");
      PatternSet set = b.compile();

      assertThat(set.match("123abc")).containsExactly(0);
      assertThat(set.match("abc123")).containsExactly(1);
      assertThat(set.match("!!!")).isEmpty();
    }
  }

  // ---------------------------------------------------------------------------
  // Anchor both
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Anchor both")
  class AnchorBothTests {
    @Test
    void fullMatchOnly() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH);
      b.add("foo");
      b.add("bar");
      PatternSet set = b.compile();

      assertThat(set.match("foo")).containsExactly(0);
      assertThat(set.match("bar")).containsExactly(1);
      assertThat(set.match("foobar")).isEmpty();
      assertThat(set.match("fo")).isEmpty();
    }

    @Test
    void multipleFullMatches() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH);
      b.add("abc");
      b.add("[a-z]+");
      b.add("...");
      PatternSet set = b.compile();

      assertThat(set.match("abc")).containsExactly(0, 1, 2);
      assertThat(set.match("xyz")).containsExactly(1, 2);
      assertThat(set.match("abcd")).containsExactly(1);
    }

    @Test
    void graphemeClusterPatternMatchesWholeText() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH);
      b.add("\\X");
      b.add("\\X+");
      PatternSet set = b.compile();

      assertThat(set.match("a")).containsExactly(0, 1);
      assertThat(set.match("e\u0301")).containsExactly(0, 1);
      assertThat(set.match("ab")).containsExactly(1);
    }
  }

  // ---------------------------------------------------------------------------
  // Metadata
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Metadata")
  class MetadataTests {
    @Test
    void sizeReturnsCount() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("a");
      b.add("b");
      b.add("c");
      PatternSet set = b.compile();

      assertThat(set.size()).isEqualTo(3);
    }

    @Test
    void patternReturnsOriginal() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("hello");
      b.add("[0-9]+");
      PatternSet set = b.compile();

      assertThat(set.pattern(0)).isEqualTo("hello");
      assertThat(set.pattern(1)).isEqualTo("[0-9]+");
    }
  }

  // ---------------------------------------------------------------------------
  // Edge cases
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Edge cases")
  class EdgeCases {
    @Test
    void emptyPattern() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("");
      PatternSet set = b.compile();

      assertThat(set.match("anything")).containsExactly(0);
      assertThat(set.match("")).containsExactly(0);
    }

    @Test
    void emptyText() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      b.add("");
      PatternSet set = b.compile();

      assertThat(set.match("")).containsExactly(1);
    }

    @Test
    void duplicatePatterns() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      b.add("foo");
      PatternSet set = b.compile();

      List<Integer> result = set.match("foo");
      assertThat(result).containsExactly(0, 1);
    }

    @Test
    void unicodePatterns() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("caf\u00e9");
      b.add("\\p{IsGreek}+");
      PatternSet set = b.compile();

      assertThat(set.match("caf\u00e9")).containsExactly(0);
      assertThat(set.match("\u03b1\u03b2\u03b3")).containsExactly(1);
    }

    @Test
    void manyPatterns() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH);
      for (int i = 0; i < 100; i++) {
        b.add("pattern" + i);
      }
      PatternSet set = b.compile();

      assertThat(set.match("pattern42")).containsExactly(42);
      assertThat(set.match("pattern99")).containsExactly(99);
      assertThat(set.match("pattern100")).isEmpty();
    }

    @Test
    void singlePattern() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH);
      b.add("hello");
      PatternSet set = b.compile();

      assertThat(set.match("hello")).containsExactly(0);
      assertThat(set.match("world")).isEmpty();
    }
  }

  // ---------------------------------------------------------------------------
  // Builder edge cases
  // ---------------------------------------------------------------------------

  @Test
  void compileEmptyBuilderThrows() {
    PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
    assertThatThrownBy(b::compile).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void compileAlreadyCompiledThrows() {
    PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
    b.add("abc");
    b.compile();
    assertThatThrownBy(b::compile).isInstanceOf(IllegalStateException.class);
  }

  // ---------------------------------------------------------------------------
  // Anchor-mode coverage (non-fallback paths)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("Anchor mode coverage")
  class AnchorModeCoverage {

    @Test
    void unanchoredMultipleOverlapping() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("[a-z]+");
      b.add("abc");
      b.add("xyz");
      PatternSet set = b.compile();

      assertThat(set.match("abc")).containsExactly(0, 1);
      assertThat(set.match("xyz")).containsExactly(0, 2);
      assertThat(set.match("abcxyz")).containsExactly(0, 1, 2);
      assertThat(set.match("123")).isEmpty();
    }

    @Test
    void anchorStartPartialMatch() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_START);
      b.add("foo");
      b.add("bar");
      PatternSet set = b.compile();

      assertThat(set.match("foobar")).containsExactly(0);
      assertThat(set.match("barfoo")).containsExactly(1);
      assertThat(set.match("xfoo")).isEmpty();
    }

    @Test
    void anchorBothNoPartial() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH);
      b.add("foo");
      b.add("foobar");
      PatternSet set = b.compile();

      assertThat(set.match("foo")).containsExactly(0);
      assertThat(set.match("foobar")).containsExactly(1);
      assertThat(set.match("foob")).isEmpty();
    }

    @Test
    void matchesConvenience() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("hello");
      PatternSet set = b.compile();

      assertThat(set.matches("say hello")).isTrue();
      assertThat(set.matches("goodbye")).isFalse();
    }

    @Test
    void sizeAndPatternAccessors() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("alpha");
      b.add("beta");
      PatternSet set = b.compile();

      assertThat(set.size()).isEqualTo(2);
      assertThat(set.pattern(0)).isEqualTo("alpha");
      assertThat(set.pattern(1)).isEqualTo("beta");
    }

    @Test
    void addAfterCompileThrows() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("x");
      b.compile();
      assertThatThrownBy(() -> b.add("y")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidPatternThrows() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      assertThatThrownBy(() -> b.add("[invalid")).isInstanceOf(PatternSyntaxException.class);
    }
  }

  // ---------------------------------------------------------------------------
  // DFA fallback (matchFallback)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("DFA fallback")
  class FallbackTests {

    @Test
    void fallbackUnanchored() {
      // Use maxDfaStates=1 to force immediate DFA bailout → NFA fallback
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      b.add("bar");
      b.add("[0-9]+");
      PatternSet set = b.compile(1);
      List<Integer> matches = set.match("foobar123");
      assertThat(matches).containsExactly(0, 1, 2);
    }

    @Test
    void fallbackAnchorStart() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_START);
      b.add("foo");
      b.add("bar");
      PatternSet set = b.compile(1);
      List<Integer> matches = set.match("foobar");
      assertThat(matches).containsExactly(0);
    }

    @Test
    void fallbackAnchorBoth() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH);
      b.add("hello");
      b.add("world");
      PatternSet set = b.compile(1);
      assertThat(set.match("hello")).containsExactly(0);
      assertThat(set.match("world")).containsExactly(1);
      assertThat(set.match("other")).isEmpty();
    }

    @Test
    void fallbackNoMatch() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("xyz");
      PatternSet set = b.compile(1);
      assertThat(set.match("abc")).isEmpty();
    }
  }

  // ---------------------------------------------------------------------------
  // Tests ported from RE2 C++ set_test.cc (P3)
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("RE2 C++ set_test.cc ports")
  class RE2SetTests {

    @Test
    @DisplayName("Unanchored with overlapping factored patterns")
    void unanchoredFactored() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      b.add("foobar");
      PatternSet set = b.compile();

      assertThat(set.match("foobar")).containsExactly(0, 1);
      assertThat(set.match("obarfoobaroo")).containsExactly(0, 1);
      assertThat(set.match("fooba")).containsExactly(0);
      assertThat(set.match("oobar")).isEmpty();
    }

    @Test
    @DisplayName("Unanchored with $ (end-of-string) anchor")
    void unanchoredDollar() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo$");
      PatternSet set = b.compile();

      assertThat(set.match("foo")).containsExactly(0);
      assertThat(set.match("foobar")).isEmpty();
    }

    @Test
    @DisplayName("Unanchored with \\b (word boundary)")
    void unanchoredWordBoundary() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo\\b");
      PatternSet set = b.compile();

      assertThat(set.match("foo")).containsExactly(0);
      assertThat(set.match("foobar")).isEmpty();
      assertThat(set.match("foo bar")).containsExactly(0);
    }

    @Test
    @DisplayName("Anchored with prefix and digit quantifier")
    void anchoredPrefix() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH);
      b.add("/prefix/\\d*");
      PatternSet set = b.compile();

      assertThat(set.match("/prefix")).isEmpty();
      assertThat(set.match("/prefix/")).containsExactly(0);
      assertThat(set.match("/prefix/42")).containsExactly(0);
    }

    @Test
    @DisplayName("Empty set unanchored rejects compile")
    void emptySetUnanchored() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      assertThatThrownBy(b::compile).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Empty set anchored rejects compile")
    void emptySetAnchored() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.ANCHOR_BOTH);
      assertThatThrownBy(b::compile).isInstanceOf(IllegalStateException.class);
    }
  }

  // ---------------------------------------------------------------------------
  // DFA setup and instance caching
  // ---------------------------------------------------------------------------

  @Nested
  @DisplayName("DFA caching")
  class DfaCachingTests {

    @Test
    void reusesCachedDfaAndSetupAcrossCalls() throws ReflectiveOperationException {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      b.add("bar\\d+");
      b.add("baz");
      PatternSet set = b.compile();

      assertThat(set.cachedDfa()).isNull();
      assertThat(set.match("foo bar42")).containsExactly(0, 1);
      Dfa firstDfa = set.cachedDfa();
      Dfa.Setup firstSetup = set.dfaSetup();
      assertThat(firstDfa).isNotNull();
      assertThat(firstSetup).isNotNull();
      assertThat(ownerThreadId(set)).isEqualTo(Thread.currentThread().threadId());
      assertThat(otherThreadDfas(set)).isNull();

      assertThat(set.match("nothing here")).isEmpty();
      assertThat(set.matches("only baz")).isTrue();
      assertThat(set.matches("miss")).isFalse();
      assertThat(set.match("baz foo bar9")).containsExactly(0, 1, 2);

      assertThat(set.cachedDfa()).isSameAs(firstDfa);
      assertThat(set.dfaSetup()).isSameAs(firstSetup);
      assertThat(otherThreadDfas(set)).isNull();
    }

    @Test
    void nullTextThrowsWithoutInitializingCache() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("foo");
      PatternSet set = b.compile();

      assertThatThrownBy(() -> set.match(null)).isInstanceOf(NullPointerException.class);
      assertThat(set.cachedDfa()).isNull();
    }

    @Test
    void fallbackOnlyPatternSetDoesNotCreateDfaCache() {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("\\X");
      b.add("foo");
      PatternSet set = b.compile();

      assertThat(set.match("foo")).containsExactly(0, 1);
      assertThat(set.cachedDfa()).isNull();
    }

    @Test
    void deferredBoundaryMatchesDoNotCollideInCachedDfa() {
      for (PatternSet.Anchor anchor :
          List.of(PatternSet.Anchor.ANCHOR_START, PatternSet.Anchor.UNANCHORED)) {
        PatternSet.Builder wordBuilder = new PatternSet.Builder(anchor);
        wordBuilder.add("foo\\b");
        wordBuilder.add("bar\\b");
        wordBuilder.add(".*baz");
        PatternSet wordSet = wordBuilder.compile();

        assertThat(wordSet.match("foo!")).containsExactly(0);
        assertThat(wordSet.match("bar!")).containsExactly(1);
        assertThat(wordSet.match("foo!baz")).containsExactly(0, 2);
        assertThat(wordSet.match("bar!baz")).containsExactly(1, 2);
        if (anchor == PatternSet.Anchor.ANCHOR_START) {
          assertThat(wordSet.match("foobar!")).isEmpty();
        } else {
          assertThat(wordSet.match("foobar!")).containsExactly(1);
        }
        assertThat(wordSet.match("fooxxx!")).isEmpty();
        assertThat(wordSet.match("foo!")).containsExactly(0);

        PatternSet.Builder lineBuilder = new PatternSet.Builder(anchor);
        lineBuilder.add("(?m)foo$");
        lineBuilder.add("(?m)bar$");
        PatternSet lineSet = lineBuilder.compile();

        assertThat(lineSet.match("foo\n")).containsExactly(0);
        assertThat(lineSet.match("bar\n")).containsExactly(1);
        assertThat(lineSet.match("foo\nx")).containsExactly(0);
        assertThat(lineSet.match("bar\nx")).containsExactly(1);
        assertThat(lineSet.match("baz\n")).isEmpty();
      }
    }

    @Test
    void cachedDeadStateTransitionsMatchFreshDfa() {
      for (PatternSet.Anchor anchor :
          List.of(PatternSet.Anchor.ANCHOR_START, PatternSet.Anchor.ANCHOR_BOTH)) {
        PatternSet.Builder b = new PatternSet.Builder(anchor);
        b.add("ERROR");
        b.add("WARNING");
        b.add("INFO:\\d+");
        b.add("caf\u00e9");
        PatternSet set = b.compile();

        List<String> inputs =
            List.of(
                "The quick brown fox",
                "The quick brown fox",
                "ERRO",
                "ERROR",
                "ERROR: extra",
                "WARNING",
                "WARN",
                "INFO:123",
                "INFO:abc",
                "",
                "caf\u00e9",
                "caf\u00e9!",
                "zzz");
        boolean anchored = (anchor != PatternSet.Anchor.UNANCHORED);
        for (String input : inputs) {
          assertThat(set.match(input))
              .as("anchor=%s input=%s", anchor, input)
              .isEqualTo(freshDfaMatch(set, input, anchored));
        }
      }
    }

    @Test
    void graphemeBoundarySearchReleasesInputContext() throws ReflectiveOperationException {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("\\b{g}z");
      b.add("a\\b{g}");
      PatternSet set = b.compile();

      List<String> inputs =
          List.of("", "z", "a", "az", "e\u0301z", "a\u0301", "\uD83C\uDDE6\uD83C\uDDE6z");
      for (String input : inputs) {
        assertThat(set.match(input)).isEqualTo(freshDfaMatch(set, input, false));
        Dfa dfa = set.cachedDfa();
        assertThat(dfa).isNotNull();
        Field graphemeContextField = Dfa.class.getDeclaredField("graphemeContext");
        graphemeContextField.setAccessible(true);
        assertThat(graphemeContextField.get(dfa)).isNull();
      }
    }

    @Test
    void ownerDfaRebuildsAfterSoftReferenceIsCleared() throws ReflectiveOperationException {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("alpha");
      b.add("[0-9]+");
      PatternSet set = b.compile();

      assertThat(set.match("alpha 123")).containsExactly(0, 1);
      Dfa firstDfa = set.cachedDfa();
      Dfa.Setup setup = set.dfaSetup();
      assertThat(firstDfa).isNotNull();

      SoftReference<?> ref = cachedDfaReference(set);
      assertThat(ref).isNotNull();
      ref.clear();

      assertThat(set.cachedDfa()).isNull();
      assertThat(set.match("999")).containsExactly(1);
      assertThat(set.cachedDfa()).isNotNull().isNotSameAs(firstDfa);
      assertThat(set.dfaSetup()).isSameAs(setup);
    }

    @Test
    void otherThreadDfaRebuildsAfterSoftReferenceIsCleared() throws Exception {
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("alpha");
      b.add("beta");
      PatternSet set = b.compile();

      onNewThread(() -> set.match("alpha"));
      assertThat(ownerThreadId(set)).isNotEqualTo(Thread.currentThread().threadId());

      assertThat(set.match("beta")).containsExactly(1);
      Dfa firstDfa = set.cachedDfa();
      Dfa.Setup setup = set.dfaSetup();
      assertThat(firstDfa).isNotNull();

      SoftReference<?> ref = cachedDfaReference(set);
      assertThat(ref).isNotNull();
      ref.clear();

      assertThat(set.cachedDfa()).isNull();
      assertThat(set.match("alpha beta")).containsExactly(0, 1);
      assertThat(set.cachedDfa()).isNotNull().isNotSameAs(firstDfa);
      assertThat(set.dfaSetup()).isSameAs(setup);
    }

    @Test
    void repeatedCallsAfterBudgetExhaustionAndRecovery() {
      // Immediate bailout (maxDfaStates = 1) repeatedly falls back cleanly.
      PatternSet.Builder tinyBuilder = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      tinyBuilder.add("foo");
      tinyBuilder.add("bar");
      tinyBuilder.add("[0-9]+");
      PatternSet tiny = tinyBuilder.compile(1);

      for (int i = 0; i < 10; i++) {
        assertThat(tiny.match("foo123")).containsExactly(0, 2);
        assertThat(tiny.matches("foo123")).isTrue();
        assertThat(tiny.match("bar")).containsExactly(1);
        assertThat(tiny.match("nothing")).isEmpty();
        assertThat(tiny.matches("nothing")).isFalse();
      }

      // Moderate budget: exhaust unproductively on an adversarial input, fall back cleanly,
      // and verify recovery on later productive inputs.
      PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
      b.add("[A-Za-z]{10}\\s+[\\s\\S]{0,100}Result[\\s\\S]{0,100}\\s+[A-Za-z]{10}");
      b.add("alpha");
      b.add("beta");
      PatternSet set = b.compile(500);

      assertThat(set.match("alpha only")).containsExactly(1);
      Dfa cachedDfa = set.cachedDfa();
      assertThat(cachedDfa).isNotNull();
      assertThat(cachedDfa.lastSearchBailed()).isFalse();

      Random rng = new Random(1);
      StringBuilder denseBuilder = new StringBuilder();
      while (denseBuilder.length() < 3_000) {
        denseBuilder.append("abcdefghijklmno", 0, 10 + rng.nextInt(5)).append(' ');
      }
      String dense = denseBuilder.toString();
      assertThat(set.match(dense)).isEmpty();
      assertThat(cachedDfa.lastSearchBailed()).isTrue();
      assertThat(set.match(dense + " beta")).containsExactly(2);

      String productive = "x ".repeat(500) + "alpha beta";
      boolean recovered = false;
      for (int attempt = 0; attempt < 1_000; attempt++) {
        assertThat(set.match(productive)).containsExactly(1, 2);
        assertThat(set.matches(productive)).isTrue();
        if (!cachedDfa.lastSearchBailed()) {
          recovered = true;
          break;
        }
      }
      assertThat(recovered).as("cached PatternSet DFA should recover after bailout").isTrue();
    }

    @Test
    void cachedResultsMatchFreshDfaAcrossAnchorsAndVariedInputs() {
      List<String> patterns =
          List.of(
              "foo",
              "bar\\d+",
              "foo\\b",
              "\\bword\\b",
              "(?U)\\b\\p{IsGreek}+\\b",
              "(?i)hello",
              "^start",
              "end$",
              "(?m)^line\\d+$",
              "caf\u00e9",
              "\uD83D\uDE00+",
              "\\b{g}z",
              "",
              "foo");
      List<String> inputs =
          List.of(
              "",
              "foo",
              "foobar",
              "foo bar123",
              "start middle end",
              "start middle end\n",
              "start\nline42\nend",
              "start\r\nline7\r\n",
              "HELLO world",
              "a word here",
              "password",
              "\u03b1\u03b2\u03b3",
              "x\u03b1\u03b2\u03b3y",
              "caf\u00e9 \uD83D\uDE00\uD83D\uDE00",
              "e\u0301z",
              "\uD83C\uDDE6\uD83C\uDDE6z",
              "no matches except empty",
              "bar999",
              "foo!");

      for (PatternSet.Anchor anchor : PatternSet.Anchor.values()) {
        PatternSet.Builder b = new PatternSet.Builder(anchor);
        for (String pattern : patterns) {
          b.add(pattern);
        }
        PatternSet set = b.compile();
        boolean anchored = (anchor != PatternSet.Anchor.UNANCHORED);

        // Pass forward and backward over all inputs on the same cached PatternSet.
        for (String input : inputs) {
          List<Integer> expected = freshDfaMatch(set, input, anchored);
          assertThat(set.match(input))
              .as("forward anchor=%s input=%s", anchor, input)
              .isEqualTo(expected);
          assertThat(set.matches(input))
              .as("matches forward anchor=%s input=%s", anchor, input)
              .isEqualTo(!expected.isEmpty());
        }
        for (int i = inputs.size() - 1; i >= 0; i--) {
          String input = inputs.get(i);
          List<Integer> expected = freshDfaMatch(set, input, anchored);
          assertThat(set.match(input))
              .as("reverse anchor=%s input=%s", anchor, input)
              .isEqualTo(expected);
        }
      }
    }

    @Test
    @Timeout(10)
    void concurrentCallsShareSetupAndIsolatePerThreadDfas() throws Exception {
      int threads = 8;
      List<String> inputs =
          List.of(
              "ERROR: connection timeout after 30s to host db-primary.internal:5432",
              "The quick brown fox jumps over the lazy dog",
              "WARNING host cache-1.internal port:8080",
              "foo! bar!",
              "INFO: 10.0.0.1",
              "");
      for (int round = 0; round < 10; round++) {
        PatternSet.Builder b = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
        b.add("ERROR");
        b.add("WARNING");
        b.add("connection\\s+timeout");
        b.add("\\d+\\.\\d+\\.\\d+\\.\\d+");
        b.add("host\\s+\\S+");
        b.add("foo\\b");
        b.add("bar\\b");
        PatternSet set = b.compile();

        List<List<Integer>> expected =
            inputs.stream().map(input -> freshDfaMatch(set, input, false)).toList();

        CyclicBarrier barrier = new CyclicBarrier(threads);
        var executor = Executors.newFixedThreadPool(threads);
        try {
          List<Future<ThreadResult>> futures = new ArrayList<>();
          for (int t = 0; t < threads; t++) {
            int offset = t;
            futures.add(
                executor.submit(() -> {
                  barrier.await();
                  Dfa firstDfa = null;
                  Dfa.Setup setup = null;
                  for (int iter = 0; iter < 50; iter++) {
                    int idx = (iter + offset) % inputs.size();
                    String input = inputs.get(idx);
                    assertThat(set.match(input)).isEqualTo(expected.get(idx));
                    assertThat(set.matches(input)).isEqualTo(!expected.get(idx).isEmpty());
                    Dfa current = set.cachedDfa();
                    assertThat(current).isNotNull();
                    if (firstDfa == null) {
                      firstDfa = current;
                      setup = set.dfaSetup();
                    } else {
                      assertThat(current).isSameAs(firstDfa);
                    }
                  }
                  return new ThreadResult(Thread.currentThread().threadId(), firstDfa, setup);
                }));
          }
          List<Dfa> dfas = new ArrayList<>();
          List<Long> threadIds = new ArrayList<>();
          Dfa.Setup sharedSetup = null;
          for (Future<ThreadResult> future : futures) {
            ThreadResult r = future.get();
            dfas.add(r.dfa());
            threadIds.add(r.threadId());
            if (sharedSetup == null) {
              sharedSetup = r.setup();
            } else {
              assertThat(r.setup()).isSameAs(sharedSetup);
            }
          }
          assertThat(dfas).doesNotHaveDuplicates();
          assertThat(threadIds).containsOnlyOnce(ownerThreadId(set));
          assertThat(otherThreadDfas(set)).isNotNull();
        } finally {
          executor.shutdownNow();
        }
      }
    }

    private record ThreadResult(long threadId, Dfa dfa, Dfa.Setup setup) {}
  }

  private static List<Integer> freshDfaMatch(PatternSet set, String text, boolean anchored) {
    Prog prog = set.prog();
    Dfa fresh = new Dfa(prog, PatternSet.DEFAULT_MAX_DFA_STATES, Dfa.buildSetup(prog), true);
    Dfa.ManyMatchResult result = fresh.doSearchMany(text, anchored);
    assertThat(result).isNotNull();
    if (!result.matched()) {
      return List.of();
    }
    List<Integer> ids = new ArrayList<>(result.matchIds().length);
    for (int id : result.matchIds()) {
      ids.add(id);
    }
    return ids;
  }

  private static long ownerThreadId(PatternSet set) throws ReflectiveOperationException {
    return (long) patternSetField("ownerThreadId").get(set);
  }

  private static Object otherThreadDfas(PatternSet set) throws ReflectiveOperationException {
    return patternSetField("otherThreadDfas").get(set);
  }

  private static SoftReference<?> cachedDfaReference(PatternSet set)
      throws ReflectiveOperationException {
    if (ownerThreadId(set) == Thread.currentThread().threadId()) {
      return (SoftReference<?>) patternSetField("ownerDfa").get(set);
    }
    ThreadLocal<?> dfas = (ThreadLocal<?>) patternSetField("otherThreadDfas").get(set);
    return dfas != null ? (SoftReference<?>) dfas.get() : null;
  }

  private static Field patternSetField(String name) throws ReflectiveOperationException {
    Field field = PatternSet.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static <T> T onNewThread(Callable<T> task) throws Exception {
    FutureTask<T> future = new FutureTask<>(task);
    Thread thread = new Thread(future);
    thread.start();
    thread.join();
    return future.get();
  }

  private static void assertFourXInputStaysNearLinear(String scenario, IntConsumer task) {
    task.accept(1_000);
    long smallerWork = WorkCounter.countForTesting(() -> task.accept(5_000));
    long largerWork = WorkCounter.countForTesting(() -> task.accept(20_000));

    assertThat(smallerWork).as("%s: smaller input should use matcher work", scenario).isPositive();
    assertThat(largerWork)
        .as(
            "%s: 4x input should stay near-linear, smallerWork=%d largerWork=%d",
            scenario, smallerWork, largerWork)
        .isLessThan(smallerWork * 5);
  }

  private static long allocatedFor(
      AllocationTracker allocationTracker, long threadId, Runnable task) {
    long before = allocationTracker.allocatedBytes(threadId);
    task.run();
    return allocationTracker.allocatedBytes(threadId) - before;
  }

  private static void compileAndMatchEachPattern(List<String> patterns, String text) {
    for (String pattern : patterns) {
      Pattern.compile(pattern).matcher(text).find();
    }
  }

  private static AllocationTracker allocationTracker() {
    try {
      Class<?> managementFactoryClass = Class.forName("java.lang.management.ManagementFactory");
      Object threadBean = managementFactoryClass.getMethod("getThreadMXBean").invoke(null);
      Class<?> allocationBeanClass = Class.forName("com.sun.management.ThreadMXBean");
      Assumptions.assumeTrue(allocationBeanClass.isInstance(threadBean));

      Method supportedMethod = allocationBeanClass.getMethod("isThreadAllocatedMemorySupported");
      Method enabledMethod = allocationBeanClass.getMethod("isThreadAllocatedMemoryEnabled");
      Method setEnabledMethod =
          allocationBeanClass.getMethod("setThreadAllocatedMemoryEnabled", boolean.class);
      Method allocatedBytesMethod =
          allocationBeanClass.getMethod("getThreadAllocatedBytes", long.class);

      Assumptions.assumeTrue((Boolean) supportedMethod.invoke(threadBean));
      if (!(Boolean) enabledMethod.invoke(threadBean)) {
        setEnabledMethod.invoke(threadBean, true);
      }
      return new AllocationTracker(threadBean, allocatedBytesMethod);
    } catch (ReflectiveOperationException e) {
      Assumptions.abort("thread allocation tracking is unavailable: " + e);
      throw new AssertionError("unreachable");
    }
  }

  private record AllocationTracker(Object threadBean, Method allocatedBytesMethod) {
    long allocatedBytes(long threadId) {
      try {
        return (Long) allocatedBytesMethod.invoke(threadBean, threadId);
      } catch (IllegalAccessException e) {
        throw new AssertionError(e);
      } catch (InvocationTargetException e) {
        throw new AssertionError(e.getCause());
      }
    }
  }
}
