package com.viralground.backend.controller;

import com.viralground.backend.entity.ContactRequest;
import com.viralground.backend.service.ContactService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/contact")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService contactService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(@Valid @RequestBody ContactRequestDto dto) {
        ContactRequest saved = contactService.submit(dto.email(), dto.brandName(), dto.contactName());
        return ResponseEntity.ok(Map.of(
                "id", saved.getId(),
                "message", "상담 신청이 접수되었습니다."));
    }

    public record ContactRequestDto(
            @Email(message = "올바른 이메일 형식이 아닙니다")
            @NotBlank(message = "이메일을 입력해주세요")
            @Size(max = 320)
            String email,

            @NotBlank(message = "브랜드명을 입력해주세요")
            @Size(max = 200)
            String brandName,

            @Size(max = 100)
            String contactName
    ) {}
}
