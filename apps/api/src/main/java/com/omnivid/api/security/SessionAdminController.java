package com.omnivid.api.security;

import com.omnivid.api.auth.UserAccount;
import com.omnivid.api.auth.UserAccountRepository;
import com.omnivid.api.common.ApiException;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/sessions")
@SuppressWarnings({"rawtypes", "unchecked"})
public class SessionAdminController {
    private final UserAccountRepository users;
    private final ObjectProvider<FindByIndexNameSessionRepository> sessions;

    public SessionAdminController(
            UserAccountRepository users,
            ObjectProvider<FindByIndexNameSessionRepository> sessions
    ) {
        this.users = users;
        this.sessions = sessions;
    }

    @PostMapping("/users/{userId}/invalidate")
    SessionInvalidationResponse invalidateUserSessions(@PathVariable long userId) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        FindByIndexNameSessionRepository repository = sessions.getIfAvailable();
        if (repository == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Indexed session repository is not available",
                    "Start the API with the redis or docker profile before invalidating sessions",
                    "spring.session.store-type must be redis"
            );
        }

        Map<String, ? extends Session> activeSessions = repository.findByPrincipalName(user.email());
        activeSessions.keySet().forEach(repository::deleteById);
        return new SessionInvalidationResponse(user.id(), user.email(), activeSessions.size());
    }
}
