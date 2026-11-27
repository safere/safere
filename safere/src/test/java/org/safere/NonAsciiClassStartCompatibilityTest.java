// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Compares {@code find}, {@code find(int)} and region searches with {@code java.util.regex} for
 * patterns whose candidate starts come from a character class with non-ASCII members: non-ASCII
 * literal alternations, classes around U+0800, case-fold partners and supplementary members.
 */
@DisabledForCrosscheck("differential test already compares SafeRE with java.util.regex")
class NonAsciiClassStartCompatibilityTest {

  private static final String[] ALPHABET = {
    "a",
    "b",
    "c",
    "k",
    "K",
    "q",
    "x",
    "y",
    "z",
    "s",
    " ",
    ",",
    ".",
    "\n",
    "\u00e9",
    "\u017f",
    "\u212a",
    "\u00df",
    "\u1e9e",
    "\u00e5",
    "\u00c5",
    "\u212b",
    "\u03a3",
    "\u03c3",
    "\u03c2",
    "\u03c8",
    "\u0416",
    "\u0436",
    "\u044f",
    "\u041c",
    "\u041a",
    "\u0421",
    "\u0422",
    "\u07fe",
    "\u07ff",
    "\u0800",
    "\u0801",
    "\u4e2d",
    "\ud83d\ude00",
    "\ud83d",
    "\ude00",
    "\u041c\u043e\u0441\u043a\u0432\u0430",
    "\u041a\u0430\u0437\u0430\u043d\u044c",
    "\u0421\u0430\u043c\u0430\u0440\u0430",
    "\u0422\u0432\u0435\u0440\u044c",
    "\u0391\u03b8\u03ae\u03bd\u03b1",
    "\u03a0\u03ac\u03c4\u03c1\u03b1",
    "Paris"
  };

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\u041c\u043e\u0441\u043a\u0432\u0430|\u041a\u0430\u0437\u0430\u043d\u044c"
            + "|\u0421\u0430\u043c\u0430\u0440\u0430|\u0422\u0432\u0435\u0440\u044c",
        "\u0391\u03b8\u03ae\u03bd\u03b1|\u03a0\u03ac\u03c4\u03c1\u03b1"
            + "|\u039b\u03ac\u03c1\u03b9\u03c3\u03b1",
        "\u041c\u043e\u0441\u043a\u0432\u0430|\u0391\u03b8\u03ae\u03bd\u03b1|Paris"
            + "|\u0421\u0430\u043c\u0430\u0440\u0430",
        "(?iu)\u043c\u043e\u0441\u043a\u0432\u0430|\u043a\u0430\u0437\u0430\u043d\u044c"
            + "|\u0441\u0430\u043c\u0430\u0440\u0430",
        "(?iu)\u03c3x|\u03c8y|\u0436z",
        "(?iu)k\u044f|\u0436k|\u03c8k",
        "(?i)k\u044f|\u017fa|\u212ab|\u0436c",
        "(?iu)\u00dfx|\u00e5y|\u0436z",
        "(?iu)\u017fa|\u0436b|\u03c8c",
        "[\u07ff\u0800q]x",
        "\u07ffa|\u0800b|qc",
        "[\u07fe-\u0801]y",
        "\ud83d\ude00a|\u0436b|\u03c8c",
        "[\ud83d\ude00\u0436\u03c8]z",
        "[,;.] \\p{Lu}",
        "\u4e2da|\u0436b|\u03c8c"
      })
  void matchesJdk(String regex) {
    Pattern pattern = Pattern.compile(regex);
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);
    Random random = new Random(regex.hashCode());
    for (int trial = 0; trial < 200; trial++) {
      String text = randomText(random, random.nextInt(40));
      assertThat(spans(pattern.matcher(text)))
          .as("find() of /%s/ on %s", regex, text)
          .isEqualTo(spans(jdk.matcher(text)));
      for (int from = 0; from <= text.length(); from++) {
        assertThat(findFrom(pattern.matcher(text), from))
            .as("find(%d) of /%s/ on %s", from, regex, text)
            .isEqualTo(findFrom(jdk.matcher(text), from));
      }
      int start = text.isEmpty() ? 0 : random.nextInt(text.length());
      int end = start + random.nextInt(text.length() - start + 1);
      assertThat(spans(pattern.matcher(text).region(start, end)))
          .as("region(%d, %d) of /%s/ on %s", start, end, regex, text)
          .isEqualTo(spans(jdk.matcher(text).region(start, end)));
    }
  }

  private static String randomText(Random random, int tokens) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < tokens; i++) {
      sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
    }
    return sb.toString();
  }

  private static List<Integer> spans(Matcher matcher) {
    List<Integer> spans = new ArrayList<>();
    while (matcher.find()) {
      spans.add(matcher.start());
      spans.add(matcher.end());
    }
    return spans;
  }

  private static List<Integer> spans(java.util.regex.Matcher matcher) {
    List<Integer> spans = new ArrayList<>();
    while (matcher.find()) {
      spans.add(matcher.start());
      spans.add(matcher.end());
    }
    return spans;
  }

  private static List<Integer> findFrom(Matcher matcher, int from) {
    return matcher.find(from) ? List.of(matcher.start(), matcher.end()) : List.of();
  }

  private static List<Integer> findFrom(java.util.regex.Matcher matcher, int from) {
    return matcher.find(from) ? List.of(matcher.start(), matcher.end()) : List.of();
  }
}
