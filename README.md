# Forex – local proxy for exchange rates

Local proxy for currency exchange rates (Paidy take-home exercise). Built from the [forex-mtl](https://github.com/paidy/interview/tree/master/forex-mtl) scaffold.

## Quick start

**1. Start the Forex app** (from the project root):

```bash
cd /Users/jiewen/IdeaProjects/PaidyExercises
./sbtw run
```

If `sbt` is on your PATH you can use `sbt run` instead. Wait until you see `started at http://[::]:8081/`.

**2. Call the API** (in another terminal):

```bash
curl 'http://localhost:8081/rates?from=USD&to=JPY'
```

Without One-Frame running you’ll get **502** (expected). To get real rates, start One-Frame first (see below), then start the app, then run the curl again.

**3. (Optional) Start One-Frame** for live rates:

```bash
docker run -p 8080:8080 paidyinc/one-frame
```

Keep that terminal open, then start the Forex app in another terminal. `curl` above will then return **200** with a rate from One-Frame.

---

## Run the server

```bash
./sbtw run
# or: sbt run
```

The app starts a long-lived HTTP server (default: `http://0.0.0.0:8081`). It does **not** run once and exit.

## Run tests

```bash
sbt test
```

Unit tests cover domain (Currency), program layer, services (dummy, caching), and HTTP routes (validation, errors). Integration tests run the full stack (routes → program → service) with the dummy backend.

## Format code

The project uses [Scalafmt](https://scalameta.org/scalafmt/). Format all Scala sources with:

```bash
sbt scalafmtAll
```

Check formatting without writing: `sbt scalafmtCheckAll`. Config: `.scalafmt.conf`.

## CI/CD (Jenkins)

A `Jenkinsfile` is included for Jenkins Pipeline. To use it:

1. In Jenkins, create a **Pipeline** job.
2. Under **Pipeline**, choose **Pipeline script from SCM**.
3. Set SCM to **Git**, repository URL, and branch.
4. Set **Script Path** to `Jenkinsfile`.

The pipeline runs **Build** (compile), **Test** (sbt test), and **Format check** (scalafmtCheckAll). The agent must have **Java 11+** and **sbt** on the PATH. To use a Docker agent instead, change the first line to e.g. `agent { docker { image 'eclipse-temurin:17' args '-v $HOME/.sbt:/root/.sbt' } }` and install sbt in that image or use a Scala/sbt image.

**Validating the Jenkinsfile**

- **Run the same steps locally** (from the project root):
  ```bash
  sbt -batch compile
  sbt -batch test
  sbt -batch scalafmtCheckAll
  ```
  If you use the wrapper: `./sbtw -batch compile` etc. If all three succeed, the pipeline commands are valid for this project.
- **In Jenkins**: Create the Pipeline job and run **Build Now**. A successful run confirms both syntax and that the agent has the required tools (Java, sbt). To only check syntax without running, use **Pipeline** → **Pipeline Syntax** (or **Replay**) after the first load.

```bash
curl 'http://localhost:8081/rates?from=USD&to=JPY'
```

**Live One-Frame:** The app uses the real One-Frame API. Start One-Frame first so the proxy can reach it:

```bash
docker pull paidyinc/one-frame
docker run -p 8080:8080 paidyinc/one-frame
```

Then start the Forex app (`sbt run`) and call the API. Rates come from One-Frame. If One-Frame is down, the API returns an error.

**1k/day One-Frame limit vs 10k proxy requests:** Rates are cached in memory for 5 minutes per pair (O(1) lookup), so repeated requests for the same pair do not hit One-Frame. For typical traffic (many requests for the same pairs), this keeps One-Frame calls under the 1k/day limit while supporting 10k+ proxy requests per day. If traffic is spread across many distinct pairs, cache misses can exceed 1k One-Frame calls; when One-Frame returns 429 (or 403 quota), the API returns **429 Too Many Requests** with code `rate_limit_exceeded` and the downstream message so clients get a clear signal. Under high load, the circuit breaker and retry also reduce hammering and handle transient failures.

## Config

- `src/main/resources/application.conf`:
  - **http**: host, port (8081), timeout. Port 8081 avoids conflict with One-Frame on 8080.
  - **oneframe**: `base-url`, `token`, `use-dummy` (see above), **cache-ttl-minutes** (default 5), **retry**, and **circuit-breaker** for the One-Frame downstream. **retry**: `enabled` (default `true`), `max-attempts` (default 3), `wait-between-attempts` (e.g. 1 second); failed calls (Left or exception) are retried before giving up. **circuit-breaker** (Resilience4j): `enabled`, `failure-rate-threshold`, `wait-duration-in-open-state`, `sliding-window-size`, `minimum-number-of-calls`; when open, the API returns 502 with "Circuit breaker open" until the wait duration elapses (half-open). Rate-limit (429) responses are not counted as failures for the circuit breaker, so quota exhaustion alone does not open the circuit. Layering is cache → circuit breaker → retry → live, so one failed logical request (after all retries) counts as one breaker failure; if retry were outside the breaker, each retry attempt would count separately.

## Production readiness

The exercise requirement does not mention a circuit breaker or retry; adding them may be over-engineering for the stated problem. The intent is to make the solution **production ready**: fail fast when the downstream is unhealthy (circuit breaker), retry transient failures, and avoid cascading load, so the design is suitable for real deployments rather than a minimal demo.

## Use of AI

I used AI tools (Cursor and Claude) during this assignment—mainly for implementation help, tests, and documentation, with Claude used to cross-check ideas and wording. I was the solution designer, reviewer, and decision maker: I defined the approach (e.g. layering, error handling, config), reviewed and adjusted all code and docs, and take full responsibility for the submission.

## Assumptions

- Rates are cached per pair with a 5-minute TTL so that repeated requests stay within the One-Frame 1k requests/day limit while allowing 10k+ proxy requests per day.
- One API token is used for One-Frame; the proxy does not implement batching (One-Frame supports multiple `pair=` per request), so cache refills are one pair per call. Batching could be added later to reduce token usage when many pairs expire at once.

The API returns descriptive JSON errors with appropriate status codes:
- **400** – missing `from`/`to` (`missing_params`), same currency (`same_currency`), or unsupported currency (`invalid_currency`); body includes `message` and `code`
- **429** – One-Frame daily request limit exceeded (429/403 from One-Frame); code `rate_limit_exceeded`, message includes downstream response
- **502** – rate lookup failed (e.g. One-Frame down or error; `rate_lookup_failed`)
