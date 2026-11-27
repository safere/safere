// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pins the exception type of replacement strings that contain more than one error. The JDK reads a
 * replacement from left to right and reports the first error, so a reference to a missing group
 * must win over a syntax error later in the string, on every replacement entry point.
 */
class ReplacementErrorOrderTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      value = {
        // regex | input | replacement
        "a | a | $9$",
        "a | aa | x$9$",
        "a | a | $1\\",
        "(a) | a | $2${",
        "[a-z] | ab1 | $1${}",
        "z | zzz | $10${x",
        "(?<word>a) | a | ${w0rd}$9",
        "(?<word>a) | a | ${nope}$9$",
        "(?<word>a) | a | ${1}$9",
        "(?<word>a) | a | ${word}$9$",
      })
  void replaceReportsTheFirstErrorLikeTheJdk(String regex, String input, String replacement) {
    Class<? extends Throwable> expected = jdkReplaceAllFailure(regex, input, replacement);
    assertThat(expected).isNotNull();

    assertThatThrownBy(() -> Pattern.compile(regex).matcher(input).replaceAll(replacement))
        .as("replaceAll /%s/ with %s", regex, replacement)
        .isExactlyInstanceOf(expected);
    assertThatThrownBy(() -> Pattern.compile(regex).matcher(input).replaceFirst(replacement))
        .as("replaceFirst /%s/ with %s", regex, replacement)
        .isExactlyInstanceOf(expected);
    assertThatThrownBy(
            () -> {
              Matcher m = Pattern.compile(regex).matcher(input);
              StringBuilder sb = new StringBuilder();
              while (m.find()) {
                m.appendReplacement(sb, replacement);
              }
            })
        .as("appendReplacement /%s/ with %s", regex, replacement)
        .isExactlyInstanceOf(expected);
    assertThatThrownBy(() ->
            Pattern.compile(regex)
                .matcher(utf8(input))
                .replaceAll((bytes, offset, length) -> {}, utf8(replacement)))
        .as("UTF-8 replaceAll /%s/ with %s", regex, replacement)
        .isExactlyInstanceOf(expected);
    assertThatThrownBy(() ->
            Pattern.compile(regex)
                .matcher(utf8(input))
                .replaceFirst((bytes, offset, length) -> {}, utf8(replacement)))
        .as("UTF-8 replaceFirst /%s/ with %s", regex, replacement)
        .isExactlyInstanceOf(expected);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {"a | xyz | $9$", "a | xyz | $", "(?<word>a) | xyz | ${nope}$9"})
  void replaceWithoutAMatchDoesNotParseTheReplacement(
      String regex, String input, String replacement) {
    assertThat(java.util.regex.Pattern.compile(regex).matcher(input).replaceAll(replacement))
        .isEqualTo(input);
    assertThat(Pattern.compile(regex).matcher(input).replaceAll(replacement)).isEqualTo(input);
    assertThat(Pattern.compile(regex).matcher(input).replaceFirst(replacement)).isEqualTo(input);
  }

  private static Class<? extends Throwable> jdkReplaceAllFailure(
      String regex, String input, String replacement) {
    try {
      String unused = java.util.regex.Pattern.compile(regex).matcher(input).replaceAll(replacement);
      return null;
    } catch (RuntimeException e) {
      return e.getClass();
    }
  }

  private static Utf8Input utf8(String text) {
    return Utf8Input.trusted(text.getBytes(UTF_8));
  }
}
