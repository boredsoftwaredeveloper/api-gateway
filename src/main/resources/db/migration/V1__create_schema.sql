-- ============================================================
-- V1: Create all portfolio and stream tables
-- ============================================================

-- 1. Profile
CREATE TABLE profile (
    profile_id BIGSERIAL    PRIMARY KEY,
    first_name VARCHAR(100) NOT NULL,
    last_name  VARCHAR(100) NOT NULL,
    photo_url  VARCHAR(500),
    status     VARCHAR(255)
);

-- 2. Experience
CREATE TABLE experience (
    experience_id BIGSERIAL    PRIMARY KEY,
    profile_id    BIGINT       NOT NULL REFERENCES profile(profile_id) ON DELETE CASCADE,
    slug          VARCHAR(50)  NOT NULL,
    company       VARCHAR(100) NOT NULL,
    role          VARCHAR(255) NOT NULL,
    role_style    VARCHAR(20)  NOT NULL DEFAULT 'tag',
    description   TEXT,
    start_date    DATE,
    end_date      DATE,
    sort_order    INT          NOT NULL DEFAULT 0
);

-- 3. Achievement
CREATE TABLE achievement (
    achievement_id BIGSERIAL    PRIMARY KEY,
    profile_id     BIGINT       NOT NULL REFERENCES profile(profile_id) ON DELETE CASCADE,
    slug           VARCHAR(50)  NOT NULL,
    title          VARCHAR(255) NOT NULL,
    subtitle       VARCHAR(255),
    emoji          VARCHAR(10),
    progress_percent INT        NOT NULL DEFAULT 0,
    variant        VARCHAR(20)  NOT NULL DEFAULT 'indigo',
    stat_label     VARCHAR(50),
    stat_value     VARCHAR(50),
    sort_order     INT          NOT NULL DEFAULT 0
);

-- 4. Aspiration
CREATE TABLE aspiration (
    aspiration_id   BIGSERIAL    PRIMARY KEY,
    profile_id      BIGINT       NOT NULL REFERENCES profile(profile_id) ON DELETE CASCADE,
    slug            VARCHAR(50)  NOT NULL,
    title           VARCHAR(255) NOT NULL,
    subtitle        VARCHAR(255),
    status_text     VARCHAR(100),
    progress_percent INT         NOT NULL DEFAULT 0,
    variant         VARCHAR(20)  NOT NULL DEFAULT 'blue',
    footer_text     VARCHAR(255),
    animated        BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order      INT          NOT NULL DEFAULT 0
);

-- 5. Feed Post (Regret Stream)
CREATE TABLE feed_post (
    post_id       BIGSERIAL     PRIMARY KEY,
    author        VARCHAR(100)  NOT NULL,
    avatar        VARCHAR(10)   NOT NULL DEFAULT 'S',
    timestamp     VARCHAR(100)  NOT NULL,
    location      VARCHAR(100),
    content_type  VARCHAR(10)   NOT NULL CHECK (content_type IN ('code', 'image')),
    code_snippet  JSONB,
    image_content JSONB,
    caption       TEXT          NOT NULL,
    hashtags      TEXT[]        NOT NULL DEFAULT '{}',
    sort_order    INT           NOT NULL DEFAULT 0
);

-- 6. Indexes
CREATE INDEX idx_experience_profile  ON experience(profile_id);
CREATE INDEX idx_achievement_profile ON achievement(profile_id);
CREATE INDEX idx_aspiration_profile  ON aspiration(profile_id);
CREATE INDEX idx_feed_post_sort      ON feed_post(sort_order);
