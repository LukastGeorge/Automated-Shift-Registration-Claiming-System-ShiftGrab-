package com.shiftgrab.identity;

import java.util.List;
import java.util.Optional;

/** Read access to user profiles for other modules, e.g. to address an e-mail (SG-41). */
public interface Users {

    Optional<UserAccount> findById(long userId);

    /** Staff who asked to be reminded before shifts open (SG-42). */
    List<UserAccount> findReminderRecipients();
}
