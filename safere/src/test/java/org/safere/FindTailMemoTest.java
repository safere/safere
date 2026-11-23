// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

@DisabledForCrosscheck("tests the internal NFA failure cache")
class FindTailMemoTest {
  @Test
  void combinesFailuresFromDifferentFrontiersAtTheSamePosition() {
    FindTailMemo memo = new FindTailMemo(Pattern.compile("abc").prog(), 64);
    memo.beginSearch(0, 0);
    assertThat(seen(memo, 16, 1, 3)).isFalse();
    memo.finishSearch(1);

    memo.beginSearch(0, 1);
    assertThat(seen(memo, 16, 2, 3)).isFalse();
    memo.finishSearch(2);

    memo.beginSearch(0, 2);
    assertThat(seen(memo, 16, 2, 1)).isTrue();
    assertThat(seen(memo, 16, 3, 2)).isTrue();
    assertThat(seen(memo, 17, 3, 1)).isFalse();
  }

  @Test
  void sparseCheckpointsPreserveExactPositionsAndEndOfMatch() {
    FindTailMemo memo = new FindTailMemo(Pattern.compile("abc").prog(), 16_384);
    memo.beginSearch(5, 5);
    assertThat(seen(memo, 4_088, 1)).isFalse();
    assertThat(seen(memo, 4_101, 1)).isFalse();
    assertThat(seen(memo, 4_102, 2)).isFalse();
    memo.finishSearch(4_100);

    memo.beginSearch(5, 4_087);
    assertThat(seen(memo, 4_088, 1)).isFalse();
    assertThat(seen(memo, 4_101, 1)).isTrue();
    assertThat(seen(memo, 4_102, 2)).isTrue();
    assertThat(seen(memo, 4_102, 1)).isFalse();
    assertThat(seen(memo, 4_103, 2)).isFalse();
  }

  private static boolean seen(FindTailMemo memo, int pos, int... instructions) {
    IntArrayList key = memo.key();
    for (int instruction : instructions) {
      key.add(instruction);
    }
    return memo.seen(pos);
  }
}
