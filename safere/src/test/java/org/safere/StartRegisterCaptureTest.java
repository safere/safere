// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.MatchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests that {@link StartRegisterSearch} finds matches of patterns with capture groups, and that
 * the capture groups of those matches are the same as without it.
 */
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@DisabledForCrosscheck("tests package-private SafeRE engine internals and match diagnostics")
class StartRegisterCaptureTest {
  private static final EnginePathOptions WITHOUT_START_REGISTER =
      EnginePathOptions.builder().startRegister(false).build();

  private static final String PROSE =
      "The quick brown fox jumps over the lazy dog. A stitch in time saves nine; "
          + "naïve café owners résumé-writing at 9:30, don't they? __init__ x_1 Ünïcödé ok. ";

  /** Patterns with capture groups that the start-register search accepts. */
  private static final String[] PATTERNS = {
    "\\b(\\w+)\\b",
    "(\\w+)",
    // Alternation, where priority and length disagree.
    "(a|ab)(c|bcd)(d*)",
    "(fo|foo)(o?)",
    "(foo)|(bar)|(baz)",
    // Nested.
    "((\\w)(\\w*))\\b",
    "(([a-z])([a-z]*))",
    // Optional, possibly empty groups.
    "(?:(\\d+):)?(\\d+)",
    "([a-z]+)(\\d{1,3})?",
    "(x?)(y*)z",
    "(a*)(b?)",
    "\\b([A-Z][a-z]*)(,)?",
    // Quantified groups keep their last iteration.
    "(?:(a)|(b))+c",
    "(\\w)+\\b",
    "((?:ab|a)+)(c)?",
    // Lazy quantifiers.
    "(a+?)(b)",
    "(?s)<b>(.*?)</b>",
    // Leading .* and word boundaries.
    ".*(fox|dog)",
    ".*?(\\d+)",
    "\\b(\\w)(\\w*)\\b",
    "\\B(\\w)\\B",
    // Non-ASCII.
    "(é+)(ü)?",
    "(\\p{L}+) (\\p{L}+)",
    "([^ ]+)(é)",
    // Named groups.
    "(?<word>[A-Z][a-z]+)(?:, (?<next>[a-z]+))?",
  };

  private static final String[] INPUTS = {
    PROSE.repeat(3),
    "abc abcd abcde abcdef ab a abcdefghijkl abcbcdd",
    "foo fooo foooo bar baz bazbar xbarx",
    "12:34 5 6:7 :8 9: 10:11:12",
    "aab ab acab abababc aaaa b bc abcc",
    "xyz yz z xxz yyyz",
    "<b>one</b> <b>two</b>x<b></b> <b>é</b>",
    "Alpha, beta Gamma, delta Epsilon",
    "",
    "é ééü ü éé",
    "\uD83D\uDE00a\uD83D\uDE00 bb\uD83D\uDE00 é xé",
  };

  @AfterEach
  void disableDiagnostics() {
    Pattern.setDiagnostics(SafeReMatchDiagnostics.NONE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"\\b(\\w+)\\b", "(a|ab)(c|bcd)", "(?:(\\d+):)?(\\d+)", ".*(fox|dog)"})
  void captureGroupsUseTheStartRegister(String regex) {
    assertThat(Pattern.compile(regex).startRegisterSearch(new Pattern.ThreadCache())).isNotNull();
    assertThat(
            Pattern.compile(regex, 0, WITHOUT_START_REGISTER)
                .startRegisterSearch(new Pattern.ThreadCache()))
        .isNull();
  }

  @Test
  void curatedPatternsAreEligible() {
    // Otherwise the span tests below would not exercise the start-register search.
    for (String regex : PATTERNS) {
      assertThat(Pattern.compile(regex).startRegisterSearch(new Pattern.ThreadCache()))
          .as(regex)
          .isNotNull();
    }
  }

