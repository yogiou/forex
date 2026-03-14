package forex.http.rates

import org.http4s.ParseFailure
import org.http4s.QueryParamDecoder
import org.http4s.dsl.impl.{ QueryParamDecoderMatcher, ValidatingQueryParamDecoderMatcher }

import forex.domain.Currency

object QueryParams {

  private[http] implicit val currencyQueryParam: QueryParamDecoder[Currency] =
    QueryParamDecoder[String].emap(s =>
      Currency.fromStringOption(s).toRight(ParseFailure("Invalid currency", s"Unsupported currency: $s"))
    )

  object FromQueryParam extends QueryParamDecoderMatcher[Currency]("from")
  object ToQueryParam extends QueryParamDecoderMatcher[Currency]("to")

  /** Validating matchers so invalid currency returns 400 with invalid_currency code (not 404 or missing_params). */
  object FromValidating extends ValidatingQueryParamDecoderMatcher[Currency]("from")
  object ToValidating extends ValidatingQueryParamDecoderMatcher[Currency]("to")
}
