package forex.services.rates.interpreters

import java.time.Duration

import forex.config.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.{ CircuitBreaker, CircuitBreakerConfig => JCircuitBreakerConfig }

object CircuitBreakerFactory {

  /** Builds a Resilience4j CircuitBreaker from application config. */
  def create(name: String, config: CircuitBreakerConfig): CircuitBreaker = {
    val jConfig = JCircuitBreakerConfig
      .custom()
      .failureRateThreshold(config.failureRateThreshold)
      .waitDurationInOpenState(Duration.ofMillis(config.waitDurationInOpenState.toMillis))
      .slidingWindowSize(config.slidingWindowSize)
      .minimumNumberOfCalls(config.minimumNumberOfCalls)
      .build()
    CircuitBreaker.of(name, jConfig)
  }
}
