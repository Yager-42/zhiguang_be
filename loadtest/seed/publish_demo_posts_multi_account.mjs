#!/usr/bin/env node

import { spawn } from "node:child_process";
import { access, mkdir, readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HELP = `
Publish generated posts through a fixed set of demo author accounts.

Required environment:
  BASE_URL              Application URL, for example http://101.96.237.129
  CONTENT_DIR           Directory containing 001.json, 002.json, ...

Optional environment:
  POST_COUNT            Total posts to publish (default: 500)
  AUTHOR_COUNT          Demo author accounts (default: 20)
  ACCOUNT_CONCURRENCY   Authors publishing concurrently (default: 2, max: 5)
  PUBLISH_CONCURRENCY   Posts per author concurrently (default: 1, max: 3)
  ACCOUNT_PREFIX        Email local-part prefix (default: demo-author)
  ACCOUNT_DOMAIN        Email domain (default: seed.zhiguang.test)
  PROGRESS_DIR          Per-author progress directory (default: CONTENT_DIR/publish-progress)

The default mapping for 500 posts and 20 authors is:
  demo-author-01: 001..025
  ...
  demo-author-20: 476..500
`;

if (process.argv.includes("--help") || process.argv.includes("-h")) {
  process.stdout.write(HELP.trimStart());
  process.exit(0);
}

const integer = (name, fallback, min, max) => {
  const raw = process.env[name];
  const value = raw === undefined || raw === "" ? fallback : Number(raw);
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new Error(`${name} must be an integer between ${min} and ${max}`);
  }
  return value;
};

const required = name => {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
};

const baseUrl = required("BASE_URL").replace(/\/+$/, "");
const contentDir = path.resolve(required("CONTENT_DIR"));
const postCount = integer("POST_COUNT", 500, 1, 5000);
const authorCount = integer("AUTHOR_COUNT", 20, 1, 100);
const accountConcurrency = Math.min(authorCount, integer("ACCOUNT_CONCURRENCY", 2, 1, 5));
const publishConcurrency = integer("PUBLISH_CONCURRENCY", 1, 1, 3);
const accountPrefix = process.env.ACCOUNT_PREFIX?.trim() || "demo-author";
const accountDomain = process.env.ACCOUNT_DOMAIN?.trim() || "seed.zhiguang.test";
const progressDir = path.resolve(
  process.env.PROGRESS_DIR?.trim() || path.join(contentDir, "publish-progress")
);
const publisherPath = path.join(path.dirname(fileURLToPath(import.meta.url)), "generate_demo_posts.mjs");

