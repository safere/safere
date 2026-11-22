// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class FuzzSupportTest {
  @Test
  void generatedRejectionsHaveTheExpectedCauseAndControlsStillMatch() {
    for (var feature : UnsupportedSyntaxFuzzer.FEATURES) {
      for (String context : UnsupportedSyntaxFuzzer.CONTEXTS) {
        for (String gap : UnsupportedSyntaxFuzzer.GAPS) {
          for (var mode : UnsupportedSyntaxFuzzer.Mode.values()) {
            var rejection = UnsupportedSyntaxFuzzer.rejection(feature, context, gap, mode);
            UnsupportedSyntaxFuzzer.assertRejected(rejection);
            if (feature.jdkAccepts()) {
              assertThatNoException()
                  .isThrownBy(() -> Pattern.compile(rejection.regex(), rejection.flags()));
            } else {
              assertThatThrownBy(() -> Pattern.compile(rejection.regex(), rejection.flags()))
                  .isInstanceOf(PatternSyntaxException.class);
            }
          }
        }
      }
      UnsupportedSyntaxFuzzer.assertSupportedControls(feature, "x");
    }
  }

  @Test
  void unrestrictedCompileExplorationStillReportsDialectDifferences() {
    assertThatThrownBy(() -> FuzzSupport.compileCompatibleOrSkip("(a)\\1", 0))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("compile divergence");
  }

  @Test
  void unexpectedAcceptanceFailsARejectionCheck() {
    var supported = new UnsupportedSyntaxFuzzer.Rejection("(a)", 0, "invalid Perl operator");
    assertThatThrownBy(() -> UnsupportedSyntaxFuzzer.assertRejected(supported))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("must reject");
  }

  @Test
  void rejectionByBothEnginesFailsWhenAcceptanceIsRequired() {
    assertThatThrownBy(() -> FuzzSupport.compileRequired("[", 0))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("expected acceptance");
  }

  @ParameterizedTest
  @ValueSource(strings = {"[", "(", "\\"})
  void rejectionByBothEnginesEndsTheComparison(String regex) {
    assertThatThrownBy(() -> org.safere.Pattern.compile(regex))
        .isInstanceOf(PatternSyntaxException.class);
    assertThatThrownBy(() -> Pattern.compile(regex)).isInstanceOf(PatternSyntaxException.class);
    assertThat(FuzzSupport.compileCompatibleOrSkip(regex, 0)).isNull();
  }
}
