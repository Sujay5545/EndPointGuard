# EndpointGuard: Architecture, Onboarding, and Interview Guide

**Source review date:** 2026-09-28
**Audience:** project maintainer, new contributor, interviewer
**Status:** written from the repository currently in this workspace; planned product capabilities are separated from implemented behavior.

> **Historical snapshot:** This guide describes the Phase 1 implementation reviewed on 2026-09-28. Later sections contain superseded claims about webhook signatures, GitHub REST retrieval, automatic risk evaluation, PR APIs, LLM providers, and migrations. Do not treat those details as current behavior; use [ENDPOINTGUARD_PROGRESSANDREMAINING.md](../ENDPOINTGUARD_PROGRESSANDREMAINING.md) for the latest verified state and confirm behavior against source.

`EndpointGuard-Architecture-Blueprint.md` was not present in the workspace when this Phase 1 review was performed. The existing `docs/ENDPOINTGUARD_PROJECT_GUIDE.md` and current source/migrations were inspected; source code was treated as authoritative.

## How to use this guide

Read Sections 1–5 first to form the system model. Then use Sections 6–12 to run a demo and understand its contracts. The later sections cover implementation detail, operations, limitations, and interview practice.

### Progress tracker

- [x] Repository structure and runtime topology
- [x] Frontend behavior and actual API client
- [x] Backend controllers, services, repositories, entities, and config
- [x] Database migrations and foreign keys
- [x] GitHub webhook and risk scoring flows
- [x] Existing tests and known failure conditions
- [x] Practical local demo and interview notes

## 1. Project overview

### In plain language

EndpointGuard is intended to help an engineering team understand whether a Pull Request might affect important production API routes. A user registers a project, connects a repository name, and maps API routes to source-file patterns. The system collects traffic snapshots for those routes, accepts Pull Request webhook data, maps changed file paths to endpoints, and can calculate a weighted risk score for an endpoint and a Pull Request record.

The current application is a working prototype of that flow, not yet an automated GitHub review bot. PR ingestion, endpoint mapping, metric collection, and risk calculation exist, but those stages are not yet joined into an automatic end-to-end assessment. The UI cannot list stored Pull Requests or monitoring records because those read APIs do not exist.

### Technical view

The repository is a small multi-container system:

```text
Browser
  │ React Router UI, JWT held in sessionStorage
  ▼
Nginx frontend :5173 ── proxies /api and /actuator ──► Spring Boot API :8080
                                                       │
                         ┌─────────────────────────────┼──────────────────┐
                         ▼                             ▼                  ▼
                   PostgreSQL :5432          Demo API :8081       GitHub webhook
                                               ▲                       POST only
                                               │
                                   Python traffic generator
```

- **Frontend:** React 18, Vite, React Router 7, JavaScript, CSS, Lucide icons. `frontend/src/App.jsx` contains the app shell, routes, screens, and UI behavior; `frontend/src/api.js` is the fetch client.
- **Backend:** Java 21 and Spring Boot 3.4.1. It uses Spring MVC, Spring Security, Spring Data JPA/Hibernate, Bean Validation, Flyway, JJWT, Springdoc, and Actuator.
- **Database:** PostgreSQL 16 in Compose. Flyway applies `V1__baseline_schema.sql` and `V2__seed_risk_rule_weights.sql`. Backend tests use H2 with PostgreSQL compatibility mode instead.
- **Traffic source:** a separate Spring Boot demo API emits synthetic product/order/payment requests and exposes `/actuator/endpoint-stats`. A Python process sends requests to it.
- **External GitHub:** the backend accepts an HTTP webhook-shaped payload, but does not call GitHub REST/GraphQL APIs. A GitHub PAT property exists but is not consumed by source code found in this repository.
- **LLM providers:** settings for OpenAI, Anthropic, and Ollama are bound in configuration, but no source flow calls them. They are not a functioning project feature today.

## 2. Product problem and actual scope

The product idea is a useful engineering workflow: connect a code change to the endpoints and live reliability/traffic those code paths serve. The implemented prototype provides pieces of that workflow:

1. Authenticate a user and scope projects to that user.
2. Store repository names and endpoint/source mappings.
3. Poll the demo API’s in-memory traffic window once per configured interval.
4. Store metrics for registered endpoint keys that match exactly.
5. Receive PR metadata and a caller-supplied `files` array.
6. Resolve matching source patterns to endpoint links.
7. Accept a separate risk-evaluation request, calculate a score, and persist an assessment plus a monitoring row.

It does **not** currently fetch changed files from GitHub, automatically run risk evaluation after a webhook, publish a GitHub status/check/review comment, compare a later post-merge window, or expose PR/risk/monitoring/audit read APIs. The frontend says so rather than inventing records.

## 3. Repository map

```text
endpointguard/                         Workspace root
├── docker-compose.yml                 Main local stack definition
├── frontend/                           React + Vite source and Nginx image
│   ├── src/App.jsx                     Shell, pages, forms, charts, routing
│   ├── src/api.js                      Fetch/JWT API wrapper
│   ├── src/main.jsx                    React entry point and CSS/font imports
│   ├── src/styles.css                  Design system and responsive layout
│   ├── Dockerfile                      Vite build + Nginx runtime
│   └── nginx.conf                      SPA fallback and API reverse proxy
├── endpointguard/                      Main Java/Spring Boot backend
│   ├── src/main/java/com/endpointguard/
│   │   ├── auth/                       Users, login/register, JWT filter/config
│   │   ├── project/                    Owner-scoped project APIs
│   │   ├── repository/                 Repository metadata linking
│   │   ├── endpoint/                   API route/source pattern registration
│   │   ├── metrics/                    Polling, persistence, queries, DTOs
│   │   ├── pullrequest/                Webhook controller, PR/file entities
│   │   ├── praffected/                 PR-to-endpoint association
│   │   ├── risk/                       Weights, scoring, assessment/monitor rows
│   │   └── common/                     Properties, CORS, exception handling
│   ├── src/main/resources/db/migration/ Flyway DDL and weight seed
│   ├── src/test/java/com/endpointguard/ H2 + MockMvc integration tests
│   ├── pom.xml                         Backend dependencies/build
│   └── Dockerfile                      Maven Wrapper Java build + JRE runtime
├── demo-api/                           Separate synthetic Spring Boot API
│   ├── controller/                     Products, orders, payments routes
│   ├── metrics/                        Interceptor and in-memory registry
│   ├── actuator/                       Custom endpoint-stats read operation
│   └── Dockerfile                      Maven builder and Java runtime
├── traffic-generator/                  Python requests loop and Dockerfile
├── .github/modernize/                  Modernization workflow artifacts, not runtime
└── .idea/                              IDE metadata, not runtime
```

`frontend/dist/` and each module’s `target/` are generated build output, not primary source. The root Compose file is the one used to run the full system. `endpointguard/compose.yaml` is a separate Spring Boot development-service definition for a pgvector PostgreSQL container (`mydatabase`); it is not included by root `docker compose up` and the application source does not use a vector store. `endpointguard/HELP.md` is Spring’s generated getting-started boilerplate, not project-specific architecture documentation.

## 4. Complete workflow from source

### A. Create an account and project

```text
Login/Register form
  → frontend/src/api.js: api.register/api.login
  → POST /api/auth/register or /api/auth/login
  → AuthController
  → AuthService
  → UserRepository + BCrypt password encoder
  → JwtService signs token
  → frontend stores token in sessionStorage
```

The frontend then requests `GET /api/projects` with a bearer token. `ProjectController` passes the authenticated email to `ProjectService`, which queries projects owned by that email.

### B. Link a repository and register an endpoint

```text
Projects form → POST /api/projects/{projectId}/repositories
  → GithubRepositoryController → GithubRepositoryService
  → ProjectService.getOwned checks project owner → GithubRepositoryRepository.save

Endpoint form → POST /api/repositories/{repositoryId}/endpoints
  → EndpointController → EndpointService.register
  → ownedRepository checks repository's project owner
  → EndpointRepository.save + EndpointMappingRepository.save(s)
```

The “GitHub repository” is a validated `owner/name` string. The backend does not check GitHub to confirm it exists or that the user can access it.

### C. Collect traffic

