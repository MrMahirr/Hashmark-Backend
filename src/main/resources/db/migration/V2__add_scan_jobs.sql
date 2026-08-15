CREATE TABLE scan_jobs (
    id BIGSERIAL PRIMARY KEY,
    repo_id BIGINT REFERENCES repos(id) ON DELETE CASCADE,
    status VARCHAR(20) DEFAULT 'PENDING',
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    debt_found INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW()
);
