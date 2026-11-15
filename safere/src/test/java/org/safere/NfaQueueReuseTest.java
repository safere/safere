// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Capture ownership across NFA queue clearing, delayed merges, and cached-engine reuse. */
@DisabledForCrosscheck("forces the package-private NFA execution path")
class NfaQueueReuseTest {
  private static final EnginePathOptions NFA_ONLY =
      EnginePathOptions.builder()
          .literalFastPaths(false)
          .charClassMatchFastPaths(false)
          .charClassReplacementFastPath(false)
          .keywordAlternationFastPath(false)
          .startAcceleration(false)
          .shiftDfa(false)
          .onePass(false)
          .dfa(false)
          .bitState(false)
          .lazyCaptureExtraction(false)
          .build();

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
}
