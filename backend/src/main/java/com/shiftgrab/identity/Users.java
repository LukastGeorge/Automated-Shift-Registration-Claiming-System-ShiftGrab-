package com.shiftgrab.identity;

import java.util.Optional;

/** Read access to user profiles for other modules, e.g. to address an e-mail (SG-41). */
public interface Users {

    Optional<UserAccount> findById(long userId);
}
