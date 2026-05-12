package borg.trikeshed.cmc.pro.generated.api

/**
 * Generated from /Users/jim/work/TrikeShed/libs/cmc/pro-api/openapi/coinmarketcap-pro.openapi.yaml
 * by ./gradlew generateCmcProClientSources.
 * Repository policy: this checked-in file must be regenerated, not edited by hand.
 */

import borg.trikeshed.cmc.pro.generated.infrastructure.GeneratedRequest
import borg.trikeshed.cmc.pro.generated.infrastructure.HttpMethod

/** Generated API interface for CmcPro. */
interface CmcProApi {
    suspend fun cryptocurrencyMap(symbol: CharSequence?, slug: CharSequence?, start: Int?, limit: CharSequence?): CharSequence
    suspend fun cryptocurrencyListingsLatest(start: Int?, limit: CharSequence?, sort: CharSequence?, sort_dir: CharSequence?, convert: CharSequence?): CharSequence
    suspend fun cryptocurrencyQuotesLatestV2(id: CharSequence?, symbol: CharSequence?, slug: CharSequence?, convert: CharSequence?): CharSequence
    suspend fun cryptocurrencyQuotesHistoricalV3(id: CharSequence?, symbol: CharSequence?, time_start: CharSequence?, time_end: CharSequence?, count: Int?, interval: CharSequence?, convert: CharSequence?): CharSequence
    suspend fun cryptocurrencyOhlcvHistoricalV2(id: CharSequence?, symbol: CharSequence?, time_start: CharSequence?, time_end: CharSequence?, count: Int?, interval: CharSequence?, convert: CharSequence?): CharSequence
    suspend fun cryptocurrencyInfoV2(id: CharSequence?, symbol: CharSequence?, slug: CharSequence?): CharSequence
    suspend fun exchangeMap(slug: CharSequence?, start: Int?, limit: CharSequence?): CharSequence
    suspend fun exchangeListingsLatest(start: Int?, limit: CharSequence?, sort: CharSequence?, sort_dir: CharSequence?, convert: CharSequence?): CharSequence
    suspend fun globalMetricsQuotesLatest(convert: CharSequence?): CharSequence
    suspend fun fearAndGreedLatest(): CharSequence
    suspend fun klineCandles(symbol: CharSequence?, interval: CharSequence?, time_start: CharSequence?, time_end: CharSequence?, count: Int?, convert: CharSequence?): CharSequence
    suspend fun priceConversionV2(amount: CharSequence, id: CharSequence?, symbol: CharSequence?, time_start: CharSequence?, convert: CharSequence?): CharSequence
}

