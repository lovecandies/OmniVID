package com.omnivid.api.account;

import com.omnivid.api.auth.AuthenticatedUser;
import com.omnivid.api.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

@Service
@SuppressWarnings({"rawtypes", "unchecked"})
public class AccountSessionService {
    private final ObjectProvider<FindByIndexNameSessionRepository> sessions;

    public AccountSessionService(ObjectProvider<FindByIndexNameSessionRepository> sessions) {
        this.sessions = sessions;
    }

    public List<AccountSessionResponse> list(AuthenticatedUser user, HttpServletRequest request) {
        FindByIndexNameSessionRepository repository = sessions.getIfAvailable();
        HttpSession currentSession = request.getSession(false);
        String currentSessionId = currentSession == null ? "" : currentSession.getId();
        if (repository == null) {
            if (currentSession == null) {
                return List.of();
            }
            return List.of(new AccountSessionResponse(
                    currentSession.getId(),
                    user.email(),
                    Instant.ofEpochMilli(currentSession.getCreationTime()),
                    Instant.ofEpochMilli(currentSession.getLastAccessedTime()),
                    currentSession.getMaxInactiveInterval(),
                    true
            ));
        }

        Map<String, ? extends Session> activeSessions = repository.findByPrincipalName(user.email());
        return activeSessions.entrySet().stream()
                .map(entry -> toResponse(entry.getKey(), entry.getValue(), user.email(), entry.getKey().equals(currentSessionId)))
                .sorted(Comparator.comparing(AccountSessionResponse::lastAccessedAt).reversed())
                .toList();
    }

    public void delete(AuthenticatedUser user, String sessionId, HttpServletRequest request) {
        FindByIndexNameSessionRepository repository = sessions.getIfAvailable();
        HttpSession currentSession = request.getSession(false);
        if (repository == null) {
            if (currentSession != null && currentSession.getId().equals(sessionId)) {
                currentSession.invalidate();
                return;
            }
            throw new ApiException(HttpStatus.CONFLICT, "Indexed session repository is not available");
        }

        Map<String, ? extends Session> activeSessions = repository.findByPrincipalName(user.email());
        if (!activeSessions.containsKey(sessionId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Session not found");
        }
        repository.deleteById(sessionId);
    }

    public int invalidateAll(String principalName) {
        FindByIndexNameSessionRepository repository = sessions.getIfAvailable();
        if (repository == null) {
            return 0;
        }
        Map<String, ? extends Session> activeSessions = repository.findByPrincipalName(principalName);
        activeSessions.keySet().forEach(repository::deleteById);
        return activeSessions.size();
    }

    private AccountSessionResponse toResponse(String id, Session session, String principalName, boolean current) {
        return new AccountSessionResponse(
                id,
                principalName,
                session.getCreationTime(),
                session.getLastAccessedTime(),
                session.getMaxInactiveInterval().toSeconds(),
                current
        );
    }
}
