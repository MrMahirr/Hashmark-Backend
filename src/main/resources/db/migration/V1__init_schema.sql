CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    github_id VARCHAR(64) UNIQUE NOT NULL,
    email VARCHAR(255),
    name VARCHAR(255),
    github_token TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE repos (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    github_repo_id VARCHAR(64) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    private BOOLEAN DEFAULT FALSE,
    last_scanned_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, github_repo_id)
);

CREATE TABLE debts (
    id BIGSERIAL PRIMARY KEY,
    repo_id BIGINT REFERENCES repos(id) ON DELETE CASCADE,
    file_path TEXT NOT NULL,
    line_no INTEGER NOT NULL,
    label VARCHAR(10) NOT NULL,
    content TEXT,
    detected_at TIMESTAMP DEFAULT NOW(),
    resolved_at TIMESTAMP,
    UNIQUE(repo_id, file_path, line_no)
);
