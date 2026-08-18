package com.viralground.backend.repository;

import com.viralground.backend.entity.MemberConsentEvidence;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * 증적 저장만 노출한다. delete/update API는 컴파일 단계에서도 제공하지 않고 DB trigger가
 * 우회 SQL까지 최종 차단한다.
 */
public interface MemberConsentEvidenceRepository extends Repository<MemberConsentEvidence, Long> {
    <S extends MemberConsentEvidence> List<S> saveAllAndFlush(Iterable<S> entities);
}
