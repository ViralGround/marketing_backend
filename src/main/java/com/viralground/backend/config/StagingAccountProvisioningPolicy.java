package com.viralground.backend.config;

import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.entity.Role;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Restricts the short pre-E2E provisioning window to declared synthetic identities. */
@Component
public final class StagingAccountProvisioningPolicy {
    private final Environment environment;

    public StagingAccountProvisioningPolicy(Environment environment) {
        this.environment = environment;
    }

    public void requireAllowedEmail(String rawEmail) {
        if (!isEnabled()) return;
        String normalized = rawEmail == null
                ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        if (!allowedEmails().contains(normalized)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
    }

    public void requireAllowedApproval(Member member, MemberStatus newStatus) {
        if (!isEnabled()) return;
        requireAllowedEmail(member == null ? null : member.getEmail());
        if (member == null
                || member.getStatus() != MemberStatus.PENDING
                || !Boolean.TRUE.equals(member.getEmailVerified())
                || member.getRole() == Role.ADMIN
                || newStatus != MemberStatus.APPROVED) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
    }

    private boolean isEnabled() {
        return "preproduction".equals(
                environment.getProperty("app.environment", "development"))
                && environment.getProperty(
                "app.staging.account-provisioning-enabled", Boolean.class, false);
    }

    private Set<String> allowedEmails() {
        return Arrays.stream(environment.getProperty(
                        "app.staging.provisioning-allowed-emails", "").split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
