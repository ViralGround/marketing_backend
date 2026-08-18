-- ViralGround pre-launch baseline.
-- Existing production databases must be backed up and baselined with FLYWAY_BASELINE_ON_MIGRATE=true once.
-- Fresh environments receive the complete schema from this migration.

CREATE TABLE members (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    agreed_terms_at TIMESTAMP,
    agreed_privacy_at TIMESTAMP,
    agreed_age14_at TIMESTAMP,
    agreed_third_party_at TIMESTAMP,
    marketing_opt_in_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE creator_profiles (
    id SERIAL PRIMARY KEY,
    member_id INTEGER NOT NULL UNIQUE REFERENCES members(id),
    can_edit BOOLEAN NOT NULL DEFAULT FALSE,
    editing_skill VARCHAR(32),
    editing_tool VARCHAR(32),
    gender VARCHAR(32),
    age INTEGER,
    face_exposure BOOLEAN NOT NULL DEFAULT FALSE,
    profile_image VARCHAR(500),
    instagram_id VARCHAR(255),
    tiktok_id VARCHAR(255),
    youtube_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE company_profiles (
    id SERIAL PRIMARY KEY,
    member_id INTEGER NOT NULL UNIQUE REFERENCES members(id),
    company_name VARCHAR(255) NOT NULL,
    business_number VARCHAR(30) NOT NULL,
    representative_name VARCHAR(100) NOT NULL,
    contact_name VARCHAR(100) NOT NULL,
    contact_phone VARCHAR(40) NOT NULL,
    address VARCHAR(500),
    homepage VARCHAR(500),
    industry VARCHAR(100),
    introduction TEXT,
    logo_file_key VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE campaigns (
    id SERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    brand_name VARCHAR(255) NOT NULL,
    brand_introduction TEXT,
    brand_logo_file_key VARCHAR(500),
    reward_amount INTEGER NOT NULL,
    total_budget INTEGER NOT NULL DEFAULT 0,
    escrow_status VARCHAR(32) NOT NULL DEFAULT 'NONE',
    deposit_requested_at TIMESTAMP,
    funded_at TIMESTAMP,
    refunded_at TIMESTAMP,
    thumbnail_url VARCHAR(1000),
    thumbnail_file_key VARCHAR(500),
    requirements TEXT,
    deadline TIMESTAMP,
    max_participants INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_by_id INTEGER NOT NULL REFERENCES members(id),
    hidden_at TIMESTAMP,
    featured_order INTEGER,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE campaign_applications (
    id SERIAL PRIMARY KEY,
    campaign_id INTEGER NOT NULL REFERENCES campaigns(id),
    creator_id INTEGER NOT NULL REFERENCES members(id),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    message TEXT,
    submission_url VARCHAR(1000),
    video_file_key VARCHAR(500),
    video_content_type VARCHAR(100),
    video_size_bytes BIGINT,
    resubmission_count INTEGER NOT NULL DEFAULT 0,
    review_comment TEXT,
    reward_paid_amount INTEGER,
    applied_at TIMESTAMP NOT NULL,
    reviewed_at TIMESTAMP,
    submitted_at TIMESTAMP,
    settled_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_campaign_application UNIQUE (campaign_id, creator_id)
);

CREATE TABLE application_submissions (
    id SERIAL PRIMARY KEY,
    application_id INTEGER NOT NULL REFERENCES campaign_applications(id),
    video_file_key VARCHAR(500),
    video_content_type VARCHAR(100),
    video_size_bytes BIGINT,
    submission_url VARCHAR(1000),
    status VARCHAR(32) NOT NULL,
    reviewer_id INTEGER,
    review_comment TEXT,
    submitted_at TIMESTAMP NOT NULL,
    reviewed_at TIMESTAMP
);

CREATE TABLE email_verification_codes (
    id SERIAL PRIMARY KEY,
    email VARCHAR(320) NOT NULL UNIQUE,
    code VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    verified_at TIMESTAMP,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_email_verification_email ON email_verification_codes(email, created_at);

CREATE TABLE escrow_transactions (
    id SERIAL PRIMARY KEY,
    campaign_id INTEGER NOT NULL REFERENCES campaigns(id),
    application_id INTEGER REFERENCES campaign_applications(id),
    type VARCHAR(32) NOT NULL,
    amount INTEGER NOT NULL,
    memo VARCHAR(500),
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_escrow_campaign ON escrow_transactions(campaign_id);

CREATE TABLE reviews (
    id SERIAL PRIMARY KEY,
    application_id INTEGER NOT NULL REFERENCES campaign_applications(id),
    author_id INTEGER NOT NULL REFERENCES members(id),
    author_role VARCHAR(32) NOT NULL,
    target_id INTEGER NOT NULL REFERENCES members(id),
    rating INTEGER NOT NULL,
    comment TEXT,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_review_author_role UNIQUE (application_id, author_role),
    CONSTRAINT ck_review_rating CHECK (rating BETWEEN 1 AND 5)
);

CREATE TABLE submission_metrics (
    id SERIAL PRIMARY KEY,
    application_id INTEGER NOT NULL UNIQUE REFERENCES campaign_applications(id),
    views BIGINT NOT NULL DEFAULT 0,
    likes BIGINT NOT NULL DEFAULT 0,
    comments BIGINT NOT NULL DEFAULT 0,
    external_url VARCHAR(1000),
    recorded_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE creator_instagram_connections (
    id SERIAL PRIMARY KEY,
    creator_id INTEGER NOT NULL UNIQUE REFERENCES members(id),
    provider VARCHAR(32) NOT NULL,
    provider_user_id VARCHAR(255),
    provider_account_id VARCHAR(255),
    ig_username VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    last_error TEXT,
    connected_at TIMESTAMP,
    last_synced_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE reel_metric_snapshots (
    id SERIAL PRIMARY KEY,
    application_id INTEGER NOT NULL REFERENCES campaign_applications(id),
    views BIGINT NOT NULL DEFAULT 0,
    likes BIGINT NOT NULL DEFAULT 0,
    comments BIGINT NOT NULL DEFAULT 0,
    shares BIGINT NOT NULL DEFAULT 0,
    source VARCHAR(32) NOT NULL,
    captured_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_reel_metric_nonnegative CHECK (views >= 0 AND likes >= 0 AND comments >= 0 AND shares >= 0)
);

CREATE INDEX idx_snapshot_app_captured ON reel_metric_snapshots(application_id, captured_at);

CREATE TABLE contact_requests (
    id SERIAL PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    brand_name VARCHAR(200) NOT NULL,
    contact_name VARCHAR(100),
    created_at TIMESTAMP NOT NULL
);
