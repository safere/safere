// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisabledForCrosscheck("checks SafeRE's compiler initialization")
class CompileInitializationTest {
  private static final String INDEX_NAME = "org/safere/UnicodeCaseFolding$UnicodeCaseClosureIndex";
  // Generated crosscheck tests copy this disabled test without its SafeRE-only probe class.
  private static final String PROBE_CLASS_NAME = "org.safere.CompileInitializationProbe";
  private static final String DFA_CACHE_BUDGET_NAME = "org/safere/DfaCacheBudget";

  @ParameterizedTest
  @ValueSource(strings = {"hello", "\\w+", "\\p{L}+", "(foo|bar)+"})
  void ordinaryCompilationDoesNotInitializeForcedEngineMetadata(String regex)
      throws IOException, InterruptedException, URISyntaxException {
    assertThat(runFreshProcess(regex))
        .as("forced-engine metadata initialization for %s", regex)
        .doesNotContain("Initializing 'org/safere/EnginePath'");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\\w+",
        "\\W+",
        "\\d+",
        "\\D+",
        "\\s+",
        "\\S+",
        "\\h+",
        "\\v+",
        "(?i)\\w+",
        "\\p{ASCII}+",
        "\\p{Lower}+",
        "(?iu)[h-j]+"
      })
  void builtInClassesAndCaseFoldingDoNotInitializeGeneratedUnicodeProperties(String regex)
      throws IOException, InterruptedException, URISyntaxException {
    assertThat(runFreshProcess(regex))
        .as("Unicode table initialization for %s", regex)
        .doesNotContain("Initializing 'org/safere/UnicodeGeneratedTables'");
  }

  @ParameterizedTest
  @ValueSource(strings = {"\\p{L}+", "\\p{script=Latin}+", "(?U)\\w+", "\\p{IsEmoji}+"})
  void unicodeClassesStillInitializeGeneratedUnicodeTables(String regex)
      throws IOException, InterruptedException, URISyntaxException {
    assertThat(runFreshProcess(regex))
        .as("Unicode table initialization for %s", regex)
        .contains("Initializing 'org/safere/UnicodeGeneratedTables'");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\\X", "(\\w+)\\X", "(\\d+)\\X", "(?:word|\\X)",
        "\\b{g}", "(\\w+)\\b{g}", "a\\b{g}", "(?U)(\\w+)\\X"
      })
  void graphemeProgramsPrepareTablesDuringCompilation(String regex)
      throws IOException, InterruptedException, URISyntaxException {
    assertThat(runFreshProcess(regex))
        .as("common search data preparation for %s", regex)
        .contains("Initializing 'org/safere/UnicodeGeneratedTables'")
        .contains("Initializing 'org/safere/GraphemeSupport'");
  }

  @ParameterizedTest
  @ValueSource(strings = {"\\Q\\X\\E", "\\Q\\b{g}\\E", "(?:\\X){0}", "(?:\\b{g}){0}"})
  void programsWithoutGraphemeInstructionsDoNotPrepareGraphemeTables(String regex)
      throws IOException, InterruptedException, URISyntaxException {
    assertThat(runFreshProcess(regex))
        .as("irrelevant grapheme preparation for %s", regex)
        .doesNotContain("Initializing 'org/safere/UnicodeGeneratedTables'")
        .doesNotContain("Initializing 'org/safere/GraphemeSupport'");
  }

  @ParameterizedTest
  @ValueSource(strings = {"([a-z])", "(\\w+)", "(\\w+)\\X", "(\\w+)\\b{g}"})
  void firstCaptureDoesNotInitializeSharedUnicodeTables(String regex)
      throws IOException, InterruptedException, URISyntaxException {
    String output = runFreshProcess(regex, "", "search");
    String marker = "SafeRE pattern compiled";
    assertThat(output).contains(marker);
    assertThat(output.substring(output.indexOf(marker) + marker.length()))
        .as("Unicode table initialization after compilation for %s", regex)
        .doesNotContain("Initializing 'org/safere/UnicodeGeneratedTables'")
        .doesNotContain("Initializing 'org/safere/GraphemeSupport'");
  }

  @ParameterizedTest
  @ValueSource(strings = {"(\\w+)\\X", "(\\w+)\\b{g}"})
  void concurrentCompilationsAndFirstSearchesReturnExpectedCaptures(String regex)
      throws IOException, InterruptedException, URISyntaxException {
    runFreshProcess(regex, "", "concurrent");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[h-j]+",
        "[0-9]+",
        "[Kk]",
        "[A-Za-z]+",
        "[AaBbCc]+",
        "\\p{L}+",
        "\\p{IsAlphabetic}+",
        "\\p{script=Latin}+",
        "\\p{L}[\\p{L}\\p{Nd}_]*"
      })
  void ordinaryCharacterClassesDoNotBuildUnicodeCaseIndex(String regex)
      throws IOException, InterruptedException, URISyntaxException {
    assertThat(runFreshProcess(regex))
        .as("index initialization for %s", regex)
        .doesNotContain(INDEX_NAME);
  }

  @Test
  void unicodeCaseClassBuildsIndexWithinSmallHeap()
      throws IOException, InterruptedException, URISyntaxException {
    assertThat(runFreshProcess("(?iu)[K-K]")).contains(INDEX_NAME);
  }

  @ParameterizedTest
  @ValueSource(strings = {"C", "C.UTF-8"})
  void fourMemberFoldClassStillBuildsIndexDuringCompilation(String locale)
      throws IOException, InterruptedException, URISyntaxException {
    assertThat(runFreshProcess("[Θθϑϴ]", locale)).contains(INDEX_NAME);
  }

  @Test
  void compilationDoesNotReadTheDfaCacheReferenceSize()
      throws IOException, InterruptedException, URISyntaxException {
    assertThat(runFreshProcess("a[bc]+")).doesNotContain(DFA_CACHE_BUDGET_NAME);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void firstDfaSearchUsesTheEffectiveHotSpotReferenceSize(boolean compressed)
      throws IOException, InterruptedException, URISyntaxException {
    String vm = System.getProperty("java.vm.name");
    assumeTrue(vm.contains("HotSpot") || vm.startsWith("OpenJDK"), "Requires HotSpot");
    String option = compressed ? "-XX:+UseCompressedOops" : "-XX:-UseCompressedOops";
    String expected = "SafeRE reference bytes: " + (compressed ? Integer.BYTES : Long.BYTES);

    assertThat(runFreshProcess("a[bc]+", "", "cache-budget", option))
        .containsSubsequence(
            "SafeRE pattern compiled", "Initializing '" + DFA_CACHE_BUDGET_NAME + "'", expected);
  }

  @Test
  void firstDfaSearchUsesConservativeSizeWithoutManagement()
      throws IOException, InterruptedException, URISyntaxException {
    assertThat(runFreshProcess("a[bc]+", "", "cache-budget", "--limit-modules=java.base"))
        .contains("SafeRE reference bytes: " + Long.BYTES);
  }

  private static String runFreshProcess(String regex)
      throws IOException, InterruptedException, URISyntaxException {
    return runFreshProcess(regex, "");
  }

  private static String runFreshProcess(String regex, String locale)
      throws IOException, InterruptedException, URISyntaxException {
    return runFreshProcess(regex, locale, "compile");
  }

  private static String runFreshProcess(
      String regex, String locale, String mode, String... vmOptions)
      throws IOException, InterruptedException, URISyntaxException {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String pathSeparator = System.getProperty("path.separator");
    String classPath =
        Path.of(Pattern.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            + pathSeparator
            + Path.of(
                CompileInitializationTest.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
    // Process arguments use the platform's native encoding, which may be ASCII in isolation.
    String encodedRegex =
        Base64.getEncoder().encodeToString(regex.getBytes(StandardCharsets.UTF_8));
    List<String> command = new ArrayList<>(List.of(java, "-Xmx12m", "-Xlog:class+init=info"));
    command.addAll(List.of(vmOptions));
    command.addAll(List.of("-cp", classPath, PROBE_CLASS_NAME, encodedRegex, mode));
    ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
    if (!locale.isEmpty()) {
      processBuilder.environment().put("LC_ALL", locale);
    }
    Process process = processBuilder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as(output).isZero();
    return output;
  }
}
