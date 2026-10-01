package dev.sarthak.leaderboard;

import dev.sarthak.leaderboard.v1.Entry;
import dev.sarthak.leaderboard.v1.GetAroundPlayerRequest;
import dev.sarthak.leaderboard.v1.GetFriendsRequest;
import dev.sarthak.leaderboard.v1.GetTopRequest;
import dev.sarthak.leaderboard.v1.LeaderboardGrpc;
import dev.sarthak.leaderboard.v1.Page;
import dev.sarthak.leaderboard.v1.SubmitScoreRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** gRPC edge: validates requests, then hands off to {@link LeaderboardStore}. */
@Service
class LeaderboardGrpcService extends LeaderboardGrpc.LeaderboardImplBase {

	private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

	// Redis stores scores as doubles, which are exact only up to 2^53.
	private static final long MAX_SCORE = 1L << 53;

	private final LeaderboardStore store;

	LeaderboardGrpcService(LeaderboardStore store) {
		this.store = store;
	}

	@Override
	public void submitScore(SubmitScoreRequest req, StreamObserver<Entry> out) {
		reply(out, () -> {
			check(Math.abs(req.getScore()) <= MAX_SCORE, "score must be within +/-2^53");
			return store.submit(id(req.getLeaderboardId()), id(req.getPlayerId()), req.getScore());
		});
	}

	@Override
	public void getTop(GetTopRequest req, StreamObserver<Page> out) {
		reply(out, () -> {
			check(req.getLimit() >= 1 && req.getLimit() <= 100, "limit must be 1-100");
			return store.top(id(req.getLeaderboardId()), req.getLimit());
		});
	}

	@Override
	public void getAroundPlayer(GetAroundPlayerRequest req, StreamObserver<Page> out) {
		reply(out, () -> {
			check(req.getRadius() >= 0 && req.getRadius() <= 50, "radius must be 0-50");
			Page page = store.around(id(req.getLeaderboardId()), id(req.getPlayerId()), req.getRadius());
			if (page == null) {
				throw Status.NOT_FOUND.withDescription("player has no score on this leaderboard").asRuntimeException();
			}
			return page;
		});
	}

	@Override
	public void getFriends(GetFriendsRequest req, StreamObserver<Page> out) {
		reply(out, () -> {
			check(req.getPlayerIdsCount() <= 500, "at most 500 player_ids");
			req.getPlayerIdsList().forEach(LeaderboardGrpcService::id);
			return store.friends(id(req.getLeaderboardId()), req.getPlayerIdsList());
		});
	}

	private static <T> void reply(StreamObserver<T> out, Supplier<T> call) {
		try {
			out.onNext(call.get());
			out.onCompleted();
		}
		catch (StatusRuntimeException ex) {
			out.onError(ex);
		}
	}

	private static String id(String value) {
		check(ID.matcher(value).matches(), "ids must be 1-64 letters, digits, '_', '.' or '-'");
		return value;
	}

	private static void check(boolean ok, String message) {
		if (!ok) {
			throw Status.INVALID_ARGUMENT.withDescription(message).asRuntimeException();
		}
	}

}
