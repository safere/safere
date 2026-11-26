// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

/** Conservative retained-memory estimates shared by the deterministic search caches. */
final class DfaCacheBudget {
  /**
   * Estimated retained data budget for each DFA cache. A Pattern can retain several caches per
   * thread, and a resize can briefly keep both copies of an array live.
   */
  static final long CACHE_BYTE_BUDGET = 16L * 1024 * 1024;

  // Charge 8 bytes per reference to avoid querying the VM. On HotSpot with compressed references,
  // this charges twice the actual size of each reference array element.
  static final int REFERENCE_BYTES = Long.BYTES;
  // Allow for VM-dependent headers, a map entry, and the optional state accelerator.
  static final int STATE_OVERHEAD_BYTES = 256;

  private DfaCacheBudget() {}
}
