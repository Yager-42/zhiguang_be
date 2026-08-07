# Comment Single-Node Throughput Report

## Frozen Conditions

| Field | Baseline | Candidate |
|---|---|---|
| Git SHA | `3468b38` | |
| Seed hash | | |
| App JVM / image | | |
| Docker resources | | |
| Topology audit | 1/1/1/1/1 | 1/1/1/1/1 |
| Arrival rate / duration | | |
| Cache state | | |

P3 significant regression threshold is fixed before candidate execution at **10%**.

## Five-Round Results

| Variant | Scenario | Round | Successful rate | P50 | P95 | P99 | Max | Errors | Dropped | Backlog slope | Drain seconds |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline | pure-read | 1 | | | | | | | | | |
| candidate | pure-read | 1 | | | | | | | | | |

All failed or invalid rounds remain listed with the reason and raw artifact path.

## Acceptance

| Gate | Evidence | Result |
|---|---|---|
| P1 pure-read median improves | | |
| P2 mixed read median improves | | |
| P3 mixed P95/P99 policy | | |
| P4 outbox published rate improves | | |
| P5 materialized rate improves | | |
| P6 backlog slope improves | | |
| P7 at least 4/5 candidate rounds exceed baseline median | | |
| P8 no correctness/error regression | | |

## Functional Gates

| Gate | Test/live evidence | Result |
|---|---|---|
| F1-F12 | | |
| C1-C10 | | |

## Raw Artifacts

Record every `metadata.env`, `k6-summary.json`, `k6-raw.json`, `sut.tsv`, `pending.tsv`, `outbox.tsv`, `kafka-groups.txt`, and `drain.tsv` path here.
