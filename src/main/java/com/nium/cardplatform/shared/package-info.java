/**
 * Shared kernel — cross-cutting infrastructure available to all modules.
 * Declared OPEN so Spring Modulith treats all its types as accessible
 * to card, transaction, and audit without encapsulation enforcement.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.nium.cardplatform.shared;

import org.springframework.modulith.ApplicationModule;