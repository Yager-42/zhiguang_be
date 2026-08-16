#!/usr/bin/env node

import { createHash, randomUUID } from "node:crypto";
import { appendFile, readFile } from "node:fs/promises";
import path from "node:path";

const HELP = `
Generate demo posts through the production publish API.

Required environment:
  BASE_URL             Application URL, for example http://101.96.237.129
  ACCESS_TOKEN         Existing access token, or use AUTH_IDENTIFIER below

Authentication alternatives when ACCESS_TOKEN is not set:
  AUTH_IDENTIFIER      Existing phone number or email address
  AUTH_IDENTIFIER_TYPE PHONE (default) or EMAIL
  AUTH_PASSWORD        Optional password. Without it, demo-code login is used.

Optional with ACCESS_TOKEN:
  REFRESH_TOKEN        Refresh token for long-running publication

Optional environment:
  POST_START           First content index to publish (default: 1)
  POST_COUNT           Number of posts to create (default: 500, max: 5000)
  CONCURRENCY          Parallel publishers (default: 2, max: 10)
  POST_PREFIX          Title prefix (default: 演示知识帖)
  CONTENT_DIR          Optional directory containing 001.json, 002.json, ...
  PUBLISH_PROGRESS     Optional JSONL progress file used with CONTENT_DIR
  PUBLISH_TIMEOUT_MS   Per-post publish timeout (default: 90000)

Example:
  BASE_URL=http://101.96.237.129 \\
  AUTH_IDENTIFIER=13800138000 \\
  POST_COUNT=500 CONCURRENCY=2 \\
  node loadtest/seed/generate_demo_posts.mjs
`;

if (process.argv.includes("--help") || process.argv.includes("-h")) {
  process.stdout.write(HELP.trimStart());
  process.exit(0);
}

const positiveInteger = (name, fallback, max) => {
  const raw = process.env[name];
  const value = raw === undefined || raw === "" ? fallback : Number(raw);
  if (!Number.isInteger(value) || value < 1 || value > max) {
    throw new Error(`${name} must be an integer between 1 and ${max}`);
  }
  return value;
};

const required = (name) => {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
};

const baseUrl = required("BASE_URL").replace(/\/+$/, "");
const postStart = positiveInteger("POST_START", 1, 5000);
const postCount = positiveInteger("POST_COUNT", 500, 5000);
const postEnd = postStart + postCount - 1;
if (postEnd > 5000) throw new Error("POST_START + POST_COUNT - 1 must not exceed 5000");
const concurrency = Math.min(postCount, positiveInteger("CONCURRENCY", 2, 10));
const publishTimeoutMs = positiveInteger("PUBLISH_TIMEOUT_MS", 90_000, 600_000);
const postPrefix = process.env.POST_PREFIX?.trim() || "演示知识帖";
const runId = randomUUID();
const contentDir = process.env.CONTENT_DIR ? path.resolve(process.env.CONTENT_DIR) : null;
const publishProgressFile = contentDir
  ? path.resolve(process.env.PUBLISH_PROGRESS?.trim() || path.join(contentDir, "published.jsonl"))
  : null;

const auth = {
  accessToken: process.env.ACCESS_TOKEN?.trim() || null,
  refreshToken: process.env.REFRESH_TOKEN?.trim() || null
};
let refreshInFlight = null;

const sleep = (milliseconds) => new Promise(resolve => setTimeout(resolve, milliseconds));

const parseResponse = async (response) => {
  if (response.status === 204) return null;
  const text = await response.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
};

const errorMessage = (method, path, response, data) => {
  const detail = data && typeof data === "object"
    ? data.message || data.code
    : typeof data === "string" ? data.slice(0, 160) : null;
  return `${method} ${path} failed (${response.status})${detail ? `: ${detail}` : ""}`;
};

const refreshAccessToken = async () => {
  if (!auth.refreshToken) throw new Error("Access token expired and no refresh token is available");
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      const response = await fetch(`${baseUrl}/api/v1/auth/token/refresh`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken: auth.refreshToken }),
        signal: AbortSignal.timeout(30_000)
      });
      const data = await parseResponse(response);
      if (!response.ok) throw new Error(errorMessage("POST", "/api/v1/auth/token/refresh", response, data));
      auth.accessToken = data.accessToken;
      auth.refreshToken = data.refreshToken;
    })().finally(() => {
      refreshInFlight = null;
    });
  }
  await refreshInFlight;
};

const request = async (path, { method = "GET", body, authorized = true } = {}, mayRefresh = true) => {
  const headers = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (authorized) {
    if (!auth.accessToken) throw new Error("Authentication is not initialized");
    headers.Authorization = `Bearer ${auth.accessToken}`;
  }
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(30_000)
  });
  if (response.status === 401 && authorized && mayRefresh && auth.refreshToken) {
    await refreshAccessToken();
    return request(path, { method, body, authorized }, false);
  }
  const data = await parseResponse(response);
  if (!response.ok) throw new Error(errorMessage(method, path, response, data));
  return data;
};

