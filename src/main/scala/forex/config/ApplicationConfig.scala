package forex.config

import scala.concurrent.duration.FiniteDuration

case class ApplicationConfig(
    http: HttpConfig,
    oneframe: OneFrameConfig
)

case class HttpConfig(
    host: String,
    port: Int,
    timeout: FiniteDuration
)

/** Retry settings for the One-Frame downstream. When enabled, retries on failure (Left or exception) up to maxAttempts.
  */
case class RetryConfig(
    enabled: Boolean = true,
    maxAttempts: Int = 3,
    waitBetweenAttempts: FiniteDuration = FiniteDuration(1, "second")
)

/** Resilience4j-style circuit breaker settings for the One-Frame downstream. When enabled, wraps the live client. */
case class CircuitBreakerConfig(
    enabled: Boolean = true,
    failureRateThreshold: Float = 50f,
    waitDurationInOpenState: FiniteDuration = FiniteDuration(30, "seconds"),
    slidingWindowSize: Int = 10,
    minimumNumberOfCalls: Int = 5
)

case class OneFrameConfig(
    baseUrl: String,
    token: String,
    /** When false (default), use the live OneFrame service; when true, use the in-memory dummy backend. */
    useDummy: Boolean = false,
    /** Cache TTL for rates in minutes (tune without recompiling). */
    cacheTtlMinutes: Long = 5,
    /** Retry failed One-Frame calls (Left or exception) up to maxAttempts with wait between attempts. */
    retry: RetryConfig = RetryConfig(),
    /** Circuit breaker for the One-Frame HTTP client. When enabled, opens after too many failures. */
    circuitBreaker: CircuitBreakerConfig = CircuitBreakerConfig()
)
