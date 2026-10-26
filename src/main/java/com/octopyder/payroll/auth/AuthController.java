package com.octopyder.payroll.auth;

import com.octopyder.payroll.auth.dto.ChangePasswordRequest;
import com.octopyder.payroll.auth.dto.LoginRequest;
import com.octopyder.payroll.auth.dto.LoginResponse;
import com.octopyder.payroll.auth.dto.UserDto;
import com.octopyder.payroll.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
        @Valid @RequestBody LoginRequest request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        LoginResponse response = authService.login(request, httpRequest, httpResponse);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        LoginResponse response = authService.refresh(httpRequest, httpResponse);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        authService.logout(httpRequest, httpResponse);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<UserDto> getMe(@AuthenticationPrincipal CurrentUser currentUser) {
        UserDto dto = authService.getMe(currentUser);
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> changePassword(
        @AuthenticationPrincipal CurrentUser currentUser,
        @Valid @RequestBody ChangePasswordRequest request
    ) {
        authService.changePassword(currentUser, request);
        return ResponseEntity.ok().build();
    }
}
