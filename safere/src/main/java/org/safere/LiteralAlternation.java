// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.util.Arrays;

/**
 * Complete, ordered literal alternatives for a bounded String and UTF-8 search fast path.
 *
 * <p>String searches with up to three distinct initial characters use per-literal windows. With
 * more initials, searches hop between candidates after the first window and return to windowed
 * search for dense candidates.
 *
 * <p>UTF-8 searches use an exact first-byte accelerator when one is available. The default provider
 * can also scan a compact enclosing range on longer inputs. Dense range candidates near the start
 * fall back to the DFA. The vector provider can use Vector Teddy.
 */
final class LiteralAlternation {
  static final long NO_MATCH = -1;
  static final long FALLBACK = -2;
  private static final int PROBE_LENGTH = 1_024;
  private static final int MAX_ALTERNATIVES = 8;
  private static final int WINDOW_LENGTH = 256;
  private static final int MAX_LITERAL_LENGTH = 12;
  // Searches verify first-byte candidates one at a time until they see this many within
  // DENSE_CANDIDATE_SPAN bytes, then switch to one bounded scan per literal.
  private static final int DENSE_CANDIDATES = 8;
  private static final int DENSE_CANDIDATE_SPAN = 256;

  private final String[] literals;
  private final boolean wholeInputRejection;
  private final char[] firstChars;
  private final int firstLow;
  private final int firstHigh;
  // The literals are ASCII, so their UTF-8 encodings are their chars as bytes.
  private final byte[][] utf8Literals;
  private final int[][] utf8Failures;
  // Reuse the ordinary first-byte scan when present. Otherwise default-provider searches use a
  // compact enclosing range or bounded per-literal scans; vector searches retain their own plan.
  private final Utf8StartAccelerator.CharClass utf8FirstByteScan;

  private LiteralAlternation(
      String[] literals,
      boolean wholeInputRejection,
      Utf8StartAccelerator.CharClass utf8FirstByteScan) {
    this.literals = literals;
    this.wholeInputRejection = wholeInputRejection;
    char[] initials = new char[literals.length];
    int count = 0;
    int low = 127;
    int high = 0;
    for (String literal : literals) {
      char first = literal.charAt(0);
      boolean seen = false;
      for (int i = 0; i < count; i++) {
        seen |= initials[i] == first;
      }
      if (!seen) {
        initials[count++] = first;
      }
      low = Math.min(low, first);
      high = Math.max(high, first);
    }
    this.firstChars = Arrays.copyOf(initials, count);
    this.firstLow = low;
    this.firstHigh = high;
    this.utf8Literals = new byte[literals.length][];
    this.utf8Failures = new int[literals.length][];
    for (int i = 0; i < literals.length; i++) {
      utf8Literals[i] = literals[i].getBytes(US_ASCII);
      utf8Failures[i] = Pattern.literalFailure(utf8Literals[i]);
    }
    this.utf8FirstByteScan = utf8FirstByteScan;
  }

  /**
   * Caller must have ruled out explicit captures. {@code utf8FirstByteScan} is the UTF-8 start
   * accelerator when it scans for the literals' first bytes, or null. Vector multi-literal plans
   * retain their existing search; the default provider can select the ordered literal search even
   * when there are too many first bytes for ordinary start acceleration.
   */
  static LiteralAlternation compile(
      Regexp ast, boolean wholeInputRejection, Utf8StartAccelerator.CharClass utf8FirstByteScan) {
    while (ast.op == RegexpOp.NON_CAPTURE || (ast.op == RegexpOp.CAPTURE && ast.cap == 0)) {
      ast = ast.sub();
    }
    if (ast.op != RegexpOp.ALTERNATE || ast.nsub() < 2 || ast.nsub() > MAX_ALTERNATIVES) {
      return null;
    }
    String[] literals = new String[ast.nsub()];
    for (int i = 0; i < literals.length; i++) {
      literals[i] = MultiAnchorCompiler.extractExactAsciiLiteral(ast.subs.get(i));
      if (literals[i] == null) {
        return null;
      }
      // indexOf can repeatedly compare long near-matching prefixes. Even a whole-tail rejection
      // filter may stop after its first successful alternative, whereas exact bounds require us
      // to inspect later alternatives too. MAX_LITERAL_LENGTH * MAX_ALTERNATIVES bounds the
      // comparison work per input position, keeping indexOf's O(n * m) worst case linear in n.
      // WorkCounter charges distance, not character comparisons, so it relies on these caps.
      // Keep the existing engine for longer needles.
      if (literals[i].length() > MAX_LITERAL_LENGTH) {
        return null;
      }
    }
    return new LiteralAlternation(literals, wholeInputRejection, utf8FirstByteScan);
  }

  /**
   * Returns packed start/end bounds, NO_MATCH, or FALLBACK. Only the initial search uses this plan.
   * Later searches use the existing engine, avoiding probe overhead for dense matches.
   */
  long find(String text, int from) {
    if (from != 0) {
      return FALLBACK;
    }
    return findString(text, 0, text.length(), true);
  }

