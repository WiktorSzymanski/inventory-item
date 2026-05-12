CREATE TABLE IF NOT EXISTS checkpoints (
    read_model_name  TEXT PRIMARY KEY,
    commit_position  BIGINT NOT NULL,
    prepare_position BIGINT NOT NULL
);
