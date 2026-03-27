# elastic-api

<sub>[Back to elastic](../README.md)</sub>

Shared contract library for the elastic module. Contains all request/response models,
enums, validation annotations, and constants consumed by both `elastic-app` and `elastic-testing`.
Has no Spring Boot runtime dependency — it is a plain library jar.

**Stack:** Java, Lombok, Jackson, Spring Validation

## Contents
1. [Models](#1-models)
2. [Enums](#2-enums)
3. [Validation](#3-validation)
4. [Constants](#4-constants)

---

## 1. Models
<sub>[Back to top](#elastic-api)</sub>

### Search

**`ContentSearchRequest`** — body for every search endpoint.

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `type` | String | `@NotBlank`, max 32, valid `ContentCategory` | Target index / category |
| `pattern` | String | `@NotBlank`, max 128 | Search term or phrase |
| `client` | String | `@NotBlank`, max 32, valid `ClientType` | Caller identifier |

**`ContentCategoryMetadata`** — one entry from `metadata.json`; holds the list of indexed
text fields for a given category.

| Field | Type | Description |
|-------|------|-------------|
| `contentCategory` | ContentCategory | Which dataset |
| `searchFields` | List\<String\> | Fields mapped as `text` in the ES index |

### Votes & Posts

| Class | Key fields | Description |
|-------|-----------|-------------|
| `VoteRequest` | userId, postId, action | User submits a vote |
| `UserVote` | postId, userId, action (upper-cased), timestamp | Stored vote document |
| `VoteResponse` | documentId, result (VoteResult) | Outcome of a vote operation |
| `PostRequest` | authorUserId | Request to create a post |
| `Post` | postId, author, createdAt, karma, upvotes, hotScore, risingScore, bestScore | Post document in `posts` index |

---

## 2. Enums
<sub>[Back to top](#elastic-api)</sub>

| Enum | Values | Notes |
|------|--------|-------|
| `ContentCategory` | `ALL`, `BOOKS`, `COMPANIES`, `MOVIES`, `MUSIC`, `PEOPLE` | `valueByName(String)` defaults to `ALL` for unknown input |
| `ClientType` | `MOBILE`, `WEB` | |
| `UserAction` | `UPVOTE`, `DOWNVOTE`, `NOVOTE` | Stored upper-cased in `UserVote` |
| `VoteResult` | `Created`, `Updated`, `NoOp`, `Deleted`, `NotFound` | Outcome returned by the vote endpoint |

---

## 3. Validation
<sub>[Back to top](#elastic-api)</sub>

**`@ValueOfEnum(enumClass)`** — Bean Validation constraint that checks whether a string
value is a valid name of the given enum, case-insensitively.

```java
@ValueOfEnum(enumClass = ContentCategory.class)
private String type;
```

Implemented by `ValueOfEnumValidator` which calls `Enum.valueOf` after upper-casing the input.

---

## 4. Constants
<sub>[Back to top](#elastic-api)</sub>

**`ApiLimits`** — centralised limits referenced by both validation annotations and service logic.

| Constant | Value | Used for |
|----------|------:|---------|
| `MAX_ES_LIMIT` | 1 000 | Maximum hits returned from Elasticsearch |
| `MAX_USER_ID_LENGTH` | 16 | `@Size` on userId fields |
| `MAX_POST_ID_LENGTH` | 16 | `@Size` on postId fields |
| `MAX_SEARCH_PATTERN_LENGTH` | 128 | `@Size` on search pattern |
| `MAX_ENUM_INPUT_LENGTH` | 32 | `@Size` on enum string fields |
| `MAX_TIMESTAMP_LENGTH` | 40 | `@Size` on ISO-8601 timestamp fields |
