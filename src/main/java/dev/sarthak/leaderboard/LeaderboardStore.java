package dev.sarthak.leaderboard;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.sarthak.leaderboard.v1.Entry;
import dev.sarthak.leaderboard.v1.Page;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisZSetCommands.ZAddArgs;
import org.springframework.data.redis.connection.zset.DefaultTuple;
import org.springframework.data.redis.connection.zset.Tuple;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Postgres is the source of truth. Redis keeps one sorted set per leaderboard for ranked reads,
 * and a leaderboard missing from Redis (restart, eviction) is rebuilt from Postgres on next use.
 */
@Component
class LeaderboardStore {

	private static final Logger log = LoggerFactory.getLogger(LeaderboardStore.class);

	// Only touch a leaderboard that is already cached: a ZADD on a missing key would create a
	// leaderboard holding just this player, which then looks cached but is incomplete.
	// GT keeps the higher score if two writes for the same player reach Redis out of order.
	private static final RedisScript<Long> ADD_IF_CACHED = RedisScript.of("""
			if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
			redis.call('ZADD', KEYS[1], 'GT', ARGV[1], ARGV[2])
			return redis.call('ZREVRANK', KEYS[1], ARGV[2])
			""", Long.class);

	// Same order as ZREVRANGE: score descending, ties by player id descending.
	private static final Comparator<TypedTuple<String>> REDIS_ORDER = Comparator
		.<TypedTuple<String>, Double>comparing(TypedTuple::getScore)
		.thenComparing(TypedTuple::getValue)
		.reversed();

	private final JdbcClient db;

	private final StringRedisTemplate redis;

	LeaderboardStore(JdbcClient db, StringRedisTemplate redis) {
		this.db = db;
		this.redis = redis;
	}

	Entry submit(String board, String player, long score) {
		long best = db.sql("""
				INSERT INTO scores (leaderboard_id, player_id, score) VALUES (?, ?, ?)
				ON CONFLICT (leaderboard_id, player_id) DO UPDATE
				SET score = GREATEST(scores.score, EXCLUDED.score), updated_at = now()
				RETURNING score""").params(board, player, score).query(Long.class).single();
		Long rank = redis.execute(ADD_IF_CACHED, List.of(key(board)), Long.toString(best), player);
		if (rank == -1) {
			rebuild(board); // picks up this write too: it is already committed
			rank = redis.opsForZSet().reverseRank(key(board), player);
		}
		return entry(player, best, rank + 1);
	}

	Page top(String board, int limit) {
		warm(board);
		var rows = redis.opsForZSet().reverseRangeWithScores(key(board), 0, limit - 1);
		return page(rows, 1, size(board));
	}

	/** Null if the player has no score on this leaderboard. */
	Page around(String board, String player, int radius) {
		warm(board);
		Long rank = redis.opsForZSet().reverseRank(key(board), player);
		if (rank == null) {
			return null;
		}
		long start = Math.max(0, rank - radius);
		var rows = redis.opsForZSet().reverseRangeWithScores(key(board), start, rank + radius);
		return page(rows, start + 1, size(board));
	}

	Page friends(String board, List<String> players) {
		List<String> ids = players.stream().distinct().toList();
		if (ids.isEmpty()) {
			return page(List.of(), 1, 0);
		}
		warm(board);
		List<Double> scores = redis.opsForZSet().score(key(board), ids.toArray());
		var rows = IntStream.range(0, ids.size())
			.filter(i -> scores.get(i) != null)
			.mapToObj(i -> TypedTuple.of(ids.get(i), scores.get(i)))
			.sorted(REDIS_ORDER)
			.toList();
		return page(rows, 1, rows.size());
	}

	private void warm(String board) {
		if (!Boolean.TRUE.equals(redis.hasKey(key(board)))) {
			rebuild(board);
		}
	}

	// ponytail: rebuilds inline on whichever request finds the leaderboard missing; boards with
	// millions of players want a background job plus a lock so only one worker rebuilds.
	private void rebuild(String board) {
		long start = System.nanoTime();
		byte[] key = key(board).getBytes(UTF_8);
		List<Tuple> rows = db.sql("SELECT player_id, score FROM scores WHERE leaderboard_id = ?")
			.param(board)
			.query((rs, i) -> (Tuple) new DefaultTuple(rs.getString(1).getBytes(UTF_8), (double) rs.getLong(2)))
			.list();
		for (int i = 0; i < rows.size(); i += 1000) {
			var batch = new HashSet<>(rows.subList(i, Math.min(i + 1000, rows.size())));
			// GT so a concurrent, newer submit is never overwritten by this older snapshot.
			redis.execute((RedisCallback<Long>) c -> c.zSetCommands().zAdd(key, batch, ZAddArgs.empty().gt()));
		}
		if (!rows.isEmpty()) {
			log.info("Rebuilt leaderboard {} from Postgres: {} players in {} ms", board, rows.size(),
					(System.nanoTime() - start) / 1_000_000);
		}
	}

	private long size(String board) {
		return redis.opsForZSet().zCard(key(board));
	}

	private static String key(String board) {
		return "lb:" + board;
	}

	private static Page page(Iterable<TypedTuple<String>> rows, long firstRank, long total) {
		var page = Page.newBuilder().setTotalPlayers(total);
		long rank = firstRank;
		for (var row : rows) {
			page.addEntries(entry(row.getValue(), row.getScore().longValue(), rank++));
		}
		return page.build();
	}

	private static Entry entry(String player, long score, long rank) {
		return Entry.newBuilder().setPlayerId(player).setScore(score).setRank(rank).build();
	}

}
