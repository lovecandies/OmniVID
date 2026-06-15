package com.omnivid.api.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ClientIpResolver {
    private final boolean trustForwardedFor;

    public ClientIpResolver(@Value("${omnivid.security.trust-forwarded-for:true}") boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (trustForwardedFor && isTrustedProxy(remote) && forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].trim();
        }
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    private boolean isTrustedProxy(String remote) {
        if (remote == null || remote.isBlank()) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(remote);
            return address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isSiteLocalAddress();
        } catch (Exception ignored) {
            return false;
        }
    }
}
