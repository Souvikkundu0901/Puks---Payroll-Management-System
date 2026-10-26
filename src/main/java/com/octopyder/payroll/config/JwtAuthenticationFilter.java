package com.octopyder.payroll.config;

import com.nimbusds.jwt.JWTClaimsSet;
import com.octopyder.payroll.auth.TokenService;
import com.octopyder.payroll.common.CurrentUser;
import com.octopyder.payroll.people.Role;
import com.octopyder.payroll.people.User;
import com.octopyder.payroll.people.UserRepository;
import com.octopyder.payroll.people.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final TokenService tokenService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(TokenService tokenService, UserRepository userRepository) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                JWTClaimsSet claims = tokenService.verifyAccessToken(token);
                UUID userId = UUID.fromString(claims.getSubject());
                long tokenVersion = claims.getLongClaim("tv");

                Optional<User> userOpt = userRepository.findById(userId);
                if (userOpt.isPresent()) {
                    User user = userOpt.get();
                    // Instant revocation check per BR-S5 & ARCHITECTURE §4
                    if (user.getStatus() == UserStatus.ACTIVE
                        && user.getDeletedAt() == null
                        && user.getTokenVersion() == tokenVersion) {

                        Role role = user.getRole();
                        CurrentUser currentUser = new CurrentUser(
                            user.getId(),
                            user.getCode(),
                            user.getEmail(),
                            role,
                            user.getTokenVersion()
                        );

                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            currentUser,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
                        );
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                }
            } catch (Exception ignored) {
                // Invalid or expired token: clear context, downstream security will handle unauthorized requests
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
