-- 기존 access JWT까지 비밀번호 재설정/탈퇴 즉시 폐기하기 위한 인증 세대.
-- DEFAULT 0은 배포 시 기존 회원을 안전하게 backfill하고 이후 신규 회원에도 적용된다.
ALTER TABLE members
    ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE members
    ADD CONSTRAINT ck_members_auth_version_nonnegative CHECK (auth_version >= 0);
