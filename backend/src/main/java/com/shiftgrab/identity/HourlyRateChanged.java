package com.shiftgrab.identity;

import java.math.BigDecimal;

/**
 * A manager changed a staff member's hourly rate (SG-25). Only future claims use the new rate: each registration
 * keeps the rate snapshotted at claim time (BR-10). Consumed by audit (SG-52).
 */
public record HourlyRateChanged(long userId, long actorId, BigDecimal oldRate, BigDecimal newRate) {
}
