-- V1__init.sql
-- AMRAP: users table for role-based authentication and ownership.
-- Run via Flyway later, or paste into pgAdmin Query Tool.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TYPE user_role AS ENUM ('superadmin', 'admin', 'member');

CREATE TABLE users (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email                   VARCHAR(320) NOT NULL,
    password_hash           VARCHAR(255) NOT NULL,
    role                    user_role NOT NULL,
    display_name            VARCHAR(120) NOT NULL,
    created_by              UUID NULL,
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at           TIMESTAMPTZ NULL,
    password_changed_at     TIMESTAMPTZ NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_format CHECK (
        email = LOWER(email)
        AND email ~* '^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$'
    ),
    CONSTRAINT fk_users_created_by
        FOREIGN KEY (created_by)
        REFERENCES users (id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE
);

CREATE INDEX idx_users_role ON users (role);
CREATE INDEX idx_users_created_by ON users (created_by);
CREATE INDEX idx_users_is_active ON users (is_active);

COMMENT ON TABLE users IS 'Application users; ownership via created_by; soft-deactivate with is_active.';
COMMENT ON COLUMN users.email IS 'Login identifier; always stored lowercase.';
COMMENT ON COLUMN users.password_hash IS 'Bcrypt/Argon2 hash only — never plaintext. Hash in the application.';
COMMENT ON COLUMN users.role IS 'Authorization class: superadmin | admin | member.';
COMMENT ON COLUMN users.created_by IS 'Creator user id; NULL for bootstrap Superadmin(s).';
COMMENT ON COLUMN users.is_active IS 'FALSE blocks login (soft deactivation). Prefer over hard DELETE.';

CREATE OR REPLACE FUNCTION set_users_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_set_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE PROCEDURE set_users_updated_at();
