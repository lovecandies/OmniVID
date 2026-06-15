package com.omnivid.api.admin;

import java.util.List;

public record AdminUserDetail(
        AdminUserSummary user,
        List<AdminProviderSummary> providers
) {
}
