# Docker Local Environment

This setup runs only the backing services in Docker. The Spring Boot backend and Vite frontend run directly on the host machine.

## Start Services

```bash
docker compose up -d
```

Services exposed to the host:

- MySQL: `localhost:3306`
- Redis: `localhost:6379`
- Kafka: `localhost:9092`
- Elasticsearch: `http://localhost:9200`
- MinIO API: `http://localhost:9000`
- MinIO Console: `http://localhost:9001`

MySQL defaults:

- Database: `zhiguang`
- User: `zhiguang`
- Password: `zhiguang123456`
- Root password: `root123456`

The first MySQL startup imports `db/schema.sql`. If you need to re-import from scratch, remove the MySQL volume:

```bash
docker compose down -v
docker compose up -d
```

## Run Backend Locally

```bash
mvn spring-boot:run
```

Optional external-service environment variables:

```bash
export SILICONFLOW_API_KEY=your_siliconflow_key
export SILICONFLOW_BASE_URL=https://api.siliconflow.com
export SILICONFLOW_CHAT_MODEL=Qwen/Qwen3-32B
export SILICONFLOW_EMBEDDING_MODEL=Qwen/Qwen3-Embedding-0.6B
export SILICONFLOW_EMBEDDING_DIMENSIONS=1024
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_PUBLIC_ENDPOINT=http://localhost:9000
export MINIO_ACCESS_KEY=minioadmin
export MINIO_SECRET_KEY=minioadmin
export MINIO_BUCKET=zhiguang
export MINIO_PUBLIC_DOMAIN=
```

AI/RAG features need `SILICONFLOW_API_KEY`. The app uses SiliconFlow through Spring AI's OpenAI-compatible client. Object upload uses the local MinIO service by default.

## Run Frontend Locally

```bash
cd zhiguang_fe
npm install
npm run dev
```

The Vite dev proxy sends `/api` requests to `http://localhost:8080`.

## Stop Services

```bash
docker compose down
```
