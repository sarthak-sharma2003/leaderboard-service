-- Source of truth. Redis holds a rebuildable ranked copy of each leaderboard.
CREATE TABLE IF NOT EXISTS scores (
    leaderboard_id TEXT        NOT NULL,
    player_id      TEXT        NOT NULL,
    score          BIGINT      NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (leaderboard_id, player_id)
);
