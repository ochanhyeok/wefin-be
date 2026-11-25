package com.solv.wefin.domain.trading.market.service;

import com.solv.wefin.domain.trading.market.client.HantuMarketClient;
import com.solv.wefin.domain.trading.market.client.dto.HantuPriceApiResponse;
import com.solv.wefin.domain.trading.market.client.dto.HantuRankingApiResponse;
import com.solv.wefin.domain.trading.market.client.dto.HantuRecentTradeApiResponse;
import com.solv.wefin.domain.trading.market.client.dto.RankingType;
import com.solv.wefin.domain.trading.market.client.dto.StockRankingItem;
import com.solv.wefin.domain.trading.market.dto.PriceResponse;
import com.solv.wefin.domain.trading.market.dto.RecentTradeResponse;
import com.solv.wefin.domain.trading.stock.service.StockService;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MarketServiceTest {

    @Mock
    private HantuMarketClient hantuMarketClient;

    @Mock
    private StockService stockService;

    @InjectMocks
    private MarketService marketService;


    // === getPrice 테스트 ===

    @Test
    void 현재가_정상조회() {
        // given
        given(stockService.existsByCode("005930")).willReturn(true);
        given(hantuMarketClient.fetchCurrentPrice("005930")).willReturn(
                new HantuPriceApiResponse("0", null, null, new HantuPriceApiResponse.Output(
                        "97500", "1200", "2", "1.25",
                        "12340567", "1200000000000",
                        "96800", "98200", "96300",
                        "126700", "68100",
                        "15.2", "1.8", "580000000"
                ))
        );

        // when
        PriceResponse result = marketService.getPrice("005930");

        // then
        assertThat(result.currentPrice()).isEqualTo(new BigDecimal("97500"));
        assertThat(result.stockCode()).isEqualTo("005930");
    }

    @Test
    void 현재가_캐시히트시_API_호출안함() {
        // given
        given(stockService.existsByCode("005930")).willReturn(true);
        given(hantuMarketClient.fetchCurrentPrice("005930")).willReturn(
                new HantuPriceApiResponse("0", null, null, new HantuPriceApiResponse.Output(
                        "97500", "1200", "2", "1.25",
                        "12340567", "1200000000000",
                        "96800", "98200", "96300",
                        "126700", "68100",
                        "15.2", "1.8", "580000000"
                ))
        );

        // when
        marketService.getPrice("005930"); // 첫 호출 — API 호출
        marketService.getPrice("005930"); // 두 번째 — 캐시 히트

        // then
        verify(hantuMarketClient, times(1)).fetchCurrentPrice("005930");
    }

    @Test
    void 존재하지_않는_종목코드로_현재가_조회시_예외() {
        // given
        given(stockService.existsByCode("999999")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> marketService.getPrice("999999"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MARKET_STOCK_NOT_FOUND);
    }



    // === getCandles 테스트 ===

    @Test
    void 캔들_시작일이_종료일보다_늦으면_예외() {
        // given
        given(stockService.existsByCode("005930")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> marketService.getCandles(
                "005930", LocalDate.of(2026, 4, 10), LocalDate.of(2026, 4, 1), "D"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MARKET_INVALID_DATE);
    }

    @Test
    void 유효하지_않은_기간코드로_캔들_조회시_예외() {
        // given
        given(stockService.existsByCode("005930")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> marketService.getCandles(
                "005930", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 10), "X"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MARKET_INVALID_PERIOD_CODE);
    }



    // === getRecentTrades 테스트 ===

        @Test
    void 존재하지_않는_종목코드로_최근체결_조회시_예외() {
        given(stockService.existsByCode("999999")).willReturn(false);

        assertThatThrownBy(() -> marketService.getRecentTrades("999999"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MARKET_STOCK_NOT_FOUND);
    }

    @Test
    void 최근체결_정상조회() {
        // given
        given(stockService.existsByCode("005930")).willReturn(true);
        given(hantuMarketClient.fetchRecentTrades("005930")).willReturn(
                new HantuRecentTradeApiResponse(null, List.of(
                        new HantuRecentTradeApiResponse.Output(
                                "104610", "97500", "1200", "2", "1", "106.78", "1.25"
                        ),
                        new HantuRecentTradeApiResponse.Output(
                                "104611", "97600", "1300", "2", "3", "106.80", "1.32"
                        )
                ))
        );

        // when
        List<RecentTradeResponse> result = marketService.getRecentTrades("005930");

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).price()).isEqualByComparingTo(new BigDecimal("97500"));
        assertThat(result.get(0).tradeStrength()).isEqualTo(106.78f);
        assertThat(result.get(1).tradeTime()).isEqualTo("104611");
    }

    /**
     * 정상 응답 (rt_cd = "0"):
     */
    @Test
    void 최근체결_정상응답코드_조회() {
        // given
        given(stockService.existsByCode("005930")).willReturn(true);
        given(hantuMarketClient.fetchRecentTrades("005930")).willReturn(
                new HantuRecentTradeApiResponse(
                        new HantuRecentTradeApiResponse.Output1("0", "KIOK0530", "정상처리"),
                        List.of(
                                new HantuRecentTradeApiResponse.Output(
                                        "104610", "97500", "1200", "2", "1", "106.78", "1.25"
                                )
                        ))
        );

        // when
        List<RecentTradeResponse> result = marketService.getRecentTrades("005930");

        // then
        assertThat(result).hasSize(1);
    }

    /**
     * 에러 응답 (rt_cd != "0"):
     */
    @Test
    void 최근체결_API에러시_예외발생() {
        // given
        given(stockService.existsByCode("005930")).willReturn(true);
        given(hantuMarketClient.fetchRecentTrades("005930")).willReturn(
                new HantuRecentTradeApiResponse(
                        new HantuRecentTradeApiResponse.Output1("1", "KIOK0000", "오류 발생"),
                                List.of()
                        )
                );

        // when & then
        assertThatThrownBy(() -> marketService.getRecentTrades("005930"))
                .isInstanceOf(BusinessException.class);
    }



    // === getStockRanking FALLING 재정렬 테스트 ===

    /**
     * FALLING 재부여 시 같은 changeRate 면 거래량 큰 쪽이 먼저 오고,
     * 거래량도 같으면 stockCode 오름차순으로 안정화된다.
     */
    @Test
    void 급락_랭킹_동률시_거래량_내림차순_종목코드_오름차순으로_tie_break() {
        // given — 같은 등락률 -3.00 (종목 A, B) + 다른 등락률 -5.00 (종목 C)
        // 종목 A: volume=100, B: volume=500 → B 가 먼저 와야 함
        List<HantuRankingApiResponse.Output> output = List.of(
            rankingOutput("1", "A0000001", "종목A", "1000", "-3.00", "100"),
            rankingOutput("2", "B0000002", "종목B", "1000", "-3.00", "500"),
            rankingOutput("3", "C0000003", "종목C", "1000", "-5.00", "300")
        );
        given(hantuMarketClient.fetchChangeRateRanking(false))
            .willReturn(new HantuRankingApiResponse(output));

        // when
        List<StockRankingItem> result = marketService.getStockRanking(RankingType.FALLING);

        // then
        // 가장 큰 하락(-5.00) 이 1위, 그 다음 -3.00 두 개 중 거래량 큰 B 가 2위
        assertThat(result).extracting(StockRankingItem::stockCode)
            .containsExactly("C0000003", "B0000002", "A0000001");
        assertThat(result).extracting(StockRankingItem::rank)
            .containsExactly(1, 2, 3);
    }

    // === getStockRanking 네거티브 캐시 테스트 ===

    /**
     * 외부 API 실패 시 stale 캐시가 있으면 예외 대신 stale 데이터를 반환한다.
     * 프론트 5초 polling 상황에서 KIS rate limit 폭주 방지.
     */
    @Test
    void 랭킹_조회_실패시_직전_캐시를_stale로_반환() {
        // given — 첫 호출은 성공해서 캐시에 저장됨
        List<HantuRankingApiResponse.Output> firstOutput = List.of(
            rankingOutput("1", "A0000001", "종목A", "1000", "+5.00", "100")
        );
        given(hantuMarketClient.fetchVolumeRanking())
            .willReturn(new HantuRankingApiResponse(firstOutput))
            .willThrow(new RuntimeException("KIS API 장애"));

        // when — 첫 호출 (성공 → 캐시 저장)
        List<StockRankingItem> firstResult = marketService.getStockRanking(RankingType.VOLUME);
        assertThat(firstResult).hasSize(1);

        // 캐시 만료시키기 위해 TTL 경과 대기 대신 내부 강제 만료하지 않고,
        // 새 쿼리가 미스로 판정되도록 직접 캐시 제거 후 stale 복원 시나리오를 재현.
        // 실제로는 TTL 만료 후 API 호출 → 실패 → 이전 값 유지.
        // 여기서는 두 번째 호출이 예외를 던지도록 stub 했으므로
        // 캐시가 만료된 상태를 시뮬레이션하기 위해 timestamp 만 과거로 돌림
        forceCacheExpire("VOLUME");

        // when — 두 번째 호출 (TTL 만료 + API 실패 → stale 반환)
        List<StockRankingItem> staleResult = marketService.getStockRanking(RankingType.VOLUME);

        // then — 예외 대신 직전 값이 반환됨
        assertThat(staleResult).isEqualTo(firstResult);
    }

    /**
     * 실패 + 이전 캐시도 없음 → 기존처럼 BusinessException 전파.
     */
    @Test
    void 랭킹_조회_실패시_이전캐시_없으면_예외_전파() {
        // given
        given(hantuMarketClient.fetchVolumeRanking())
            .willThrow(new RuntimeException("KIS API 장애"));

        // when & then
        assertThatThrownBy(() -> marketService.getStockRanking(RankingType.VOLUME))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.MARKET_API_FAILED);
    }

    // === 헬퍼 ===

    private HantuRankingApiResponse.Output rankingOutput(String rank, String code, String name,
                                                         String price, String changeRate, String volume) {
        return new HantuRankingApiResponse.Output(
            rank, code, name, price, "0", "2", changeRate, volume, "0"
        );
    }

    /**
     * 캐시 TTL 이 만료된 상태를 시뮬레이션하기 위해 내부 timestamp 를 과거로 돌린다.
     * 리플렉션으로 private 맵 접근.
     */
    private void forceCacheExpire(String cacheKey) {
        try {
            var field = MarketService.class.getDeclaredField("rankingCacheTimestamp");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.concurrent.ConcurrentHashMap<String, Long>) field.get(marketService);
            map.put(cacheKey, 0L); // 아주 과거 — 무조건 만료
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}