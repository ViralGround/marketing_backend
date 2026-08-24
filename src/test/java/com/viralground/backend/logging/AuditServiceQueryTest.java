package com.viralground.backend.logging;

import com.viralground.backend.dto.admin.AuditLogPageResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceQueryTest {

    @Mock AuditLogRepository repository;

    @Test
    void returnsBoundedNewestFirstPiiSafeProjection() {
        AuditLog audit = new AuditLog("request-12345678", 7, "ADMIN",
                AuditAction.MEMBER_STATUS_CHANGED, "member", "22", "SUCCESS",
                "free-form reason must not leave the API");
        when(repository.search(eq(AuditAction.MEMBER_STATUS_CHANGED), eq(7), eq("member"),
                isNull(), any(), any(), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(audit), invocation.getArgument(6), 1));

        AuditLogPageResponse response = new AuditService(repository).search(
                AuditAction.MEMBER_STATUS_CHANGED, 7, " member ", null,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"), 0, 500);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().action()).isEqualTo("MEMBER_STATUS_CHANGED");
        assertThat(response.items().getFirst().resourceId()).isEqualTo("22");

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(repository).search(any(), any(), any(), any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").isDescending()).isTrue();
    }

    @Test
    void rejectsUnsafeFiltersAndInvertedTimeRange() {
        AuditService service = new AuditService(repository);

        assertThatThrownBy(() -> service.search(null, null, "member%", null,
                null, null, 0, 50)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.search(null, null, null, null,
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"), 0, 50))
                .isInstanceOf(ResponseStatusException.class);
    }
}
