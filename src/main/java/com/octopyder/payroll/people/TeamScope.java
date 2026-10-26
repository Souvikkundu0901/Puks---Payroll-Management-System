package com.octopyder.payroll.people;

import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TeamScope {

    private final UserRepository userRepository;

    public TeamScope(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public void assertCanAccess(CurrentUser currentUser, UUID targetUserId) {
        if (currentUser == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        }
        if (currentUser.isAdmin()) {
            return;
        }
        if (currentUser.id().equals(targetUserId)) {
            return;
        }
        if (currentUser.isManager()) {
            boolean isTeamMember = userRepository.findById(targetUserId)
                .map(user -> currentUser.id().equals(user.getManagerId()))
                .orElse(false);
            if (isTeamMember) {
                return;
            }
        }
        throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Access denied for target resource");
    }
}