/** Default implementation — caller provides the low-level call. */
class DefaultCmcProApi(
   val call: suspend (GeneratedRequest) -> CharSequence,
) : CmcProApi {
    override suspend fun cryptocurrencyMap(symbol: CharSequence?, slug: CharSequence?, start: Int?, limit: CharSequence?): CharSequence = run {
        val queryParams = mutableMapOf<CharSequence, CharSequence>()
        if (symbol != null) queryParams["symbol"] = symbol.toString()
        if (slug != null) queryParams["slug"] = slug.toString()
        if (start != null) queryParams["start"] = start.toString()
        if (limit != null) queryParams["limit"] = limit.toString()

        call(CmcProApiContract.CryptocurrencyMap.request.copy(queryParams = queryParams)) }

    override suspend fun cryptocurrencyListingsLatest(start: Int?, limit: CharSequence?, sort: CharSequence?, sort_dir: CharSequence?, convert: CharSequence?): CharSequence = run {
        val queryParams = mutableMapOf<CharSequence, CharSequence>()
        if (start != null) queryParams["start"] = start.toString()
        if (limit != null) queryParams["limit"] = limit.toString()
        if (sort != null) queryParams["sort"] = sort.toString()
        if (sort_dir != null) queryParams["sort_dir"] = sort_dir.toString()
        if (convert != null) queryParams["convert"] = convert.toString()

        call(CmcProApiContract.CryptocurrencyListingsLatest.request.copy(queryParams = queryParams)) }

    override suspend fun cryptocurrencyQuotesLatestV2(id: CharSequence?, symbol: CharSequence?, slug: CharSequence?, convert: CharSequence?): CharSequence = run {
        val queryParams = mutableMapOf<CharSequence, CharSequence>()
        if (id != null) queryParams["id"] = id.toString()
        if (symbol != null) queryParams["symbol"] = symbol.toString()
        if (slug != null) queryParams["slug"] = slug.toString()
        if (convert != null) queryParams["convert"] = convert.toString()

        call(CmcProApiContract.CryptocurrencyQuotesLatestV2.request.copy(queryParams = queryParams)) }

    override suspend fun cryptocurrencyQuotesHistoricalV3(id: CharSequence?, symbol: CharSequence?, time_start: CharSequence?, time_end: CharSequence?, count: Int?, interval: CharSequence?, convert: CharSequence?): CharSequence = run {
        val queryParams = mutableMapOf<CharSequence, CharSequence>()
        if (id != null) queryParams["id"] = id.toString()
        if (symbol != null) queryParams["symbol"] = symbol.toString()
        if (time_start != null) queryParams["time_start"] = time_start.toString()
        if (time_end != null) queryParams["time_end"] = time_end.toString()
        if (count != null) queryParams["count"] = count.toString()
        if (interval != null) queryParams["interval"] = interval.toString()
        if (convert != null) queryParams["convert"] = convert.toString()

        call(CmcProApiContract.CryptocurrencyQuotesHistoricalV3.request.copy(queryParams = queryParams)) }

    override suspend fun cryptocurrencyOhlcvHistoricalV2(id: CharSequence?, symbol: CharSequence?, time_start: CharSequence?, time_end: CharSequence?, count: Int?, interval: CharSequence?, convert: CharSequence?): CharSequence = run {
        val queryParams = mutableMapOf<CharSequence, CharSequence>()
        if (id != null) queryParams["id"] = id.toString()
        if (symbol != null) queryParams["symbol"] = symbol.toString()
        if (time_start != null) queryParams["time_start"] = time_start.toString()
        if (time_end != null) queryParams["time_end"] = time_end.toString()
        if (count != null) queryParams["count"] = count.toString()
        if (interval != null) queryParams["interval"] = interval.toString()
        if (convert != null) queryParams["convert"] = convert.toString()

        call(CmcProApiContract.CryptocurrencyOhlcvHistoricalV2.request.copy(queryParams = queryParams)) }

    override suspend fun cryptocurrencyInfoV2(id: CharSequence?, symbol: CharSequence?, slug: CharSequence?): CharSequence = run {
        val queryParams = mutableMapOf<CharSequence, CharSequence>()
        if (id != null) queryParams["id"] = id.toString()
        if (symbol != null) queryParams["symbol"] = symbol.toString()
        if (slug != null) queryParams["slug"] = slug.toString()

        call(CmcProApiContract.CryptocurrencyInfoV2.request.copy(queryParams = queryParams)) }

    override suspend fun exchangeMap(slug: CharSequence?, start: Int?, limit: CharSequence?): CharSequence = run {
        val queryParams = mutableMapOf<CharSequence, CharSequence>()
        if (slug != null) queryParams["slug"] = slug.toString()
        if (start != null) queryParams["start"] = start.toString()
        if (limit != null) queryParams["limit"] = limit.toString()

        call(CmcProApiContract.ExchangeMap.request.copy(queryParams = queryParams)) }

    override suspend fun exchangeListingsLatest(start: Int?, limit: CharSequence?, sort: CharSequence?, sort_dir: CharSequence?, convert: CharSequence?): CharSequence = run {
        val queryParams = mutableMapOf<CharSequence, CharSequence>()
        if (start != null) queryParams["start"] = start.toString()
        if (limit != null) queryParams["limit"] = limit.toString()
        if (sort != null) queryParams["sort"] = sort.toString()
        if (sort_dir != null) queryParams["sort_dir"] = sort_dir.toString()
        if (convert != null) queryParams["convert"] = convert.toString()

        call(CmcProApiContract.ExchangeListingsLatest.request.copy(queryParams = queryParams)) }

    override suspend fun globalMetricsQuotesLatest(convert: CharSequence?): CharSequence = run {
        val queryParams = mutableMapOf<CharSequence, CharSequence>()
        if (convert != null) queryParams["convert"] = convert.toString()

        call(CmcProApiContract.GlobalMetricsQuotesLatest.request.copy(queryParams = queryParams)) }

    override suspend fun fearAndGreedLatest(): CharSequence =
        call(CmcProApiContract.FearAndGreedLatest.request)

    override suspend fun klineCandles(symbol: CharSequence?, interval: CharSequence?, time_start: CharSequence?, time_end: CharSequence?, count: Int?, convert: CharSequence?): CharSequence = run {
        val queryParams = mutableMapOf<CharSequence, CharSequence>()
        if (symbol != null) queryParams["symbol"] = symbol.toString()
        if (interval != null) queryParams["interval"] = interval.toString()
        if (time_start != null) queryParams["time_start"] = time_start.toString()
        if (time_end != null) queryParams["time_end"] = time_end.toString()
        if (count != null) queryParams["count"] = count.toString()
        if (convert != null) queryParams["convert"] = convert.toString()

        call(CmcProApiContract.KlineCandles.request.copy(queryParams = queryParams)) }

    override suspend fun priceConversionV2(amount: CharSequence, id: CharSequence?, symbol: CharSequence?, time_start: CharSequence?, convert: CharSequence?): CharSequence = run {
        val queryParams = mutableMapOf<CharSequence, CharSequence>()
        if (amount != null) queryParams["amount"] = amount.toString()
        if (id != null) queryParams["id"] = id.toString()
        if (symbol != null) queryParams["symbol"] = symbol.toString()
        if (time_start != null) queryParams["time_start"] = time_start.toString()
        if (convert != null) queryParams["convert"] = convert.toString()

        call(CmcProApiContract.PriceConversionV2.request.copy(queryParams = queryParams)) }


}

