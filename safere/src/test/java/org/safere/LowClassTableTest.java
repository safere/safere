// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for the flat U+0000..U+07FF class lookup tables in {@link Dfa}, {@link
 * StartRegisterSearch}, and {@link InputScanner#lowAt}.
 */
@DisabledForCrosscheck("tests package-private Dfa, StartRegisterSearch, and InputScanner internals")
class LowClassTableTest {

  @Test
  void lowUnicodeWordBitsetMatchesNfaForAllLowCodePoints() {
    for (int cp = 0; cp < Dfa.LOW_LIMIT; cp++) {
      assertThat(Dfa.isLowUnicodeWordChar(cp))
          .as("U+%04X", cp)
          .isEqualTo(Nfa.isUnicodeWordChar(cp));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\\p{L}+",
        "[а-яА-ЯёЁ]+",
        "(?U)\\b\\w+\\b",
        "\\b\\w+\\b",
        "(?m)^\\p{L}+$",
        "(?i)k\\u017f\\u212a",
        "(?iu)\\u03c3\\u00df\\u1e9e",
        "[\\u007f\\u0080\\u07ff\\u0800]+",
        "[\\u07fe-\\u0801]+"
      })
  void dfaLowClassMapMatchesBinarySearchReference(String regex) {
    Pattern pattern = Pattern.compile(regex);
    Dfa.Setup setup = Dfa.buildSetup(pattern.flatDfaProg());
    int[] boundaries = setup.boundaries();
    int[] lowClassMap = setup.lowClassMap();
    boolean split = setup.splitUnicodeWordClasses();

    assertThat(lowClassMap).hasSize(Dfa.LOW_LIMIT);
    for (int cp = 0; cp < Dfa.LOW_LIMIT; cp++) {
      int idx = Arrays.binarySearch(boundaries, cp);
      int expected = idx >= 0 ? idx : (-idx - 1) - 1;
      if (split) {
        boolean word = cp < 128 ? Nfa.isWordChar(cp) : Nfa.isUnicodeWordChar(cp);
        expected = expected * 2 + (word ? 1 : 0);
      }
      assertThat(lowClassMap[cp]).as("regex=%s cp=U+%04X", regex, cp).isEqualTo(expected);
    }
  }

  @Test
  void forwardAndReverseDfaShareLowClassTableWhenClassesMatch() {
    Pattern pattern = Pattern.compile("(?m)^\\p{L}+$");
    Prog forward = pattern.flatDfaProg();
    Prog reverse = pattern.flatReverseDfaProg();
    Dfa.Setup forwardSetup = Dfa.buildSetup(forward);
    Dfa.Setup sharedReverse = Dfa.buildSetup(reverse, forwardSetup);
    assertThat(sharedReverse).isSameAs(forwardSetup);

    Dfa.Setup other = Dfa.buildSetup(Pattern.compile("[0-9]+").flatDfaProg(), forwardSetup);
    assertThat(other).isNotSameAs(forwardSetup);
  }

  @Test
  void inputScannersLowAtRespectsLimitAndBoundaries() {
    String s = "\u0000\u007f\u0080\u0410\u07ff\u0800\ud83d\ude00";
    StringInputScanner stringScanner = new StringInputScanner(s);
    assertThat(stringScanner.lowAt(0)).isEqualTo(0x0000);
    assertThat(stringScanner.lowAt(1)).isEqualTo(0x007F);
    assertThat(stringScanner.lowAt(2)).isEqualTo(0x0080);
    assertThat(stringScanner.lowAt(3)).isEqualTo(0x0410);
    assertThat(stringScanner.lowAt(4)).isEqualTo(0x07FF);
    assertThat(stringScanner.lowAt(5)).isEqualTo(-1);
    assertThat(stringScanner.lowAt(6)).isEqualTo(-1);
    assertThat(stringScanner.lowAt(7)).isEqualTo(-1);

    StringRegionInputScanner regionScanner =
        new StringRegionInputScanner("xx" + s + "yy", 2, 2 + s.length());
    for (int i = 0; i < s.length(); i++) {
      assertThat(regionScanner.lowAt(i)).isEqualTo(stringScanner.lowAt(i));
    }

    byte[] utf8Bytes = "\u007f\u0080\u07ff\u0800".getBytes(UTF_8);
    Utf8InputScanner utf8Scanner = new Utf8InputScanner(utf8Bytes);
    assertThat(utf8Scanner.lowAt(0)).isEqualTo(0x7F);
    for (int i = 1; i < utf8Bytes.length; i++) {
      assertThat(utf8Scanner.lowAt(i)).isEqualTo(-1);
    }
  }

  @Test
  void boundariesAroundAsciiAndLowLimitAgreeAcrossScanners() {
    String text = "a\u007f\u0080\u0081\u07fe\u07ff\u0800\u0801\ud83d\ude00\ud83d\ude00b";
    String[] patterns = {
      "[\\u007f\\u0080]+",
      "[\\u007e-\\u0081]+",
      "[\\u07ff\\u0800]+",
      "[\\u07fe-\\u0801]+",
      "[\\u0080-\\u07ff]+",
      "[\\u0800-\\uffff]+",
      "[^\\u007f\\u07ff]+"
    };
    for (String regex : patterns) {
      assertMatchesJdkAndScanners(regex, text);
    }
  }

  @Test
  void startRegisterLowColumnsMatchNonAsciiColumnAcrossAllTableModes() {
    StringBuilder manyCols = new StringBuilder("[");
    for (int cp = 0x100; cp < 0x3000; cp += 2) {
      manyCols.append((char) cp);
    }
    manyCols.append("]+");

    // Covers plain-shared, word-shared, >0x7FF-only shared, per-program plain, per-program word,
    // and unfilled (>4096 non-ASCII columns) modes.
    String[] patterns = {
      "[a-z]+",
      "\\b\\w+\\b",
      "[\\u4e00-\\u9fff]+",
      "[а-яА-ЯёЁ]+",
      "\\p{L}+",
      "(?U)\\b[а-яА-ЯёЁ]+\\b",
      "(?U)\\b\\p{L}+\\b",
      manyCols.toString()
    };
    for (String regex : patterns) {
      Pattern pattern = Pattern.compile(regex);
      StartRegisterSearch search = pattern.startRegisterSearch(new Pattern.ThreadCache());
      assertThat(search).as(regex).isNotNull();
      int[] lowColumns = search.lowColumnsForTesting();
      assertThat(lowColumns).hasSize(Dfa.LOW_LIMIT);
      for (int cp = 0; cp < Dfa.LOW_LIMIT; cp++) {
        int expected = cp < 128 ? cp : search.nonAsciiColumnForTesting(cp);
        assertThat(lowColumns[cp]).as("regex=%s cp=U+%04X", regex, cp).isEqualTo(expected);
      }
    }
  }

  @Test
  void caseFoldsAcrossLowLimitAgreeAcrossEngines() {
    String text =
        ("kK\u212a sS\u017f \u03a3\u03c3\u03c2 \u00df\u1e9e \u00e5\u00c5\u212b \u0451\u0401"
                + " \u0430\u0410 ")
            .repeat(64);
    String[] jdkParityPatterns = {
      "(?i)k+",
      "(?iu)k+",
      "(?i)s+",
      "(?iu)s+",
      "(?iu)\u03c3+",
      "(?iu)\u1e9e+",
      "(?iu)\u00e5+",
      "(?iu)\u0451+",
      "(?iu)[\u0430-\u044f]+"
    };
    for (String regex : jdkParityPatterns) {
      assertMatchesJdkAndScanners(regex, text);
    }
    // Pre-existing SafeRE/JDK divergence: SafeRE folds U+00DF (ß) to U+1E9E (ẞ) for (?iu)ß+, while
    // JDK only folds both ways for (?iu)ẞ+. Verify DFA/SRS matches SafeRE's NFA reference.
    Pattern sharpS = Pattern.compile("(?iu)\u00df+");
    Pattern sharpSRef =
        Pattern.compile(
            "(?iu)\u00df+", 0, EnginePathOptions.builder().dfa(false).startRegister(false).build());
    List<String> expected = spans(sharpSRef.matcher(text));
    assertThat(spans(sharpS.matcher(text))).isEqualTo(expected);
    assertThat(spans(sharpS.matcher(CharBuffer.wrap(text)))).isEqualTo(expected);
  }

  @Test
  void wordBoundariesWithAndWithoutUnicodeCharacterClass() {
    String text =
        ("abc \u043f\u0440\u0438\u0432\u0435\u0442 \u03b1\u03b2\u03b3 e\u0301 \u07ff\u0800"
                + " foo\u00dc foo\u20ac ")
            .repeat(48);
    String[] patterns = {
      "\\b\\w+\\b", "(?U)\\b\\w+\\b", "(?U)\\b\\p{L}+\\b", "(?U)\\B\\w+\\B", "(?U)\\w+"
    };
    for (String regex : patterns) {
      Pattern pattern = Pattern.compile(regex);
      Pattern reference =
          Pattern.compile(
              regex, 0, EnginePathOptions.builder().dfa(false).startRegister(false).build());
      assertThat(spans(pattern.matcher(text)))
          .as("String /%s/", regex)
          .isEqualTo(spans(reference.matcher(text)));
      assertThat(spans(pattern.matcher(CharBuffer.wrap(text))))
          .as("CharBuffer /%s/", regex)
          .isEqualTo(spans(reference.matcher(text)));
      Utf8Input utf8 = Utf8Input.validated(text.getBytes(UTF_8));
      assertThat(pattern.find(utf8)).as("UTF-8 /%s/", regex).isEqualTo(reference.find(utf8));
    }
  }

  @Test
  void multilineTerminatorsIncludingNextLineAndLineSeparator() {
    String text =
        ("\u043f\u0440\u0438\u0432\u0435\u0442\u0085"
                + "\u03b1\u03b2\u03b3\u2028"
                + "\u043c\u0438\u0440\u2029"
                + "\u0442\u0435\u0441\u0442\r\n"
                + "\u043a\u043e\u043d\u0435\u0446\r"
                + "end\n")
            .repeat(48);
    String[] patterns = {
      "(?m)^\\p{L}+$", "(?dm)^\\p{L}+$", "(?m)^.+$", "(?dm)^.+$", "(?m)^\\p{L}+", "(?m)\\p{L}+$"
    };
    for (String regex : patterns) {
      assertMatchesJdkAndScanners(regex, text);
    }
  }

  @Test
  void reverseDfaRegionsAndTransparentBoundsOnNonAscii() {
    String text =
        "\u0430\u0431\u0432 \u03b1\u03b2\u03b3 \u0433\u0434\u0435\u0085\u0436\u0437\u0438 "
            .repeat(80);
    String[] patterns = {"(?m)^\\p{L}+", "(?m)\\p{L}+$", "\\p{L}+", "(?U)\\b\\p{L}+\\b"};
    int[] starts = {0, 7, 257, 611, text.length() - 25};
    int[] ends = {29, 319, 913, text.length() - 5, text.length()};
    for (String regex : patterns) {
      Pattern pattern = Pattern.compile(regex);
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);
      for (int start : starts) {
        for (int end : ends) {
          if (start > end) {
            continue;
          }
          assertThat(spans(pattern.matcher(text).region(start, end)))
              .as("region(%d,%d) /%s/", start, end, regex)
              .isEqualTo(spans(jdk.matcher(text).region(start, end)));
          assertThat(spans(pattern.matcher(text).region(start, end).useTransparentBounds(true)))
              .as("transparent region(%d,%d) /%s/", start, end, regex)
              .isEqualTo(spans(jdk.matcher(text).region(start, end).useTransparentBounds(true)));
        }
      }
    }
  }

  @Test
  void patternSetDoSearchManyHandlesNonAsciiAndLowLimit() {
    PatternSet.Builder builder = new PatternSet.Builder(PatternSet.Anchor.UNANCHORED);
    builder.add("[а-яА-ЯёЁ]{4,}");
    builder.add("[\\u03b1-\\u03c9]{3,}");
    builder.add("[\\u07ff\\u0800]+");
    builder.add("(?m)^\\p{L}+$");
    PatternSet set = builder.compile();

    assertThat(set.match("\u043f\u0440\u0438\u0432\u0435\u0442")).containsExactly(0, 3);
    assertThat(set.match("\u03b1\u03b2\u03b3")).containsExactly(1, 3);
    assertThat(set.match("123 \u07ff 456")).containsExactly(2);
    assertThat(set.match("123 \u0800 456")).containsExactly(2);
    assertThat(set.match("123 \u043f\u0440\u0438\u0432\u0435\u0442 \u03b1\u03b2\u03b3"))
        .containsExactly(0, 1);
  }

  @Test
  void startRegisterSearchFallsBackWhenNonAsciiColumnsExceedLimit() {
    StringBuilder manyCols = new StringBuilder("[");
    for (int cp = 0x100; cp < 0x3000; cp += 2) {
      manyCols.append((char) cp);
    }
    manyCols.append("]+");
    Pattern pattern = Pattern.compile(manyCols.toString());
    StartRegisterSearch search = pattern.startRegisterSearch(new Pattern.ThreadCache());
    assertThat(search).isNotNull();

    String input = "abc\u0100\u0102\u0104def\u0101\u0106";
    // Non-ASCII below LOW_LIMIT hits the unfilled column and returns FALLBACK.
    assertThat(search.searchForTesting(new StringInputScanner(input), 0))
        .isEqualTo(StartRegisterSearch.FALLBACK);
    // Pure ASCII with no match still answers NO_MATCH directly.
    assertThat(search.searchForTesting(new StringInputScanner("abcdef"), 0))
        .isEqualTo(StartRegisterSearch.NO_MATCH);
    // Full matcher falls back cleanly to DFA and returns exact spans.
    assertThat(spans(pattern.matcher(input))).containsExactly("3-6", "10-11");
  }

  private static void assertMatchesJdkAndScanners(String regex, String text) {
    Pattern pattern = Pattern.compile(regex);
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);
    List<String> expected = spans(jdk.matcher(text));
    assertThat(spans(pattern.matcher(text))).as("String /%s/", regex).isEqualTo(expected);
    assertThat(spans(pattern.matcher(CharBuffer.wrap(text))))
        .as("CharBuffer /%s/", regex)
        .isEqualTo(expected);
    Utf8Input utf8 = Utf8Input.validated(text.getBytes(UTF_8));
    assertThat(pattern.find(utf8)).as("UTF-8 /%s/", regex).isEqualTo(!expected.isEmpty());
  }

  private static List<String> spans(Matcher matcher) {
    List<String> out = new ArrayList<>();
    while (matcher.find()) {
      out.add(matcher.start() + "-" + matcher.end());
    }
    return out;
  }

  private static List<String> spans(java.util.regex.Matcher matcher) {
    List<String> out = new ArrayList<>();
    while (matcher.find()) {
      out.add(matcher.start() + "-" + matcher.end());
    }
    return out;
  }
}
