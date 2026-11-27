// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.CharBuffer;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Adversarial differential tests for DFA-loop start acceleration under position-dependent
 * assertions.
 */
@DisabledForCrosscheck("implementation test uses package-private SafeRE internals")
class StartAcceleratorDifferentialTest {

  private static final EnginePathOptions ACCELERATED =
      EnginePathOptions.builder().startAcceleration(true).build();
  private static final EnginePathOptions UNACCELERATED =
      EnginePathOptions.builder().startAcceleration(false).build();

  @ParameterizedTest
  @ValueSource(strings = {"😀", "🧪", "👍"})
  void wideExpansionsPreserveGraphemeProgramLowSurrogateStarts(String excluded) {
    String padding = "p".repeat(300);
    String text = padding + excluded + "error!";
    for (String repetition : List.of("+", "+?", "{1,3}")) {
      String regex = "([^" + excluded + "]" + repetition + ")error\\X";
      Pattern accelerated = Pattern.compile(regex, 0, ACCELERATED);
      Pattern control = Pattern.compile(regex, 0, UNACCELERATED);
      Matcher expected = control.matcher(text);
      assertThat(expected.find()).isTrue();
      assertThat(expected.start()).isEqualTo(padding.length() + 1);
      assertThat(expected.group(1)).isEqualTo(excluded.substring(1));
      for (CharSequence input : List.of(text, CharBuffer.wrap(text))) {
        Matcher actual = accelerated.matcher(input);
        assertThat(actual.find()).as("%s on %s", regex, input.getClass()).isTrue();
        assertThat(actual.start()).isEqualTo(expected.start());
        assertThat(actual.end()).isEqualTo(expected.end());
        assertThat(actual.group(1)).isEqualTo(expected.group(1));
        assertThat(actual.find()).isFalse();
      }
      assertThat(accelerated.asPredicate().test(text)).isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "(b|(?m:^a))cd[0-9]",
        "((?m:^first)|second)token",
        "(b|\\Aa)cd[0-9]",
        "(\\Aprefix|unanchored)token",
        "(b|\\ba)cd[0-9]",
        "(b|\\Ba)cd[0-9]",
        "(\\bword|regular)match",
        "(b|(?m:a$))cd",
        "(b|a\\z)cd"
      })
  @DisplayName("Ablation: accelerated DFA matches unaccelerated baseline on false candidates")
  void acceleratedDfaMatchesBaselineOnFalseCandidates(String regex) {
    List<String> adversarialInputs =
        List.of(
            "x".repeat(200) + "0cb\r1bacd19c1__19x y_",
            "x".repeat(200) + "0cb1bacd19c1__19x y_",
            "x".repeat(200) + "foo_acd19bar",
            "x".repeat(200) + "foo acd19bar",
            "x".repeat(200) + "prefixfirsttoken_rest",
            "x".repeat(200) + "123firsttoken_rest",
            "x".repeat(200) + "nonwordmatch_rest",
            "x".repeat(200) + "wordmatch rest");

    Pattern accelerated = Pattern.compile(regex, 0, ACCELERATED);
    Pattern control = Pattern.compile(regex, 0, UNACCELERATED);
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(regex);

    for (String input : adversarialInputs) {
      boolean expected = control.matcher(input).find();
      boolean actual = accelerated.matcher(input).find();
      assertThat(actual)
          .as("String find() divergence for /%s/ on input %s", regex, input)
          .isEqualTo(expected);

      Utf8Input utf8 = Utf8Input.validated(input.getBytes(UTF_8));
      boolean actualUtf8 = accelerated.find(utf8);
      assertThat(actualUtf8)
          .as("UTF-8 find() divergence for /%s/ on input %s", regex, input)
          .isEqualTo(expected);

      // Verify consistency against JDK regex engine
      boolean jdkExpected = jdk.matcher(input).find();
      assertThat(actual)
          .as("SafeRE vs JDK divergence for /%s/ on input %s", regex, input)
          .isEqualTo(jdkExpected);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "(b|(?m:^a))cd[0-9]",
        "((?m:^first)|second)token",
        "(b|\\Aa)cd[0-9]",
        "(b|\\ba)cd[0-9]",
        "(\\bword|regular)match"
      })
  @DisplayName("Ablation: accelerated DFA matches unaccelerated baseline on true candidates")
  void acceleratedDfaMatchesBaselineOnTrueCandidates(String regex) {
    List<String> matchingInputs =
        List.of(
            "x".repeat(200) + "\nacd19c1__19x y_",
            "x".repeat(200) + "\r\nfirsttoken rest",
            "firsttoken at start of text",
            "x".repeat(200) + " wordmatch rest",
            "x".repeat(200) + "\nwordmatch rest");

    Pattern accelerated = Pattern.compile(regex, 0, ACCELERATED);
    Pattern control = Pattern.compile(regex, 0, UNACCELERATED);

    for (String input : matchingInputs) {
      boolean expected = control.matcher(input).find();
      boolean actual = accelerated.matcher(input).find();
      assertThat(actual)
          .as("String find() divergence for /%s/ on input %s", regex, input)
          .isEqualTo(expected);

      Utf8Input utf8 = Utf8Input.validated(input.getBytes(UTF_8));
      boolean actualUtf8 = accelerated.find(utf8);
      assertThat(actualUtf8)
          .as("UTF-8 find() divergence for /%s/ on input %s", regex, input)
          .isEqualTo(expected);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\u041c\u043e\u0441\u043a\u0432\u0430|\u041a\u0430\u0437\u0430\u043d\u044c|\u0422\u0432",
        "(?iu)\u03c3x|\u03c8y|\u0436z",
        "(?iu)k\u044f|\u0436k|\u03c8k",
        "(?i)k\u044f|\u017fa|\u212ab|\u0436c",
        "(?iu)\u00dfx|\u00e5y|\u0436z",
        "[\u07ff\u0800q]x",
        "\ud83d\ude00a|\u0436b|\u03c8c",
        "[,;.] \\p{Lu}"
      })
  @DisplayName("Ablation: class start scans over non-ASCII text match the unaccelerated search")
  void nonAsciiClassStartMatchesBaseline(String regex) {
    String[] alphabet = {
      "a",
      "k",
      "q",
      "x",
      "z",
      " ",
      ",",
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
      "\u0422",
      "\u07ff",
      "\u0800",
      "\u4e2d",
      "\ud83d\ude00",
      "\ud83d",
      "\ude00",
      "\u041c\u043e\u0441\u043a\u0432\u0430"
    };
    Pattern accelerated = Pattern.compile(regex, 0, ACCELERATED);
    Pattern control = Pattern.compile(regex, 0, UNACCELERATED);
    Random random = new Random(regex.hashCode());
    for (int trial = 0; trial < 300; trial++) {
      StringBuilder sb = new StringBuilder();
      int tokens = random.nextInt(40);
      for (int i = 0; i < tokens; i++) {
        sb.append(alphabet[random.nextInt(alphabet.length)]);
      }
      String text = sb.toString();
      String expected = spans(control.matcher(text));
      for (CharSequence input : List.of(text, CharBuffer.wrap(text))) {
        assertThat(spans(accelerated.matcher(input)))
            .as("/%s/ on %s %s", regex, input.getClass().getSimpleName(), text)
            .isEqualTo(expected);
      }
      // Lone surrogates are not valid UTF-8.
      if (text.codePoints().noneMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)) {
        Utf8Input utf8 = Utf8Input.validated(text.getBytes(UTF_8));
        assertThat(accelerated.find(utf8))
            .as("/%s/ on UTF-8 %s", regex, text)
            .isEqualTo(!expected.isEmpty());
      }
    }
  }

  private static String spans(Matcher matcher) {
    StringBuilder sb = new StringBuilder();
    while (matcher.find()) {
      sb.append(matcher.start()).append('-').append(matcher.end()).append(' ');
    }
    return sb.toString();
  }

  @Test
  void exactIssue711Reproducer() {
    String regex = "(b|(?m:^a))c[0-9]";
    String input = "0cb\r1bac19c1__19x y_";
    Pattern accelerated = Pattern.compile(regex, 0, ACCELERATED);
    Pattern control = Pattern.compile(regex, 0, UNACCELERATED);
    Utf8Input utf8 = Utf8Input.validated(input.getBytes(UTF_8));

    assertThat(control.find(utf8)).isFalse();
    assertThat(accelerated.find(utf8)).isFalse();
    assertThat(java.util.regex.Pattern.compile(regex).matcher(input).find()).isFalse();
  }
}
