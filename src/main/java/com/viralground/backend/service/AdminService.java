package com.viralground.backend.service;

import com.viralground.backend.dto.admin.CampaignDetailResponse;
import com.viralground.backend.dto.admin.MemberDetailResponse;
import com.viralground.backend.dto.campaign.CampaignCreateRequest;
import com.viralground.backend.entity.*;
import com.viralground.backend.exception.AppException;
import com.viralground.backend.exception.ErrorCode;
import com.viralground.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final MemberRepository memberRepository;
    private final CreatorProfileRepository profileRepository;
    private final CampaignRepository campaignRepository;
    private final CampaignApplicationRepository applicationRepository;
    private final EmailService emailService;

    // ── 회원 관리 ──────────────────────────────────

    public Map<String, Object> getMembers(String statusStr, String search) {
        MemberStatus status = (statusStr != null && !"ALL".equals(statusStr))
                ? MemberStatus.valueOf(statusStr) : null;
        List<Member> members = memberRepository.findAllByStatusAndSearch(status, search);

        long pending = members.stream().filter(m -> m.getStatus() == MemberStatus.PENDING).count();
        long approved = members.stream().filter(m -> m.getStatus() == MemberStatus.APPROVED).count();
        long rejected = members.stream().filter(m -> m.getStatus() == MemberStatus.REJECTED).count();

        List<Map<String, Object>> list = members.stream()
                .map(m -> Map.<String, Object>of(
                        "id", m.getId(),
                        "email", m.getEmail(),
                        "name", m.getName(),
                        "role", m.getRole().name(),
                        "status", m.getStatus().name(),
                        "emailVerified", m.getEmailVerified(),
                        "createdAt", m.getCreatedAt()
                ))
                .toList();

        return Map.of(
                "stats", Map.of("pending", pending, "approved", approved, "rejected", rejected),
                "members", list
        );
    }

    public MemberDetailResponse getMember(Integer id) {
        Member member = memberRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        CreatorProfile profile = profileRepository.findByMemberId(id).orElse(null);
        long appCount = applicationRepository.findByCreatorIdOrderByAppliedAtDesc(id).size();
        return new MemberDetailResponse(member, profile, appCount);
    }

    @Transactional
    public void deleteMember(Integer id, Integer requesterId) {
        if (id.equals(requesterId)) throw new AppException(ErrorCode.SELF_DELETE_FORBIDDEN);
        memberRepository.findById(id).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        memberRepository.deleteById(id);
    }

    @Transactional
    public void updateMemberStatus(Integer id, String statusStr) {
        Member member = memberRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        MemberStatus newStatus = MemberStatus.valueOf(statusStr);
        member.setStatus(newStatus);
        memberRepository.save(member);

        if (newStatus == MemberStatus.APPROVED || newStatus == MemberStatus.REJECTED) {
            emailService.notifyCreatorOfStatusChange(member.getEmail(), member.getName(), newStatus.name());
        }
    }

    // ── 캠페인 관리 ──────────────────────────────────

    public List<Map<String, Object>> getCampaigns(String statusStr) {
        CampaignStatus status = (statusStr != null && !"ALL".equals(statusStr))
                ? CampaignStatus.valueOf(statusStr) : null;
        return campaignRepository.findAllByStatus(status).stream()
                .map(c -> Map.<String, Object>of(
                        "id", c.getId(),
                        "title", c.getTitle(),
                        "brandName", c.getBrandName(),
                        "rewardAmount", c.getRewardAmount(),
                        "maxParticipants", c.getMaxParticipants(),
                        "status", c.getStatus().name(),
                        "deadline", c.getDeadline() != null ? c.getDeadline() : "",
                        "createdAt", c.getCreatedAt(),
                        "applicationCount", applicationRepository.findByCampaignIdOrderByAppliedAtDesc(c.getId()).size()
                ))
                .toList();
    }

    public CampaignDetailResponse getCampaign(Integer id) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
        List<CampaignApplication> apps = applicationRepository.findByCampaignIdOrderByAppliedAtDesc(id);
        return new CampaignDetailResponse(campaign, apps);
    }

    @Transactional
    public Campaign createCampaign(CampaignCreateRequest req, Integer adminId) {
        return campaignRepository.save(Campaign.builder()
                .title(req.getTitle())
                .description(req.getDescription())
                .brandName(req.getBrandName())
                .rewardAmount(req.getRewardAmount())
                .maxParticipants(req.getMaxParticipants())
                .thumbnailUrl(req.getThumbnailUrl())
                .requirements(req.getRequirements())
                .deadline(req.getDeadline())
                .createdById(adminId)
                .build());
    }

    @Transactional
    public void updateCampaign(Integer id, Map<String, Object> body) {
        Campaign c = campaignRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
        if (body.containsKey("title")) c.setTitle(body.get("title").toString());
        if (body.containsKey("description")) c.setDescription(body.get("description").toString());
        if (body.containsKey("brandName")) c.setBrandName(body.get("brandName").toString());
        if (body.containsKey("rewardAmount")) c.setRewardAmount(Integer.parseInt(body.get("rewardAmount").toString()));
        if (body.containsKey("maxParticipants")) c.setMaxParticipants(Integer.parseInt(body.get("maxParticipants").toString()));
        if (body.containsKey("thumbnailUrl")) c.setThumbnailUrl(body.get("thumbnailUrl").toString());
        if (body.containsKey("requirements")) c.setRequirements(body.get("requirements").toString());
        if (body.containsKey("status")) c.setStatus(CampaignStatus.valueOf(body.get("status").toString()));
        campaignRepository.save(c);
    }

    @Transactional
    public void deleteCampaign(Integer id) {
        campaignRepository.findById(id).orElseThrow(() -> new AppException(ErrorCode.CAMPAIGN_NOT_FOUND));
        campaignRepository.deleteById(id);
    }

    // ── 지원 관리 ──────────────────────────────────

    @Transactional
    public void updateApplication(Integer id, Map<String, Object> body) {
        CampaignApplication app = applicationRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.APPLICATION_NOT_FOUND));

        ApplicationStatus newStatus = ApplicationStatus.valueOf(body.get("status").toString());
        app.setStatus(newStatus);
        app.setReviewedAt(LocalDateTime.now());

        if (body.containsKey("rewardPaidAmount") && body.get("rewardPaidAmount") != null) {
            app.setRewardPaidAmount(Integer.parseInt(body.get("rewardPaidAmount").toString()));
        }
        if (newStatus == ApplicationStatus.SETTLED) {
            app.setSettledAt(LocalDateTime.now());
        }
        applicationRepository.save(app);

        if (newStatus == ApplicationStatus.APPROVED || newStatus == ApplicationStatus.REJECTED) {
            Member creator = memberRepository.findById(app.getCreatorId()).orElseThrow();
            Campaign campaign = campaignRepository.findById(app.getCampaignId()).orElseThrow();
            emailService.notifyCreatorOfApplicationResult(
                    creator.getEmail(), creator.getName(),
                    campaign.getTitle(), newStatus.name(), app.getRewardPaidAmount());
        }
    }
}
