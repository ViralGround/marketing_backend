package com.viralground.backend.service;

import com.viralground.backend.entity.ContactRequest;
import com.viralground.backend.repository.ContactRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ContactService {

    private final ContactRequestRepository repository;
    private final EmailService emailService;

    /** 어드민 페이지용 — 접수일 내림차순 전체 조회. */
    @Transactional(readOnly = true)
    public List<ContactRequest> listAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * 랜딩 페이지 상담신청 폼 접수. DB 에 저장하고 관리자에게 알림 메일 발송.
     * controller 단에서 @Valid 로 형식 검증이 이미 끝났다고 가정하고, 여기서는 정규화만 한다.
     */
    @Transactional
    public ContactRequest submit(String email, String brandName, String contactName) {
        String normalizedEmail = email == null ? "" : email.trim();
        String normalizedBrand = brandName == null ? "" : brandName.trim();
        String normalizedContact = (contactName == null || contactName.isBlank())
                ? null : contactName.trim();

        ContactRequest saved = repository.save(ContactRequest.builder()
                .email(normalizedEmail)
                .brandName(normalizedBrand)
                .contactName(normalizedContact)
                .build());

        emailService.notifyAdminsOfNewContact(normalizedEmail, normalizedBrand, normalizedContact);

        return saved;
    }
}