  /** Searches an opaque String region and returns region-relative bounds. */
  long find(StringRegionInputScanner region, int from) {
    if (from != 0) {
      return FALLBACK;
    }
    int start = region.offset();
    long match = findString(region.text(), start, start + region.length(), false);
    if (match < 0) {
      return match;
    }
    int matchStart = (int) (match >>> 32) - start;
    int matchEnd = (int) match - start;
    return ((long) matchStart << 32) | matchEnd;
  }

  /** Searches a bounded range using absolute String offsets. */
  private long findString(String text, int start, int end, boolean wholeInput) {
    if (firstChars.length > 3) {
      // Keep early hits on the existing short-window path. Beyond it, character searches use
      // the JVM's single-character intrinsics instead of one substring scan per alternative.
      int limit = start + Math.min(WINDOW_LENGTH, end - start);
      long match = search(text, start, limit, end);
      if (match >= 0 || limit == end) {
        return match;
      }
      if (!wholeInput && hasDensePrefix(text, limit, end)) {
        return searchFixedWindows(text, limit, end);
      }
      return searchCandidates(text, limit, end, wholeInput);
    }
    if (!wholeInput || !wholeInputRejection) {
      return searchFixedWindows(text, start, end);
    }
    if (end - start <= PROBE_LENGTH * 4) {
      return search(text, start, end, end);
    }
    int probeEnd = start + PROBE_LENGTH;
    long match = search(text, start, probeEnd, end);
    return match >= 0 ? match : searchGrowingWindows(text, probeEnd, end, PROBE_LENGTH * 2);
  }

  private long searchFixedWindows(String text, int start, int end) {
    while (start < end) {
      int limit = start + Math.min(WINDOW_LENGTH, end - start);
      long match = search(text, start, limit, end);
      if (match >= 0) {
        return match;
      }
      start = limit;
    }
    return NO_MATCH;
  }

  /** Avoids verifying each literal separately when the next few region starts are already dense. */
  private boolean hasDensePrefix(String text, int start, int end) {
    int limit = start + Math.min(2 * DENSE_CANDIDATES, end - start);
    for (int i = 0; i < DENSE_CANDIDATES; i++) {
      int candidate = StringInputScanner.indexOfSmallSet(text, firstChars, start, limit);
      if (candidate < 0) {
        return false;
      }
      start = candidate + 1;
    }
    return true;
  }

  /** Candidate starts advance monotonically, including on a switch to windowed search. */
  private long searchCandidates(String text, int start, int end, boolean wholeInput) {
    int spanStart = start;
    int candidates = 0;
    for (int position = start; ; ) {
      int candidate = StringInputScanner.indexOfSmallSet(text, firstChars, position, end);
      if (candidate < 0) {
        return NO_MATCH;
      }
      for (String literal : literals) {
        if (WorkCounterConfig.ENABLED) {
          WorkCounter.record(literal.length());
        }
        if (literal.length() <= end - candidate && text.startsWith(literal, candidate)) {
          return ((long) candidate << 32) | (candidate + literal.length());
        }
      }
      position = candidate + 1;
      if (++candidates == DENSE_CANDIDATES) {
        if (candidate - spanStart < DENSE_CANDIDATE_SPAN) {
          return wholeInput
              ? searchGrowingWindows(text, position, end, PROBE_LENGTH)
              : searchFixedWindows(text, position, end);
        }
        candidates = 0;
        spanStart = candidate;
      }
    }
  }

  /**
   * UTF-8 counterpart to {@link #find(String, int)}, returning byte offsets. ASCII literals only
   * match ASCII bytes, so a match never splits a code point, even in malformed input.
   *
   * <p>Every match starts with one of the literals' first bytes. Reuse the fallback's first-byte
   * scan when available; the default provider can also scan a compact enclosing range. Check
   * literals in priority order at every candidate. After {@link #DENSE_CANDIDATES} candidates
   * within {@link #DENSE_CANDIDATE_SPAN} bytes, a dense range near the start falls back to the DFA.
   * Other dense candidates switch to the String path's strategy of one bounded scan per literal,
   * starting with {@link #PROBE_LENGTH}-byte windows for the word-at-a-time literal filter.
   */
  long find(Utf8InputScanner text, int from) {
    if (from != 0) {
      return FALLBACK;
    }
    boolean scanRange = utf8FirstByteScan == null;
    int length = text.length();
    byte[] bytes = text.bytes();
    int offset = text.offset();
    if (scanRange
        && (VectorScanProviders.vectorProviderAvailable()
            || length < WINDOW_LENGTH
            || firstHigh - firstLow > 31)) {
      return FALLBACK;
    }

    int candidate = findUtf8Candidate(text, 0);
    if (candidate < 0) {
      return NO_MATCH;
    }
    // Count density from the first candidate when using the enclosing range.
    int spanStart = scanRange ? candidate : 0;
    int candidates = 0;
    do {
      long match = matchAt(bytes, offset, length, candidate);
      if (match >= 0) {
        return match;
      }
      int position = candidate + 1;
      if (++candidates == DENSE_CANDIDATES) {
        if (candidate - spanStart < DENSE_CANDIDATE_SPAN) {
          // For a dense range near the start, retry with the DFA. The retried prefix is
          // bounded by the admission window plus one density span.
          if (scanRange && spanStart < WINDOW_LENGTH) {
            return FALLBACK;
          }
          return searchFrom(text, position);
        }
        candidates = 0;
        spanStart = candidate;
      }
      candidate = findUtf8Candidate(text, position);
    } while (candidate >= 0);
    return NO_MATCH;
  }

