ALTER TABLE creator_profiles
    ADD COLUMN public_profile_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN public_profile_consented_at TIMESTAMP;

ALTER TABLE creator_profiles
    ADD CONSTRAINT ck_creator_public_profile_consent
        CHECK (
            (public_profile_opt_in = TRUE AND public_profile_consented_at IS NOT NULL)
            OR
            (public_profile_opt_in = FALSE AND public_profile_consented_at IS NULL)
        );

CREATE INDEX idx_creator_profiles_public_opt_in
    ON creator_profiles(member_id)
    WHERE public_profile_opt_in = TRUE;

COMMENT ON COLUMN creator_profiles.public_profile_opt_in IS
    'Explicit, revocable opt-in for the public creator directory; legacy and new profiles default private';