/** Contract constants for each CmcPro operation. */
object CmcProApiContract {
          object CryptocurrencyMap {
              const val operationId: String = "cryptocurrencyMap"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/v1/cryptocurrency/map")
          }


          object CryptocurrencyListingsLatest {
              const val operationId: String = "cryptocurrencyListingsLatest"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/v1/cryptocurrency/listings/latest")
          }


          object CryptocurrencyQuotesLatestV2 {
              const val operationId: String = "cryptocurrencyQuotesLatestV2"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/v2/cryptocurrency/quotes/latest")
          }


          object CryptocurrencyQuotesHistoricalV3 {
              const val operationId: String = "cryptocurrencyQuotesHistoricalV3"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/v3/cryptocurrency/quotes/historical")
          }


          object CryptocurrencyOhlcvHistoricalV2 {
              const val operationId: String = "cryptocurrencyOhlcvHistoricalV2"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/v2/cryptocurrency/ohlcv/historical")
          }


          object CryptocurrencyInfoV2 {
              const val operationId: String = "cryptocurrencyInfoV2"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/v2/cryptocurrency/info")
          }


          object ExchangeMap {
              const val operationId: String = "exchangeMap"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/v1/exchange/map")
          }


          object ExchangeListingsLatest {
              const val operationId: String = "exchangeListingsLatest"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/v1/exchange/listings/latest")
          }


          object GlobalMetricsQuotesLatest {
              const val operationId: String = "globalMetricsQuotesLatest"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/v1/global-metrics/quotes/latest")
          }


          object FearAndGreedLatest {
              const val operationId: String = "fearAndGreedLatest"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/v3/fear-and-greed/latest")
          }


          object KlineCandles {
              const val operationId: String = "klineCandles"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/v1/k-line/candles")
          }


          object PriceConversionV2 {
              const val operationId: String = "priceConversionV2"
              val request: GeneratedRequest = GeneratedRequest(method = HttpMethod.GET, path = "/v2/tools/price-conversion")
          }

}
