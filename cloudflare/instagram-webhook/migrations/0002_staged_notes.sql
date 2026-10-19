CREATE TABLE staged_notes (
    sender_id TEXT PRIMARY KEY,
    note TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    FOREIGN KEY (sender_id) REFERENCES instagram_identities(sender_id) ON DELETE CASCADE
);
