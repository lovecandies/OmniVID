package com.omnivid.api.auth;

public record AuthMeResponse(boolean authenticated, AuthUserResponse user) {
    public static AuthMeResponse authenticated(AuthenticatedUser user) {
        return new AuthMeResponse(true, AuthUserResponse.from(user));
    }

    public static AuthMeResponse authenticated(UserAccount user) {
        return new AuthMeResponse(true, AuthUserResponse.from(user));
    }

    public static AuthMeResponse unauthenticated() {
        return new AuthMeResponse(false, null);
    }
}
