package com.viralground.backend.config;

import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.entity.Role;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StagingAccountProvisioningPolicyTest {

    @Test
    void provisioningAllowsOnlyAnExactDeclaredSyntheticEmail() {
        StagingAccountProvisioningPolicy policy = policy();

        assertThatCode(() -> policy.requireAllowedEmail("creator.qa@viralground.kr"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireAllowedEmail("other@viralground.kr"))
                .isInstanceOfSatisfying(AppException.class, error ->
                        org.assertj.core.api.Assertions.assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void provisioningApprovalRequiresPendingVerifiedNonAdminAllowlistedMember() {
        StagingAccountProvisioningPolicy policy = policy();
        Member creator = Member.builder()
                .email("creator.qa@viralground.kr")
                .role(Role.CREATOR)
                .status(MemberStatus.PENDING)
                .emailVerified(true)
                .build();

        assertThatCode(() -> policy.requireAllowedApproval(
                creator, MemberStatus.APPROVED)).doesNotThrowAnyException();

        creator.setStatus(MemberStatus.APPROVED);
        assertThatThrownBy(() -> policy.requireAllowedApproval(
                creator, MemberStatus.REJECTED)).isInstanceOf(AppException.class);
    }

    @Test
    void policyIsInactiveOutsideTheExplicitPreproductionProvisioningWindow() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "preproduction")
                .withProperty("app.staging.account-provisioning-enabled", "false");
        StagingAccountProvisioningPolicy policy =
                new StagingAccountProvisioningPolicy(environment);

        assertThatCode(() -> policy.requireAllowedEmail("anyone@example.test"))
                .doesNotThrowAnyException();
    }

    private static StagingAccountProvisioningPolicy policy() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.environment", "preproduction")
                .withProperty("app.staging.account-provisioning-enabled", "true")
                .withProperty("app.staging.provisioning-allowed-emails",
                        "creator.qa@viralground.kr,company.qa@viralground.kr");
        return new StagingAccountProvisioningPolicy(environment);
    }
}
