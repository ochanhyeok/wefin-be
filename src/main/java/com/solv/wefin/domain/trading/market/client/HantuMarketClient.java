package com.solv.wefin.domain.trading.market.client;

import com.solv.wefin.domain.trading.market.client.dto.*;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 한투 Open API 시세 조회 클라이언트
 */
@RequiredArgsConstructor
@Component
public class HantuMarketClient {

    private final RestClient hantuRestClient;          // API 호출용
    private final HantuTokenManager hantuTokenManager; // 토큰 가져오기용
    private final CircuitBreaker hantuPriceCircuitBreaker;

    @Value("${hantu.api.appkey}")
    private String appKey;

    @Value("${hantu.api.appsecret}")
    private String appSecret;

    /**
     * 국내 주식 현재가 시세를 조회합니다.
     * @param stockCode 종목코드 (ex: "005930")
     * @return 현재가 시세 응답
     */
    public HantuPriceApiResponse fetchCurrentPrice(String stockCode) {
        // 회로가 열려 있으면 호출 없이 CallNotPermittedException → MarketService 가 MARKET_API_FAILED(503)로 바꾼다
        return hantuPriceCircuitBreaker.executeSupplier(() -> requestCurrentPrice(stockCode));
    }

    private HantuPriceApiResponse requestCurrentPrice(String stockCode) {
        return hantuRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-price")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_INPUT_ISCD", stockCode)
                        .build())
                .header("authorization", "Bearer " + hantuTokenManager.getAccessToken())
                .header("appkey", appKey)
                .header("appsecret", appSecret)
                .header("tr_id", "FHKST01010100")
                .header("custtype", "P")
                .retrieve().body(HantuPriceApiResponse.class);
    }

    /**
     * 국내 주식 기본정보(시가총액/상장주식수/외국인소진율)를 조회합니다.
     * fetchCurrentPrice와 동일한 endpoint 호출이지만 다른 필드 subset을 매핑한 DTO로 받습니다.
     * @param stockCode 종목코드
     * @return 기본정보 응답
     */
    public HantuStockInfoApiResponse fetchStockInfo(String stockCode) {
        return hantuRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-price")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_INPUT_ISCD", stockCode)
                        .build())
                .header("authorization", "Bearer " + hantuTokenManager.getAccessToken())
                .header("appkey", appKey)
                .header("appsecret", appSecret)
                .header("tr_id", "FHKST01010100")
                .header("custtype", "P")
                .retrieve().body(HantuStockInfoApiResponse.class);
    }

    /**
     * 국내 주식 호가를 조회합니다.
     * @param stockCode 종목코드
     * @return 호가 응답
     */
    public HantuOrderbookApiResponse fetchOrderbook(String stockCode) {
        return hantuRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-asking-price-exp-ccn")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_INPUT_ISCD", stockCode)
                        .build())
                .header("authorization", "Bearer " + hantuTokenManager.getAccessToken())
                .header("appkey", appKey)
                .header("appsecret", appSecret)
                .header("tr_id", "FHKST01010200")
                .header("custtype", "P")
                .retrieve().body(HantuOrderbookApiResponse.class);
    }

    /**
     * 국내 주식 기간별 시세를 조회합니다.
     * @param stockCode 종목코드
     * @param start 조회 시작일자
     * @param end 조회 종료일자
     * @param periodCode 기간 분류 코드 (D:일봉, W:주봉, M:월봉, Y:년봉)
     * @return 기간별 시세 응답
     */
    public HantuCandleApiResponse fetchPeriodPrice(String stockCode, LocalDate start, LocalDate end, String periodCode) {
        return hantuRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_INPUT_ISCD", stockCode)
                        .queryParam("FID_INPUT_DATE_1", start.format(DateTimeFormatter.ofPattern("yyyyMMdd")))
                        .queryParam("FID_INPUT_DATE_2", end.format(DateTimeFormatter.ofPattern("yyyyMMdd")))
                        .queryParam("FID_PERIOD_DIV_CODE", periodCode)
                        .queryParam("FID_ORG_ADJ_PRC", "0")
                        .build())
                .header("authorization", "Bearer " + hantuTokenManager.getAccessToken())
                .header("appkey", appKey)
                .header("appsecret", appSecret)
                .header("tr_id", "FHKST03010100")
                .header("custtype", "P")
                .retrieve().body(HantuCandleApiResponse.class);
    }

    public HantuRecentTradeApiResponse fetchRecentTrades(String stockCode) {
        return hantuRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-ccnl")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_INPUT_ISCD", stockCode)
                        .build())
                .header("authorization", "Bearer " + hantuTokenManager.getAccessToken())
                .header("appkey", appKey)
                .header("appsecret", appSecret)
                .header("tr_id", "FHKST01010300")
                .header("custtype", "P")
                .retrieve().body(HantuRecentTradeApiResponse.class);
    }

    /**
     * 국내 주식 당일 분봉 시세를 조회합니다.
     * @param stockCode 종목코드
     * @param inputHour 조회 시작 시간 (HHMMSS, ex: "153000")
     * @return 분봉 시세 응답 (1분 단위 OHLCV)
     */
    public HantuMinuteCandleApiResponse fetchMinutePrice(String stockCode, String inputHour) {
        return hantuRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_INPUT_ISCD", stockCode)
                        .queryParam("FID_INPUT_HOUR_1", inputHour)
                        .queryParam("FID_ETC_CLS_CODE", "")
                        .queryParam("FID_PW_DATA_INCU_YN", "N")
                        .build())
                .header("authorization", "Bearer " + hantuTokenManager.getAccessToken())
                .header("appkey", appKey)
                .header("appsecret", appSecret)
                .header("tr_id", "FHKST03010200")
                .header("custtype", "P")
                .retrieve().body(HantuMinuteCandleApiResponse.class);
    }

    /**
     * 종목별 투자자(외국인/기관/개인) 일별 순매수 동향을 조회합니다.
     * 최근 약 30영업일 데이터가 리스트로 반환됩니다 (최신일 앞).
     * @param stockCode 종목코드
     */
    public HantuInvestorTrendApiResponse fetchInvestorTrend(String stockCode) {
        return hantuRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-investor")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_INPUT_ISCD", stockCode)
                        .build())
                .header("authorization", "Bearer " + hantuTokenManager.getAccessToken())
                .header("appkey", appKey)
                .header("appsecret", appSecret)
                .header("tr_id", "FHKST01010900")
                .header("custtype", "P")
                .retrieve().body(HantuInvestorTrendApiResponse.class);
    }

    /**
     * 국내 주식 거래량 순위를 조회
     */
    public HantuRankingApiResponse fetchVolumeRanking() {
        return hantuRestClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/uapi/domestic-stock/v1/quotations/volume-rank")
                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                .queryParam("FID_COND_SCR_DIV_CODE", "20171")
                .queryParam("FID_INPUT_ISCD", "0000")
                .queryParam("FID_DIV_CLS_CODE", "0")
                .queryParam("FID_BLNG_CLS_CODE", "0")
                .queryParam("FID_TRGT_CLS_CODE", "111111111")
                .queryParam("FID_TRGT_EXLS_CLS_CODE", "000000")
                .queryParam("FID_INPUT_PRICE_1", "0")
                .queryParam("FID_INPUT_PRICE_2", "0")
                .queryParam("FID_VOL_CNT", "0")
                .queryParam("FID_INPUT_DATE_1", "")
                .build())
            .header("authorization", "Bearer " + hantuTokenManager.getAccessToken())
            .header("appkey", appKey)
            .header("appsecret", appSecret)
            .header("tr_id", "FHPST01710000")
            .header("custtype", "P")
            .retrieve().body(HantuRankingApiResponse.class);
    }

    /**
     * 국내 주식 거래대금 순위를 조회
     */
    public HantuRankingApiResponse fetchTradingAmountRanking() {
        return hantuRestClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/uapi/domestic-stock/v1/quotations/volume-rank")
                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                .queryParam("FID_COND_SCR_DIV_CODE", "20171")
                .queryParam("FID_INPUT_ISCD", "0000")
                .queryParam("FID_DIV_CLS_CODE", "0")
                .queryParam("FID_BLNG_CLS_CODE", "3")       // 3: 거래금액순 (공식 문서)
                .queryParam("FID_TRGT_CLS_CODE", "111111111")
                .queryParam("FID_TRGT_EXLS_CLS_CODE", "000000")
                .queryParam("FID_INPUT_PRICE_1", "0")
                .queryParam("FID_INPUT_PRICE_2", "0")
                .queryParam("FID_VOL_CNT", "0")
                .queryParam("FID_INPUT_DATE_1", "")
                .build())
            .header("authorization", "Bearer " + hantuTokenManager.getAccessToken())
            .header("appkey", appKey)
            .header("appsecret", appSecret)
            .header("tr_id", "FHPST01710000")  // 거래량과 동일 API, DIV_CLS_CODE로 구분
            .header("custtype", "P")
            .retrieve().body(HantuRankingApiResponse.class);
    }

    /**
     * 국내 주식 등락률 순위를 조회
     * @param isRising true: 급등 (상승률 상위), false: 급락 (하락률 상위)
     */
    public HantuRankingApiResponse fetchChangeRateRanking(boolean isRising) {
        return hantuRestClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/uapi/domestic-stock/v1/ranking/fluctuation")
                .queryParam("fid_cond_mrkt_div_code", "J")
                .queryParam("fid_cond_scr_div_code", "20170")
                .queryParam("fid_input_iscd", "0000")
                .queryParam("fid_rank_sort_cls_code", isRising ? "0" : "1")
                .queryParam("fid_input_cnt_1", "0")
                .queryParam("fid_prc_cls_code", "0")
                .queryParam("fid_input_price_1", "0")
                .queryParam("fid_input_price_2", "0")
                .queryParam("fid_vol_cnt", "0")
                .queryParam("fid_trgt_cls_code", "0")
                .queryParam("fid_trgt_exls_cls_code", "0")
                .queryParam("fid_div_cls_code", "0")
                .queryParam("fid_rsfl_rate1", "")
                .queryParam("fid_rsfl_rate2", "")
                .build())
            .header("authorization", "Bearer " + hantuTokenManager.getAccessToken())
            .header("appkey", appKey)
            .header("appsecret", appSecret)
            .header("tr_id", "FHPST01720000")
            .header("custtype", "P")
            .retrieve().body(HantuRankingApiResponse.class);
    }

}
