// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * {@link StringLiteralSearch} must be indistinguishable from {@link String#indexOf(String, int)}.
 * It is a pure throughput change, so every test here is a differential against the JDK.
 */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
final class StringLiteralSearchTest {

  /**
   * Filler long enough to push the remaining window past {@link
   * StringLiteralSearch#MIN_ANCHORED_WINDOW}, made of a character none of the literals here
   * contains so that padding cannot introduce a match.
   */
  private static final String FILLER = "z".repeat(StringLiteralSearch.MIN_ANCHORED_WINDOW);

  private static int anchored(String text, String literal, int fromIndex) {
    int offset = StringLiteralSearch.anchorOffset(literal);
    return StringLiteralSearch.indexOf(
        text, literal, offset, StringLiteralSearch.anchorAt(literal, offset), fromIndex);
  }

  private static void assertAgreesAt(String text, String literal, int from) {
    assertThat(anchored(text, literal, from))
        .as("literal=%s text=%s fromIndex=%s", literal, text, from)
        .isEqualTo(text.indexOf(literal, from));
  }

  /**
   * Runs the differential twice: once on {@code text} as given, and once on {@code text} followed
   * by enough filler that every start index tested leaves a window long enough to anchor. Without
   * the second pass the short-window guard would route these cases straight to the JDK and the
   * anchored loop would go untested.
   */
  private static void assertAgreesAtEveryStart(String text, String literal) {
    String padded = text + FILLER;
    for (int from = 0; from <= text.length() + 1; from++) {
      assertAgreesAt(text, literal, from);
      assertAgreesAt(padded, literal, from);
    }
  }

