package com.hotelpms.auth.dto;

import com.hotelpms.auth.validation.ValidPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @ValidPassword String password,
        @NotBlank @Size(max = 150) String fullName,
        @Size(max = 30) String phoneNumber) { }
