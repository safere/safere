// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Capture ownership across NFA queue clearing, delayed merges, and cached-engine reuse. */
@DisabledForCrosscheck("forces the package-private NFA execution path")
class NfaQueueReuseTest {
  private static final EnginePathOptions NFA_ONLY = nfaOptions(false);
  private static final EnginePathOptions FLAT_NFA = nfaOptions(true);

  private static EnginePathOptions nfaOptions(boolean dfa) {
    return EnginePathOptions.builder()
        .literalFastPaths(false)
        .charClassMatchFastPaths(false)
        .charClassReplacementFastPath(false)
        .keywordAlternationFastPath(false)
        .startAcceleration(false)
        .shiftDfa(false)
        .onePass(false)
        .dfa(dfa)
        .bitState(false)
        .lazyCaptureExtraction(false)
        .build();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "(?s).*(a.*b|c+)x?.*",
        "((a|ab)+)(b?)",
        "((a?)*)(b)",
        "(?U)(\\b(\\w+)\\b)+",
        "((\\X)|(a.))+(b?)",
        "((.)|(\\X))+(b?)",
        "(\\X+?)(.?)",
        "((?:\\X|.)*?)(b)"
      })
  void queueReusePreservesCapturesAndPriority(String regex) {
    Pattern pattern = Pattern.compile(regex, 0, NFA_ONLY);
    java.util.regex.Pattern oracle = java.util.regex.Pattern.compile(regex);
    Matcher actual = pattern.matcher("");
    for (String input :
        List.of("", "a", "ab", "aacbbx", "ccxabc", "aab".repeat(20), "áb😀áb", "👩‍💻ab")) {
      // Change input and operation repeatedly so both cleared queues and cached engines are reused.
      for (int iteration = 0; iteration < 3; iteration++) {
        java.util.regex.Matcher expected = oracle.matcher(input);
        actual.reset(input);
        while (expected.find()) {
          assertThat(actual.find()).as("/%s/ on %s", regex, input).isTrue();
          for (int group = 0; group <= expected.groupCount(); group++) {
            assertThat(actual.start(group)).isEqualTo(expected.start(group));
            assertThat(actual.end(group)).isEqualTo(expected.end(group));
          }
        }
        assertThat(actual.find()).isFalse();
        assertThat(actual.reset().matches()).isEqualTo(expected.reset().matches());
        assertThat(actual.reset().replaceAll("<$1>"))
            .isEqualTo(expected.reset().replaceAll("<$1>"));

        Utf8Matcher utf8 = pattern.matcher(Utf8Input.validated(input.getBytes(UTF_8)));
        expected.reset();
        while (expected.find()) {
          assertThat(utf8.find()).as("UTF-8 /%s/ on %s", regex, input).isTrue();
          for (int group = 0; group <= expected.groupCount(); group++) {
            int start = expected.start(group);
            int end = expected.end(group);
            assertThat(utf8.start(group))
                .isEqualTo(start < 0 ? -1 : input.substring(0, start).getBytes(UTF_8).length);
            assertThat(utf8.end(group))
                .isEqualTo(end < 0 ? -1 : input.substring(0, end).getBytes(UTF_8).length);
          }
        }
        assertThat(utf8.find()).isFalse();
        assertThat(utf8.reset().matches()).isEqualTo(expected.reset().matches());
      }
    }
  }

  @Test
  void reusedQueuesFollowChangingInputAndConsumptionBounds() {
    for (String regex : List.of("([a-z]+)", "(?s)(.)(.)?", "(?:(a?)*)(b)", "(😀|a)+")) {
      Pattern pattern = Pattern.compile(regex, 0, NFA_ONLY);
      Matcher actual = pattern.matcher("");
      for (String input : List.of("xaa😀bZ", "ab", "😀x", "", "bb")) {
        for (int start = 0; start <= input.length(); start++) {
          for (int end = start; end <= input.length(); end++) {
            actual.reset(input).region(start, end);
            Matcher expected = pattern.matcher(input.substring(start, end));
            while (expected.find()) {
              assertThat(actual.find())
                  .as("/%s/ in [%s,%s] of %s", regex, start, end, input)
                  .isTrue();
              for (int group = 0; group <= expected.groupCount(); group++) {
                int expectedStart = expected.start(group);
                int expectedEnd = expected.end(group);
                assertThat(actual.start(group))
                    .isEqualTo(expectedStart < 0 ? -1 : expectedStart + start);
                assertThat(actual.end(group)).isEqualTo(expectedEnd < 0 ? -1 : expectedEnd + start);
              }
            }
            assertThat(actual.find()).isFalse();
            assertThat(actual.reset(input).region(start, end).matches())
                .isEqualTo(expected.reset().matches());
          }
        }
      }
    }
  }

  @Test
  void alternativeListsPreserveCapturePriorityAndText() {
    // ASCII boundaries around combining marks have a documented JDK divergence and separate tests.
    List<String> inputs = new ArrayList<>(List.of("", "😀", "a😀b", "\r\na", "é😀b"));
    List<String> level = List.of("");
    for (int length = 1; length <= 4; length++) {
      List<String> next = new ArrayList<>();
      for (String prefix : level) {
        for (String scalar : List.of("a", "b", "\n")) {
          next.add(prefix + scalar);
        }
      }
      inputs.addAll(next);
      level = next;
    }
    for (String regex :
        List.of(
            "(a|(a))",
            "((ab)|a)(b?)",
            "((a)|(ab))+b?",
            "(a|ab)+?b?",
            "(?s).*(a.*b|b+)a?.*",
            "(^|a)(b|$)",
            "(?m)(^a|b$)",
            "\\b(a|b+)\\b",
            "(a|)(b|a)",
            "(()|())(a|b)?",
            "((a|b){1,3}?)(b?)",
            "([ab]*)((a)?b)",
            "(?i)(a|[ab])+",
            "(?s)(😀|.)(b?)")) {
      Pattern pattern = Pattern.compile(regex, 0, FLAT_NFA);
      assertThat(pattern.nfaProg(pattern.prog())).isSameAs(pattern.flatProg());
      assertThat(pattern.nfaProg(pattern.prog()).didFlatten()).as(regex).isTrue();
      java.util.regex.Pattern oracle = java.util.regex.Pattern.compile(regex);
      Matcher actual = pattern.matcher("");
      for (String input : inputs) {
        java.util.regex.Matcher expected = oracle.matcher(input);
        actual.reset(input);
        byte[] bytes = input.getBytes(UTF_8);
        Utf8Matcher utf8 = pattern.matcher(Utf8Input.validated(bytes));
        while (expected.find()) {
          assertThat(actual.find()).as("/%s/ on %s", regex, input).isTrue();
          for (int group = 0; group <= expected.groupCount(); group++) {
            assertThat(actual.start(group)).isEqualTo(expected.start(group));
            assertThat(actual.end(group)).isEqualTo(expected.end(group));
            assertThat(actual.group(group)).isEqualTo(expected.group(group));
          }
        }
        assertThat(actual.find()).as("extra String match /%s/ on %s", regex, input).isFalse();
        // UTF-8 empty-match progress skips a complete scalar, while String/JDK progress can
        // designate the low surrogate. Advance this oracle separately in UTF-16 coordinates.
        java.util.regex.Matcher scalarOracle = oracle.matcher(input);
        int cursor = 0;
        while (cursor <= input.length() && scalarOracle.find(cursor)) {
          assertThat(utf8.find()).as("UTF-8 /%s/ on %s", regex, input).isTrue();
          for (int group = 0; group <= scalarOracle.groupCount(); group++) {
            int start = scalarOracle.start(group);
            int end = scalarOracle.end(group);
            assertThat(utf8.start(group))
                .isEqualTo(start < 0 ? -1 : input.substring(0, start).getBytes(UTF_8).length);
            assertThat(utf8.end(group))
                .isEqualTo(end < 0 ? -1 : input.substring(0, end).getBytes(UTF_8).length);
            int byteStart = utf8.start(group);
            int byteEnd = utf8.end(group);
            assertThat(
                    byteStart < 0 ? null : new String(bytes, byteStart, byteEnd - byteStart, UTF_8))
                .isEqualTo(scalarOracle.group(group));
          }
          cursor = scalarOracle.end();
          if (scalarOracle.start() == cursor) {
            cursor += cursor == input.length() ? 1 : Character.charCount(input.codePointAt(cursor));
          }
        }
        assertThat(utf8.find()).as("extra UTF-8 match /%s/ on %s", regex, input).isFalse();
        assertThat(actual.reset().replaceAll("<$1>"))
            .isEqualTo(expected.reset().replaceAll("<$1>"));
      }
    }
  }

  @Test
  void progressAndGraphemeProgramsKeepTheirGraphSemantics() {
    for (String regex : List.of("((a?)*)(b)", "((a?|b?){1,3})(b)", "(\\X|a)+", "\\b{g}(a)")) {
      Pattern pattern = Pattern.compile(regex, 0, FLAT_NFA);
      assertThat(pattern.nfaProg(pattern.prog())).as(regex).isSameAs(pattern.prog());
    }
  }
}