  @Test
  void agreesWithJdkOnRandomInputs() {
    // A small alphabet makes accidental near-matches common, which is what exercises the
    // candidate-verification path rather than just the scan.
    Random random = new Random(20260914L);
    String alphabet = "abcq:-/x";
    for (int trial = 0; trial < 3000; trial++) {
      int textLength = random.nextInt(40);
      StringBuilder text = new StringBuilder(textLength);
      for (int i = 0; i < textLength; i++) {
        text.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      int literalLength = 1 + random.nextInt(5);
      StringBuilder literal = new StringBuilder(literalLength);
      for (int i = 0; i < literalLength; i++) {
        literal.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      assertAgreesAtEveryStart(text.toString(), literal.toString());
    }
  }

  @Test
  void agreesWithJdkWhenTheAnchorIsDenseEnoughToExhaustTheStrikeBudget() {
    // The rarity model says 'q' is rare. This haystack says otherwise, which is exactly the case
    // adaptive defeat exists for: every position is a candidate and every one fails.
    String literal = "qx";
    assertThat(StringLiteralSearch.anchorOffset(literal))
        .isNotEqualTo(StringLiteralSearch.NO_ANCHOR);
    String dense = "q".repeat(5000);
    assertThat(anchored(dense, literal, 0)).isEqualTo(-1);
    assertThat(anchored(dense + "qx", literal, 0)).isEqualTo(dense.length());
    // A match beyond the point where the budget is exhausted must still be found. The prefix has
    // to clear MIN_ANCHORED_WINDOW, or the scan never anchors and the budget is never spent.
    int prefix = 4 * StringLiteralSearch.MIN_ANCHORED_WINDOW;
    assertThat(anchored("q".repeat(prefix) + "qx" + "q".repeat(200), literal, 0)).isEqualTo(prefix);
  }

  @Test
  void agreesWithJdkWhenTheAnchorRecursJustInsideTheDensityStride() {
    // The shape the strike charge is calibrated on: an anchor the rarity model rates highly, which
    // nevertheless recurs every 46 characters -- the stride measured for this literal on a log
    // line, and inside MIN_DENSITY_STRIDE -- so every observation is a dense one and none of them
    // verifies. How soon the scan concedes is not visible in the result, only in the benchmark;
    // what is pinned here is that conceding still finds everything the JDK finds.
    String literal = "error:[";
    int offset = StringLiteralSearch.anchorOffset(literal);
    assertThat(offset).isNotEqualTo(StringLiteralSearch.NO_ANCHOR);
    String unit = StringLiteralSearch.anchorAt(literal, offset) + "y".repeat(45);
    String noise = unit.repeat(64);
    assertThat(noise.length()).isGreaterThan(StringLiteralSearch.MIN_ANCHORED_WINDOW);
    assertThat(anchored(noise, literal, 0)).isEqualTo(-1);
    assertThat(anchored(noise + literal, literal, 0)).isEqualTo(noise.length());
    String surrounded = noise + literal + noise;
    for (int from = 0; from < surrounded.length(); from += 137) {
      assertAgreesAt(surrounded, literal, from);
    }
  }

  @Test
  void agreesWithJdkWhenTheAnchorClumpsAndThenThinsOut() {
    // Repayment has to keep a mostly sparse anchor alive, so a handful of dense observations
    // followed by a long sparse run must not be treated the same as a uniformly dense anchor.
    String literal = "error:[";
    char anchor = StringLiteralSearch.anchorAt(literal, StringLiteralSearch.anchorOffset(literal));
    String text =
        ((anchor + "y".repeat(9)).repeat(5) + (anchor + "y".repeat(400)).repeat(3)).repeat(4);
    assertThat(text.length()).isGreaterThan(StringLiteralSearch.MIN_ANCHORED_WINDOW);
    assertThat(anchored(text, literal, 0)).isEqualTo(-1);
    assertAgreesAt(text + literal, literal, 0);
    assertAgreesAt(text + literal + text, literal, 0);
  }

  @Test
  void agreesWithJdkOnWindowsTooShortToAnchor() {
    // Below MIN_ANCHORED_WINDOW the search delegates rather than anchoring; it still has to be
    // indistinguishable from the JDK, including when the window shrinks only because fromIndex has
    // advanced into a long text.
    String literal = "qx";
    String text = "q".repeat(StringLiteralSearch.MIN_ANCHORED_WINDOW * 2) + "qx" + "q".repeat(50);
    for (int from = text.length() - 1; from >= 0; from -= 7) {
      assertAgreesAt(text, literal, from);
    }
    assertAgreesAt("q".repeat(StringLiteralSearch.MIN_ANCHORED_WINDOW - 1) + "qx", literal, 0);
  }

  @Test
  void agreesWithJdkOnOverlappingAndRepeatedLiterals() {
    assertAgreesAtEveryStart("aqaqaqaqaq", "aqa");
    assertAgreesAtEveryStart("qqqqqqqq", "qqq");
    assertAgreesAtEveryStart("://://://", "://");
    assertAgreesAtEveryStart("xxxxxxxxxx", "xy");
  }

  @Test
  void agreesWithJdkOnBoundaryConditions() {
    assertThat(anchored("", "qx", 0)).isEqualTo("".indexOf("qx", 0));
    assertThat(anchored("q", "qx", 0)).isEqualTo("q".indexOf("qx", 0));
    assertThat(anchored("qx", "qx", 0)).isEqualTo(0);
    assertThat(anchored("qx", "qx", 1)).isEqualTo(-1);
    assertThat(anchored("qx", "qx", 99)).isEqualTo(-1);
    assertThat(anchored("qx", "qx", -5)).isEqualTo("qx".indexOf("qx", -5));
    assertThat(anchored("abc", "abcd", 0)).isEqualTo(-1);
  }

  @Test
  void agreesWithJdkOnNonAsciiText() {
    // A non-ASCII literal has no anchor, but a non-ASCII haystack with an ASCII literal does, and
    // the haystack is UTF16-coded there, which is a different JDK kernel.
    assertAgreesAtEveryStart("日本語 q: 日本語", "q:");
    assertAgreesAtEveryStart("ünïcödé-qx-ünïcödé", "qx");
    assertAgreesAtEveryStart("日本語日本語", "日本");
    assertAgreesAtEveryStart("aa\uD83D\uDE00bb", "\uD83D\uDE00b");
  }

  @Test
  void declinesToAnchorWhereAnchoringCannotPayOff() {
    assertThat(StringLiteralSearch.anchorOffset(null)).isEqualTo(StringLiteralSearch.NO_ANCHOR);
    assertThat(StringLiteralSearch.anchorOffset("")).isEqualTo(StringLiteralSearch.NO_ANCHOR);
    // The JDK already routes a single-character needle to its own character kernel.
    assertThat(StringLiteralSearch.anchorOffset("q")).isEqualTo(StringLiteralSearch.NO_ANCHOR);
    // No ASCII character to anchor on; the rarity model has nothing to say.
    assertThat(StringLiteralSearch.anchorOffset("日本")).isEqualTo(StringLiteralSearch.NO_ANCHOR);
    // Every character is common enough to be a poisonous anchor.
    assertThat(StringLiteralSearch.anchorOffset("   ")).isEqualTo(StringLiteralSearch.NO_ANCHOR);
  }

  @Test
  void anchorsOnTheRarestCharacter() {
    assertThat(StringLiteralSearch.anchorOffset("id:")).isEqualTo(2);
    assertThat(StringLiteralSearch.anchorOffset("error:")).isEqualTo(5);
    assertThat(StringLiteralSearch.anchorAt("id:", 2)).isEqualTo(':');
  }

  /**
   * Literals whose rarest character is also their last one, searched over haystacks that carry
   * false candidates — an anchor occurrence that is not part of a match.
   *
   * <p>Both halves matter, and the rest of the suite has neither. When the anchor is the last
   * character, the candidate pre-check re-reads the byte the anchor scan just returned, so it
   * always passes and every candidate runs a full verification. A haystack whose only anchor
   * occurrences are real matches then never reaches that verification with anything to reject.
   */
  @Test
  void agreesWithJdkWhereTheAnchorIsTheLiteralsLastCharacter() {
    for (String literal : new String[] {"id:", "record:", "users/"}) {
      assertThat(StringLiteralSearch.anchorOffset(literal)).isEqualTo(literal.length() - 1);
    }
    assertAgreesAtEveryStart("other:x id:7 more:y", "id:");
    assertAgreesAtEveryStart("tag:a record:b tag:c", "record:");
    assertAgreesAtEveryStart("/a/ users/ /b/", "users/");
    // The anchor present without the rest of the literal, and the rest of the literal present
    // without the anchor.
    assertAgreesAtEveryStart("rrrr: record:", "record:");
    assertAgreesAtEveryStart("recorx: record:", "record:");
    // A match at the very start of the text, and one whose anchor is its last character.
    assertAgreesAtEveryStart("record: trailing", "record:");
    assertAgreesAtEveryStart("leading record:", "record:");
  }

  @Test
  void declinedLiteralsStillAgreeWithJdk() {
    assertAgreesAtEveryStart("hello world", "o");
    assertAgreesAtEveryStart("a b  c   d", "  ");
    assertAgreesAtEveryStart("日本語テキスト", "テキ");
  }

  @Test
  void routesOnlyOneCharacterAsciiLiteralsToTheCharacterKernel() {
    assertThat(StringLiteralSearch.singleAsciiChar("-")).isEqualTo('-');
    assertThat(StringLiteralSearch.singleAsciiChar("\n")).isEqualTo('\n');
    assertThat(StringLiteralSearch.singleAsciiChar("\0")).isEqualTo(0);
    // 0x7f is the last character the two kernels agree on.
    assertThat(StringLiteralSearch.singleAsciiChar("\u007f")).isEqualTo(0x7f);

    assertThat(StringLiteralSearch.singleAsciiChar(null))
        .isEqualTo(StringLiteralSearch.NOT_SINGLE_ASCII);
    assertThat(StringLiteralSearch.singleAsciiChar(""))
        .isEqualTo(StringLiteralSearch.NOT_SINGLE_ASCII);
    assertThat(StringLiteralSearch.singleAsciiChar("ab"))
        .isEqualTo(StringLiteralSearch.NOT_SINGLE_ASCII);
    // Above ASCII String.indexOf(int, int) matches by code point, so it is a different search.
    assertThat(StringLiteralSearch.singleAsciiChar("\u0080"))
        .isEqualTo(StringLiteralSearch.NOT_SINGLE_ASCII);
    assertThat(StringLiteralSearch.singleAsciiChar("é"))
        .isEqualTo(StringLiteralSearch.NOT_SINGLE_ASCII);
    // One code point, but two chars, so not one character by the length test either.
    assertThat(StringLiteralSearch.singleAsciiChar("\uD83D\uDE00"))
        .isEqualTo(StringLiteralSearch.NOT_SINGLE_ASCII);
  }

  @Test
  void plannedSearchAgreesWithJdk() {
    // Single characters, which is what the dispatch is for.
    assertPlannedAgreesAtEveryStart("hello world", "o");
    assertPlannedAgreesAtEveryStart("---a---", "-");
    assertPlannedAgreesAtEveryStart("no hit here", "q");
    assertPlannedAgreesAtEveryStart("", "q");
    assertPlannedAgreesAtEveryStart("line\nbreak", "\n");
    // A haystack the JDK stores as UTF16, where the two kernels index differently internally.
    assertPlannedAgreesAtEveryStart("日本語-日本語", "-");
    // Characters the dispatch declines, which must still reach the anchored path unchanged.
    assertPlannedAgreesAtEveryStart("ünïcödé", "é");
    assertPlannedAgreesAtEveryStart("a q: b q: c", "q:");
    assertPlannedAgreesAtEveryStart("日本語テキスト", "テキ");
  }

  @Test
  void plannedSearchAgreesWithJdkOnOutOfRangeStarts() {
    String text = "a-b-c";
    for (String literal : new String[] {"-", "q", "-b"}) {
      for (int from : new int[] {Integer.MIN_VALUE, -7, -1, 6, 99, Integer.MAX_VALUE}) {
        assertThat(planned(text, literal, from))
            .as("literal=%s fromIndex=%s", literal, from)
            .isEqualTo(text.indexOf(literal, from));
      }
    }
  }

  @Test
  void plannedSearchAgreesWithJdkOnRandomInputs() {
    Random random = new Random(20260918L);
    String alphabet = "abcq:-/x";
    for (int trial = 0; trial < 3000; trial++) {
      StringBuilder text = new StringBuilder();
      for (int i = random.nextInt(40); i > 0; i--) {
        text.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      String literal = String.valueOf(alphabet.charAt(random.nextInt(alphabet.length())));
      assertPlannedAgreesAtEveryStart(text.toString(), literal);
    }
  }

  /**
   * The dispatched and undispatched kernels have to record identical work. {@code
   * ScanDispatchAudit} compares recorded work against the path taken, so a search that charges
   * differently depending on which kernel its plan picked would show up there as a bug somewhere
   * else entirely.
   */
  @Test
  @Tag("work-counter")
  void chargesTheSameWorkWhicheverKernelThePlanChose() {
    String text = "abc-def-ghi" + FILLER + "-tail";
    for (String literal : new String[] {"-", "a", "z", "\n"}) {
      for (int from :
          new int[] {-3, 0, 1, 5, text.length() - 1, text.length(), text.length() + 4}) {
        long dispatched = WorkCounter.countForTesting(() -> planned(text, literal, from));
        long undispatched = WorkCounter.countForTesting(() -> anchored(text, literal, from));
        assertThat(dispatched).as("literal=%s fromIndex=%s", literal, from).isEqualTo(undispatched);
      }
    }
  }

  @Test
  void compiledPlanMatchesStaticHelpersAndJdk() {
    String text = "a q: b - c é d 😀 e a😀b f " + FILLER + " q: - é 😀 a😀b";
    for (String literal :
        new String[] {"", "-", "q:", "error:", "   ", "é", "日本", "😀", "a😀b", "x".repeat(140)}) {
      StringLiteralSearch.Plan plan = StringLiteralSearch.Plan.compile(literal);
      int expectedOffset = StringLiteralSearch.anchorOffset(literal);
      assertThat(plan.literal()).isEqualTo(literal);
      assertThat(plan.anchorOffset()).isEqualTo(expectedOffset);
      assertThat(plan.anchor()).isEqualTo(StringLiteralSearch.anchorAt(literal, expectedOffset));
      assertThat(plan.singleAsciiChar()).isEqualTo(StringLiteralSearch.singleAsciiChar(literal));
      for (int from :
          new int[] {-5, 0, 1, 10, text.length() - 1, text.length(), text.length() + 5}) {
        assertThat(plan.indexOf(text, from))
            .as("literal=%s fromIndex=%s", literal, from)
            .isEqualTo(text.indexOf(literal, from));
        assertThat(plan.indexOfAfterShortGap(text, from))
            .as("afterShortGap literal=%s fromIndex=%s", literal, from)
            .isEqualTo(text.indexOf(literal, from));
      }
    }
  }

  @Test
  void indexOfAfterShortGapHandlesProbeBoundariesAndDenseToSparseTransitions() {
    String tail = "y".repeat(StringLiteralSearch.MIN_ANCHORED_WINDOW + 128);
    // Test short, medium, and max-anchored-length literals across every boundary around
    // DENSE_PROBE_WINDOW so straddling matches are never missed.
    String maxAnchored = "a".repeat(StringLiteralSearch.MAX_ANCHORED_LITERAL_LENGTH - 1) + ":";
    assertThat(StringLiteralSearch.anchorOffset(maxAnchored))
        .isNotEqualTo(StringLiteralSearch.NO_ANCHOR);
    for (String literal : new String[] {"id:", "https://", maxAnchored}) {
      StringLiteralSearch.Plan plan = StringLiteralSearch.Plan.compile(literal);
      int w = StringLiteralSearch.DENSE_PROBE_WINDOW;
      int len = literal.length();
      for (int offset : new int[] {0, 1, w - len - 1, w - len, w - len + 1, w - 1, w, w + 500}) {
        if (offset < 0) {
          continue;
        }
        String text = "y".repeat(offset) + literal + tail;
        assertThat(plan.indexOfAfterShortGap(text, 0))
            .as("literalLen=%d offset=%d", len, offset)
            .isEqualTo(offset);
        // Also test non-zero fromIndex.
        String shifted = "zzz" + text;
        assertThat(plan.indexOfAfterShortGap(shifted, 3))
            .as("shifted literalLen=%d offset=%d", len, offset)
            .isEqualTo(offset + 3);
      }
      String absent = "y".repeat(StringLiteralSearch.MIN_ANCHORED_WINDOW + 512);
      assertThat(plan.indexOfAfterShortGap(absent, 0)).isEqualTo(-1);
    }

    // Dense burst followed by a long sparse gap followed by more dense matches across all entry
    // points.
    String denseThenSparse =
        "a https://b https://c https://"
            + "y".repeat(StringLiteralSearch.MIN_ANCHORED_WINDOW + 512)
            + "https://d https://e"
            + "y".repeat(StringLiteralSearch.MIN_ANCHORED_WINDOW + 512);
    Pattern safere = Pattern.compile("https://");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("https://");
    assertThat(collectFindBounds(safere, denseThenSparse))
        .isEqualTo(collectJdkFindBounds(jdk, denseThenSparse));
    assertThat(safere.matcher(denseThenSparse).replaceAll("http://"))
        .isEqualTo(jdk.matcher(denseThenSparse).replaceAll("http://"));
    assertThat(safere.split(denseThenSparse, -1)).containsExactly(jdk.split(denseThenSparse, -1));
    assertThat(safere.splitAsStream(denseThenSparse).toList())
        .isEqualTo(jdk.splitAsStream(denseThenSparse).toList());
  }

  @Test
  void wholePatternLiteralEntryPointsAgreeWithJdk() {
    String longText =
        "alpha beta gamma ".repeat(80)
            + "id:42 https://example.com; café 😀 a😀b "
            + ":".repeat(StringLiteralSearch.MIN_ANCHORED_WINDOW + 100)
            + "id:99 https://example.org; café 😀 a😀b tail";
    String shortText = "id:1; café 😀 a😀b";
    String noMatchLong = "alpha beta gamma ".repeat(120);
    String[] inputs = {
      "",
      shortText,
      longText,
      noMatchLong,
      "日本語;日本語https://日本語",
      ":".repeat(StringLiteralSearch.MIN_ANCHORED_WINDOW * 2) + "id:" + ":".repeat(50),
    };
    String[] regexes = {
      "id:",
      "https://",
      "error:",
      ";",
      "-",
      "é",
      "日本",
      "😀",
      "a😀b",
      "   ",
      "",
      "x".repeat(StringLiteralSearch.MAX_ANCHORED_LITERAL_LENGTH + 5),
      "(id:)",
      "(;)",
      "^id:",
      "\\Aid:",
    };
    for (String regex : regexes) {
      Pattern safere = Pattern.compile(regex);
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);
      for (String input : inputs) {
        assertThat(safere.asPredicate().test(input))
            .as("asPredicate /%s/ len=%d", regex, input.length())
            .isEqualTo(jdk.asPredicate().test(input));
        assertThat(collectFindBounds(safere, input))
            .as("findAll /%s/ len=%d", regex, input.length())
            .isEqualTo(collectJdkFindBounds(jdk, input));
        assertThat(safere.matcher(input).replaceFirst("X"))
            .as("replaceFirst /%s/ len=%d", regex, input.length())
            .isEqualTo(jdk.matcher(input).replaceFirst("X"));
        assertThat(safere.matcher(input).replaceAll("X"))
            .as("replaceAll /%s/ len=%d", regex, input.length())
            .isEqualTo(jdk.matcher(input).replaceAll("X"));
        for (int limit : new int[] {-1, 0, 2}) {
          assertThat(safere.split(input, limit))
              .as("split /%s/ limit=%d len=%d", regex, limit, input.length())
              .containsExactly(jdk.split(input, limit));
          assertThat(safere.splitWithDelimiters(input, limit))
              .as("splitWithDelimiters /%s/ limit=%d len=%d", regex, limit, input.length())
              .containsExactly(jdk.splitWithDelimiters(input, limit));
        }
        assertThat(safere.splitAsStream(input).toList())
            .as("splitAsStream /%s/ len=%d", regex, input.length())
            .isEqualTo(jdk.splitAsStream(input).toList());
      }
    }
  }

  @Test
  void regionFindWithExactLiteralDoesNotScanPastRegionEnd() {
    String prefix = "x".repeat(StringLiteralSearch.MIN_ANCHORED_WINDOW + 128);
    String input = "id:" + prefix + "id:";
    for (String regex : new String[] {"id:", ";", "😀"}) {
      Pattern safere = Pattern.compile(regex);
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);
      String text = regex + prefix + regex;
      Matcher m = safere.matcher(text).region(regex.length(), regex.length() + prefix.length());
      java.util.regex.Matcher jm =
          jdk.matcher(text).region(regex.length(), regex.length() + prefix.length());
      assertThat(m.find()).as("region /%s/", regex).isEqualTo(jm.find()).isFalse();
    }
    Matcher bounded = Pattern.compile("id:").matcher(input).region(3, input.length() - 3);
    assertThat(bounded.find()).isFalse();
  }

  /**
   * Verifies that all four whole-pattern String literal fast paths execute {@link
   * StringLiteralSearch} and charge the exact same work without double- or under-counting.
   *
   * <p>Spaced false anchor occurrences cause {@link StringLiteralSearch#indexOf} to record strictly
   * more than {@code text.length()} units (scan plus candidate verification), distinguishing it
   * from both uncounted and manually counted bare {@link String#indexOf(String, int)} calls.
   */
  @Test
  @Tag("work-counter")
  void wholePatternLiteralEntryPointsMatchPlanWorkAccounting() {
    // Anchor for "id:" is ':' at offset 2. False ':' hits every 300 chars stay sparse (no defeat),
    // so StringLiteralSearch charges scan + verification (> text.length()).
    String spacedFalseAnchors = ("y".repeat(299) + ":").repeat(6) + "y".repeat(200);
    assertThat(spacedFalseAnchors.length()).isGreaterThan(StringLiteralSearch.MIN_ANCHORED_WINDOW);

    Pattern anchoredPattern = Pattern.compile("id:");
    StringLiteralSearch.Plan anchoredPlan = StringLiteralSearch.Plan.compile("id:");
    long expectedAnchored =
        WorkCounter.countForTesting(
            () -> assertThat(anchoredPlan.indexOf(spacedFalseAnchors, 0)).isEqualTo(-1));
    assertThat(expectedAnchored).isGreaterThan(spacedFalseAnchors.length());
    assertAllEntryPointsCharge(anchoredPattern, spacedFalseAnchors, expectedAnchored, false);

    // Capturing-group wrapper bypasses LiteralPreparedRunner for find(), but
    // replaceAll/split/asPredicate
    // still use Pattern.literalSearchPlan().
    Pattern capturedLiteral = Pattern.compile("(id:)");
    assertThat(
            WorkCounter.countForTesting(
                () -> assertThat(capturedLiteral.asPredicate().test(spacedFalseAnchors)).isFalse()))
        .isEqualTo(expectedAnchored);
    assertThat(
            WorkCounter.countForTesting(() ->
                assertThat(capturedLiteral.matcher(spacedFalseAnchors).replaceAll("X"))
                    .isEqualTo(spacedFalseAnchors)))
        .isEqualTo(expectedAnchored);
    assertThat(
            WorkCounter.countForTesting(
                () -> assertThat(capturedLiteral.split(spacedFalseAnchors)).hasSize(1)))
        .isEqualTo(expectedAnchored);

    // Single-ASCII literal charges identical work across all entry points.
    Pattern singleCharPattern = Pattern.compile(";");
    StringLiteralSearch.Plan singleCharPlan = StringLiteralSearch.Plan.compile(";");
    long expectedSingleChar =
        WorkCounter.countForTesting(
            () -> assertThat(singleCharPlan.indexOf(spacedFalseAnchors, 0)).isEqualTo(-1));
    assertAllEntryPointsCharge(singleCharPattern, spacedFalseAnchors, expectedSingleChar, false);

    // Repeated matches across a long input must stay linear (single-counted per gap).
    String repeatedMatches = ("y".repeat(250) + "id:").repeat(10) + "y".repeat(250);
    long findAllWork =
        WorkCounter.countForTesting(
            () -> assertThat(collectFindBounds(anchoredPattern, repeatedMatches)).hasSize(10));
    long replaceAllWork =
        WorkCounter.countForTesting(() ->
            assertThat(anchoredPattern.matcher(repeatedMatches).replaceAll("X"))
                .doesNotContain("id:"));
    long splitWork =
        WorkCounter.countForTesting(
            () -> assertThat(anchoredPattern.split(repeatedMatches)).hasSize(11));
    assertThat(findAllWork).isEqualTo(replaceAllWork).isEqualTo(splitWork);
    assertThat(findAllWork).isLessThan(repeatedMatches.length() + 100L);

    // Dense burst followed by a long sparse tail must remain strictly single-counted.
    String denseThenSparse =
        ("a".repeat(20) + "id:").repeat(6)
            + "y".repeat(StringLiteralSearch.MIN_ANCHORED_WINDOW + 512)
            + "id:tail";
    long denseFindAll =
        WorkCounter.countForTesting(
            () -> assertThat(collectFindBounds(anchoredPattern, denseThenSparse)).hasSize(7));
    long denseReplaceAll =
        WorkCounter.countForTesting(() ->
            assertThat(anchoredPattern.matcher(denseThenSparse).replaceAll("X"))
                .doesNotContain("id:"));
    long denseSplit =
        WorkCounter.countForTesting(
            () -> assertThat(anchoredPattern.split(denseThenSparse)).hasSize(8));
    assertThat(denseFindAll).isEqualTo(denseReplaceAll).isEqualTo(denseSplit);
    assertThat(denseFindAll).isLessThanOrEqualTo(denseThenSparse.length() + 10L);
  }

  /**
   * Static bytecode guardrail ensuring all four whole-pattern String literal fast paths route
   * through {@link StringLiteralSearch.Plan#indexOf} rather than bare {@link String#indexOf}.
   */
  @Test
  void wholePatternLiteralFastPathsInvokeStringLiteralSearchPlan() throws IOException {
    assertMethodUsesLiteralPlan(
        Matcher.LiteralPreparedRunner.class, "find", "(Lorg/safere/Matcher;Z)Z");
    assertMethodUsesLiteralPlan(
        Matcher.class, "literalReplaceFastPath", "(Ljava/lang/String;I)Ljava/lang/String;");
    assertMethodUsesLiteralPlan(
        Matcher.class,
        "findLiteralSplitPositions",
        "(Lorg/safere/StringLiteralSearch$Plan;ILorg/safere/Matcher$SplitBuffer;)I");
    assertMethodUsesLiteralPlan(Matcher.class, "findNextMatchPacked", "(II)J");
    assertMethodUsesLiteralPlan(Pattern.class, "findInString", "(Ljava/lang/String;)Z");
  }

  private static void assertAllEntryPointsCharge(
      Pattern pattern, String input, long expectedWork, boolean expectedMatch) {
    assertThat(
            WorkCounter.countForTesting(
                () -> assertThat(pattern.matcher(input).find()).isEqualTo(expectedMatch)))
        .as("Matcher.find /%s/", pattern.pattern())
        .isEqualTo(expectedWork);
    assertThat(
            WorkCounter.countForTesting(
                () -> assertThat(pattern.asPredicate().test(input)).isEqualTo(expectedMatch)))
        .as("Pattern.asPredicate /%s/", pattern.pattern())
        .isEqualTo(expectedWork);
    assertThat(WorkCounter.countForTesting(() -> pattern.matcher(input).replaceFirst("X")))
        .as("Matcher.replaceFirst /%s/", pattern.pattern())
        .isEqualTo(expectedWork);
    assertThat(WorkCounter.countForTesting(() -> pattern.matcher(input).replaceAll("X")))
        .as("Matcher.replaceAll /%s/", pattern.pattern())
        .isEqualTo(expectedWork);
    assertThat(WorkCounter.countForTesting(() -> pattern.split(input)))
        .as("Pattern.split /%s/", pattern.pattern())
        .isEqualTo(expectedWork);
  }

  private static void assertMethodUsesLiteralPlan(
      Class<?> owner, String methodName, String methodDescriptor) throws IOException {
    String resource = owner.getName().replace('.', '/') + ".class";
    boolean[] foundMethod = {false};
    boolean[] callsPlan = {false};
    List<String> bareStringIndexOfCalls = new ArrayList<>();
    try (InputStream in = owner.getClassLoader().getResourceAsStream(resource)) {
      assertThat(in).as("class bytes for %s", resource).isNotNull();
      new ClassReader(in)
          .accept(
              new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                  if (!name.equals(methodName) || !descriptor.equals(methodDescriptor)) {
                    return null;
                  }
                  foundMethod[0] = true;
                  return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                        int opcode,
                        String callOwner,
                        String callName,
                        String callDescriptor,
                        boolean isInterface) {
                      if (callOwner.equals("org/safere/StringLiteralSearch$Plan")
                          && callName.equals("indexOf")
                          && callDescriptor.equals("(Ljava/lang/String;I)I")) {
                        callsPlan[0] = true;
                      }
                      if (callOwner.equals("java/lang/String")
                          && callName.equals("indexOf")
                          && (callDescriptor.equals("(Ljava/lang/String;)I")
                              || callDescriptor.equals("(Ljava/lang/String;I)I"))) {
                        bareStringIndexOfCalls.add(callOwner + "." + callName + callDescriptor);
                      }
                    }
                  };
                }
              },
              ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
    String target = owner.getSimpleName() + "." + methodName;
    assertThat(foundMethod[0]).as("method %s exists", target).isTrue();
    assertThat(callsPlan[0]).as("%s calls StringLiteralSearch.Plan.indexOf", target).isTrue();
    assertThat(bareStringIndexOfCalls)
        .as("%s must not call bare String.indexOf(String)", target)
        .isEmpty();
  }

  private static List<String> collectFindBounds(Pattern pattern, String input) {
    List<String> bounds = new ArrayList<>();
    Matcher matcher = pattern.matcher(input);
    while (matcher.find()) {
      bounds.add(matcher.start() + ":" + matcher.end());
    }
    return bounds;
  }

  private static List<String> collectJdkFindBounds(java.util.regex.Pattern pattern, String input) {
    List<String> bounds = new ArrayList<>();
    java.util.regex.Matcher matcher = pattern.matcher(input);
    while (matcher.find()) {
      bounds.add(matcher.start() + ":" + matcher.end());
    }
    return bounds;
  }

  private static int planned(String text, String literal, int fromIndex) {
    int offset = StringLiteralSearch.anchorOffset(literal);
    return StringLiteralSearch.indexOfPlanned(
        text,
        literal,
        offset,
        StringLiteralSearch.anchorAt(literal, offset),
        StringLiteralSearch.singleAsciiChar(literal),
        fromIndex);
  }

  private static void assertPlannedAgreesAtEveryStart(String text, String literal) {
    String padded = text + FILLER;
    for (int from = 0; from <= text.length() + 1; from++) {
      assertThat(planned(text, literal, from))
          .as("literal=%s text=%s fromIndex=%s", literal, text, from)
          .isEqualTo(text.indexOf(literal, from));
      assertThat(planned(padded, literal, from))
          .as("literal=%s paddedText=%s fromIndex=%s", literal, padded, from)
          .isEqualTo(padded.indexOf(literal, from));
    }
  }
}
