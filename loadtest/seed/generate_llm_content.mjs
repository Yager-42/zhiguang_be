#!/usr/bin/env node

import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import path from "node:path";

const HELP = `
Generate one knowledge post per LLM API request and save each response as JSON.

Required environment:
  LLM_API_KEY          API key (read from the environment only)

Optional environment:
  LLM_BASE_URL         OpenAI-compatible base URL (default: https://pro3.o0n0o.cc/v1)
  LLM_MODEL            Model name (default: gpt-5.6-luna)
  POST_COUNT           Number of articles (default: 500, max: 5000)
  CONCURRENCY          Concurrent API requests (default: 2, max: 10)
  OUTPUT_DIR           JSON output directory (default: loadtest/seed/generated/luna-500)
  REQUEST_TIMEOUT_MS   Per-request timeout (default: 120000)

Example:
  LLM_API_KEY=... POST_COUNT=500 CONCURRENCY=2 \\
  node loadtest/seed/generate_llm_content.mjs
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

const apiKey = process.env.LLM_API_KEY?.trim();
if (!apiKey) throw new Error("LLM_API_KEY is required");

const baseUrl = (process.env.LLM_BASE_URL?.trim() || "https://pro3.o0n0o.cc/v1").replace(/\/+$/, "");
const model = process.env.LLM_MODEL?.trim() || "gpt-5.6-luna";
const postCount = integer("POST_COUNT", 500, 1, 5000);
const concurrency = Math.min(postCount, integer("CONCURRENCY", 2, 1, 10));
const requestTimeoutMs = integer("REQUEST_TIMEOUT_MS", 120_000, 10_000, 600_000);
const outputDir = path.resolve(process.env.OUTPUT_DIR?.trim() || "loadtest/seed/generated/luna-500");

const CATEGORIES = [
  "高效阅读", "时间管理", "编程学习", "数据分析", "写作表达",
  "英语学习", "产品思维", "职业成长", "数学思维", "公开演讲",
  "团队协作", "信息检索", "项目管理", "设计基础", "人工智能",
  "网络安全", "摄影入门", "心理韧性", "校园学习", "知识管理"
];
const FORMATS = ["入门指南", "常见误区", "实践清单", "案例复盘", "进阶方法"];
const AUDIENCES = ["大学生", "零基础初学者", "正在做项目的学习者", "希望提升效率的职场新人", "有一定经验的实践者"];

const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const fileFor = index => path.join(outputDir, `${String(index).padStart(3, "0")}.json`);

const briefFor = index => ({
  category: CATEGORIES[(index - 1) % CATEGORIES.length],
  format: FORMATS[Math.floor((index - 1) / CATEGORIES.length) % FORMATS.length],
  audience: AUDIENCES[Math.floor((index - 1) / (CATEGORIES.length * FORMATS.length)) % AUDIENCES.length]
});

const normalizeArticle = (value, index) => {
  if (!value || typeof value !== "object") throw new Error("response is not a JSON object");
  const title = typeof value.title === "string" ? value.title.trim() : "";
  const description = typeof value.description === "string" ? value.description.trim() : "";
  const body = typeof value.body === "string" ? value.body.trim() : "";
  const tags = Array.isArray(value.tags)
    ? [...new Set(value.tags.filter(tag => typeof tag === "string").map(tag => tag.trim()).filter(Boolean))]
    : [];

  if (title.length < 8 || title.length > 80) throw new Error(`title length ${title.length} is outside 8..80`);
  if (description.length < 15 || description.length > 50) {
    throw new Error(`description length ${description.length} is outside 15..50`);
  }
  if (body.length < 500 || body.length > 1800) throw new Error(`body length ${body.length} is outside 500..1800`);
  if (tags.length < 3 || tags.length > 5) throw new Error(`tag count ${tags.length} is outside 3..5`);
  if (body.includes("http://") || body.includes("https://") || body.includes("![")) {
    throw new Error("body contains an external link or image");
  }
  const sourceModel = typeof value.model === "string" && value.model.trim() ? value.model.trim() : model;
  const generatedAt = typeof value.generatedAt === "string" && value.generatedAt.trim()
    ? value.generatedAt.trim()
    : new Date().toISOString();
  return { index, title, description, tags, body, model: sourceModel, generatedAt };
};

const readValidArticle = async index => {
  try {
    const parsed = JSON.parse(await readFile(fileFor(index), "utf8"));
    return normalizeArticle(parsed, index);
  } catch (error) {
    if (error?.code === "ENOENT") return null;
    return null;
  }
};

const writeArticle = async article => {
  const target = fileFor(article.index);
  const temporary = `${target}.${process.pid}.tmp`;
  await writeFile(temporary, `${JSON.stringify(article, null, 2)}\n`, { mode: 0o600 });
  await rename(temporary, target);
};

const promptFor = index => {
  const brief = briefFor(index);
  return `请生成第 ${index}/${postCount} 篇中文知识社区文章。

