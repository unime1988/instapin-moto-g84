CREATE TABLE devices (
    id TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    disconnected_at INTEGER
);

CREATE TABLE pairing_codes (
    code TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    used_at INTEGER,
    FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE
);

CREATE TABLE instagram_identities (
    sender_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    paired_at INTEGER NOT NULL,
    FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE
);

CREATE TABLE inbox_items (
    id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    meta_message_id TEXT NOT NULL UNIQUE,
    instagram_url TEXT NOT NULL,
    reel_video_id TEXT,
    title TEXT NOT NULL DEFAULT '',
    note TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL,
    acknowledged_at INTEGER,
    FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE
);

CREATE TABLE pending_notes (
    sender_id TEXT PRIMARY KEY,
    inbox_item_id TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    FOREIGN KEY (sender_id) REFERENCES instagram_identities(sender_id) ON DELETE CASCADE,
    FOREIGN KEY (inbox_item_id) REFERENCES inbox_items(id) ON DELETE CASCADE
);

CREATE INDEX idx_pairing_codes_device ON pairing_codes(device_id);
CREATE INDEX idx_pairing_codes_expiry ON pairing_codes(expires_at);
CREATE INDEX idx_inbox_device_created ON inbox_items(device_id, created_at);
CREATE INDEX idx_inbox_device_ack ON inbox_items(device_id, acknowledged_at);
