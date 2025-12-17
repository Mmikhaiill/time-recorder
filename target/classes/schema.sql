CREATE TABLE IF NOT EXISTS time_records (
    id BIGSERIAL PRIMARY KEY,
    
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    
    persisted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    was_buffered BOOLEAN NOT NULL DEFAULT FALSE
);

COMMENT ON TABLE time_records IS 'Stores timestamps recorded by the application every second';
COMMENT ON COLUMN time_records.id IS 'Auto-incrementing ID that maintains chronological insertion order';
COMMENT ON COLUMN time_records.recorded_at IS 'Timestamp when the record was created in application memory';
COMMENT ON COLUMN time_records.persisted_at IS 'Timestamp when the record was actually written to database';
COMMENT ON COLUMN time_records.was_buffered IS 'True if record was buffered during database unavailability';