```text
Traffic generator → demo-api /api/{products,orders,payments}
  → MetricsInterceptor records method, normalized URI, status, elapsed ms
  → EndpointStatsRegistry updates in-memory counters
  → MetricsPollerService @Scheduled GETs demo-api /actuator/endpoint-stats
  → matches METHOD:/path exactly against registered Endpoint records
  → TrafficMetricRepository.save per matched endpoint/minute
```

The demo API’s `/actuator/endpoint-stats` calls `getAndResetSnapshot`, so reading the snapshot resets its process-local counters. EndpointGuard stores the returned snapshot under the current minute bucket. Matching is exact on HTTP method and route path; a traffic key that has no corresponding registered endpoint is discarded.

### D. Receive a Pull Request webhook

```text
GitHub (or a local test caller) → POST /api/webhooks/github
  → GithubWebhookController (public route)
  → GithubWebhookService.processWebhook (@Transactional)
  → find stored repository by full name
  → insert/update PullRequest by repository + GitHub PR number
  → replace its PrChangedFile rows from payload.files
  → PrAffectedEndpointService maps each file path to source patterns
  → insert pr_affected_endpoints associations
  → HTTP 202
```

The controller does not authenticate the caller. The service does not check `X-Hub-Signature-256`. It consumes `payload.files`, although the standard GitHub `pull_request` webhook payload does not include the full changed-file list in that shape. No GitHub API request retrieves it.

### E. Evaluate risk (separate manual call)

```text
Endpoint detail form → POST /api/endpoints/{endpointId}/risk/evaluate
  → RiskController → RiskEvaluationService.evaluate
  → verify endpoint exists
  → calculate six normalized factors and weighted sum
  → save RiskAssessment and PostMergeMonitoring
  → return RiskEvaluationResponse → render result/factor bars
```

This is **not** automatically called by webhook processing. The service does not look up the PR’s affected endpoint set, does not derive its inputs from stored metrics, and does not join the given PR to the endpoint. The UI sends available endpoint metric totals plus an internal PR record ID, `diffSize: 0`, equal recent/baseline error and latency, and a user-entered incident count. This is a demo/manual evaluation, not the intended automatic PR assessment pipeline.

## 5. Frontend architecture

### Files and state

- `frontend/src/main.jsx`: imports React, BrowserRouter, locally packaged DM Sans/IBM Plex Mono fonts, and `styles.css`; mounts `<App />`.
- `frontend/src/App.jsx`: one-file composition of login, shell, dashboard, projects/repositories, endpoint catalog, endpoint detail, risk result, and capability notice components. React state is local `useState`; async loading uses `useEffect`; filtered lists/chart rollups use `useMemo`. There is no Redux or server-state library.
- `frontend/src/api.js`: reads/writes a JWT under `sessionStorage['endpointguard.jwt']`, adds the bearer header, parses JSON, turns network errors into user-facing messages, and centralizes API functions.
- `frontend/vite.config.js`: dev proxy to `localhost:8080`; preview proxy to `backend:8080`.
- `frontend/nginx.conf`: static SPA fallback plus `/api/` and `/actuator/` proxy to `backend:8080` in Docker.

### Routes

Implemented and linked in the sidebar: `/` overview, `/projects`, `/endpoints`, `/endpoints/:endpointId`, and `/login` for unauthenticated users. The code also has explicit limitation-notice routes for `/pull-requests`, `/monitoring`, `/risk-rules`, and `/audit-logs`, but they are not in navigation because the backend cannot populate them.

### Authentication and errors

The frontend stores the JWT in tab-scoped `sessionStorage`, attaches it to API calls, removes it on explicit sign-out, and listens for a custom unauthorized event. The API wrapper dispatches that event only for HTTP 401; the current Spring Security behavior may return 403 for unauthenticated requests, so that status is not handled by this redirect branch. This should be aligned before production use.

Data screens show loading skeletons, empty states, and generic error panels. API error responses are reduced to `message`, `error`, or a generic status. No frontend test framework is declared in `package.json`; browser verification has been manual.

## 6. Exact UI fields and what they mean

| Field | What to enter | Required/validation | Sent to / stored as |
|---|---|---|---|
| Work email | A valid email address | Required; backend `@Email` | `/api/auth/register` or `/api/auth/login`; user email |
| Password | Account password; at least 8 characters for registration | Required; backend registration `@Size(min=8)` | Auth request; stored as BCrypt hash, never plaintext |
| Project name | Short label such as `Payments API` | Required, nonblank, max 255 | `POST /api/projects`; `projects.name` |
| GitHub repository | `owner/name`, e.g. `acme/orders-api` | Required; regex disallows slash inside either segment and whitespace | `githubRepoFullName`; no GitHub API validation |
| Webhook secret reference | A reference/label such as `GITHUB_WEBHOOK_SECRET` | Required, max 255; not used to verify webhook signatures | `repositories.webhook_secret_ref`; despite the name it is not the secret itself or a functional verifier |
| HTTP method | GET/POST/PUT/PATCH/DELETE in the UI | Required, max 10 characters; backend uppercases it | Endpoint `method` |
| Path pattern | Exact registered path such as `/api/orders` | Required, max 500; should match demo endpoint key for metrics | Endpoint `path_pattern` |
| Criticality | LOW/MEDIUM/HIGH/CRITICAL | Optional in API; UI defaults MEDIUM | Endpoint metadata; not the computed risk tier |
| Source patterns | File/class path glob, one per line, e.g. `**/OrderController.java` | Required; nonempty list; each nonblank, max 500 | `endpoint_mappings.source_pattern`; used to resolve file paths |
| Time range | 1H, 6H, 24H, or 7D | UI query selection | `from`/`to` query parameters of endpoint metrics |
| Internal PR record ID | Database `pull_requests.id`, **not** GitHub’s PR number or a URL | Required in form, HTML minimum 1; backend only validates non-null | `RiskEvaluationRequest.pullRequestId`; SQL FKs require an existing PR in PostgreSQL |
| Historical incidents | Nonnegative integer in the UI | UI min 0; backend DTO has no `@Min` | Risk `incidentCount`; normalized against 5 |

The risk screen does not expose all request DTO fields. It automatically sets `requestCount` from the selected endpoint’s returned metric buckets, rate-limit use from the latest bucket, error and latency recent/baseline to the same current aggregate, and `diffSize` to zero. Consequently those trend factors calculate as zero and the form cannot model a real diff/baseline yet.

## 7. API contract inventory

All routes except auth, webhook, actuator, and Swagger are protected by the Spring Security filter chain. The frontend talks through same-origin Nginx in Compose; local Vite uses its proxy.

| Method | Endpoint | Purpose / request | Response | Auth |
|---|---|---|---|---|
| POST | `/api/auth/register` | `{email,password,role?}`; role is ignored by service | `{token, tokenType}` | Public |
| POST | `/api/auth/login` | `{email,password}` | `{token, tokenType}` | Public |
| GET | `/api/projects` | Current user’s projects | `ProjectResponse[]` | Bearer JWT |
| POST | `/api/projects` | `{name}` | `ProjectResponse`, 201 | Bearer JWT |
| GET | `/api/projects/{projectId}` | Owner-scoped project | `ProjectResponse` | Bearer JWT |
| GET | `/api/projects/{projectId}/repositories` | Project repositories | `RepositoryResponse[]` | Bearer JWT + owner check |
| POST | `/api/projects/{projectId}/repositories` | `{githubRepoFullName,webhookSecretRef}` | `RepositoryResponse`, 201 | Bearer JWT + owner check |
| GET | `/api/repositories/{repositoryId}/endpoints` | Endpoints scoped to repository plus legacy project-wide endpoints | `EndpointResponse[]` | Bearer JWT + project owner check |
| POST | `/api/repositories/{repositoryId}/endpoints` | `{method,pathPattern,criticality?,sourcePatterns[]}`; supported methods GET/POST/PUT/PATCH/DELETE, path starts `/` | `EndpointResponse`, 201 | Bearer JWT + project owner check |
| GET | `/api/repositories/{repositoryId}/endpoints/resolve?filePath=...` | Resolve source file to endpoints | `EndpointResponse[]` | Bearer JWT + project owner check |
| GET | `/api/endpoints/{endpointId}/metrics?from=...&to=...` | Metrics; default window is last 24h | `TrafficMetricResponse[]` | Bearer JWT + endpoint project owner check |
| POST | `/api/endpoints/{endpointId}/risk/evaluate` | `RiskEvaluationRequest` with internal PR ID and bounded numeric inputs | score/tier/factor map/timestamp | Bearer JWT + endpoint owner, PR-in-project, and PR-endpoint association checks |
| POST | `/api/webhooks/github` | GitHub-shaped JSON body; reads `X-GitHub-Event`, optional delivery ID | 202 Accepted | Public; no signature check |
| GET | `/actuator/health` | Health check | Actuator health object | Public |
| GET | `/swagger-ui/index.html` | Springdoc UI | HTML | Public |