  private int findUtf8Candidate(Utf8InputScanner text, int position) {
    Utf8StartAccelerator.CharClass firstByteScan = utf8FirstByteScan;
    int candidate =
        firstByteScan != null
            ? firstByteScan.findCandidate(text, position)
            : ByteSwarScan.indexOfByteRange(
                text.bytes(), text.offset(), text.length(), firstLow, firstHigh, position);
    if (WorkCounterConfig.ENABLED) {
      WorkCounter.record((candidate < 0 ? text.length() : candidate + 1) - position);
    }
    return candidate;
  }

  /** Returns the bounds of the highest-priority literal starting at {@code position}, or -1. */
  private long matchAt(byte[] bytes, int offset, int length, int position) {
    for (byte[] literal : utf8Literals) {
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record(literal.length);
      }
      if (literal.length <= length - position
          && ByteSwarScan.matchesAt(bytes, offset, literal, position)) {
        return ((long) position << 32) | (position + literal.length);
      }
    }
    return NO_MATCH;
  }

  /** Searches [start, length), where no literal starts before start. */
  private long searchFrom(Utf8InputScanner text, int start) {
    int length = text.length();
    if (length - start <= PROBE_LENGTH * 4) {
      return search(text, start, length);
    }
    long match = search(text, start, start + PROBE_LENGTH);
    return match >= 0 ? match : searchGrowingWindows(text, start + PROBE_LENGTH, PROBE_LENGTH * 2);
  }

  private long searchGrowingWindows(Utf8InputScanner text, int start, int windowLength) {
    int length = text.length();
    while (start < length) {
      int remaining = length - start;
      int width = Math.min(windowLength, remaining);
      int limit = remaining - width <= PROBE_LENGTH * 4 ? length : start + width;
      long match = search(text, start, limit);
      if (match >= 0) {
        return match;
      }
      start = limit;
      windowLength = (int) Math.min(2L * windowLength, length - start);
    }
    return NO_MATCH;
  }

  /** Search literal starts in [from, limit), including literals crossing the upper boundary. */
  private long search(Utf8InputScanner text, int from, int limit) {
    int bestStart = limit;
    int bestEnd = -1;
    for (int i = 0; i < utf8Literals.length; i++) {
      byte[] literal = utf8Literals[i];
      // indexOfWithin charges WorkCounter for the bytes it scans.
      int idx = text.indexOfWithin(literal, utf8Failures[i], from, bestStart - 1);
      if (idx >= 0) {
        bestStart = idx;
        bestEnd = idx + literal.length;
        // Alternatives are visited in priority order; no later alternative can improve this.
        if (idx == from) {
          break;
        }
      }
    }
    return bestEnd < 0 ? NO_MATCH : ((long) bestStart << 32) | bestEnd;
  }

  private long searchGrowingWindows(String text, int start, int end, int windowLength) {
    while (start < end) {
      int remaining = end - start;
      int width = Math.min(windowLength, remaining);
      // Combine a small final remainder with this window, avoiding another range search.
      int limit = remaining - width <= PROBE_LENGTH * 4 ? end : start + width;
      long match = search(text, start, limit, end);
      if (match >= 0) {
        return match;
      }
      start = limit;
      // Candidate-start ranges do not overlap. search() includes the literal-length overlap
      // needed for crossing matches; long arithmetic keeps growth safe at String's size limit.
      windowLength = (int) Math.min(2L * windowLength, end - start);
    }
    return NO_MATCH;
  }

  /** Searches literal starts in [from, limit), allowing matches to end at or before {@code end}. */
  private long search(String text, int from, int limit, int end) {
    int bestStart = limit;
    int bestEnd = -1;
    int length = text.length();
    for (String literal : literals) {
      int searchEnd = bestStart + Math.min(literal.length() - 1, end - bestStart);
      int idx =
          searchEnd == length
              ? text.indexOf(literal, from)
              : text.indexOf(literal, from, searchEnd);
      if (WorkCounterConfig.ENABLED) {
        WorkCounter.record(idx >= 0 ? idx - from + literal.length() : searchEnd - from);
      }
      if (idx >= 0 && idx < bestStart) {
        bestStart = idx;
        bestEnd = idx + literal.length();
        // Alternatives are visited in priority order; no later alternative can improve this.
        if (idx == from) {
          break;
        }
      }
    }
    return bestEnd < 0 ? NO_MATCH : ((long) bestStart << 32) | bestEnd;
  }
}
