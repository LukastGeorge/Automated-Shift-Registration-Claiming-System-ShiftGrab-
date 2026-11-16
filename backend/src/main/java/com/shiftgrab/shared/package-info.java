/**
 * Cross-cutting building blocks used by every module: error handling, security configuration and locking.
 * Declared OPEN so other modules may use its sub-packages directly.
 */
@ApplicationModule(displayName = "Shared", type = ApplicationModule.Type.OPEN)
package com.shiftgrab.shared;

import org.springframework.modulith.ApplicationModule;
