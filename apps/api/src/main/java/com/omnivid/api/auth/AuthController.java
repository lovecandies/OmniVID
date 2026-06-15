package com.omnivid.api.auth;

import com.omnivid.api.common.ApiException;
import com.omnivid.api.security.AdminAuthorityService;
import com.omnivid.api.security.LoginAttemptService;
import com.omnivid.api.security.LoginAttemptStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final CurrentUserService currentUserService;
    private final SecurityContextRepository securityContextRepository;
    private final AdminAuthorityService adminAuthorityService;
    private final LoginAttemptService loginAttempts;
    private final UserAccountRepository users;

    public AuthController(
            AuthService authService,
            CurrentUserService currentUserService,
            SecurityContextRepository securityContextRepository,
            AdminAuthorityService adminAuthorityService,
            LoginAttemptService loginAttempts,
            UserAccountRepository users
    ) {
        this.authService = authService;
        this.currentUserService = currentUserService;
        this.securityContextRepository = securityContextRepository;
        this.adminAuthorityService = adminAuthorityService;
        this.loginAttempts = loginAttempts;
        this.users = users;
    }

    @GetMapping("/me")
    AuthMeResponse me() {
        return currentUserService.currentUser()
                .flatMap(user -> users.findById(user.id()))
                .filter(user -> !user.disabled() && user.deletedAt() == null)
                .map(AuthMeResponse::authenticated)
                .orElseGet(AuthMeResponse::unauthenticated);
    }

    @PostMapping("/register")
    AuthMeResponse register(
            @Valid @RequestBody AuthRegisterRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        UserAccount user = authService.register(request);
        saveAuthentication(user, servletRequest, servletResponse);
        return AuthMeResponse.authenticated(user);
    }

    @PostMapping("/login")
    AuthMeResponse login(
            @Valid @RequestBody AuthLoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        loginAttempts.requireAllowed(servletRequest, request.email());
        UserAccount user;
        try {
            user = authService.login(request);
        } catch (ApiException exception) {
            LoginAttemptStatus status = loginAttempts.recordFailure(servletRequest, request.email());
            throw new ApiException(
                    exception.status(),
                    exception.getMessage(),
                    status.captchaRequired() ? "Captcha verification should be required before the next login attempt" : exception.suggestion(),
                    status.detail()
            );
        }
        loginAttempts.clear(servletRequest, request.email());
        saveAuthentication(user, servletRequest, servletResponse);
        return AuthMeResponse.authenticated(user);
    }

    @PostMapping("/logout")
    AuthMeResponse logout(HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(servletRequest, servletResponse, authentication);
        return AuthMeResponse.unauthenticated();
    }

    private void saveAuthentication(
            UserAccount user,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        AuthenticatedUser principal = AuthenticatedUser.from(user);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                adminAuthorityService.authorities(user.email())
        );
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(servletRequest));
        servletRequest.getSession(true)
                .setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, user.email());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, servletRequest, servletResponse);
    }
}
