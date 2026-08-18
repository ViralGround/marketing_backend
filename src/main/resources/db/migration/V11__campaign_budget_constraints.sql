-- 공개 프로필에 남아 있던 legacy 비-HTTPS/과대 URL을 노출 전에 제거한다.
UPDATE company_profiles
SET homepage = NULL
WHERE homepage IS NOT NULL
  AND (
      char_length(homepage) > 500
      OR homepage !~ '^https://'
      OR homepage <> btrim(homepage)
      OR homepage ~ '[[:cntrl:]]'
      OR homepage ~ '^https://[^/?#]*@'
  );

ALTER TABLE company_profiles
    ADD CONSTRAINT ck_company_homepage_https
        CHECK (homepage IS NULL OR (char_length(homepage) <= 500 AND homepage ~ '^https://'));

-- 캠페인 예산은 정산/에스크로 원장의 기준값이므로 서비스 검증과 별도로 DB가 불변식을 보장한다.
-- 잘못된 기존 경제 데이터를 조용히 보정하지 않고 migration을 중단해 운영자가 먼저 감사하게 한다.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM campaigns
        WHERE reward_amount NOT BETWEEN 1 AND 100000000
           OR max_participants NOT BETWEEN 1 AND 10000
           OR total_budget <= 0
           OR total_budget::BIGINT <> reward_amount::BIGINT * max_participants::BIGINT
    ) THEN
        RAISE EXCEPTION 'campaign budget audit failed before V11 constraints';
    END IF;
END
$$;

ALTER TABLE campaigns
    ADD CONSTRAINT ck_campaign_reward_amount
        CHECK (reward_amount BETWEEN 1 AND 100000000),
    ADD CONSTRAINT ck_campaign_max_participants
        CHECK (max_participants BETWEEN 1 AND 10000),
    ADD CONSTRAINT ck_campaign_total_budget
        CHECK (
            total_budget > 0
            AND total_budget::BIGINT = reward_amount::BIGINT * max_participants::BIGINT
            AND total_budget::BIGINT <= 2147483647
        );
