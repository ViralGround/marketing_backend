package com.viralground.backend.config;

import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.entity.Role;
import com.viralground.backend.repository.MemberRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
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
            @Value("${admin.bootstrap.name:Admin}") String name) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
        this.name = name;
    }

    @Override
    public void run(String... args) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        String normalized = email.trim().toLowerCase();
        if (memberRepository.existsByEmail(normalized)) {
            log.info("event=admin_bootstrap_skipped reason=already_exists");
            return;
        }
        Member admin = memberRepository.save(Member.builder()
                .email(normalized)
                .password(passwordEncoder.encode(password))
                .name(name)
                .role(Role.ADMIN)
                .status(MemberStatus.APPROVED)
                .emailVerified(true)
                .build());
        log.info("event=admin_bootstrap_created memberId={}", admin.getId());
    }
}