选题领域：${brief.category}
内容形式：${brief.format}
目标读者：${brief.audience}

只输出一个 JSON 对象，字段严格如下：
{
  "title": "自然、具体且不使用编号的标题，8到30个汉字",
  "description": "15到50个汉字的摘要",
  "tags": ["3到5个简短标签"],
  "body": "Markdown 正文"
}

正文要求：
1. 600到1000个汉字，包含 3 到 5 个二级标题。
2. 给出具体方法、例子或操作步骤，避免空泛口号。
3. 内容必须原创、独立成篇，不提及批量生成、测试数据、AI 或提示词。
4. 不编造时效性新闻、研究数据、人物引语或来源。
5. 不涉及医疗诊断、投资建议、法律结论、政治观点和成人内容。
6. 不包含链接、图片、联系方式、广告或真实个人信息。
7. description 最多 50 个汉字，这是数据库硬限制。
8. title、description、tags 和 body 必须是有效 JSON 字符串，不要输出 Markdown 代码围栏。`;
};

const callLlm = async (index, correction = null) => {
  const messages = [
    {
      role: "system",
      content: "你是中文知识社区编辑。严格输出可解析的 JSON，文章务实、准确、自然，避免内容同质化。"
    },
    { role: "user", content: promptFor(index) }
  ];
  if (correction) {
    messages.push({
      role: "user",
      content: `上一次输出未通过格式校验：${correction}。请重新生成，并严格满足所有字数与字段限制。`
    });
  }
  const response = await fetch(`${baseUrl}/chat/completions`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      model,
      messages,
      response_format: { type: "json_object" },
      temperature: 0.9,
      max_tokens: 2600
    }),
    signal: AbortSignal.timeout(requestTimeoutMs)
  });
  const text = await response.text();
  let payload;
  try {
    payload = JSON.parse(text);
  } catch {
    throw new Error(`LLM returned non-JSON HTTP body (${response.status})`);
  }
  if (!response.ok) {
    const detail = payload?.error?.message || payload?.message || `HTTP ${response.status}`;
    const error = new Error(String(detail).slice(0, 240));
    error.status = response.status;
    error.retryAfter = Number(response.headers.get("retry-after")) || null;
    throw error;
  }
  const content = payload?.choices?.[0]?.message?.content;
  if (typeof content !== "string") throw new Error("LLM response did not contain message.content");
  let parsed;
  try {
    parsed = JSON.parse(content);
  } catch {
    throw new Error("message.content is not valid JSON");
  }
  return normalizeArticle(parsed, index);
};

const usedTitles = new Set();
let nextIndex = 1;
let completed = 0;
let reused = 0;
const failures = [];

await mkdir(outputDir, { recursive: true, mode: 0o700 });

for (let index = 1; index <= postCount; index += 1) {
  const existing = await readValidArticle(index);
  if (existing && !usedTitles.has(existing.title)) {
    usedTitles.add(existing.title);
  }
}

const generateOne = async index => {
  const existing = await readValidArticle(index);
  if (existing) {
    reused += 1;
    return existing;
  }

  let lastError;
  for (let attempt = 1; attempt <= 4; attempt += 1) {
    try {
      const correction = lastError instanceof Error ? lastError.message : null;
      const article = await callLlm(index, correction);
      if (usedTitles.has(article.title)) throw new Error(`duplicate title: ${article.title}`);
      usedTitles.add(article.title);
      await writeArticle(article);
      return article;
    } catch (error) {
      lastError = error;
      if (attempt < 4) {
        const delay = error?.retryAfter ? error.retryAfter * 1000 : 1000 * (2 ** (attempt - 1));
        await sleep(delay);
      }
    }
  }
  throw lastError;
};

const worker = async () => {
  while (true) {
    const index = nextIndex;
    nextIndex += 1;
    if (index > postCount) return;
    try {
      await generateOne(index);
      completed += 1;
      if (completed % 10 === 0 || completed === postCount) {
        console.log(`Progress: ${completed}/${postCount} (reused=${reused})`);
      }
    } catch (error) {
      failures.push({ index, message: error instanceof Error ? error.message : String(error) });
      console.error(`Article ${index} failed after retries: ${failures.at(-1).message}`);
    }
  }
};

console.log(`Generating ${postCount} articles with model=${model}, concurrency=${concurrency}`);
console.log(`Output: ${outputDir}`);
await Promise.all(Array.from({ length: concurrency }, () => worker()));

const manifest = {
  model,
  requested: postCount,
  completed,
  reused,
  failed: failures,
  updatedAt: new Date().toISOString()
};
await writeFile(path.join(outputDir, "manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o600 });
console.log(`Finished: generated=${completed}, failed=${failures.length}, reused=${reused}`);
if (failures.length > 0) process.exitCode = 1;
