// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.US_ASCII;

/**
 * An exact search for the leftmost start of any of a few short ASCII literals, used as the inner
 * plan of a leading expansion over a wide class, as in {@code .*(?:apple|banana|cherry)}.
 *
 * <p>Unlike the general multi-literal accelerators, this search never reports a first-byte hit that
 * isn't a literal start, and never declines and reports the search start. A wide leading expansion
 * can then treat it like a single literal: every hit it walks back from is a real inner candidate,
 * and no hit means no match.
 *
 * <p>Each literal is searched for separately with the ordinary literal search, over windows that
 * double in size, and each later literal only searches up to the best hit so far. A search that
 * finds its first hit {@code d} units in therefore scans at most about {@code 3 * d} units per
 * literal (plus the first window), and a search that finds nothing scans the rest of the input once
 * per literal.
 */
final class InnerLiteralSet {
  static final int MAX_LITERALS = 8;
  static final int MIN_LITERAL_LENGTH = 3;

  /**
   * Bounds the comparison work per input position of the plain literal searches, as in {@link
   * LiteralAlternation}.
   */
  static final int MAX_LITERAL_LENGTH = 12;

  private static final int INITIAL_WINDOW = 256;

  private final String[] literals;
  // The literals are ASCII, so their UTF-8 encodings are their chars as bytes.
  private final byte[][] utf8Literals;
  private final int[][] utf8Failures;

  private InnerLiteralSet(String[] literals) {
    this.literals = literals.clone();
    this.utf8Literals = new byte[literals.length][];
    this.utf8Failures = new int[literals.length][];
    for (int i = 0; i < literals.length; i++) {
      utf8Literals[i] = literals[i].getBytes(US_ASCII);
      utf8Failures[i] = Pattern.literalFailure(utf8Literals[i]);
    }
  }

  /** Returns a search for {@code literals}, which must pass {@link MultiAnchorCompiler}'s gate. */
  static InnerLiteralSet create(String[] literals) {
    if (!MultiAnchorCompiler.isWideInnerLiteralSet(literals)) {
      throw new IllegalArgumentException("unsupported inner literal set");
    }
    return new InnerLiteralSet(literals);
  }

  /**
   * Returns the leftmost start at or after {@code from} of a literal lying wholly within {@code
   * text}, or -1. {@code text} is a {@link String} or a {@link StringRegionInputScanner}.
   */
  int indexOf(CharSequence text, int from) {
    int length = text.length();
    int start = Math.max(0, from);
    long window = INITIAL_WINDOW;
    while (start < length) {
      int limit = (int) Math.min(length, start + window);
      int best = limit;
      for (String literal : literals) {
        // A literal starting before best ends at or before best - 1 + literal.length().
        int end = (int) Math.min(length, (long) best + literal.length() - 1);
        int idx = indexOf(text, literal, start, end);
        if (idx >= 0) {
          best = idx;
          if (idx == start) {
            break;
          }
        }
      }
      if (best < limit) {
        return best;
      }
      start = limit;
      window *= 2;
    }
    return -1;
  }

  private static int indexOf(CharSequence text, String literal, int from, int end) {
    if (text instanceof String string) {
      int idx = string.indexOf(literal, from, end);
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record(idx >= 0 ? idx - from + literal.length() : Math.max(0, end - from));
      }
      return idx;
    }
    // Charges WorkCounter itself.
    return ((StringRegionInputScanner) text).indexOf(literal, from, end);
  }

  /** The UTF-8 counterpart of {@link #indexOf(CharSequence, int)}, in byte offsets. */
  int indexOf(Utf8InputScanner text, int from) {
    int length = text.length();
    int start = Math.max(0, from);
    long window = INITIAL_WINDOW;
    while (start < length) {
      int limit = (int) Math.min(length, start + window);
      int best = limit;
      for (int i = 0; i < utf8Literals.length; i++) {
        // indexOfWithin charges WorkCounter for the bytes it scans.
        int idx = text.indexOfWithin(utf8Literals[i], utf8Failures[i], start, best - 1);
        if (idx >= 0) {
          best = idx;
          if (idx == start) {
            break;
          }
        }
      }
      if (best < limit) {
        return best;
      }
      start = limit;
      window *= 2;
    }
    return -1;
  }
}