class ApiError extends Error {
  constructor(message, status, code) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

const apiRequest = async (route, { method = "GET", body, token } = {}) => {
  const headers = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(`${baseUrl}${route}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(30_000)
  });
  const text = await response.text();
  let payload = null;
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch {
      payload = text;
    }
  }
  if (!response.ok) {
    const code = payload && typeof payload === "object" ? payload.code : null;
    const detail = payload && typeof payload === "object"
      ? payload.message || code
      : typeof payload === "string" ? payload.slice(0, 160) : null;
    throw new ApiError(`${method} ${route} failed (${response.status})${detail ? `: ${detail}` : ""}`, response.status, code);
  }
  return payload;
};

const requestCode = async (email, scene) => {
  const sent = await apiRequest("/api/v1/auth/send-code", {
    method: "POST",
    body: { scene, identifierType: "EMAIL", identifier: email }
  });
  if (!sent?.demoCode) throw new Error(`Server did not return a demo code for ${email}`);
  return sent.demoCode;
};

const login = async email => {
  const code = await requestCode(email, "LOGIN");
  return apiRequest("/api/v1/auth/login", {
    method: "POST",
    body: { identifierType: "EMAIL", identifier: email, code }
  });
};

const register = async email => {
  const code = await requestCode(email, "REGISTER");
  return apiRequest("/api/v1/auth/register", {
    method: "POST",
    body: { identifierType: "EMAIL", identifier: email, code, agreeTerms: true }
  });
};

const ensureAuthor = async authorNumber => {
  const sequence = String(authorNumber).padStart(2, "0");
  const email = `${accountPrefix}-${sequence}@${accountDomain}`;
  let authResponse;
  let created = false;
  try {
    authResponse = await login(email);
  } catch (error) {
    if (!(error instanceof ApiError) || error.code !== "IDENTIFIER_NOT_FOUND") throw error;
    authResponse = await register(email);
    created = true;
  }
  const accessToken = authResponse?.token?.accessToken;
  if (!accessToken) throw new Error(`Authentication response did not contain a token for ${email}`);
  await apiRequest("/api/v1/profile", {
    method: "PATCH",
    token: accessToken,
    body: {
      nickname: `知光演示作者 ${sequence}`,
      bio: "用于展示知识社区内容、搜索和推荐效果的演示作者。",
      zgId: `demo_author_${sequence}`
    }
  });
  return {
    authorNumber,
    sequence,
    email,
    created,
    accessToken,
    refreshToken: authResponse?.token?.refreshToken || null
  };
};

const prefixStream = (stream, prefix, target) => {
  let buffered = "";
  stream.setEncoding("utf8");
  stream.on("data", chunk => {
    buffered += chunk;
    const lines = buffered.split("\n");
    buffered = lines.pop() || "";
    for (const line of lines) target.write(`[${prefix}] ${line}\n`);
  });
  stream.on("end", () => {
    if (buffered) target.write(`[${prefix}] ${buffered}\n`);
  });
};

const runPublisher = (author, start, count) => new Promise((resolve, reject) => {
  const progressFile = path.join(progressDir, `published-author-${author.sequence}.jsonl`);
  const child = spawn(process.execPath, [publisherPath], {
    env: {
      ...process.env,
      BASE_URL: baseUrl,
      ACCESS_TOKEN: author.accessToken,
      REFRESH_TOKEN: author.refreshToken || "",
      CONTENT_DIR: contentDir,
      PUBLISH_PROGRESS: progressFile,
      POST_START: String(start),
      POST_COUNT: String(count),
      CONCURRENCY: String(publishConcurrency)
    },
    stdio: ["ignore", "pipe", "pipe"]
  });
  prefixStream(child.stdout, `author-${author.sequence}`, process.stdout);
  prefixStream(child.stderr, `author-${author.sequence}`, process.stderr);
  child.on("error", reject);
  child.on("exit", (code, signal) => {
    if (code === 0) resolve();
    else reject(new Error(`author-${author.sequence} publisher exited with ${signal || code}`));
  });
});

await access(publisherPath);
for (let index = 1; index <= postCount; index += 1) {
  await access(path.join(contentDir, `${String(index).padStart(3, "0")}.json`));
  JSON.parse(await readFile(path.join(contentDir, `${String(index).padStart(3, "0")}.json`), "utf8"));
}
await mkdir(progressDir, { recursive: true, mode: 0o700 });

console.log(`Preparing ${authorCount} demo authors for ${postCount} posts...`);
const authors = [];
for (let authorNumber = 1; authorNumber <= authorCount; authorNumber += 1) {
  const author = await ensureAuthor(authorNumber);
  authors.push(author);
  console.log(`Author ${author.sequence} ready (${author.created ? "created" : "reused"})`);
}

const baseShare = Math.floor(postCount / authorCount);
const remainder = postCount % authorCount;
const assignments = [];
let nextPost = 1;
for (const author of authors) {
  const count = baseShare + (author.authorNumber <= remainder ? 1 : 0);
  if (count > 0) assignments.push({ author, start: nextPost, count });
  nextPost += count;
}

console.log(`Publishing with ${accountConcurrency} active authors and ${publishConcurrency} post(s) per author...`);
let nextAssignment = 0;
const failures = [];
const worker = async () => {
  while (true) {
    const assignmentIndex = nextAssignment;
    nextAssignment += 1;
    if (assignmentIndex >= assignments.length) return;
    const assignment = assignments[assignmentIndex];
    try {
      await runPublisher(assignment.author, assignment.start, assignment.count);
    } catch (error) {
      failures.push({
        author: assignment.author.sequence,
        start: assignment.start,
        count: assignment.count,
        message: error instanceof Error ? error.message : String(error)
      });
    }
  }
};

await Promise.all(Array.from({ length: accountConcurrency }, () => worker()));
if (failures.length > 0) {
  console.error(JSON.stringify({ failures }, null, 2));
  process.exitCode = 1;
} else {
  console.log(`Finished: ${postCount} posts assigned across ${assignments.length} authors`);
}
