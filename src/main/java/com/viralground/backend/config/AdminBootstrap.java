package com.viralground.backend.config;

import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.entity.Role;
import com.viralground.backend.repository.MemberRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "admin.bootstrap", name = "enabled", havingValue = "true")
@Slf4j
public class AdminBootstrap implements CommandLineRunner {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;
    private final String name;

    public AdminBootstrap(
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder,
            @Value("${admin.bootstrap.email:}") String email,
            @Value("${admin.bootstrap.password:}") String password,
            @Value("${admin.bootstrap.name:}") String name) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
        this.name = name;
    }

    @Override
    public void run(String... args) {
        if (email == null || email.isBlank() || password == null || password.isBlank()
                || name == null || name.isBlank()) {
            throw new IllegalStateException(
                    "enabled admin bootstrap requires email, password, and name");
        }
        String normalized = email.trim().toLowerCase();
        var existing = memberRepository.findByEmail(normalized);
        if (existing.isPresent()) {
            Member member = existing.get();
            if (member.getRole() != Role.ADMIN
                    || member.getStatus() != MemberStatus.APPROVED
                    || !Boolean.TRUE.equals(member.getEmailVerified())) {
                throw new IllegalStateException(
                        "existing bootstrap email is not an approved, verified ADMIN");
            }
            throw new IllegalStateException(
                    "one-shot admin already exists; disable bootstrap, remove credentials, and restart");
        }
        Member admin = memberRepository.save(Member.builder()
                .email(normalized)
                .password(passwordEncoder.encode(password))
                .name(name.trim())
                .role(Role.ADMIN)
                .status(MemberStatus.APPROVED)
                .emailVerified(true)
                .build());
        log.info("event=admin_bootstrap_created memberId={}", admin.getId());
        throw new IllegalStateException(
                "one-shot admin created; process intentionally stopped—disable bootstrap, remove credentials, and restart");
    }
}