const initializeAuthentication = async () => {
  if (auth.accessToken) return;
  const identifier = required("AUTH_IDENTIFIER");
  const identifierType = (process.env.AUTH_IDENTIFIER_TYPE?.trim() || "PHONE").toUpperCase();
  if (identifierType !== "PHONE" && identifierType !== "EMAIL") {
    throw new Error("AUTH_IDENTIFIER_TYPE must be PHONE or EMAIL");
  }

  const password = process.env.AUTH_PASSWORD;
  let loginPayload;
  if (password) {
    loginPayload = { identifierType, identifier, password };
  } else {
    const sent = await request("/api/v1/auth/send-code", {
      method: "POST",
      authorized: false,
      body: { scene: "LOGIN", identifierType, identifier }
    });
    if (!sent?.demoCode) {
      throw new Error("The server did not return demoCode; set ACCESS_TOKEN or AUTH_PASSWORD");
    }
    loginPayload = { identifierType, identifier, code: sent.demoCode };
  }

  const loggedIn = await request("/api/v1/auth/login", {
    method: "POST",
    authorized: false,
    body: loginPayload
  });
  auth.accessToken = loggedIn?.token?.accessToken;
  auth.refreshToken = loggedIn?.token?.refreshToken;
  if (!auth.accessToken) throw new Error("Login response did not contain an access token");
};

const TOPICS = [
  { name: "高效阅读", tags: ["阅读", "学习方法"], angles: ["建立问题清单", "三遍阅读法", "如何做读书笔记", "从输入到输出"] },
  { name: "时间管理", tags: ["效率", "时间管理"], angles: ["安排深度工作", "减少任务切换", "每周复盘模板", "管理碎片时间"] },
  { name: "编程入门", tags: ["编程", "技术成长"], angles: ["拆解第一个项目", "理解调试思路", "建立练习反馈", "阅读优秀代码"] },
  { name: "数据分析", tags: ["数据", "分析方法"], angles: ["从问题定义开始", "避免指标陷阱", "设计清晰图表", "验证分析结论"] },
  { name: "写作表达", tags: ["写作", "表达"], angles: ["写出清楚的开头", "组织论证结构", "删掉无效信息", "建立素材系统"] },
  { name: "英语学习", tags: ["英语", "语言学习"], angles: ["积累高频表达", "训练听力精度", "建立复习周期", "从阅读练写作"] },
  { name: "产品思维", tags: ["产品", "用户研究"], angles: ["识别真实需求", "定义最小方案", "分析用户反馈", "设计验证实验"] },
  { name: "职业成长", tags: ["成长", "职业规划"], angles: ["建立能力地图", "准备有效沟通", "复盘一次项目", "积累可信成果"] },
  { name: "数学思维", tags: ["数学", "思维训练"], angles: ["理解抽象概念", "练习证明思路", "整理错题原因", "连接知识结构"] },
  { name: "公开演讲", tags: ["演讲", "沟通"], angles: ["设计核心观点", "控制表达节奏", "准备现场问答", "用故事解释概念"] }
];

const buildTemplatePost = (index) => {
  const topic = TOPICS[(index - 1) % TOPICS.length];
  const round = Math.floor((index - 1) / TOPICS.length);
  const angle = topic.angles[round % topic.angles.length];
  const sequence = String(index).padStart(3, "0");
  const title = `${postPrefix} ${sequence}｜${topic.name}：${angle}`;
  const description = `围绕${topic.name}整理的第 ${sequence} 篇实践笔记，主题是${angle}。`;
  const body = `# ${title}

学习一个主题时，最重要的不是一次记住所有内容，而是先找到可以重复执行的小步骤。这篇笔记围绕“${angle}”整理一套简单方法。

## 先明确目标

把目标写成一个能够观察的结果，再列出当前最需要解决的一个问题。目标越具体，后续选择资料和安排练习就越容易。

## 用行动形成反馈

每次只安排一个小练习，完成后记录遇到的困难、采取的办法和最终结果。下一次练习优先修正最明显的问题，不追求一次做到完美。

## 定期整理

每周用十分钟回顾记录，将重复出现的问题归类，并保留真正有效的方法。经过几轮调整，就能形成适合自己的${topic.name}流程。

> 这是一篇用于展示知光内容、搜索和推荐效果的合成帖子，不包含真实个人信息。
`;
  return { title, description, body, tags: [...topic.tags, "演示数据"] };
};