Not present in the controller source: PR list/detail/file/risk-read APIs, monitoring-read APIs, risk-rule read/update APIs, audit-log APIs, webhook event APIs, repository deletion, endpoint deletion, reviewer actions, or GitHub status/comment endpoints.

### What the DTO/controller does

- `@Valid` plus Jakarta validation is used for login/register, project, repository, and endpoint request DTOs.
- `RegisterEndpointRequest` validates supported HTTP methods, requires a slash-prefixed path, and requires nonempty nonblank source patterns.
- `RiskEvaluationRequest` validates a positive PR ID, nonnegative counts/latencies, utilization in 0–100, and error rates in 0–1. Service logic additionally checks PR ownership/project and the PR-to-endpoint association.
- `GithubWebhookController` forwards the raw body and headers without DTO validation. The service parses Jackson `JsonNode` manually.
- Endpoint metrics and risk controllers pass authenticated email to owner-scoped service/repository checks. The public webhook is not user-authenticated; its authenticity remains a known gap until Phase 2.

## 8. Backend layers and key classes

### Authentication

- `AuthController`: maps `/api/auth/register` and `/login` to `AuthService`.
- `AuthService`: checks duplicate email, BCrypt-encodes password, saves `User`, loads `UserDetails`, signs JWT. Login delegates credential verification to `AuthenticationManager`.
- `JwtService`: Base64-decodes configured secret, signs HS JWT with subject/email, issued time, expiry; validates username and expiry.
- `JwtAuthenticationFilter`: once per request, reads `Authorization: Bearer ...`, parses subject, loads user, validates token, places an authentication in Spring’s `SecurityContext`. It catches all token exceptions, logs debug, and continues unauthenticated; protected access is rejected later.
- `UserDetailsServiceImpl`: reads by email and converts stored role to `ROLE_MEMBER` or `ROLE_ADMIN` authorities.
- `SecurityConfig`: stateless session, CSRF disabled, CORS enabled, public auth/webhook/actuator/Swagger, every other route authenticated, BCrypt provider.

There is no role-based authorization rule in the observed controllers. `@EnableMethodSecurity` is present, but no `@PreAuthorize` usage was found. Ownership is mostly implemented by passing authenticated email into service methods.

### Project/repository/endpoint

- Controllers are thin REST adapters; services enforce data ownership and create entities; Spring Data repositories provide queries.
- `ProjectService.getOwned(projectId,email)` uses `findByIdAndOwnerEmail`.
- `GithubRepositoryService` verifies project ownership, uses `existsByGithubRepoFullName` for duplicate detection, and saves metadata.
- `EndpointService.register` uppercases method, chooses MEDIUM if criticality is null, stores both `projectId` and `repositoryId` plus source mappings in one transaction.
- `EndpointService.list` and `resolve` return endpoints for that repository plus legacy project-wide endpoints whose `repositoryId` is null.
- `EndpointService.resolve` and webhook mapping share `EndpointSourcePatternMatcher`, backed by `AntPathMatcher` after slash normalization.
- `EndpointService.mappingsFor` queries mappings by endpoint ID. Listing multiple endpoints can still issue repeated mapping queries.

### Metrics

- `MetricsPollerService.pollDemoApiMetrics()` is scheduled with `app.metrics.poll-interval-ms` (60s default). It GETs demo Actuator endpoint stats, ignores null/malformed/zero-request entries, splits `METHOD:/path`, queries every endpoint with that method/path, and saves a row for each matching repository endpoint in a transaction.
- A unique database key `(endpoint_id,bucket_start)` makes a minute bucket unique. Duplicate `DataIntegrityViolationException` is caught and logged as safe-to-ignore; transaction semantics around catching a persistence exception should be tested on PostgreSQL.
- `MetricsQueryService` checks endpoint existence and queries buckets or aggregate SQL. The controller exposes only the bucket list; aggregation methods are not exposed by any controller.
- `MetricsAggregationService` calculates recent and baseline aggregations but has no injection/use in `RiskEvaluationService` or a controller in this repository.

### Webhook and affected endpoints

- `GithubWebhookService.processWebhook` is synchronous and `@Transactional`. Unknown event type, missing repository/PR nodes, blank full name, or nonpositive GitHub PR number returns without processing. Repository lookup uses a case-insensitive repository query.
- PR identity is the pair `(repository_id, github_pr_number)`. Existing changed-file rows are deleted, then replaced from the incoming `files` array. PR metadata/status is overwritten from the latest body.
- `PrAffectedEndpointService` limits endpoints to the PR repository’s project and that repository’s endpoints plus legacy project-wide endpoints, then uses the shared `AntPathMatcher` source-pattern matcher.
- Before inserting newly matched endpoint associations, it deletes all previous links for the PR. If updated files no longer match, stale links are removed.
- A read helper `findAffectedEndpointsForPullRequest` exists in the service but no HTTP endpoint calls it.

### Risk

- `RiskEvaluationService.evaluate(endpointId, ownerEmail, request)` verifies endpoint ownership, loads a PR in the same project owned by the user, verifies the PR-to-endpoint association, creates normalized factor values, loads active weights, sums weighted values, maps score to tier, and saves `RiskAssessment` plus a `PostMergeMonitoring` row in one transaction.
- The service still does not derive risk inputs from `TrafficMetricRepository`, `MetricsAggregationService`, changed files, or incident records. The request supplies numeric context; only PR identity, endpoint ownership, and the affected-endpoint link are verified server-side.
- `factorBreakdown` stores normalized factors (0–1), not weight or weighted contribution. Response returns the same normalized factors and calculated score.

## 9. Database architecture

PostgreSQL is the production/local Compose DB. JPA uses `ddl-auto=validate`; Flyway owns schema changes. `V1` creates tables and indexes, `V2` seeds six active risk weights, and `V3` adds endpoint-to-repository scope and a weight-range constraint.

| Table | Purpose and relationships |
|---|---|
| `users` | Email unique; BCrypt password hash; role enum text; timestamps |
| `projects` | Belongs to owner `users.id`; user delete cascades |
| `repositories` | Belongs to project; unique GitHub full name; secret-reference string |
| `endpoints` | Belongs to a project and, for newly registered routes, a repository; method/path/criticality. `repository_id` remains nullable for legacy project-wide endpoints |
| `endpoint_mappings` | Many mappings per endpoint; source pattern index |
| `traffic_metrics` | Time-series endpoint buckets; unique endpoint+bucket; request/error/latency/rate-limit fields |
| `pull_requests` | Repository + GitHub number unique; title, author, status, dates, head SHA |
| `pr_changed_files` | Pull request child; filename/additions/deletions |
| `pr_affected_endpoints` | Composite PK linking PR and endpoint |
| `risk_rule_weights` | Unique factor name, numeric weight, active flag, description |
| `risk_assessments` | FK PR ID, score/tier/time, JSONB factor breakdown |
| `webhook_events` | Delivery unique key, raw JSONB/status/audit fields; **no entity/service uses it** |
| `post_merge_monitoring` | FK PR and endpoint, windows, baseline/observed fields, verdict |
| `audit_logs` | Entity/action/details JSONB and indexes; **no entity/service/API uses it** |

Schema cascades PR children/associations when a parent is deleted. Most write paths use service `@Transactional`; read service methods often use `readOnly=true`. `PullRequest` has an eager repository relation to avoid accessing a detached repository proxy in webhook-related paths; other relations are mainly lazy.

