package com.shiftgrab.shared.web;

import com.shiftgrab.shared.error.BusinessException;
import com.shiftgrab.shared.error.ErrorCode;

/**
 * Versions travel as entity tags: {@code ETag: "3"} on reads, {@code If-Match: "3"} on writes (SG-21).
 * Weak tags ({@code W/"3"}) are accepted because some proxies weaken ETags when they re-encode a response.
 */
public final class ETags {

    private ETags() {
    }

    /**
     * @throws BusinessException {@code PRECONDITION_REQUIRED} when the header is missing, and
     *         {@code CONCURRENT_MODIFICATION} when it cannot be a version we issued (so it can never match)
     */
    public static long requireVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new BusinessException(ErrorCode.PRECONDITION_REQUIRED,
                    "Send the ETag of the version you edited in the If-Match header.");
        }
        String tag = ifMatch.strip();
        if (tag.startsWith("W/")) {
            tag = tag.substring(2);
        }
        if (tag.length() >= 2 && tag.startsWith("\"") && tag.endsWith("\"")) {
            tag = tag.substring(1, tag.length() - 1);
        }
        try {
            return Long.parseLong(tag);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                    "If-Match does not match the current version. Reload and try again.");
        }
    }
}