const buildPost = async (index) => {
  if (!contentDir) return buildTemplatePost(index);
  const filename = path.join(contentDir, `${String(index).padStart(3, "0")}.json`);
  const parsed = JSON.parse(await readFile(filename, "utf8"));
  if (typeof parsed.title !== "string" || typeof parsed.description !== "string"
      || typeof parsed.body !== "string" || !Array.isArray(parsed.tags)) {
    throw new Error(`${filename} does not contain title, description, tags and body`);
  }
  return {
    title: parsed.title.trim(),
    description: parsed.description.trim(),
    body: parsed.body.trim(),
    tags: parsed.tags.map(tag => String(tag).trim()).filter(Boolean)
  };
};

const uploadBody = async (postId, body) => {
  const content = Buffer.from(body, "utf8");
  const sha256 = createHash("sha256").update(content).digest("hex");
  const presign = await request("/api/v1/storage/presign", {
    method: "POST",
    body: {
      scene: "knowpost_content",
      postId,
      contentType: "text/markdown",
      ext: ".md"
    }
  });
  const response = await fetch(presign.putUrl, {
    method: "PUT",
    headers: presign.headers,
    body: content,
    signal: AbortSignal.timeout(30_000)
  });
  if (!response.ok) throw new Error(`PUT presigned content failed (${response.status})`);
  const etag = response.headers.get("etag")?.replaceAll('"', "") || sha256.slice(0, 32);
  await request(`/api/v1/knowposts/${postId}/content/confirm`, {
    method: "POST",
    body: { objectKey: presign.objectKey, etag, size: content.byteLength, sha256 }
  });
};

const waitForPublish = async (postId, attemptId) => {
  const deadline = Date.now() + publishTimeoutMs;
  let retried = false;
  while (Date.now() < deadline) {
    const status = await request(
      `/api/v1/knowposts/${postId}/publish/status?attemptId=${encodeURIComponent(attemptId)}`
    );
    if (status.attemptStatus === "succeeded" && status.postStatus === "published") return;
    if (status.attemptStatus === "failed") {
      if (status.retryable && !retried) {
        await request(`/api/v1/knowposts/${postId}/publish/${attemptId}/retry`, { method: "POST" });
        retried = true;
      } else {
        throw new Error(`Publish failed at ${status.failedStep || "unknown step"}`);
      }
    }
    await sleep(500);
  }
  throw new Error(`Publish timed out after ${publishTimeoutMs}ms`);
};

const createPost = async (index) => {
  const post = await buildPost(index);
  const draft = await request("/api/v1/knowposts/drafts", { method: "POST" });
  const postId = draft.id;
  await uploadBody(postId, post.body);
  await request(`/api/v1/knowposts/${postId}`, {
    method: "PATCH",
    body: {
      title: post.title,
      tags: post.tags,
      visible: "public",
      isTop: false,
      description: post.description
    }
  });
  const accepted = await request(`/api/v1/knowposts/${postId}/publish`, {
    method: "POST",
    body: { idempotentKey: `demo-seed:${runId}:${index}` }
  });
  await waitForPublish(postId, accepted.publishAttemptId);
  return postId;
};

await initializeAuthentication();
console.log(`Creating ${postCount} posts (${postStart}..${postEnd}) with concurrency ${concurrency}...`);

let nextIndex = postStart;
let completed = 0;
const createdIds = [];
const failures = [];
const alreadyPublished = new Map();
let progressWrite = Promise.resolve();

if (publishProgressFile) {
  try {
    const lines = (await readFile(publishProgressFile, "utf8")).split("\n").filter(Boolean);
    for (const line of lines) {
      const record = JSON.parse(line);
      if (Number.isInteger(record.index) && record.postId) {
        alreadyPublished.set(record.index, String(record.postId));
      }
    }
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
  }
}

const recordPublished = async (index, postId) => {
  if (!publishProgressFile) return;
  const line = `${JSON.stringify({ index, postId, publishedAt: new Date().toISOString() })}\n`;
  progressWrite = progressWrite.then(() => appendFile(publishProgressFile, line, { mode: 0o600 }));
  await progressWrite;
};

const worker = async () => {
  while (true) {
    const index = nextIndex;
    nextIndex += 1;
    if (index > postEnd) return;
    if (alreadyPublished.has(index)) {
      completed += 1;
      continue;
    }
    try {
      const postId = await createPost(index);
      await recordPublished(index, postId);
      createdIds.push(postId);
      completed += 1;
      if (completed % 10 === 0 || completed === postCount) {
        console.log(`Progress: ${completed}/${postCount}`);
      }
    } catch (error) {
      failures.push({ index, message: error instanceof Error ? error.message : String(error) });
      console.error(`Post ${index} failed: ${failures.at(-1).message}`);
    }
  }
};

await Promise.all(Array.from({ length: concurrency }, () => worker()));

console.log(`Finished: created=${createdIds.length}, failed=${failures.length}`);
if (createdIds.length > 0) {
  console.log(`Created post IDs: first=${createdIds[0]}, last=${createdIds.at(-1)}`);
}
if (failures.length > 0) {
  console.error("Failed indexes:", failures.map(item => item.index).join(","));
  process.exitCode = 1;
}
