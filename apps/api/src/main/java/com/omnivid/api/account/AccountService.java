package com.omnivid.api.account;

import com.omnivid.api.auth.AuthenticatedUser;
import com.omnivid.api.auth.CurrentUserService;
import com.omnivid.api.auth.UserAccount;
import com.omnivid.api.auth.UserAccountRepository;
import com.omnivid.api.common.ApiException;
import com.omnivid.api.quota.UserQuotaResponse;
import com.omnivid.api.quota.UserQuotaService;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private static final String EMAIL_VERIFY = "EMAIL_VERIFY";
    private static final String PASSWORD_RESET = "PASSWORD_RESET";

    private final CurrentUserService currentUser;
    private final UserAccountRepository users;
    private final AccountTokenRepository tokens;
    private final UserQuotaService quotas;
    private final AccountDataExportService exports;
    private final PasswordEncoder passwordEncoder;
    private final Duration emailTokenTtl;
    private final Duration passwordResetTokenTtl;
    private final boolean exposeDevTokens;
    private final SecureRandom random = new SecureRandom();

    public AccountService(
            CurrentUserService currentUser,
            UserAccountRepository users,
            AccountTokenRepository tokens,
            UserQuotaService quotas,
            AccountDataExportService exports,
            PasswordEncoder passwordEncoder,
            @Value("${omnivid.account.email-token-ttl:24h}") Duration emailTokenTtl,
            @Value("${omnivid.account.password-reset-token-ttl:30m}") Duration passwordResetTokenTtl,
            @Value("${omnivid.account.expose-dev-tokens:true}") boolean exposeDevTokens
    ) {
        this.currentUser = currentUser;
        this.users = users;
        this.tokens = tokens;
        this.quotas = quotas;
        this.exports = exports;
        this.passwordEncoder = passwordEncoder;
        this.emailTokenTtl = normalize(emailTokenTtl, Duration.ofHours(24));
        this.passwordResetTokenTtl = normalize(passwordResetTokenTtl, Duration.ofMinutes(30));
        this.exposeDevTokens = exposeDevTokens;
    }

    public UserQuotaResponse quota() {
        return quotas.current(requireActiveUser().id());
    }

    @Transactional
    public AccountTokenResponse requestEmailVerification() {
        UserAccount user = requireActiveUser();
        if (user.emailVerified()) {
            return new AccountTokenResponse(EMAIL_VERIFY, "Email is already verified", Instant.now(), null);
        }
        return createToken(user.id(), EMAIL_VERIFY, emailTokenTtl, "Email verification token created");
    }

    @Transactional
    public PasswordChangeResponse confirmEmailVerification(EmailVerificationConfirmRequest request) {
        AccountToken token = tokens.findUsable(request.token(), EMAIL_VERIFY)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Email verification token is invalid or expired"));
        users.markEmailVerified(token.userId());
        tokens.consume(token.token());
        return new PasswordChangeResponse(token.userId(), "Email verified");
    }

    @Transactional
    public AccountTokenResponse forgotPassword(ForgotPasswordRequest request) {
        String email = normalizeEmail(request.email());
        return users.findByEmail(email)
                .filter(user -> !user.disabled() && user.deletedAt() == null)
                .map(user -> createToken(user.id(), PASSWORD_RESET, passwordResetTokenTtl, "Password reset token created"))
                .orElseGet(() -> new AccountTokenResponse(
                        PASSWORD_RESET,
                        "If the email exists, a password reset token has been issued",
                        Instant.now().plus(passwordResetTokenTtl),
                        null
                ));
    }

    @Transactional
    public PasswordChangeResponse resetPassword(ResetPasswordRequest request) {
        AccountToken token = tokens.findUsable(request.token(), PASSWORD_RESET)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Password reset token is invalid or expired"));
        UserAccount user = users.findById(token.userId())
                .filter(account -> !account.disabled() && account.deletedAt() == null)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        users.updatePassword(user.id(), passwordEncoder.encode(request.newPassword()));
        tokens.consume(token.token());
        return new PasswordChangeResponse(user.id(), "Password reset successfully");
    }

    @Transactional
    public PasswordChangeResponse changePassword(ChangePasswordRequest request) {
        UserAccount user = requireActiveUser();
        if (!passwordEncoder.matches(request.currentPassword(), user.passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Current password is incorrect");
        }
        users.updatePassword(user.id(), passwordEncoder.encode(request.newPassword()));
        return new PasswordChangeResponse(user.id(), "Password changed successfully");
    }

    public Map<String, Object> exportData() {
        UserAccount user = requireActiveUser();
        return exports.export(user, quotas.current(user.id()));
    }

    @Transactional
    public AccountDeletionResponse deleteAccount(AccountDeletionRequest request, AccountSessionService sessions) {
        UserAccount user = requireActiveUser();
        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Password is incorrect");
        }
        users.softDelete(user.id());
        int invalidated = sessions.invalidateAll(user.email());
        return new AccountDeletionResponse(user.id(), true, invalidated);
    }

    private AccountTokenResponse createToken(long userId, String purpose, Duration ttl, String message) {
        String token = generateToken();
        Instant expiresAt = Instant.now().plus(ttl);
        tokens.insert(token, userId, purpose, expiresAt);
        return new AccountTokenResponse(purpose, message, expiresAt, exposeDevTokens ? token : null);
    }

    private UserAccount requireActiveUser() {
        AuthenticatedUser principal = currentUser.requireUser();
        return users.findById(principal.id())
                .filter(user -> !user.disabled() && user.deletedAt() == null)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Authentication required"));
    }

    private Duration normalize(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
