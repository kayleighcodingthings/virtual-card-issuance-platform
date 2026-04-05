/**
 * Card module — owns card lifecycle and expiry scheduling.
 * Declared OPEN because the transaction module requires direct access
 * to Card entities and CardService for balance mutation.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.nium.cardplatform.card;

import org.springframework.modulith.ApplicationModule;