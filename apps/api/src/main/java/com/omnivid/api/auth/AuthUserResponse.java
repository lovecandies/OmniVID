package com.omnivid.api.auth;

public record AuthUserResponse(
        long id,
        String email,
        String nickname,
        boolean emailVerified,
        boolean disabled
) {
    public static AuthUserResponse from(AuthenticatedUser user) {
        return new AuthUserResponse(user.id(), user.email(), user.nickname(), false, false);
    }

    public static AuthUserResponse from(UserAccount user) {
        return new AuthUserResponse(user.id(), user.email(), user.nickname(), user.emailVerified(), user.disabled());
    }
}
