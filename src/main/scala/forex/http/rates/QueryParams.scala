package forex.http.rates

import org.http4s.ParseFailure
import org.http4s.QueryParamDecoder
import org.http4s.dsl.impl.ValidatingQueryParamDecoderMatcher

import forex.domain.Currency

object QueryParams {

  private[http] implicit val currencyQueryParam: QueryParamDecoder[Currency] =
    QueryParamDecoder[String].emap(s =>
      Currency.fromStringOption(s).toRight(ParseFailure("Invalid currency", s"Unsupported currency: $s"))
    )

  object FromValidating extends ValidatingQueryParamDecoderMatcher[Currency]("from")
  object ToValidating extends ValidatingQueryParamDecoderMatcher[Currency]("to")
}
