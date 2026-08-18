package com.viralground.backend.config;

import com.viralground.backend.entity.CompanyProfile;
import com.viralground.backend.entity.CreatorProfile;
import com.viralground.backend.entity.EditingSkill;
import com.viralground.backend.entity.EditingTool;
import com.viralground.backend.entity.Gender;
import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.entity.Role;
import com.viralground.backend.repository.CompanyProfileRepository;
import com.viralground.backend.repository.CreatorProfileRepository;
import com.viralground.backend.repository.MemberRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Component
@ConditionalOnProperty(prefix = "demo.bootstrap", name = "enabled", havingValue = "true")
@Slf4j
public class DemoAccountBootstrap implements CommandLineRunner {

    private final MemberRepository memberRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final String environment;
    private final String password;
    private final String creatorEmail;
    private final String companyEmail;

    public DemoAccountBootstrap(
            MemberRepository memberRepository,
            CreatorProfileRepository creatorProfileRepository,
            CompanyProfileRepository companyProfileRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.environment:development}") String environment,
            @Value("${demo.bootstrap.password:}") String password,
            @Value("${demo.bootstrap.creator-email:creator.demo@viralground.local}") String creatorEmail,
            @Value("${demo.bootstrap.company-email:company.demo@viralground.local}") String companyEmail) {
        this.memberRepository = memberRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.companyProfileRepository = companyProfileRepository;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
        this.password = password;
        this.creatorEmail = creatorEmail;
        this.companyEmail = companyEmail;
    }

    @Override
    @Transactional
    public void run(String... args) {
        String normalizedEnvironment = environment == null
                ? ""
                : environment.trim().toLowerCase(Locale.ROOT);
        if (normalizedEnvironment.equals("prod") || normalizedEnvironment.equals("production")) {
            throw new IllegalStateException("Demo accounts cannot be enabled in production");
        }
        if (password == null || password.length() < 12) {
            throw new IllegalStateException("demo.bootstrap.password must contain at least 12 characters");
        }

        createCreatorIfMissing();
        createCompanyIfMissing();
        log.info("event=demo_accounts_ready creatorEmail={} companyEmail={}",
                redactEmail(creatorEmail), redactEmail(companyEmail));
    }

    private void createCreatorIfMissing() {
        String email = normalizeEmail(creatorEmail);
        if (memberRepository.existsByEmail(email)) return;

        Member creator = memberRepository.save(Member.builder()
                .email(email)
                .password(passwordEncoder.encode(password))
                .name("데모 크리에이터")
                .role(Role.CREATOR)
                .status(MemberStatus.APPROVED)
                .emailVerified(true)
                .build());
        creatorProfileRepository.save(CreatorProfile.builder()
                .memberId(creator.getId())
                .gender(Gender.OTHER)
                .age(25)
                .faceExposure(true)
                .canEdit(true)
                .editingSkill(EditingSkill.MEDIUM)
                .editingTool(EditingTool.CAPCUT)
                .instagramId("viralground_demo")
                .build());
    }

    private void createCompanyIfMissing() {
        String email = normalizeEmail(companyEmail);
        if (memberRepository.existsByEmail(email)) return;

        Member company = memberRepository.save(Member.builder()
                .email(email)
                .password(passwordEncoder.encode(password))
                .name("데모 기업 담당자")
                .role(Role.COMPANY)
                .status(MemberStatus.APPROVED)
                .emailVerified(true)
                .build());
        companyProfileRepository.save(CompanyProfile.builder()
                .memberId(company.getId())
                .companyName("ViralGround Demo Brand")
                .businessNumber("0000000000")
                .representativeName("데모 대표")
                .contactName("데모 담당자")
                .contactPhone("010-0000-0000")
                .industry("AI SaaS")
                .introduction("로컬 화면 확인을 위한 데모 기업입니다.")
                .build());
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("Demo account email cannot be blank");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String redactEmail(String email) {
        int separator = email == null ? -1 : email.indexOf('@');
        return separator <= 1 ? "<redacted>" : email.charAt(0) + "***" + email.substring(separator);
    }
}