V3 backfills `repository_id` only for projects with exactly one repository; endpoints in projects with multiple repositories remain project-wide (`NULL`) to avoid assigning an arbitrary repository. Its composite foreign key `(repository_id, project_id)` prevents pairing an endpoint with a repository from a different project, and cascades endpoint deletion when that repository is deleted at the database level. V1 cascades endpoint deletion to mappings, metrics, and affected-endpoint links. The application currently has no repository/endpoint DELETE API, so those database cascades are not exposed through the UI/API; explicit delete endpoints and confirmation UX belong to the later repository/endpoint UX phase. The seeded endpoint table index remains project/path; V3 adds repository/path.

### Production/test mismatch

Test properties use H2 `MODE=PostgreSQL`, `create-drop`, and disable Flyway. JPA models `RiskAssessment.pullRequestId` as a scalar column, not an entity relation; therefore H2 schema generation does not reproduce the Flyway foreign key from `risk_assessments.pull_request_id` to `pull_requests.id`. Phase 1 test helpers now create a real PR and affected-endpoint row for risk tests, but a PostgreSQL integration test is still needed to verify migration and FK behavior.

## 10. Security, privacy, and GitHub integration

### JWT request path

1. Register/login is public.
2. Registration stores BCrypt hash; service forces MEMBER regardless of request `role`.
3. JWT subject is email; token is signed with a Base64-configured HMAC key and expires after 86,400,000 ms by default.
4. Browser stores token in sessionStorage and sends bearer header.
5. Filter verifies signature/expiry and sets `SecurityContext`.
6. Spring Security permits public matcher routes; other routes require authenticated context.

CSRF is disabled because the backend is stateless/bearer-token based. CORS origins are configurable, allowed headers are all, and allowed methods are GET/POST/PUT/DELETE/OPTIONS. Do not commit real JWT/GitHub/LLM secrets. The Compose default JWT secret is a known local-dev value and must be overridden in any deployed environment.

### GitHub integration and webhook processing

- Repository linking validates and canonicalizes the repository through the configured GitHub REST client. A PAT is supplied with `GITHUB_PAT`; API base URL is configurable.
- The webhook verifies `X-Hub-Signature-256` against the environment-managed `GITHUB_WEBHOOK_SECRET`; `webhookSecretRef` is a UI/reference label, not secret storage.
- Delivery IDs are claimed in `webhook_events` for idempotency. PR metadata and changed files are fetched/persisted; file listing handles pagination and retries/rate limits.
- Actionable open-PR events resolve endpoint mappings, derive risk from persisted diff/metric windows, and queue advisory review after commit. GitHub comments/check publication is not implemented.
- A live user-owned private-repository lifecycle still requires a configured PAT and matching GitHub webhook secret; local signed-webhook coverage is separate from that credentialed check.

## 11. Risk engine: formula, inputs, example

Default weights sum to 1.0; configured weights for recognized active factors are normalized before scoring:

| Factor | Weight | Normalized value |
|---|---:|---|
| TRAFFIC_VOLUME | 0.20 | `min(requestCount / configuredRequestThreshold, 1)`; null/≤0 → 0 |
| RATE_LIMIT_UTILIZATION | 0.20 | clamp percentage to 0–100, then divide by 100 |
| ERROR_RATE_TREND | 0.25 | if inputs present and recent > baseline: `min((recent-baseline)/max(baseline,0.01),1)`; otherwise 0 |
| LATENCY_TREND | 0.15 | if inputs present and recent > baseline: `min((recent-baseline)/max(baseline,1),1)`; otherwise 0 |
| DIFF_SIZE | 0.10 | `min(diffSize / configuredDiffSizeThreshold,1)`; null/≤0 → 0 |
| HISTORICAL_INCIDENTS | 0.10 | `min(incidentCount / configuredIncidentThreshold,1)`; null/≤0 → 0 |

Formula:

```text
score = Σ(normalizedFactor[name] × normalizedActiveWeight[name])
```

Unknown, invalid, or non-positive weights are ignored. If no valid recognized active weights remain, the default weights are used. Missing traffic samples and incident history are recorded in data-quality notes; insufficient metric samples produce `INSUFFICIENT_DATA` rather than a LOW result.

Tier code is:

```text
highThreshold   = configuredHigh // default 0.8
mediumThreshold = configuredMedium // default 0.6
score ≥ high → HIGH
score ≥ medium → MEDIUM
otherwise → LOW
```

Endpoint *criticality* is not an input to the scoring formula. Although its enum has CRITICAL, computed risk tiers do not produce CRITICAL. Current/baseline metric windows and diff size are derived server-side. Historical incident records are not available yet, so the factor is neutral and a data-quality note explains the missing history; callers cannot submit replacement numeric factors.

A “review decision” is not made by code: there is no reviewer/escalation action. `PostMergeMonitoring.verdict` is set to the risk tier at evaluation time, not calculated from a later monitoring comparison.

## 12. End-to-end practical demo

### Run services

From workspace root:

```powershell
docker compose up -d --build
docker compose ps
```

Open `http://localhost:5173`, create account, create a project (`Demo orders`), link `demo/orders-api` with a label for webhook secret reference, then register:

- GET `/api/orders` → `**/OrderController.java`, HIGH
- POST `/api/orders` → `**/OrderController.java`, HIGH

The Python container begins traffic automatically. It weights `/api/orders` GET 20 and POST 20 of 100 scenario weight. The demo API itself uses in-memory mock data, sleeps to simulate latency, and may return occasional errors.

### Wait for persisted metrics

The poller runs every 60 seconds by default. You can manually generate more traffic:

```powershell
1..10 | ForEach-Object { Invoke-RestMethod http://localhost:8081/api/orders | Out-Null }
```

Browse the endpoint detail in the UI. If it is still empty, wait for the next poll and confirm exact method/path match. Stored metrics belong to registered endpoints only.

### Simulate a PR webhook locally

Change the name below to exactly match the linked repository full name:

```powershell
$payload = @'
{
  "action": "opened",
  "repository": { "full_name": "demo/orders-api" },
  "pull_request": {
    "number": 501,
    "title": "Adjust order creation",
    "user": { "login": "local-demo" },
    "state": "open",
    "created_at": "2026-09-27T10:00:00Z",
    "head": { "sha": "local-demo-sha" }
  },
  "files": [
    { "filename": "src/main/java/com/acme/OrderController.java", "additions": 40, "deletions": 5 }
  ]
}
'@
Invoke-WebRequest -Uri http://localhost:8080/api/webhooks/github -Method Post `
  -Headers @{ 'X-GitHub-Event'='pull_request'; 'X-GitHub-Delivery'=[guid]::NewGuid().ToString() } `
  -ContentType 'application/json' -Body $payload
```

Expected response: 202 Accepted. To inspect database mapping:

```powershell
docker compose exec -T postgres psql -U endpointguard -d endpointguard -c "SELECT pr.id AS internal_pr_id, pr.github_pr_number, e.id AS endpoint_id, e.method, e.path_pattern FROM pull_requests pr JOIN pr_affected_endpoints a ON a.pull_request_id=pr.id JOIN endpoints e ON e.id=a.endpoint_id WHERE pr.github_pr_number=501;"
```

Use the returned **internal_pr_id** (not 501) in endpoint detail’s risk form. Important: the browser form requires metrics and supplies same recent/baseline error and latency, so those two trend factors are zero; it sets diff size to zero. For a meaningful full-factor scoring demo, use Swagger or PowerShell and provide all factors, while using an existing internal PR database ID.

Example direct risk request:

```powershell
$body = @{
  pullRequestId = 1       # Replace with internal_pr_id returned above
  diffSize = 1500
  incidentCount = 4
  requestCount = 2000
  rateLimitUtilization = 80.0
  recentErrorRate = 0.22
  baselineErrorRate = 0.05
  recentLatencyMs = 600.0
  baselineLatencyMs = 220.0
} | ConvertTo-Json
Invoke-RestMethod -Uri 'http://localhost:8080/api/endpoints/1/risk/evaluate' `
  -Method Post -Headers @{ Authorization = 'Bearer <JWT>' } `
  -ContentType 'application/json' -Body $body
```

