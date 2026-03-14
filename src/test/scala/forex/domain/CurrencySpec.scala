package forex.domain

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class CurrencySpec extends AnyFreeSpec with Matchers {

  "Currency.fromStringOption" - {
    "returns Some for all supported codes" in {
      Currency.fromStringOption("USD") shouldEqual Some(Currency.USD)
      Currency.fromStringOption("JPY") shouldEqual Some(Currency.JPY)
      Currency.fromStringOption("EUR") shouldEqual Some(Currency.EUR)
      Currency.fromStringOption("GBP") shouldEqual Some(Currency.GBP)
      Currency.fromStringOption("AUD") shouldEqual Some(Currency.AUD)
      Currency.fromStringOption("CAD") shouldEqual Some(Currency.CAD)
      Currency.fromStringOption("CHF") shouldEqual Some(Currency.CHF)
      Currency.fromStringOption("NZD") shouldEqual Some(Currency.NZD)
      Currency.fromStringOption("SGD") shouldEqual Some(Currency.SGD)
    }
    "is case insensitive" in {
      Currency.fromStringOption("usd") shouldEqual Some(Currency.USD)
      Currency.fromStringOption("Usd") shouldEqual Some(Currency.USD)
      Currency.fromStringOption("USD") shouldEqual Some(Currency.USD)
    }
    "returns None for unsupported or invalid input" in {
      Currency.fromStringOption("XXX") shouldEqual None
      Currency.fromStringOption("") shouldEqual None
      Currency.fromStringOption("US") shouldEqual None
      Currency.fromStringOption("USDD") shouldEqual None
    }
  }

  "Currency.show" - {
    "formats supported currencies" in {
      import cats.Show
      import Currency.show
      Show[Currency].show(Currency.USD) shouldEqual "USD"
      Show[Currency].show(Currency.JPY) shouldEqual "JPY"
    }
  }
}
