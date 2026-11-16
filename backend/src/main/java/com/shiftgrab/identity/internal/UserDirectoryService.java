package com.shiftgrab.identity.internal;

import java.math.BigDecimal;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shiftgrab.identity.CurrentUserService;
import com.shiftgrab.identity.HourlyRateChanged;
import com.shiftgrab.identity.Role;
import com.shiftgrab.identity.UserAccount;
import com.shiftgrab.shared.error.BusinessException;
import com.shiftgrab.shared.error.ErrorCode;
import com.shiftgrab.shared.web.PageResponse;

/**
 * Manager view of users (SG-25). Users appear here after their first login, when their profile is provisioned
 * from the token (SG-10).
 */
@Service
public class UserDirectoryService {

    private final UserAccountRepository users;
    private final CurrentUserService currentUser;
    private final ApplicationEventPublisher events;

    UserDirectoryService(UserAccountRepository users, CurrentUserService currentUser,
            ApplicationEventPublisher events) {
        this.users = users;
        this.currentUser = currentUser;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserAccount> search(Role role, int page, int size) {
        return users.search(role, page, size);
    }

    /** Applies to future claims only (BR-10); setting the same rate again is a no-op without an event. */
    @Transactional
    public UserAccount setHourlyRate(long userId, BigDecimal hourlyRate) {
        UserAccount before = users.findByIdForUpdate(userId).orElseThrow(
                () -> new BusinessException(ErrorCode.USER_NOT_FOUND, "User " + userId + " does not exist."));
        if (before.hourlyRate().compareTo(hourlyRate) == 0) {
            return before;
        }
        UserAccount after = users.updateHourlyRate(userId, hourlyRate);
        events.publishEvent(new HourlyRateChanged(userId, currentUser.currentUser().id(), before.hourlyRate(),
                after.hourlyRate()));
        return after;
    }
}