  @Test
  void findWithCapturesRecoversTheStartInTheForwardPass() {
    List<OperationDiagnostics> events = recordDiagnostics();
    String input = PROSE.repeat(4);
    Utf8Input utf8 = Utf8Input.validated(input.getBytes(UTF_8));
    Pattern pattern = Pattern.compile("\\b(\\w+)\\b");
    Pattern reference = Pattern.compile("\\b(\\w+)\\b", 0, WITHOUT_START_REGISTER);

    int matches = countFinds(pattern.matcher(input));
    assertForwardPassOnly(events, matches);
    events.clear();
    assertThat(countFinds(pattern.matcher(utf8))).isEqualTo(matches);
    assertForwardPassOnly(events, matches);

    // startRegister(false) pins the forward and reverse DFA.
    events.clear();
    assertThat(countFinds(reference.matcher(input))).isEqualTo(matches);
    assertThat(events.subList(0, matches))
        .allSatisfy(event -> {
          assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
          assertThat(event.reverseDfaSearchCount()).isEqualTo(1);
        });
  }

  @Test
  void readingGroupsKeepsTheForwardPass() {
    // Reading inner groups replays the captures over the match, but later finds still take the
    // start-register search.
    String input = PROSE.repeat(2);
    Pattern pattern = Pattern.compile("\\b(\\w)(\\w*)\\b");
    Matcher matcher = pattern.matcher(input);
    java.util.regex.Matcher jdk =
        java.util.regex.Pattern.compile("\\b(\\w)(\\w*)\\b").matcher(input);
    List<OperationDiagnostics> events = recordDiagnostics();
    int matches = 0;
    while (matcher.find()) {
      assertThat(jdk.find()).isTrue();
      assertThat(matcher.group(1)).isEqualTo(jdk.group(1));
      assertThat(matcher.start(2)).isEqualTo(jdk.start(2));
      assertThat(matcher.end(2)).isEqualTo(jdk.end(2));
      matches++;
    }
    assertThat(jdk.find()).isFalse();
    assertThat(events.stream().filter(e -> e.operation() == MatchOperation.FIND))
        .hasSize(matches + 1)
        .allSatisfy(event -> assertThat(event.reverseDfaSearchCount()).isZero());
  }

  @Test
  void curatedGroupSpansAgree() {
    for (String regex : PATTERNS) {
      for (int flags : new int[] {0, Pattern.UNICODE_CHARACTER_CLASS}) {
        for (String input : INPUTS) {
          assertSpansAgree(regex, flags, input, true);
        }
      }
    }
  }

