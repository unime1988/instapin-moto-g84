CREATE TABLE analytics_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    install_id TEXT NOT NULL,
    event TEXT NOT NULL,
    props TEXT NOT NULL DEFAULT '{}',
    app_version TEXT NOT NULL DEFAULT '',
    client_ts INTEGER NOT NULL,
    received_at INTEGER NOT NULL
);

CREATE INDEX idx_analytics_event_time ON analytics_events(event, received_at);
CREATE INDEX idx_analytics_received ON analytics_events(received_at);
