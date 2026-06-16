# Cassandra text storage

This project stores post bodies in `zhiguang.post_text_by_post_id` and comment bodies in `zhiguang.comment_text_by_comment_id`.

## Local startup

Start Cassandra and apply the schema from [`db/cassandra/init.cql`](/Volumes/lexar/revive/zhiguang_be/db/cassandra/init.cql):

```bash
docker compose up cassandra -d
docker compose up cassandra-init
```

The Cassandra smoke test at [`src/test/java/com/tongji/storage/text/TextStorageSmokeTest.java`](/Volumes/lexar/revive/zhiguang_be/src/test/java/com/tongji/storage/text/TextStorageSmokeTest.java) starts a temporary `cassandra:4.1` container through the local `docker` CLI and executes this same `db/cassandra/init.cql` file before running assertions, so local bootstrap and the smoke-test path share one schema source.

Check that the container is healthy:

```bash
docker ps --filter name=zhiguang-cassandra
docker logs zhiguang-cassandra --tail 50
```

## Schema verification

Verify the keyspace and tables:

```bash
docker exec zhiguang-cassandra cqlsh -e "DESCRIBE KEYSPACES;"
docker exec zhiguang-cassandra cqlsh -e "DESCRIBE TABLES IN zhiguang;"
docker exec zhiguang-cassandra cqlsh -e "DESCRIBE TABLE zhiguang.post_text_by_post_id;"
docker exec zhiguang-cassandra cqlsh -e "DESCRIBE TABLE zhiguang.comment_text_by_comment_id;"
```

Expected tables:

- `post_text_by_post_id`
- `comment_text_by_comment_id`

## Reset

To rebuild the local Cassandra data from scratch:

```bash
docker compose down cassandra cassandra-init
docker volume rm zhiguang_be_cassandra-data
docker compose up cassandra -d
docker compose up cassandra-init
```

If your Compose project name differs, confirm the volume name first:

```bash
docker volume ls | grep cassandra
```

## cqlsh examples

Open an interactive shell:

```bash
docker exec -it zhiguang-cassandra cqlsh
```

Insert and inspect a post body:

```sql
USE zhiguang;

INSERT INTO post_text_by_post_id (post_id, body, version, sha256, updated_at)
VALUES (101, 'hello post', 1, 'sha-101', toTimestamp(now()));

SELECT post_id, body, version, sha256, updated_at
FROM post_text_by_post_id
WHERE post_id = 101;
```

Insert and inspect comment bodies:

```sql
USE zhiguang;

INSERT INTO comment_text_by_comment_id (comment_id, body, version, updated_at)
VALUES (201, 'comment one', 1, toTimestamp(now()));

INSERT INTO comment_text_by_comment_id (comment_id, body, version, updated_at)
VALUES (203, 'comment three', 1, toTimestamp(now()));

SELECT comment_id, body, version, updated_at
FROM comment_text_by_comment_id
WHERE comment_id IN (201, 203);
```

Delete sample rows:

```sql
USE zhiguang;

DELETE FROM post_text_by_post_id WHERE post_id = 101;
DELETE FROM comment_text_by_comment_id WHERE comment_id IN (201, 203);
```

## Smoke test note

Run the dedicated Cassandra smoke test with:

```bash
mvn -Dtest=TextStorageSmokeTest test
```

The test requires a working local `docker` CLI because it launches a temporary Cassandra container directly. It no longer depends on Testcontainers.
