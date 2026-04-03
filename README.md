# Forex – local proxy for exchange rates

Local proxy for currency exchange rates (Paidy take-home exercise). Built from the [forex-mtl](https://github.com/paidy/interview/tree/master/forex-mtl) scaffold.

## Quick start

**1. Start One-Frame:**

```bash
docker run -p 8080:8080 paidyinc/one-frame
```

**2. Start the Forex proxy** (in another terminal):

```bash
./sbtw run
```

Wait for `started at http://[::]:8081/`. The app pre-warms the cache from One-Frame before binding the port, so the very first request is always served from a warm cache.

**3. Call the API:**

```bash
curl 'http://localhost:8081/rates?from=USD&to=JPY'
```

If One-Frame is not running, the app still starts but returns `502` for rate requests until One-Frame becomes reachable and the next refresh cycle completes.

---

## Architecture Design

The project follows a small layered architecture so each concern is isolated and testable:

1. **HTTP layer** (`forex.http.rates`)  
   Parses query params, validates input, and maps program errors to HTTP status codes + API error codes.
2. **Program layer** (`forex.programs.rates`)  
   Orchestrates use-cases and translates service-level errors into API-facing domain errors.
3. **Service layer** (`forex.services.rates`)  
   Exposes an algebra (`get(pair)`) and interpreters (live One-Frame client, proactive cache, retry/circuit-breaker wrappers).
4. **Infrastructure / wiring** (`forex.Module`, `forex.Main`, `forex.config`)  
   Builds dependencies, starts background refresh, and runs the server.

### Runtime flow (live mode)

```
Client GET /rates
  -> RatesHttpRoutes
  -> RatesProgram
  -> ProactiveCachingRatesService (in-memory lookup + freshness check)
  -> return cached rate OR typed error

Background (parallel):
  Module.refreshStream
    -> OneFrameLive.getAll(allPairs)
    -> atomic cache replace + lastRefreshTime update
```

### Key design choices

- **Proactive batch refresh instead of per-request downstream calls**
  - One-Frame is called once per refresh cycle for all 72 pairs.
  - Request path is O(1) map lookup with no network hop.
- **Strict freshness enforcement**
  - Requests return `503 service_unavailable` once data is older than 5 minutes.
  - During refresh lag/failure windows, stale data is not served.
- **Resilience on refresh path**
  - Retry and circuit-breaker are applied to the batch refresh operation.
  - On refresh failure, old snapshot remains available only until freshness expires.
- **Config-level safety guard**
  - `cache-ttl-minutes` is validated to `2..5`.
  - This keeps One-Frame calls under 1000/day and preserves the 5-minute freshness target.

---

## How it works

### Proactive cache

On startup, the app fetches all 72 supported currency pairs from One-Frame in **a single HTTP request** and stores them in an in-memory map. A background job then repeats this every `cache-ttl-minutes` (default: 5 minutes). Every incoming rate request is an O(1) map lookup — One-Frame is never called per-request.

```
startup:     initialRefresh  →  one HTTP call → all 72 pairs cached
serving:     GET /rates?from=X&to=Y  →  map.get(pair)  (O(1), no network)
background:  every 5 min  →  one HTTP call → cache updated atomically
```

### Why this satisfies the constraints

| Constraint | Requirement | Actual |
|---|---|---|
| One-Frame daily limit | ≤ 1,000 calls/day | 288 calls/day (24 × 60 / 5) |
| Proxy request volume | ≥ 10,000/day | Unlimited (all from cache) |
| Rate freshness | ≤ 5 minutes old | Refreshed every 5 minutes |

288 One-Frame calls/day leaves substantial headroom under the 1,000 limit regardless of how traffic is distributed across pairs.

### Startup sequence

1. `initialRefresh` — fetches all pairs from One-Frame before the server binds. If One-Frame is unreachable at boot, the cache starts empty and the periodic refresh keeps retrying.
2. HTTP server binds to `:8081`.
3. `refreshStream` — repeats the batch fetch every `cache-ttl-minutes` in the background.

---

## API

### `GET /rates?from={currency}&to={currency}`

Returns the current exchange rate between two supported currencies.

**Supported currencies:** `AUD`, `CAD`, `CHF`, `EUR`, `GBP`, `JPY`, `NZD`, `SGD`, `USD`

**Success (200):**
```json
{"from":"USD","to":"JPY","price":0.718,"timestamp":"2026-04-03T12:42:39.823Z"}
```

**Errors:**

| Status | Code | Reason |
|---|---|---|
| 400 | `missing_params` | `from` or `to` not provided |
| 400 | `invalid_currency` | Unsupported currency code |
| 400 | `same_currency` | `from` and `to` are the same |
| 503 | `service_unavailable` | Cache not yet populated or rates stale (transient) |
| 502 | `rate_lookup_failed` | One-Frame unreachable or returned an error |
| 429 | `rate_limit_exceeded` | One-Frame returned 429 or 403 |

---

## Run tests

```bash
./sbtw test
```

48 tests cover: domain validation, HTTP routes (all error cases including 503), program layer error mapping, proactive cache service, reactive cache service, retry service, circuit breaker service, One-Frame HTTP client (with mock), and a full-stack integration test using the dummy backend.

## Format code

```bash
./sbtw scalafmtAll        # format in place
./sbtw scalafmtCheckAll   # check without writing
```

---

## Config

`src/main/resources/application.conf`:

- **`http.port`** — default `8081` (avoids conflict with One-Frame on `8080`)
- **`http.timeout`** — request timeout, default `40 seconds`
- **`oneframe.base-url`** — One-Frame URL, default `http://localhost:8080`
- **`oneframe.token`** — One-Frame auth token
- **`oneframe.use-dummy`** — `true` to use an in-memory dummy backend (no One-Frame needed); default `false`
- **`oneframe.cache-ttl-minutes`** — how often to refresh all pairs from One-Frame; must be `2..5`, default `5`

---

## Use of AI

I used AI tools (Cursor and Claude) during this assignment as required to disclose. I was the solution designer, reviewer, and decision maker: I defined the architecture (proactive batch cache, startup sequencing, error handling), reviewed all generated code, and take full responsibility for the submission.

---

## Assumptions

- All 9 supported currencies and their 72 directed pairs (9 × 8) are known statically. This allows a single One-Frame request to fetch every possible pair at once.
- Rate freshness is determined by `lastRefreshTime` — when the proxy last successfully fetched from One-Frame. We deliberately do not use One-Frame's embedded `time_stamp`, because it reflects One-Frame's internal update schedule which we cannot control (the spec example shows `"2019-01-01T00:00:00.000"`). If the last successful fetch is older than 5 minutes, requests return 503 `service_unavailable` (strict compliance: stale data is never served). The `lastRefreshTime` Ref also guards initial startup: until the first successful refresh completes, all requests return 503.
- One-Frame timestamps are parsed with an `OffsetDateTime` parser that falls back to `LocalDateTime` (assuming UTC) when no timezone offset is present, since the One-Frame API may omit offset information.
- Setting `cache-ttl-minutes` below 5 (e.g. 4) can reduce brief 503 windows during refresh lag, at the cost of more One-Frame calls/day (360/day at 4 minutes vs 288/day at 5 minutes). Values outside `2..5` are rejected at startup.
- `use-dummy = false` is the default (live mode). Set `use-dummy = true` in `application.conf` to run without One-Frame for local development or testing.
