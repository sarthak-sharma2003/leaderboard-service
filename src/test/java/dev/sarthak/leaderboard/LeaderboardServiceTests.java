package dev.sarthak.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.sarthak.leaderboard.v1.Entry;
import dev.sarthak.leaderboard.v1.GetAroundPlayerRequest;
import dev.sarthak.leaderboard.v1.GetFriendsRequest;
import dev.sarthak.leaderboard.v1.GetTopRequest;
import dev.sarthak.leaderboard.v1.LeaderboardGrpc;
import dev.sarthak.leaderboard.v1.Page;
import dev.sarthak.leaderboard.v1.SubmitScoreRequest;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.StringUtils;

/** End to end over a real gRPC port, against real Postgres and Redis containers. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.grpc.server.port=0")
@ExtendWith(OutputCaptureExtension.class)
class LeaderboardServiceTests {

	@LocalGrpcServerPort
	int port;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	JdbcClient db;

	ManagedChannel channel;

	LeaderboardGrpc.LeaderboardBlockingStub client;

	@BeforeEach
	void connect() {
		channel = Grpc.newChannelBuilderForAddress("localhost", port, InsecureChannelCredentials.create()).build();
		client = LeaderboardGrpc.newBlockingStub(channel);
	}

	@AfterEach
	void disconnect() {
		channel.shutdownNow();
	}

	@Test
	void keepsBestScoreAndRanksHighestFirst() {
		submit("b1", "ana", 100);
		submit("b1", "ben", 300);
		assertThat(submit("b1", "ana", 50).getScore()).isEqualTo(100); // lower score ignored
		assertThat(submit("b1", "cat", 200).getRank()).isEqualTo(2);

		Page top = top("b1", 10);
		assertThat(top.getEntriesList()).extracting(Entry::getPlayerId, Entry::getRank)
			.containsExactly(tuple("ben", 1L), tuple("cat", 2L), tuple("ana", 3L));
		assertThat(top.getTotalPlayers()).isEqualTo(3);
	}

	@Test
	void aroundPlayerAndFriends() {
		for (int i = 1; i <= 10; i++) {
			submit("b2", "p" + i, i * 10); // p10 ranks 1st ... p1 ranks 10th
		}

		Page around = client.getAroundPlayer(GetAroundPlayerRequest.newBuilder()
			.setLeaderboardId("b2").setPlayerId("p5").setRadius(1).build());
		assertThat(around.getEntriesList()).extracting(Entry::getPlayerId, Entry::getRank)
			.containsExactly(tuple("p6", 5L), tuple("p5", 6L), tuple("p4", 7L));

		Page friends = client.getFriends(GetFriendsRequest.newBuilder()
			.setLeaderboardId("b2").addAllPlayerIds(List.of("p2", "p9", "stranger", "p2")).build());
		assertThat(friends.getEntriesList()).extracting(Entry::getPlayerId, Entry::getRank)
			.containsExactly(tuple("p9", 1L), tuple("p2", 2L));
	}

	@Test
	void rebuildsFromPostgresWhenRedisLosesEverything() {
		submit("b3", "ana", 100);
		submit("b3", "ben", 200);
		redis.execute((RedisCallback<Void>) c -> {
			c.serverCommands().flushAll();
			return null;
		});

		// The first write after the loss must not leave a leaderboard holding only "cat".
		assertThat(submit("b3", "cat", 150).getRank()).isEqualTo(2);
		assertThat(top("b3", 10).getTotalPlayers()).isEqualTo(3);
	}

	@Test
	void coldLeaderboardIsRebuiltOnceUnderConcurrentRequests(CapturedOutput output) throws Exception {
		// Straight into Postgres, so Redis has never seen this leaderboard.
		db.sql("INSERT INTO scores (leaderboard_id, player_id, score) SELECT 'b5', 'p' || g, g FROM generate_series(1, 20000) g")
			.update();

		try (var pool = Executors.newFixedThreadPool(16)) {
			var calls = IntStream.range(0, 16).mapToObj(i -> pool.submit(() -> top("b5", 10))).toList();
			for (var call : calls) {
				assertThat(call.get().getTotalPlayers()).isEqualTo(20000); // never a half-loaded leaderboard
			}
		}
		assertThat(StringUtils.countOccurrencesOf(output.getOut(), "Rebuilt leaderboard b5")).isEqualTo(1);
	}

	@Test
	void rejectsBadRequests() {
		assertThat(statusOf(() -> submit("no spaces", "ana", 1))).isEqualTo(Status.Code.INVALID_ARGUMENT);
		assertThat(statusOf(() -> top("b4", 0))).isEqualTo(Status.Code.INVALID_ARGUMENT);
		assertThat(statusOf(() -> client.getAroundPlayer(GetAroundPlayerRequest.newBuilder()
			.setLeaderboardId("b4").setPlayerId("ghost").build()))).isEqualTo(Status.Code.NOT_FOUND);
	}

	private Entry submit(String board, String player, long score) {
		return client.submitScore(SubmitScoreRequest.newBuilder()
			.setLeaderboardId(board).setPlayerId(player).setScore(score).build());
	}

	private Page top(String board, int limit) {
		return client.getTop(GetTopRequest.newBuilder().setLeaderboardId(board).setLimit(limit).build());
	}

	private static Status.Code statusOf(Runnable call) {
		try {
			call.run();
		}
		catch (StatusRuntimeException ex) {
			return ex.getStatus().getCode();
		}
		throw new AssertionError("expected the call to fail");
	}

}
