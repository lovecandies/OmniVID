package com.omnivid.api.account;

import com.omnivid.api.auth.CurrentUserService;
import com.omnivid.api.quota.UserQuotaResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account")
public class AccountController {
    private final AccountService accounts;
    private final AccountSessionService sessions;
    private final CurrentUserService currentUser;

    public AccountController(AccountService accounts, AccountSessionService sessions, CurrentUserService currentUser) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.currentUser = currentUser;
    }

    @GetMapping("/quota")
    UserQuotaResponse quota() {
        return accounts.quota();
    }

    @PostMapping("/email/verification/request")
    AccountTokenResponse requestEmailVerification() {
        return accounts.requestEmailVerification();
    }

    @PostMapping("/email/verification/confirm")
    PasswordChangeResponse confirmEmailVerification(@Valid @RequestBody EmailVerificationConfirmRequest request) {
        return accounts.confirmEmailVerification(request);
    }

    @PostMapping("/password/forgot")
    AccountTokenResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return accounts.forgotPassword(request);
    }

    @PostMapping("/password/reset")
    PasswordChangeResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return accounts.resetPassword(request);
    }

    @PostMapping("/password/change")
    PasswordChangeResponse changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return accounts.changePassword(request);
    }

    @GetMapping("/sessions")
    List<AccountSessionResponse> sessions(HttpServletRequest request) {
        return sessions.list(currentUser.requireUser(), request);
    }

    @DeleteMapping("/sessions/{sessionId}")
    void deleteSession(@PathVariable String sessionId, HttpServletRequest request) {
        sessions.delete(currentUser.requireUser(), sessionId, request);
    }

    @GetMapping("/export")
    Map<String, Object> exportData() {
        return accounts.exportData();
    }

    @DeleteMapping
    AccountDeletionResponse deleteAccount(
            @Valid @RequestBody AccountDeletionRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        AccountDeletionResponse response = accounts.deleteAccount(request, sessions);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(servletRequest, servletResponse, authentication);
        return response;
    }
}
