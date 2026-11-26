// This file is part of a Java port of RE2 (https://github.com/google/re2).
// Original RE2 code is Copyright (c) 2009 The RE2 Authors.
// Modifications and Java port Copyright (c) 2026 Eddie Aftandilian.
// Licensed under the BSD 3-Clause License (see LICENSE file).

package org.safere;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;

/** Conservative retained-memory estimates shared by the deterministic search caches. */
final class DfaCacheBudget {
  /**
   * Estimated retained data budget for each DFA cache. A Pattern can retain several caches per
   * thread, and a resize can briefly keep both copies of an array live.
   */
  static final long CACHE_BYTE_BUDGET = 16L * 1024 * 1024;

  // Read the effective VM setting once, when the first cache needs its row size.
  static final int REFERENCE_BYTES = referenceBytes();
  // Allow for VM-dependent headers, a map entry, and the optional state accelerator.
  static final int STATE_OVERHEAD_BYTES = 256;

  private DfaCacheBudget() {}

  private static int referenceBytes() {
    Module management = ModuleLayer.boot().findModule("jdk.management").orElse(null);
    if (management == null || !DfaCacheBudget.class.getModule().canRead(management)) {
      return Long.BYTES;
    }
    return hotSpotReferenceBytes();
  }

  private static int hotSpotReferenceBytes() {
    try {
      var bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
      if (bean != null && Boolean.parseBoolean(bean.getVMOption("UseCompressedOops").getValue())) {
        return Integer.BYTES;
      }
    } catch (IllegalArgumentException | SecurityException ignored) {
      // The optional VM setting is unavailable. Keep the conservative estimate.
    }
    return Long.BYTES;
  }
}
