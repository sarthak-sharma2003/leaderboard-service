-- 100,000 players on one leaderboard, written straight to Postgres so Redis starts cold.
INSERT INTO scores (leaderboard_id, player_id, score)
SELECT 'season1', 'p' || g, (random() * 1000000)::bigint
FROM generate_series(1, 100000) g
ON CONFLICT DO NOTHING;
