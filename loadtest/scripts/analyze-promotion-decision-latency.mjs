import readline from 'node:readline';

const latencies = [];
const decisionTypes = new Map();
let earliestSubmittedAt = Number.POSITIVE_INFINITY;
let latestSubmittedAt = Number.NEGATIVE_INFINITY;
let earliestDecidedAt = Number.POSITIVE_INFINITY;
let latestDecidedAt = Number.NEGATIVE_INFINITY;

const input = readline.createInterface({
  input: process.stdin,
  crlfDelay: Infinity,
});

for await (const line of input) {
  if (!line.trim().startsWith('{')) {
    continue;
  }
  const envelope = JSON.parse(line);
  const decision = envelope.decision;
  decisionTypes.set(decision.type, (decisionTypes.get(decision.type) || 0) + 1);
  if (!decision.type.startsWith('BID_') || !decision.payload?.submittedAt) {
    continue;
  }
  const submittedAt = Date.parse(decision.payload.submittedAt);
  const decidedAt = Date.parse(decision.decidedAt);
  latencies.push(decidedAt - submittedAt);
  earliestSubmittedAt = Math.min(earliestSubmittedAt, submittedAt);
  latestSubmittedAt = Math.max(latestSubmittedAt, submittedAt);
  earliestDecidedAt = Math.min(earliestDecidedAt, decidedAt);
  latestDecidedAt = Math.max(latestDecidedAt, decidedAt);
}

latencies.sort((left, right) => left - right);

function percentile(percent) {
  if (latencies.length === 0) {
    return null;
  }
  const index = (latencies.length - 1) * percent;
  const lower = Math.floor(index);
  const upper = Math.ceil(index);
  if (lower === upper) {
    return latencies[lower];
  }
  return latencies[lower] + (latencies[upper] - latencies[lower]) * (index - lower);
}

const total = latencies.reduce((sum, value) => sum + value, 0);
const decisionSpanSeconds = (latestDecidedAt - earliestDecidedAt) / 1000;
console.log(JSON.stringify({
  bidDecisionCount: latencies.length,
  decisionTypes: Object.fromEntries([...decisionTypes.entries()].sort()),
  timing: latencies.length === 0 ? null : {
    earliestSubmittedAt: new Date(earliestSubmittedAt).toISOString(),
    latestSubmittedAt: new Date(latestSubmittedAt).toISOString(),
    earliestDecidedAt: new Date(earliestDecidedAt).toISOString(),
    latestDecidedAt: new Date(latestDecidedAt).toISOString(),
    submissionSpanSeconds: (latestSubmittedAt - earliestSubmittedAt) / 1000,
    decisionSpanSeconds,
    decisionThroughputPerSecond: decisionSpanSeconds === 0 ? null : latencies.length / decisionSpanSeconds,
    drainAfterLastSubmissionMs: latestDecidedAt - latestSubmittedAt,
  },
  latencyMs: {
    average: latencies.length === 0 ? null : total / latencies.length,
    p50: percentile(0.5),
    p95: percentile(0.95),
    p99: percentile(0.99),
    maximum: latencies.at(-1) ?? null,
  },
}, null, 2));