Replace endpoint ID and token with values from the account/session. In the UI, the network client attaches JWT automatically.

## 13. Pull Request ID: GitHub number versus internal ID

A GitHub PR number is a repository-scoped number such as `501`. The database primary key is `pull_requests.id`, a generated internal `Long`. The table has a unique constraint on `(repository_id, github_pr_number)` because number 501 can exist in many repositories.

The webhook stores both. The risk DTO field is named `pullRequestId`, and the database migration defines it as a foreign key to `pull_requests.id`; the risk screen label correctly asks for “Internal PR record ID.” Do not paste a GitHub URL or 501 unless the internal key also happens to equal it. The mapping is currently obtained with SQL because there is no PR detail/list API.

- PR not in the database: PostgreSQL FK rejects risk/monitor row; current generic exception handler returns 500, not a useful “PR not found” response. H2 test profile does not enforce that FK.
- PR exists but unrelated to endpoint: risk service does not check relationship; it will calculate/persist for any existing endpoint + valid PR key. The webhook may produce no affected link, but risk evaluation does not consult that mapping.
- Private repo: no GitHub API call occurs. A locally supplied webhook payload still works. Real GitHub delivery requires endpoint reachability; this code has no signature verification.
- GitHub API outage, PR file retrieval, API rate-limit: not applicable to current implementation because it does not call GitHub API. Polling the local demo API does handle exceptions by warning and continuing.

## 14. Special cases and actual behavior

| Case | Observed behavior / limitation |
|---|---|
| Non-`pull_request` webhook event | Service returns; controller still responds 202 |
| Missing repository or PR node, blank full name, invalid number | Returns without work and 202 |
| Unknown repository name | Throws not-found inside webhook service; catch wraps it in `IllegalStateException`, so global handler generally returns generic 500 |
| Missing/empty `files` array | PR metadata is saved; changed-file list becomes empty; no endpoint links created |
| No source pattern match | No affected endpoint row; no risk evaluation automatically triggered |
| PR file list changes on update | Existing changed files and affected-endpoint links are replaced transactionally; stale associations are removed |
| Duplicate delivery ID | No dedupe by delivery ID; PR uniqueness reuses record and changed files are replaced. Delivery-event schema is unused |
| Duplicate repository link | `existsByGithubRepoFullName` throws Conflict; DB unique index also enforces it |
| Unsupported endpoint method / path lacking leading slash | `RegisterEndpointRequest` validation returns 400; accepted methods are GET/POST/PUT/PATCH/DELETE |
| Duplicate endpoint metric bucket | Unique index; save exception caught/logged, but PostgreSQL rollback-only behavior should be integration-tested |
| Invalid/expired JWT | Filter ignores parse/validation exception and leaves request unauthenticated; Spring Security blocks protected route (observed no-token response was 403) |
| Endpoint does not exist | Metrics and risk service throw ResourceNotFound (404 via advice) |
| Missing metric data | Metrics query returns empty list; UI shows “No metrics” state. Risk form disabled until metric rows exist |
| Demo API down | Poller catches exception, logs warning, waits for next scheduled call |
| DB unavailable | Spring app startup/migration or repository call fails; no custom DB-recovery path found |
| Unexpected exception | `GlobalExceptionHandler` logs stack and sends generic 500 ProblemDetail |
| High traffic/error/rate limit | Risk factors cap at 1. Demo payment endpoint returns 429 after its configured 100/minute limit. For `POST:/api/payments`, the demo stats registry reports request count divided by that configured capacity, capped at 100%; 429 is separately counted as a 4xx |
| Concurrent webhook writes | PR unique constraint protects duplicate logical PR; no retry after uniqueness race; delivery ID not idempotent |
| User guesses another endpoint ID | Owner-scoped endpoint lookup makes metrics/risk return not-found for other users’ endpoints; risk also checks PR project ownership and PR-to-endpoint association |

## 15. Transactions, concurrency, and consistency

- `AuthService.register`, project/repository/endpoint writes, webhook ingestion, mapping, metrics processing, and risk evaluation use `@Transactional` at service level.
- Webhook transaction groups PR metadata, file replacement, and endpoint associations: an unhandled exception can roll back the transaction, subject to repository exception behavior.
- Metric counters use `ConcurrentHashMap` + `AtomicLong`; order/payment demo IDs use `AtomicLong`.
- The payment rate-limit simulation serializes its per-minute counter method with `synchronized`; it is still a single-process demo limiter, not a distributed production rate limiter.
- Snapshot counters are reset one map at a time; the set of counters is not captured under one lock, so concurrent requests can land in different snapshots.
- Webhook delivery idempotency is not implemented. The unique PR key and changed-file replacement are partial logical repeat protection, not delivery-ID tracking. Concurrent first deliveries can race on the unique PR constraint.
- The scheduled poller runs in every backend instance if horizontally scaled. Multiple instances could poll the same demo snapshot and contend on the unique endpoint/minute key.
- Composite PK on PR/endpoint prevents duplicate link rows; service-level “check then insert” itself can race.

## 16. Performance and scaling outlook

### Likely bottlenecks at ~100 users

For a local prototype, PostgreSQL and the single Spring process are probably adequate. Endpoint metrics now query by method/path and save to each matching endpoint, but webhook mapping still loads project endpoints then loads each endpoint’s mappings separately. Repository and endpoint lists are unpaged, and the dashboard makes one metrics request per endpoint. Metrics are polled globally every minute and only persist if endpoint keys match.

### Likely bottlenecks at ~10,000 users

- One poller per app instance and one shared demo API snapshot do not form a robust multi-tenant collector.
- Unbounded time-series retention increases `traffic_metrics` storage and query/index cost.
- Webhook path matching is O(files × endpoints-in-project × mappings) with repeated mapping queries.
- No pagination or async webhook queue; webhook processing stays within request transaction/time limit.
- No automatic GitHub API client/rate-limit policy; adding one synchronously would increase webhook latency.
- No read-side caching, queue, retention policy, monitoring scheduler, idempotency store, or tenant-scoped partitioning.

### Sensible production evolution

1. Add tenant-scoped SQL lookups and ownership checks to every endpoint/metrics/risk operation.
2. Verify signatures and persist a webhook inbox keyed by delivery ID; return quickly and enqueue work.
3. Retrieve changed files via GitHub API with bounded timeout/retry/rate-limit handling, cache repo metadata, and persist sync status.
4. Make risk input construction explicit from metrics, changed files, PR metadata, and incident history; normalize/validate weights; test against PostgreSQL.
5. Build real post-merge windows and a scheduled evaluator with minimum sample-count handling.
6. Add pagination and retention/partitioning for metrics; indexes from actual query plans.
7. Move scheduled work to one elected worker/queue and define idempotent job keys before multiple replicas.
8. Add observability around poll latency/failure, webhook delay/failure, DB pool, and risk components.

## 17. Technologies and architectural choices

| Technology | Actual use | Trade-off / interview note |
|---|---|---|
| Java 21 | Backend and demo API | Strong typing/JVM ecosystem; more ceremony than JS/Python |
| Spring Boot 3.4.1 | REST, dependency injection, configuration, scheduling, Actuator | Fast standard application framework; large runtime surface |
| Spring MVC | REST controllers and demo API | Synchronous servlet request model; webhook work currently blocks request |
| Spring Security | Stateless request authorization + Dao authentication | Flexible filter chain; owner-level checks still need consistent service enforcement |
| JJWT 0.12.6 | Signed JWT creation/validation | Stateless access token; secret rotation/refresh/revocation are not implemented |
| BCrypt | Password hash | Adaptive one-way password hash; no plaintext storage |
| Spring Data JPA/Hibernate | ORM and repository interfaces | Productive entity persistence; watch N+1, lazy loading, transaction boundaries |
| PostgreSQL 16 | Main persistent store and JSONB | Relational constraints and JSONB; production DDL is Flyway-managed |
| Flyway | V1 schema/V2 seed | Versioned database evolution; migrations should be tested against Postgres |
| H2 | Backend integration tests | Fast test DB but differs from Postgres semantics, especially FKs/JSONB |
| React 18 + Vite | SPA build/UI dev server | Simple client app; `App.jsx` is large and owns many flows |
| React Router 7 | Client routes | Declarative browser routing; no SSR |
| Nginx | Serves built SPA and proxies API | Same-origin deployment avoids browser CORS in Compose |
| Python 3.12 + requests | Synthetic traffic loop | Easy demo simulator; fixed small scenario mix and no persistent state |
| Actuator/Micrometer/Prometheus registry | Health + demo metrics endpoints | Actuator health and endpoint stats exist; no full production alerting dashboard |
| Docker Compose | Local orchestration | Easy demo environment; single host, development credentials unless overridden |
| Resilience4j | Dependency declared only | No actual retry/circuit-breaker annotation/config found |
| Springdoc/OpenAPI | Swagger UI | API documentation available at `/swagger-ui/index.html` |

