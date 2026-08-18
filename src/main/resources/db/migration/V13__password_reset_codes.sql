-- 비밀번호 재설정 코드 저장소.
-- email_verification_codes 와 분리한다: 가입 인증은 "미가입 이메일"이 전제,
-- 재설정은 "가입된 이메일"이 전제라 정책이 정반대이며 섞이면 계정 존재가 누출된다.
-- code 컬럼은 BCrypt 해시만 저장한다 (평문 코드는 메일로만 전달).
CREATE TABLE password_reset_codes (
    id SERIAL PRIMARY KEY,
    email VARCHAR(320) NOT NULL UNIQUE,
    code VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_password_reset_email ON password_reset_codes(email, created_at);
