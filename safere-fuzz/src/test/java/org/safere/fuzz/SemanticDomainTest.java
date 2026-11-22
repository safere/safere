// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

final class SemanticDomainTest {
  @Test
  void sampledCapturePatternsCompileInTheirDomain() {
    Random choices = new Random(1209);
    for (int i = 0; i < 512; i++) {
      FuzzSupport.compileRequired(CaptureSemanticsFuzzer.generateRegex(choices::nextInt, false), 0);
      FuzzSupport.compileRequired(CaptureSemanticsFuzzer.generateRegex(choices::nextInt, true), 0);
    }
  }

  @Test
  void possessiveChoiceDependsOnTheImmediateOperand() {
    // The directly quantified consuming capture cannot take a possessive modifier.
    String consuming = CaptureSemanticsFuzzer.generateRegex(choices(1, 0, 10, 0, 0, 0, 0), false);
    assertThat(consuming).isEqualTo("(?:(a))*");
    // An inner zero count makes the wrapped operand statically zero-width.
    String zeroCount =
        CaptureSemanticsFuzzer.generateRegex(choices(1, 1, 1, 0, 1, 10, 0, 0), false);
    assertThat(zeroCount).isEqualTo("(?:(?:(a)){0})*+");
    var pattern = FuzzSupport.compileRequired(zeroCount, 0);
    assertThat(pattern.matcher("").matches()).isTrue();
    // A count following a quantifier owns a separate empty operand.
    String implicit = CaptureSemanticsFuzzer.generateRegex(choices(1, 0, 0, 1, 6, 0, 0, 0), false);
    assertThat(implicit).isEqualTo("(?:(a))*{1}+");
    assertThat(FuzzSupport.compileRequired(implicit, 0).matcher("aaa").matches()).isTrue();
  }

  @Test
  void replayQuantifiedCaptureExamples() {
    CaptureSemanticsFuzzer.compareCaptures("(a)+", "aaay", false);
    CaptureSemanticsFuzzer.compareCaptures("(a?)*", "aaa", false);
    CaptureSemanticsFuzzer.compareCaptures("(?<word>a){1,2}", "xaa", true);
  }

  private static IntUnaryOperator choices(Integer... values) {
    Iterator<Integer> next = List.of(values).iterator();
    return bound -> {
      int value = next.next();
      assertThat(value).isBetween(0, bound - 1);
      return value;
    };
  }
}
