# Publish Attempt API Examples

## Accept Publish

`POST /api/v1/knowposts/{id}/publish`

Request:

```json
{
  "idempotentKey": "publish-20260616-001"
}
```

Response: `202 Accepted`

```json
{
  "publishAttemptId": "90010001"
}
```

## Query Publish Status

`GET /api/v1/knowposts/{id}/publish/status?attemptId=90010001`

Response:

```json
{
  "publishAttemptId": "90010001",
  "attemptStatus": "publishing",
  "postStatus": "publishing",
  "failedStep": null,
  "retryable": false
}
```

## Retry Failed Publish

`POST /api/v1/knowposts/{id}/publish/90010001/retry`

Response: `202 Accepted`

```json
{
  "publishAttemptId": "90010001"
}
```

Notes:

- `publishAttemptId` is stable for the original attempt and later retries.
- `idempotentKey` is required on the initial publish request.
- Clients should use the status endpoint to observe final `published` or `publish_failed` outcomes before deciding to retry.