This is a **modular monolith**, not a microservice system: one main backend is divided into packages. The demo API and traffic generator are separate support containers.

## 18. Configuration and how to run

### Runtime configuration

- Backend `application.properties`: default PostgreSQL URL via `DB_HOST/PORT/NAME/USER/PASSWORD`; `ddl-auto=validate`; Flyway migrations; Actuator exposure; JWT; GitHub/LLM placeholders; risk thresholds; monitoring values; polling interval/base URL; logging; CORS.
- Test profile `application-test.properties`: in-memory H2 PostgreSQL mode, create-drop, Flyway off, test JWT secret.
- Root `docker-compose.yml`: Postgres user/password/database, backend env, demo API, traffic generator `REQUESTS_PER_SECOND=5`, frontend port mapping.
- Frontend dev: Vite proxies API to localhost:8080. Compose runtime: Nginx proxies API to backend container.

### Commands

```powershell
cd C:\Users\Swanand\Downloads\endpointguard
docker compose up -d --build
docker compose ps
```

UI: `http://localhost:5173`; backend: `http://localhost:8080`; demo API: `http://localhost:8081`; Swagger: `http://localhost:8080/swagger-ui/index.html`.

```powershell
docker compose logs -f backend
docker compose logs -f demo-api
docker compose logs -f traffic-generator
docker compose stop
docker compose start
docker compose down
```

`docker compose down` retains the named Postgres volume. `docker compose down -v` deletes it. Run backend tests from `endpointguard/` with the following command:

```powershell
cd C:\Users\Swanand\Downloads\endpointguard\endpointguard
.\mvnw.cmd clean test
```

For UI build/audit:

```powershell
cd C:\Users\Swanand\Downloads\endpointguard\frontend
npm ci
npm run build
npm audit
```

For production, set a strong, random, Base64 JWT secret with `JWT_SECRET`; override the development Postgres credentials; do not expose Postgres or the unsigned webhook endpoint publicly. No `.env.example` is present in the reviewed tree.

## 19. Error handling and observability

- `GlobalExceptionHandler` returns RFC-style `ProblemDetail` for not-found (404), conflict (409), custom access denied (403), Bean Validation (400 with field map), and generic exception (500 with generic message + timestamp; logs full exception).
- Spring Security filter-chain errors occur before controller advice and use Spring’s configured defaults; test clients should distinguish 401/403 behavior.
- Frontend API wrapper masks network failures and non-JSON bodies; pages show Retry panels. It handles 401 only for token-clearing redirect.
- Backend logging: `com.endpointguard=DEBUG`, Security WARN. Poller logs failures as warning; webhook errors log delivery ID and stack trace; successful registrations log user email.
- Backend Actuator exposure: health/info/metrics; health details only when authorized, though `/actuator/**` is permitAll in SecurityConfig. Demo API exposes health/info/metrics/prometheus/endpoint-stats and health details always.
- Demo API stats are in-memory, reset when polled, and disappear on restart. No request tracing/correlation IDs or alerting system found. `X-GitHub-Delivery` is not propagated into a durable event record.

## 20. Test coverage and fresh result

The backend source has eleven JUnit 5 test classes (13 methods): Spring Boot/MockMvc integration tests using H2 `create-drop`, Spring Security test dependency, and a test profile. The demo API has two focused JUnit test classes (3 methods). Several backend tests build a small user→project→repo→endpoint path.

| Test | Main assertion |
|---|---|
| `EndpointGuardApplicationTests` | Application context loads |
| `PhaseTwoApiTests` | Register user, create/list project, link/list repo |
| `PhaseThreeApiTests` | Endpoint registration uppercases method; source path resolves, unrelated path returns empty |
| `PhaseFourMetricsApiTests` | Valid stats persist, malformed entries ignored, metrics API returns row |
| `PhaseFiveRiskApiTests` | Risk call returns HIGH and factor values |
| `PhaseSixRiskPersistenceTests` | Assessment and monitoring persistence (also manually inserts an additional monitoring row) |
| `PhaseSevenPullRequestWebhookTests` | Webhook creates PR and changed files |
| `PhaseEightAffectedEndpointTests` | Webhook maps changed controller path to an endpoint |
| `PhaseNineRiskLinkageTests` | Affected endpoint can be resolved for a PR via service |
| `PhaseTenMonitoringVerdictTests` | Current computed risk tier is persisted into monitoring verdict |
| `PhaseOneOwnershipAndValidationTests` | Other users cannot read endpoint metrics/evaluate risk; risk and endpoint request inputs reject invalid values |

**Phase 1 verification:** `mvnw.cmd clean test` passed all 13 backend test methods (0 failures/errors); the demo API clean test run passed all 3 unit-test methods (0 failures/errors). Focused regressions also passed: `PhaseOneOwnershipAndValidationTests` (3 tests), `PhaseFourMetricsApiTests` (1 test), and `PhaseNineRiskLinkageTests` (1 test). Frontend `npm run build` passed and `npm audit` reported zero vulnerabilities. `docker compose config --quiet` passed; image builds could not be verified in this shell because the Docker Desktop Linux engine pipe was unavailable.

Missing/high-value tests: PostgreSQL/Testcontainers migration + FK behavior; actual webhook signature rejection; webhook delivery deduplication; cross-project identical source path; malformed/nonstandard webhook cases; PostgreSQL risk using a real PR row; scoring boundaries and custom/partial weights; traffic-registry concurrent snapshot behavior; frontend component/API tests and mobile viewport flows.

## 21. Architectural decisions and trade-offs

- **Modular monolith:** related business domains are packages inside one Spring app. Easier transactions and local development than services; scaling/ownership boundaries are not yet independently deployable.
- **JPA + Flyway:** model entities for normal writes, SQL migrations own schema. Avoids Hibernate silently changing production schema; H2 tests cannot validate Postgres-specific details.
- **JWT stateless auth:** no server session store; easy Nginx/API deployment. Token revocation/refresh is absent; browser storage has XSS exposure compared with HttpOnly cookies.
- **Scheduled polling:** collector samples an existing demo API rather than instrumenting the business service directly. Simple but snapshots are delayed, reset-based, and single-worker assumptions exist.
- **Source pattern mapping:** maps changed file names to endpoints with configurable strings. Easy for prototype; simplistic webhook matcher and unscoped global endpoint scan create incorrect associations at scale.
- **Weighted deterministic score:** explainable normalized factors and configurable DB weights. It depends on caller-provided data, no calibration/training, no normalization checks for custom weights, and no critical tier.
- **Local Compose stack:** reproducible demo; credentials and signing default are dev-only. It is not a production deployment/security boundary.

## 22. Code smells and prioritized improvements