  @Test
  void matchResultsAndReplacementsAgree() {
    for (String regex : PATTERNS) {
      Pattern pattern = Pattern.compile(regex);
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);
      for (String input : INPUTS) {
        assertThat(spans(pattern.matcher(input).results().toList()))
            .as("results() %s on %s", regex, input)
            .isEqualTo(spans(jdk.matcher(input).results().toList()));

        List<MatchResult> snapshots = new ArrayList<>();
        Matcher matcher = pattern.matcher(input);
        while (matcher.find()) {
          snapshots.add(matcher.toMatchResult());
        }
        assertThat(spans(snapshots))
            .as("toMatchResult() %s on %s", regex, input)
            .isEqualTo(spans(jdk.matcher(input).results().toList()));

        assertThat(pattern.matcher(input).replaceAll(StartRegisterCaptureTest::describe))
            .as("replaceAll(Function) %s on %s", regex, input)
            .isEqualTo(jdk.matcher(input).replaceAll(StartRegisterCaptureTest::describe));
      }
    }
  }

  @Test
  void interleavedMatchersShareTheSearch() {
    // Both matchers use this thread's search; each replays its own captures.
    Pattern pattern = Pattern.compile("(\\w)(\\w*)");
    Matcher a = pattern.matcher("alpha beta gamma");
    Matcher b = pattern.matcher("x yy zzz wwww");
    List<String> groups = new ArrayList<>();
    while (a.find()) {
      assertThat(b.find()).isTrue();
      groups.add(a.group(2) + "/" + b.group(1));
    }
    assertThat(groups).containsExactly("lpha/x", "eta/y", "amma/z");
    assertThat(b.find()).isTrue();
    assertThat(b.group(2)).isEqualTo("www");
  }

  @Test
  void boundsChangesKeepTheDeferredCaptures() {
    // Changing the bounds keeps the current match, so its deferred captures are resolved first.
    Pattern pattern = Pattern.compile("\\b(\\w)(\\w*)\\b");
    Matcher matcher = pattern.matcher("alpha beta");
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.find()).isTrue();
    matcher.useTransparentBounds(true).useAnchoringBounds(false);
    assertThat(matcher.group(1)).isEqualTo("b");
    assertThat(matcher.start(2)).isEqualTo(7);
    assertThat(matcher.end(2)).isEqualTo(10);
  }

  @Test
  void regionsKeepTheReverseDfa() {
    // The start-register search doesn't handle regions, so they keep the forward and reverse DFA.
    String input = "xx " + PROSE + " yy";
    Pattern pattern = Pattern.compile("\\b(\\w)(\\w*)\\b");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("\\b(\\w)(\\w*)\\b");
    for (int start = 1; start < 12; start += 3) {
      int end = input.length() - start;
      Matcher matcher = pattern.matcher(input).region(start, end);
      java.util.regex.Matcher expected = jdk.matcher(input).region(start, end);
      List<OperationDiagnostics> events = recordDiagnostics();
      assertThat(allGroups(matcher)).as("region %d", start).isEqualTo(allGroups(expected));
      assertThat(events.stream().mapToInt(OperationDiagnostics::reverseDfaSearchCount).sum())
          .isPositive();
    }
  }

  /**
   * A seeded random differential of every group's span against find() without the start-register
   * search, for String and UTF-8 input.
   */
  @Test
  void randomCapturePatternsAgree() {
    Random random = new Random(1219);
    String[] atoms = {
      "a",
      "b",
      "\\w",
      "\\W",
      "\\b",
      "[a-c]",
      ".",
      "x",
      " ",
      "\\d",
      "é",
      "[^ ]",
      "(?:ab|a)",
      "(?:a|ab)",
    };
    String[] quantifiers = {"", "", "+", "*", "?", "{1,3}", "+?", "*?"};
    String[] alphabet = {"a", "b", "c", "x", " ", "1", "é", "\uD83D\uDE00", "ab", "\n", "-"};
    for (int i = 0; i < 300; i++) {
      String regex = randomCapturePattern(random, atoms, quantifiers, 0);
      StringBuilder input = new StringBuilder();
      int length = random.nextInt(random.nextInt(5) == 0 ? 300 : 40);
      for (int k = 0; k < length; k++) {
        input.append(alphabet[random.nextInt(alphabet.length)]);
      }
      assertSpansAgree(regex, 0, input.toString(), false);
    }
  }

  private static String randomCapturePattern(
      Random random, String[] atoms, String[] quantifiers, int depth) {
    StringBuilder regex = new StringBuilder();
    int count = 1 + random.nextInt(3);
    for (int k = 0; k < count; k++) {
      if (depth < 2 && random.nextInt(3) == 0) {
        regex.append('(').append(randomCapturePattern(random, atoms, quantifiers, depth + 1));
        regex.append(')');
      } else if (random.nextInt(3) == 0) {
        regex.append('(').append(atoms[random.nextInt(atoms.length)]).append(')');
      } else {
        regex.append(atoms[random.nextInt(atoms.length)]);
      }
      regex.append(quantifiers[random.nextInt(quantifiers.length)]);
      if (random.nextInt(6) == 0) {
        regex.append('|');
      }
    }
    if (regex.charAt(regex.length() - 1) == '|') {
      regex.append('x');
    }
    return regex.toString();
  }

  /**
   * Checks every group's span from find() loops against find() without the start-register search,
   * for String and UTF-8 input, and optionally against {@code java.util.regex}.
   */
  private static void assertSpansAgree(String regex, int flags, String input, boolean checkJdk) {
    Pattern pattern = Pattern.compile(regex, flags);
    Pattern reference = Pattern.compile(regex, flags, WITHOUT_START_REGISTER);
    List<List<Integer>> expected = allGroups(reference.matcher(input));
    assertThat(allGroups(pattern.matcher(input))).as("%s on %s", regex, input).isEqualTo(expected);
    if (checkJdk) {
      int jdkFlags =
          (flags & Pattern.UNICODE_CHARACTER_CLASS) != 0
              ? java.util.regex.Pattern.UNICODE_CHARACTER_CLASS
              : 0;
      assertThat(allGroups(java.util.regex.Pattern.compile(regex, jdkFlags).matcher(input)))
          .as("java.util.regex %s on %s", regex, input)
          .isEqualTo(expected);
    }
    Utf8Input utf8 = Utf8Input.validated(input.getBytes(UTF_8));
    List<List<Integer>> expectedUtf8 = allGroups(reference.matcher(utf8));
    assertThat(allGroups(pattern.matcher(utf8)))
        .as("%s on UTF-8 %s", regex, input)
        .isEqualTo(expectedUtf8);
    // String find() can match empty inside a surrogate pair, which UTF-8 input has no offset for.
    if (input.codePoints().count() == input.length()) {
      assertThat(toUtf16(expectedUtf8, input))
          .as("%s UTF-8 offsets on %s", regex, input)
          .isEqualTo(expected);
    }
  }

  private static List<List<Integer>> toUtf16(List<List<Integer>> byteSpans, String input) {
    int[] utf16 = new int[input.getBytes(UTF_8).length + 1];
    int bytes = 0;
    for (int i = 0; i < input.length(); ) {
      int cp = input.codePointAt(i);
      utf16[bytes] = i;
      bytes += new String(Character.toChars(cp)).getBytes(UTF_8).length;
      i += Character.charCount(cp);
    }
    utf16[bytes] = input.length();
    return byteSpans.stream()
        .map(match -> match.stream().map(b -> b < 0 ? b : utf16[b]).toList())
        .toList();
  }

  private static List<List<Integer>> allGroups(Matcher matcher) {
    List<List<Integer>> matches = new ArrayList<>();
    while (matcher.find()) {
      List<Integer> spans = new ArrayList<>();
      for (int g = 0; g <= matcher.groupCount(); g++) {
        spans.add(matcher.start(g));
        spans.add(matcher.end(g));
      }
      matches.add(spans);
    }
    return matches;
  }

  private static List<List<Integer>> allGroups(Utf8Matcher matcher) {
    List<List<Integer>> matches = new ArrayList<>();
    while (matcher.find()) {
      List<Integer> spans = new ArrayList<>();
      for (int g = 0; g <= matcher.groupCount(); g++) {
        spans.add(matcher.start(g));
        spans.add(matcher.end(g));
      }
      matches.add(spans);
    }
    return matches;
  }

  private static List<List<Integer>> allGroups(java.util.regex.Matcher matcher) {
    List<List<Integer>> matches = new ArrayList<>();
    while (matcher.find()) {
      List<Integer> spans = new ArrayList<>();
      for (int g = 0; g <= matcher.groupCount(); g++) {
        spans.add(matcher.start(g));
        spans.add(matcher.end(g));
      }
      matches.add(spans);
    }
    return matches;
  }

  private static List<List<Integer>> spans(List<MatchResult> results) {
    List<List<Integer>> matches = new ArrayList<>();
    for (MatchResult result : results) {
      List<Integer> spans = new ArrayList<>();
      for (int g = 0; g <= result.groupCount(); g++) {
        spans.add(result.start(g));
        spans.add(result.end(g));
      }
      matches.add(spans);
    }
    return matches;
  }

  private static String describe(MatchResult result) {
    StringBuilder out = new StringBuilder("[");
    for (int g = 1; g <= result.groupCount(); g++) {
      out.append(result.start(g)).append(':').append(result.group(g)).append(';');
    }
    return out.append(']').toString().replace("\\", "\\\\").replace("$", "\\$");
  }

  private static int countFinds(Matcher matcher) {
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    return count;
  }

  private static int countFinds(Utf8Matcher matcher) {
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    return count;
  }

  private static void assertForwardPassOnly(List<OperationDiagnostics> events, int matches) {
    assertThat(matches).isPositive();
    assertThat(events).hasSize(matches + 1);
    assertThat(events.stream().mapToInt(OperationDiagnostics::reverseDfaSearchCount).sum())
        .isZero();
    assertThat(events.subList(0, matches))
        .allSatisfy(event -> {
          assertThat(event.boundaryStrategy()).isEqualTo(MatchStrategy.DFA);
          assertThat(event.forwardDfaSearchCount()).isEqualTo(1);
        });
  }

  private static List<OperationDiagnostics> recordDiagnostics() {
    List<OperationDiagnostics> events = new ArrayList<>();
    Pattern.setDiagnostics(
        new SafeReMatchDiagnostics() {
          @Override
          public void onOperationCompleted(OperationDiagnostics event) {
            events.add(event);
          }
        });
    return events;
  }
}
