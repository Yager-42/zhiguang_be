import fs from 'node:fs';
import path from 'node:path';

const root = process.argv[2];
const medianFile = process.argv[3];
if (!root || !fs.existsSync(root)) {
  throw new Error(`result root does not exist: ${root}`);
}

const summaries = [];
const rows = [];
walk(root);
for (const directory of summaries.sort()) {
  const metadata = parseEnv(path.join(directory, 'metadata.env'));
  const summary = JSON.parse(fs.readFileSync(path.join(directory, 'k6-summary.json'), 'utf8'));
  const metrics = summary.metrics || {};
  const read = metric(metrics.comment_read_succeeded);
  const accepted = metric(metrics.comment_submit_accepted);
  const latency = metric(metrics.comment_read_duration);
  const errors = metric(metrics.comment_business_errors);
  const dropped = metric(metrics.dropped_iterations);
  const drainSeconds = readDrainSeconds(path.join(directory, 'drain.tsv'));
  const publishedDelta = readPublishedDelta(directory);
  const publishedPerSecond = rate(publishedDelta, drainSeconds);
  const materializedPerSecond = rate(accepted.count, drainSeconds);
  const measurementSeconds = durationSeconds(metadata.duration);
  const row = [
    metadata.variant, metadata.scenario, metadata.round, metadata.sha, metadata.seed_hash,
    rate(read.count, measurementSeconds), rate(accepted.count, measurementSeconds),
    value(latency.med), value(latency['p(95)']),
    value(latency['p(99)']), value(latency.max), value(errors.value ?? errors.rate), value(dropped.count),
    drainSeconds, directory, value(read.count), value(accepted.count), value(publishedDelta),
    publishedPerSecond, materializedPerSecond,
  ];
  rows.push(row);
  console.log(row.join(','));
}

if (medianFile) {
  writeMedians(medianFile, rows);
}

function walk(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const child = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      walk(child);
    } else if (entry.name === 'k6-summary.json') {
      summaries.push(directory);
    }
  }
}

function parseEnv(file) {
  return Object.fromEntries(fs.readFileSync(file, 'utf8').trim().split(/\r?\n/)
    .map((line) => line.split(/=(.*)/s).slice(0, 2)));
}

function readDrainSeconds(file) {
  if (!fs.existsSync(file)) {
    return '';
  }
  const lines = fs.readFileSync(file, 'utf8').trim().split(/\r?\n/);
  return lines.length > 1 ? lines.at(-1).split('\t')[0] : '';
}

function readPublishedDelta(directory) {
  const sutFile = path.join(directory, 'sut.tsv');
  const initialOutboxFile = path.join(directory, 'outbox-before.tsv');
  const outboxFile = path.join(directory, 'outbox.tsv');
  if (!fs.existsSync(outboxFile)) {
    return '';
  }
  let initial;
  if (fs.existsSync(initialOutboxFile)) {
    initial = publishedOutboxCount(initialOutboxFile);
  } else if (fs.existsSync(sutFile)) {
    const samples = fs.readFileSync(sutFile, 'utf8').trim().split(/\r?\n/).slice(1)
      .map((line) => line.split('\t'))
      .filter((columns) => columns.length >= 14 && Number.isFinite(Number(columns[10])));
    initial = samples.length === 0 ? undefined : Number(samples[0][10]);
  }
  if (!Number.isFinite(initial)) {
    return '';
  }
  const final = publishedOutboxCount(outboxFile);
  return Math.max(0, final - initial);
}

function publishedOutboxCount(file) {
  return fs.readFileSync(file, 'utf8').trim().split(/\r?\n/)
    .map((line) => line.split('\t'))
    .filter((columns) => Number(columns[2]) === 2)
    .map((columns) => Number(columns[3]))
    .filter(Number.isFinite)
    .reduce((sum, count) => sum + count, 0);
}

function value(input) {
  return input === undefined ? '' : input;
}

function metric(input) {
  return input?.values || input || {};
}

function rate(count, seconds) {
  const numericCount = Number(count);
  const numericSeconds = Number(seconds);
  return Number.isFinite(numericCount) && Number.isFinite(numericSeconds) && numericSeconds > 0
    ? numericCount / numericSeconds
    : '';
}

function durationSeconds(input) {
  if (!input) {
    return undefined;
  }
  const units = { ms: 0.001, s: 1, m: 60, h: 3600 };
  const pattern = /(\d+(?:\.\d+)?)(ms|s|m|h)/g;
  let seconds = 0;
  let consumed = '';
  let match;
  while ((match = pattern.exec(input)) !== null) {
    seconds += Number(match[1]) * units[match[2]];
    consumed += match[0];
  }
  return consumed === input && seconds > 0 ? seconds : undefined;
}

function writeMedians(file, resultRows) {
  const header = [
    'variant', 'scenario', 'rounds', 'read_per_s', 'accepted_per_s', 'read_p50_ms',
    'read_p95_ms', 'read_p99_ms', 'read_max_ms', 'error_rate', 'dropped', 'drain_seconds',
    'read_count', 'accepted_count', 'outbox_published_delta', 'outbox_published_per_s',
    'materialized_per_s',
  ];
  const groups = new Map();
  for (const row of resultRows) {
    const key = `${row[0]}\u0000${row[1]}`;
    const group = groups.get(key) || [];
    group.push(row);
    groups.set(key, group);
  }
  const lines = [header.join(',')];
  for (const [key, group] of [...groups.entries()].sort()) {
    const [variant, scenario] = key.split('\u0000');
    const values = [variant, scenario, group.length];
    for (let column = 5; column <= 13; column += 1) {
      values.push(median(group.map((row) => row[column])));
    }
    for (let column = 15; column <= 19; column += 1) {
      values.push(median(group.map((row) => row[column])));
    }
    lines.push(values.join(','));
  }
  fs.writeFileSync(file, `${lines.join('\n')}\n`);
}

function median(inputs) {
  const numbers = inputs.filter((input) => input !== '').map(Number).filter(Number.isFinite)
    .sort((left, right) => left - right);
  if (numbers.length === 0) {
    return '';
  }
  const middle = Math.floor(numbers.length / 2);
  return numbers.length % 2 === 1
    ? numbers[middle]
    : (numbers[middle - 1] + numbers[middle]) / 2;
}
