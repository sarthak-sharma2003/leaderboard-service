// Mixed read/write load over gRPC: 40% SubmitScore, 40% GetTop, 20% GetAroundPlayer.
// See the README for how to seed and run it.
import grpc from 'k6/net/grpc';
import { check } from 'k6';

const TARGET = __ENV.TARGET || 'localhost:9090';
const BOARD = 'season1';
const PLAYERS = 100000; // matches seed.sql

export const options = {
  vus: Number(__ENV.VUS || 32),
  duration: __ENV.DURATION || '30s',
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

const client = new grpc.Client();

export default () => {
  if (__ITER === 0) {
    client.connect(TARGET, { plaintext: true, reflect: true });
  }
  const player = `p${1 + Math.floor(Math.random() * PLAYERS)}`;
  const roll = Math.random();
  let res;
  if (roll < 0.4) {
    res = client.invoke('leaderboard.v1.Leaderboard/SubmitScore', {
      leaderboard_id: BOARD,
      player_id: player,
      score: Math.floor(Math.random() * 1000000),
    });
  } else if (roll < 0.8) {
    res = client.invoke('leaderboard.v1.Leaderboard/GetTop', { leaderboard_id: BOARD, limit: 10 });
  } else {
    res = client.invoke('leaderboard.v1.Leaderboard/GetAroundPlayer', {
      leaderboard_id: BOARD,
      player_id: player,
      radius: 5,
    });
  }
  check(res, { ok: (r) => r && r.status === grpc.StatusOK });
};
