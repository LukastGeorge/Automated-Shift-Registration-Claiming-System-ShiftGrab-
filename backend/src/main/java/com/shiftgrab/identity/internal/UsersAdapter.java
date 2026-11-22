package com.shiftgrab.identity.internal;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.shiftgrab.identity.UserAccount;
import com.shiftgrab.identity.Users;

@Component
class UsersAdapter implements Users {

    private final UserAccountRepository repository;

    UsersAdapter(UserAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<UserAccount> findById(long userId) {
        return repository.findById(userId);
    }

    @Override
    public List<UserAccount> findReminderRecipients() {
        return repository.findStaffWithReminders();
    }
}
