# Comment Single-Node Throughput Verification Report

## Conclusion

`comment-single-node-throughput-v1` is implemented and accepted on the frozen
single-node topology. Gates P1-P8 pass on the completed five-round baseline and
candidate datasets. The final image was additionally checked for four complete
rounds after dependency metrics and Cassandra retry instrumentation were added.
At the user's request, the fifth final-image round was stopped and is not used
as a complete comparison round.

## Frozen Conditions

| Field | Baseline | Candidate |
|---|---|---|
| Git SHA | `3468b38` | `51d88877c7ad3a6828dadedeb7a84e45c264f35b` + working-tree implementation |
| Seed hash | `eb9a9e8a3803cf1aeb77a3a25f5808a02e2d45efdd41182293aef4695a424265` | same |
| Topology | 1 App, 1 MySQL, 1 Redis, 1 Kafka, 1 Cassandra | same |
| Load | 500 fixed arrivals/s, 30 s, 500 VUs | same |
| Mixed load | exact 400 reads/s + 100 submits/s | same |
| Drain load | fixed 5,000 accepted writes with Kafka paused | same |
| Cache | warm head page for throughput comparison | same |
| Significant P3 regression threshold | 10% (fixed before candidate run) | 10% |

The WSL boot ID and Docker monotonic start were constant during each formal
comparison. Windows Time is running and synchronized; the final Windows/WSL
clock skew check was 175 ms. A run fails before load generation when NTP is not
synchronized, skew exceeds 1,000 ms, or WSL/Docker continuity changes.

## Five-Round Medians

| Scenario | Metric | Baseline | Candidate | Result |
|---|---|---:|---:|---|
| pure-read | successful reads/s | 499.834 | 499.976 | pass |
| pure-read | P50 / P95 / P99 ms | 7.237 / 14.127 / 119.012 | 1.811 / 12.935 / 115.640 | pass |
| exact 80/20 mixed | successful reads/s | 370.771 | 399.977 | pass |
| exact 80/20 mixed | accepted submits/s | 92.543 | 97.148 | pass |
| exact 80/20 mixed | P50 / P95 / P99 ms | 11.337 / 447.631 / 1534.054 | 2.852 / 20.916 / 305.799 | pass |
| outbox drain | drain seconds | 22.379 | 4.587 | pass |
| outbox drain | published/s | 223.424 | 2180.074 | pass |
| outbox drain | materialized/s | 223.424 | 1090.037 | pass |

All five exact-mixed candidate read rounds exceeded the baseline median. Error
rate was zero. Candidate dropped-iteration median fell from 1327.5 to 135.5.

## Final-Image Confirmation

Four complete final-image rounds used the same frozen parameters. Exact-mixed
read rates were `399.921`, `399.951`, `389.405`, and `399.500` reads/s, all above
the baseline median. Their read P95 values were `30.111`, `20.659`, `25.039`, and
`24.416` ms. Drain times were `5.961`, `6.271`, `4.345`, and `5.833` seconds.
The interrupted fifth round produced partial pure/mixed artifacts and no drain
artifact; it is retained for audit but excluded from complete-round statistics.

## Acceptance

| Gate | Evidence | Result |
|---|---|---|
| P1 | pure-read median 499.976 > 499.834 reads/s | pass |
| P2 | exact mixed median 399.977 > 370.771 reads/s | pass |
| P3 | mixed P95 improved 95.3%, P99 improved 80.1% | pass |
| P4 | outbox publish rate improved 9.76x | pass |
| P5 | materialization rate improved 4.88x | pass |
| P6 | fixed backlog drain fell from 22.379 s to 4.587 s; overload backlog recovered | pass |
| P7 | 5/5 exact-mixed candidate rounds exceeded the baseline median | pass |
| P8 | zero business errors; deterministic duplicate/finalizer/DLT tests green | pass |

The bounded 2,000 submit/s overload run accepted 48,122 requests at 1,562/s,
reached a ready-backlog peak of 14,854 and oldest age of 7 seconds, then drained
fully. The bounded-resource probe observed executor queue peak 0, heap peak
318,090,800 bytes, and Hikari pending peak 195. The 4/6/8 consumer ladder was
recorded; concurrency 4 remains the frozen production default.

## Functional Verification

- Full Maven suite: 598 tests, 0 failures, 0 errors, 39 existing skips.
- Cache live gate: cold L3, L1, L2, user-liked isolation, fragment all-or-miss,
  empty protection, cursor bypass, invalidation, dependency proof, and 100-way
  distributed singleflight passed. The cold singleflight load caused exactly one
  MySQL query.
- Write live gate: happy path, submit idempotence, Kafka outage/backlog recovery,
  Cassandra outage/recovery, Cassandra-first delete, unified outbox schema and
  clean cutover passed. During Cassandra outage the request remained `pending`;
  after recovery it became `succeeded`.
- Deterministic integration tests cover Cassandra-success/finalizer-failure,
  duplicate delivery, partial dispatcher success, DLT, retention batch bounds,
  moderation, and three independent side-effect consumer groups.
- Static removal audit found no old `comment_write_outbox` production table or
  implementation, old-to-new migration, blocking Kafka send, comment-local
  singleflight, unbounded executor, or per-request `log.info`.
- `docker compose config`, shell syntax checks, `git diff --check`, actuator
  health, metrics access, topology audit, and clock check passed.

Implementation and review followed the Alibaba Java Coding Guidelines together
with the repository development and architecture contracts, including bounded
executors, transaction boundaries, idempotence, retry observability, structured
configuration, and removal of obsolete code paths.

## Artifacts

- Five-round summaries: `loadtest/results/comment-throughput/medians.csv`
- Raw baseline: `loadtest/results/comment-throughput/baseline-capacity-500/`
- Raw exact mixed baseline: `loadtest/results/comment-throughput/baseline-mixed-exact-500/`
- Raw candidate: `loadtest/results/comment-throughput/candidate-capacity-500/`
- Raw exact mixed candidate: `loadtest/results/comment-throughput/candidate-mixed-exact-500/`
- Final-image confirmation: `loadtest/results/comment-throughput/candidate-final-capacity-500/`
- Overload and ladder: `candidate-overload-*` and `candidate-concurrency-*`
