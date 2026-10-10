package com.hotelpms.auth.dto;

import com.hotelpms.auth.entity.Role;
import com.hotelpms.auth.entity.RoleCode;
import com.hotelpms.auth.entity.User;
import java.util.List;

public record UserResponse(Long id, String email, String fullName, List<RoleCode> roles) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(),
                user.getRoles().stream().map(Role::getCode).sorted().toList());
    }
}