1. **Unsigned public webhook.** Current: `/api/webhooks/github` is permitAll and ignores `webhookSecretRef`. Anyone who can reach it can forge PR records/files. Phase 2 should verify GitHub HMAC using an environment-managed secret and enforce request limits.
2. **Webhook and risk pipeline disconnected.** Webhook ingestion maps files, while risk evaluation remains a separate request that accepts numeric context. Phase 3 should derive and validate inputs server-side, then invoke scoring for mapped endpoints.
3. **No GitHub client.** PAT/API base URL remain configuration only; webhook payload must include `files`. Phase 2 should fetch changed files and diffs with pagination and rate-limit handling.
4. **Delivery dedupe unused.** Delivery header only appears in logs; `webhook_events` is unused. Add a unique delivery inbox and retry-safe processing with signature verification.
5. **Endpoint matcher queries can be batched.** Matcher is now shared and repository/project-scoped, and stale links are replaced, but mapping reads still happen inside endpoint/file loops. Batch-fetch mappings for the project/repository when scale justifies it.
6. **Risk model semantics remain bounded.** Scores use server-derived inputs and normalized recognized weights; missing history is recorded as unavailable. CRITICAL remains endpoint criticality metadata, not a computed PR risk tier.
7. **Monitoring verdict is not a measured post-merge comparison.** A row is written during risk evaluation and verdict equals risk tier. Phase 7 should create a pending window on merge and compare real post-merge samples against baseline.
8. **No pagination/retention.** Lists are unpaged; metric history has no retention policy. Add pagination, bounded metric queries, and retention/partitioning based on observed volume.
9. **Frontend 401-only expiry handling.** Frontend clears its token on 401; Spring anonymous access may return 403. Define a consistent JWT entry point and distinguish expired authentication from authenticated-but-forbidden responses.
10. **Dormant schema/configuration.** `webhook_events` and `audit_logs` tables, GitHub/LLM config, and Resilience4j dependency have incomplete/no current runtime flow. Later phases should implement deliberately or retire safely.

## 23. What to memorize versus understand

### Memorize for a concise interview introduction

- EndpointGuard connects endpoint/source mappings, traffic metrics, PR webhook metadata, and an explainable weighted risk score.
- Stack: React/Vite; Java 21/Spring Boot; PostgreSQL/Flyway; JWT/BCrypt; Docker Compose; demo API + Python traffic generator.
- Key paths: `/api/auth`, `/api/projects`, `/api/projects/{id}/repositories`, `/api/repositories/{id}/endpoints`, `/api/endpoints/{id}/metrics`, `/api/webhooks/github`, `/api/endpoints/{id}/risk/evaluate`.
- Six weighted risk factors and HIGH/MEDIUM/LOW thresholds.
- Critical caveat: GitHub API, signature verification, automatic risk pipeline, and monitoring reads are not implemented.

### Understand, don’t memorize

- Trace a browser request through `api.js` → controller → service → repository → entity/DB → response.
- Explain where JWT is decoded and how BCrypt is used.
- Derive the scoring example by hand and explain how null/baseline values behave.
- Explain the transaction and uniqueness constraints around PR webhooks and metric buckets.
- Explain exactly what the webhook receives, what it does not fetch, and how source mapping is computed.
- Explain current tenant ownership boundaries and how you would fix them.

## 24. Interview question bank

### Basic

**Q: What does EndpointGuard do?**
Tests product understanding. **Answer:** It is a prototype for mapping source changes to API endpoints and combining endpoint traffic/reliability factors into a deterministic risk score. Current code has setup, traffic collection, webhook ingestion/mapping, and a manual risk endpoint; it does not yet automate GitHub review decisions. **Follow-up:** Which part is fully automatic today? Traffic polling and webhook-to-endpoint mapping, but not webhook-to-risk evaluation.

**Q: Why does the project have a demo API?**
Tests demo/data flow. **Answer:** It provides product/order/payment endpoints with latency/error scenarios and an Actuator snapshot, so the poller can exercise metric ingestion without a real production service. **Follow-up:** Are demo records persistent? No; demo entities/counters are in memory.

### Intermediate

**Q: How does a request authenticate?**
Tests Spring Security. **Answer:** Login authenticates email/password via `AuthenticationManager`, BCrypt verifies the stored hash, `JwtService` signs a token, and `JwtAuthenticationFilter` later parses bearer tokens into `SecurityContext`; route matchers permit auth/webhook/actuator/Swagger and require authentication elsewhere. Project/repository/endpoint management and metric/risk endpoint reads/evaluation check ownership. The public webhook still lacks signature verification. **Follow-up:** What status should an expired token return? The current filter can fall through to Spring’s anonymous authorization response; a consistent 401 entry point remains a cleanup item.

**Q: How does metrics polling work?**
Tests scheduling/ORM. **Answer:** `@EnableScheduling` activates `MetricsPollerService`; each configured interval it GETs demo `/actuator/endpoint-stats`, matches exact method/path to endpoints, rounds the bucket to a minute, and stores a unique endpoint+bucket row. **Follow-up:** What happens when demo API is down? Warning is logged; later schedule retries by polling again; there is no explicit retry policy.

**Q: What does endpoint mapping do?**
Tests data model/path matching. **Answer:** Each Endpoint has source patterns in `endpoint_mappings`; webhook file paths are compared using the shared `EndpointSourcePatternMatcher` backed by Spring `AntPathMatcher`, and matched endpoints get rows in the composite-key `pr_affected_endpoints` table. The operation scopes endpoints to the PR repository’s project and repository, then replaces prior links. **Follow-up:** What remains imperfect? Mapping loads are repeated per file/endpoint and should be batch-fetched as scale grows.

### Advanced

**Q: How do you make webhook delivery idempotent?**
Tests production readiness. **Answer:** Current code does not persist/dedupe by delivery ID even though the schema has a `webhook_events` table. It reuses a PR based on repository+number and replaces files, which is only partial logical repeat handling. I would verify signature, insert a unique delivery event, and process it with an idempotent transactional state machine. **Follow-up:** What about concurrent duplicate deliveries? Unique delivery key plus atomic claim and retry-safe processing.

**Q: Is risk automatically calculated from changed code?**
Tests whether candidate distinguishes intent from implementation. **Answer:** No. Webhook service saves PR/files and maps endpoint links. A separate risk API accepts numeric values from caller; it does not query affected endpoints or metrics. **Follow-up:** How would you connect them? Load PR, affected endpoints, metrics and baseline server-side, compute one assessment per endpoint, persist request/input provenance, then update a PR-level rollup.

**Q: What is a production scaling bottleneck?**
Tests system design. **Answer:** The global poller and demo snapshot, loop-based webhook mapping with repeated mapping reads, per-endpoint dashboard fetches, no pagination/retention, and synchronous webhook transaction are initial bottlenecks. **Follow-up:** What would you change first? Batch mapping queries and add a webhook inbox/queue with idempotency, then metrics retention and query plans.

### Deep technical

**Q: Why did a risk request with an arbitrary ID pass H2 but fail PostgreSQL?**
Tests DB semantics. **Answer:** Test profile uses H2 `create-drop` with Flyway disabled and does not reproduce the production migration exactly. V1 makes risk-assessment and monitoring PR IDs foreign keys in PostgreSQL; tests now create a real PR/affected-endpoint row, but PostgreSQL-backed migration/FK coverage is still needed.

**Q: How does the demo produce rate-limit utilization?**
Tests code reading. **Answer:** `PaymentController` has a synchronized per-process minute counter and returns HTTP 429 after the shared configured 100-request limit. `EndpointStatsRegistry` calculates payment utilization as requests in its snapshot window divided by that capacity, capped at 100%; 429 responses are separately counted as 4xx. This is demo-only, in-memory limiting, not a distributed production limiter.

**Q: What is the difference between endpoint criticality and risk tier?**
Tests domain clarity. **Answer:** Criticality is manually assigned endpoint metadata (`LOW/MEDIUM/HIGH/CRITICAL`). Risk tier is produced by score threshold code (`LOW/MEDIUM/HIGH` only). The scorer never emits CRITICAL today.

**Q: How do you avoid cross-tenant exposure?**
Tests security critique. **Answer:** Project/repository/endpoint setup uses owner-email checks. Metrics and risk evaluation resolve endpoints through `EndpointRepository.findOwnedById`; risk also loads a PR in the endpoint’s project for that owner and verifies the PR-to-endpoint association. The public webhook is the remaining trust boundary because authenticity is not yet verified.

## 25. “Why did you choose this?” answers grounded in code

