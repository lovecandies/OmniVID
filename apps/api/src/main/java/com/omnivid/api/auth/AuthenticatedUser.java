package com.omnivid.api.auth;

import java.io.Serial;
import java.io.Serializable;

public record AuthenticatedUser(long id, String email, String nickname) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public static AuthenticatedUser from(UserAccount user) {
        return new AuthenticatedUser(user.id(), user.email(), user.nickname());
    }
}