- **Why Spring Boot/Java?** The repo uses Spring’s MVC, validation, security, JPA, scheduled tasks, Actuator, and Java 21 across backend/demo API. The code does not record the author’s original decision rationale; this is a reasonable explanation of the current fit, not a confirmed historical reason.
- **Why PostgreSQL?** The runtime config and Flyway schema use PostgreSQL features including JSONB and FK/index constraints. H2 is test-only.
- **Why REST?** Controllers expose explicit HTTP resources used by the SPA and webhook sender; no GraphQL/RPC layer is present.
- **Why JWT?** Code implements stateless bearer authentication. No historical design note confirms why JWT was originally selected.
- **Why webhook?** The API accepts PR event payloads to react to updates; but full changed-file API retrieval and verification are missing.
- **Why polling?** Existing demo API exposes periodic snapshots, and backend uses `@Scheduled`; it favors simplicity over immediate events. It is not an observed business decision in a design doc.
- **Why no microservices?** Core product logic lives in one Spring app. Splitting it now would add network/consistency burden without evidence of independent scale; the demo/simulator are separate support processes.
- **Why no caching/async/queue?** No such code is present. Do not claim these are deliberate optimized choices; identify them as future production opportunities.

## 26. Interview explanations

### 30 seconds

“EndpointGuard is a traffic-aware code-review risk prototype. A user links a project and repository, registers API routes with source-file patterns, and the system polls synthetic endpoint traffic. A GitHub-shaped webhook can store PR/file metadata and map changed files to endpoints. A separate risk API calculates a weighted, explainable score from traffic, trends, diff size, and incident count. The current limitations are that GitHub API verification/file retrieval and automatic PR-to-risk orchestration are not finished.”

### 2 minutes

“The problem is that code review usually sees a diff but not the production importance of the route it changes. EndpointGuard’s data model connects users and projects to repository metadata, endpoints, and source-file patterns. The React/Vite frontend authenticates with JWT and lets the user create that mapping. A separate demo API emits synthetic traffic; the main Spring Boot backend polls its Actuator snapshot every minute, matches method/path keys to registered endpoints, and stores time buckets in PostgreSQL through JPA and Flyway. A public webhook endpoint parses PR metadata and an included files array, stores PR/file rows in a transaction, and maps file paths to endpoint source patterns. Risk evaluation is deterministic: six normalized factors are multiplied by seeded weights and summed, then the score becomes LOW/MEDIUM/HIGH. It persists factor JSON and a monitoring row. Today webhook processing does not invoke risk evaluation, the UI cannot read PR/monitoring records, and the webhook is unsigned; I’d prioritize those before production.”

### 5 minutes

Use the 2-minute version, then demonstrate: create account/project/repository; register GET and POST `/api/orders` with `**/OrderController.java`; show traffic after the 60-second poll interval; POST a local test webhook with `repository.full_name`, PR number/title/author, and a `files` entry for `OrderController.java`; query `pull_requests`/`pr_affected_endpoints` in Postgres to get the internal PR ID and linked endpoint; invoke risk evaluation with realistic normalized inputs; explain the weighted factor map and threshold. Close with the system boundary: the source code accepts a webhook but does not verify HMAC or call GitHub API; tests use H2, so production Postgres FK tests are needed; the assessment UI is manual because no PR read API/automatic risk orchestration exists. This explanation is credible because it includes both the implemented flow and its gaps.

## 27. Line-level understanding: most important code

### `AuthService.register`

1. `existsByEmail` provides a friendly conflict before the database unique key is hit.
2. `passwordEncoder.encode` hashes the password using configured BCrypt.
3. `User.builder()` creates the persistent entity; code hardcodes `User.Role.MEMBER` even though the request DTO carries role.
4. `userRepository.save` writes user inside transaction.
5. `loadUserByUsername` obtains Spring Security principal shape and authority.
6. `jwtService.generateToken` signs the token; any bad secret causes exception and transaction rolls back.

### `JwtAuthenticationFilter.doFilterInternal`

1. Read Authorization header; absent/non-Bearer request passes down chain unmodified.
2. Strip `Bearer `; parse JWT subject/email through `JwtService`.
3. If no prior authentication, load user and validate signature/expiry/subject.
4. Build `UsernamePasswordAuthenticationToken` with authorities and attach request details.
5. Store it in `SecurityContextHolder`; Spring’s later authorization rules inspect it.
6. Catch any exception and continue unauthenticated; this avoids leaking parsing details but can turn expired-token failures into default 403 rather than frontend’s expected 401.

### `MetricsPollerService.processStats`

1. Load all registered endpoints once.
2. Skip null/blank stat and zero/no request counts.
3. Split `METHOD:/path`; malformed keys are ignored.
4. Match exact method/path; unmatched demo route is not persisted.
5. Build TrafficMetric, save, catch duplicate bucket.

### `GithubWebhookService.processWebhook`

1. Ignore non-PR event types.
2. Parse raw JSON, require repository and PR nodes.
3. Find local repository by case-insensitive full name.
4. Find existing PR by repository+GitHub number or build a new record.
5. Update metadata/status/dates and save.
6. Delete previous changed-file rows, parse `files`, save new rows.
7. Delegate file→endpoint mapping.
8. Entire method is transactional; broad catch logs and wraps exceptions, which obscures typed errors as generic failures.

### `RiskEvaluationService.evaluate`

1. Resolve an endpoint owned by the authenticated user; resolve a PR in that endpoint project owned by the same user; require the PR-to-endpoint association.
2. Calculate each factor with null/edge fallback and cap.
3. Load active factor weights; fallback to constants only if none exist.
4. Sum normalized value × matching weight; unrecognized/missing factor key uses 0.
5. Select LOW/MEDIUM/HIGH from the configured medium/high thresholds.
6. Serialize factor map, save risk assessment JSONB.
7. Save monitoring row with request values, estimated baseline requests = half observed requests, verdict = same risk tier.
8. Return response with raw factor values, not contributions.

## 28. Glossary

- **REST:** HTTP resource-style API using methods such as GET/POST and JSON bodies.
- **Controller:** Spring MVC class binding URL/request to Java method.
- **DTO:** Request/response data shape; separates API contract from JPA entity.
- **Entity:** Java object mapped to a database table.
- **Repository:** Spring Data interface that generates/persists database operations.
- **JWT:** Signed token carrying claims such as a subject; this project uses email as subject.
- **BCrypt:** Adaptive one-way password hash; cannot be decrypted to original password.
- **Webhook:** One service pushes an event to another HTTP endpoint instead of the receiver polling the sender.
- **Idempotency:** Reprocessing a delivery has no duplicate side effects; this is not fully implemented by delivery ID.
- **Transaction:** Group of DB statements that should commit together or roll back together.
- **Foreign key:** Constraint requiring a referenced row to exist.
- **Flyway:** Versioned SQL migration runner.
- **Metric bucket:** Aggregate values for an endpoint over a time window/minute.
- **Baseline:** Comparison window; the current risk API receives baseline values from its caller rather than computing them.
- **Criticality:** Static endpoint importance selected at registration.
- **Risk tier:** Classification derived from weighted score.
- **JSONB:** PostgreSQL binary JSON column, used for risk factor breakdown.
- **N+1 query:** Pattern where listing N records triggers extra per-record queries; mapping lookup has this risk.

## 29. Best study order

1. Read this overview and trace the diagram in Section 4.
2. Run Compose; inspect `docker compose ps` and each service log.
3. Read frontend `api.js`, then the matching page/function in `App.jsx`.
4. Follow the same URL into its controller and request DTO.
5. Follow controller → service → repository → entity → V1 migration.
6. Study JWT and ownership checks before exposing new endpoints.
7. Study metrics source: demo interceptor → stats endpoint → poller → metric repository.
8. Study webhook ingestion → changed files → affected endpoint mapping.
9. Derive risk math from `RiskEvaluationService` by hand.
10. Run one phase integration test, then `clean test`; understand H2/Postgres differences.
11. Review the known limitations and propose a prioritized production plan.
12. Practice the 30-second pitch; then answer the deep questions without reading.

## 30. PDF-ready delivery

This report is saved as Markdown at `docs/ENDPOINTGUARD_PROJECT_GUIDE.md`, which can be revised in source control and converted without rewriting. No dedicated Markdown-to-PDF executable was found in the inspected command environment. To make a PDF, open this Markdown in VS Code’s preview (`Ctrl+Shift+V`) and use the preview/browser **Print → Save as PDF** workflow, or install a trusted Markdown-to-PDF extension/tool. The guide uses standard Markdown headings, tables, and fenced code blocks for conversion.
