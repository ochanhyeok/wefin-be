package com.solv.wefin.domain.game.participant.service;

import com.solv.wefin.domain.game.holding.entity.GameHolding;
import com.solv.wefin.domain.game.holding.repository.GameHoldingRepository;
import com.solv.wefin.domain.game.participant.dto.HoldingInfo;
import com.solv.wefin.domain.game.participant.dto.PortfolioInfo;
import com.solv.wefin.domain.game.participant.entity.GameParticipant;
import com.solv.wefin.domain.game.participant.entity.ParticipantStatus;
import com.solv.wefin.domain.game.participant.repository.GameParticipantRepository;
import com.solv.wefin.domain.game.room.entity.GameRoom;
import com.solv.wefin.domain.game.room.entity.RoomStatus;
import com.solv.wefin.domain.game.room.repository.GameRoomRepository;
import com.solv.wefin.domain.game.stock.entity.StockDaily;
import com.solv.wefin.domain.game.stock.entity.StockInfo;
import com.solv.wefin.domain.game.stock.repository.StockDailyRepository;
import com.solv.wefin.domain.game.turn.entity.GameTurn;
import com.solv.wefin.domain.game.turn.entity.TurnStatus;
import com.solv.wefin.domain.game.turn.repository.GameTurnRepository;
import com.solv.wefin.domain.quest.service.QuestProgressService;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GamePortfolioServiceTest {

    @InjectMocks
    private GamePortfolioService portfolioService;

    @Mock
    private GameRoomRepository gameRoomRepository;
    @Mock
    private GameParticipantRepository gameParticipantRepository;
    @Mock
    private GameTurnRepository gameTurnRepository;
    @Mock
    private GameHoldingRepository gameHoldingRepository;
    @Mock
    private StockDailyRepository stockDailyRepository;
    @Mock
    private QuestProgressService questProgressService;

    private static final UUID TEST_ROOM_ID = UUID.fromString("00000000-0000-4000-a000-000000000001");
    private static final UUID TEST_USER_ID = UUID.fromString("00000000-0000-4000-a000-000000000002");
    private static final Long TEST_GROUP_ID = 1L;
    private static final LocalDate TEST_TRADE_DATE = LocalDate.of(2022, 3, 2);

    // === 성공 테스트 ===

    @Nested
    @DisplayName("포트폴리오 조회 성공")
    class SuccessTests {

        @Test
        @DisplayName("보유종목 없음 — cash = seed, stockValue = 0")
        void portfolio_noHoldings() {
            // Given
            GameRoom room = createGameRoom();
            GameParticipant participant = createParticipant(room, new BigDecimal("10000000"));
            GameTurn turn = createGameTurn(room);

            setupCommonMocks(room, participant, turn);
            given(gameHoldingRepository.findAllByParticipantAndQuantityGreaterThan(participant, 0))
                    .willReturn(List.of());

            // When
            PortfolioInfo result = portfolioService.getPortfolio(TEST_ROOM_ID, TEST_USER_ID);

            // Then
            assertThat(result.seedMoney()).isEqualByComparingTo(new BigDecimal("10000000"));
            assertThat(result.cash()).isEqualByComparingTo(new BigDecimal("10000000"));
            assertThat(result.stockValue()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.totalAsset()).isEqualByComparingTo(new BigDecimal("10000000"));
            assertThat(result.profitRate()).isEqualByComparingTo(new BigDecimal("0.00"));
            verify(questProgressService).handleProfitRate(TEST_USER_ID, new BigDecimal("0.00"));
        }

        @Test
        @DisplayName("보유종목 1개 — 종가 기준 평가금액 계산")
        void portfolio_oneHolding() {
            // Given — 시드 1000만, 잔고 500만 (500만원어치 매수), 삼성전자 10주 보유
            GameRoom room = createGameRoom();
            GameParticipant participant = createParticipant(room, new BigDecimal("5000000"));
            GameTurn turn = createGameTurn(room);
            StockInfo samsung = createStockInfo("005930", "삼성전자");
            GameHolding holding = GameHolding.create(participant, samsung, 10, new BigDecimal("55000"));
            StockDaily stockDaily = createStockDaily(samsung, new BigDecimal("60000"));

            setupCommonMocks(room, participant, turn);
            given(gameHoldingRepository.findAllByParticipantAndQuantityGreaterThan(participant, 0))
                    .willReturn(List.of(holding));
            given(stockDailyRepository.findAllByStockInfoInAndTradeDate(List.of(samsung), TEST_TRADE_DATE))
                    .willReturn(List.of(stockDaily));

            // When
            PortfolioInfo result = portfolioService.getPortfolio(TEST_ROOM_ID, TEST_USER_ID);

            // Then
            // stockValue = 10 × 60000 = 600000
            assertThat(result.stockValue()).isEqualByComparingTo(new BigDecimal("600000"));
            // totalAsset = 5000000 + 600000 = 5600000
            assertThat(result.totalAsset()).isEqualByComparingTo(new BigDecimal("5600000"));
            // profitRate = (5600000 - 10000000) / 10000000 × 100 = -44.00
            BigDecimal expectedRate = new BigDecimal("5600000").subtract(new BigDecimal("10000000"))
                    .multiply(new BigDecimal("100"))
                    .divide(new BigDecimal("10000000"), 2, RoundingMode.HALF_UP);
            assertThat(result.profitRate()).isEqualByComparingTo(expectedRate);
            verify(questProgressService).handleProfitRate(TEST_USER_ID, expectedRate);
        }

        @Test
        @DisplayName("보유종목 여러 개 — 전체 stockValue 합산")
        void portfolio_multipleHoldings() {
            // Given — 시드 1000만, 잔고 300만, 삼성 10주 + SK 5주
            GameRoom room = createGameRoom();
            GameParticipant participant = createParticipant(room, new BigDecimal("3000000"));
            GameTurn turn = createGameTurn(room);

            StockInfo samsung = createStockInfo("005930", "삼성전자");
            StockInfo sk = createStockInfo("000660", "SK하이닉스");
            GameHolding holdingSamsung = GameHolding.create(participant, samsung, 10, new BigDecimal("55000"));
            GameHolding holdingSk = GameHolding.create(participant, sk, 5, new BigDecimal("120000"));

            StockDaily samsungDaily = createStockDaily(samsung, new BigDecimal("60000"));
            StockDaily skDaily = createStockDaily(sk, new BigDecimal("130000"));

            setupCommonMocks(room, participant, turn);
            given(gameHoldingRepository.findAllByParticipantAndQuantityGreaterThan(participant, 0))
                    .willReturn(List.of(holdingSamsung, holdingSk));
            given(stockDailyRepository.findAllByStockInfoInAndTradeDate(List.of(samsung, sk), TEST_TRADE_DATE))
                    .willReturn(List.of(samsungDaily, skDaily));

            // When
            PortfolioInfo result = portfolioService.getPortfolio(TEST_ROOM_ID, TEST_USER_ID);

            // Then
            // stockValue = (10 × 60000) + (5 × 130000) = 600000 + 650000 = 1250000
            assertThat(result.stockValue()).isEqualByComparingTo(new BigDecimal("1250000"));
            // totalAsset = 3000000 + 1250000 = 4250000
            assertThat(result.totalAsset()).isEqualByComparingTo(new BigDecimal("4250000"));
            // profitRate = (4250000 - 10000000) / 10000000 × 100 = -57.50
            assertThat(result.profitRate()).isEqualByComparingTo(new BigDecimal("-57.50"));
            verify(questProgressService).handleProfitRate(TEST_USER_ID, new BigDecimal("-57.50"));
        }
    }

    // === 실패 테스트 ===

    @Nested
    @DisplayName("포트폴리오 조회 실패")
    class FailTests {

        @Test
        @DisplayName("실패 — 방이 존재하지 않음")
        void fail_roomNotFound() {
            given(gameRoomRepository.findById(TEST_ROOM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> portfolioService.getPortfolio(TEST_ROOM_ID, TEST_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROOM_NOT_FOUND);
        }

        @Test
        @DisplayName("실패 — 게임 미시작 (WAITING 상태)")
        void fail_gameNotStarted() {
            GameRoom room = createWaitingRoom();
            given(gameRoomRepository.findById(TEST_ROOM_ID)).willReturn(Optional.of(room));

            assertThatThrownBy(() -> portfolioService.getPortfolio(TEST_ROOM_ID, TEST_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GAME_NOT_STARTED);
        }

        @Test
        @DisplayName("실패 — 참가자가 아님")
        void fail_notParticipant() {
            GameRoom room = createGameRoom();
            given(gameRoomRepository.findById(TEST_ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, TEST_USER_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> portfolioService.getPortfolio(TEST_ROOM_ID, TEST_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROOM_NOT_PARTICIPANT);
        }

        @Test
        @DisplayName("실패 — ACTIVE 턴 없음")
        void fail_noActiveTurn() {
            GameRoom room = createGameRoom();
            GameParticipant participant = createParticipant(room, new BigDecimal("10000000"));

            given(gameRoomRepository.findById(TEST_ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, TEST_USER_ID))
                    .willReturn(Optional.of(participant));
            given(gameTurnRepository.findByGameRoomAndStatus(room, TurnStatus.ACTIVE))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> portfolioService.getPortfolio(TEST_ROOM_ID, TEST_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GAME_NOT_STARTED);
        }

        @Test
        @DisplayName("실패 — 보유종목의 주가 데이터 없음")
        void fail_stockPriceNotFound() {
            GameRoom room = createGameRoom();
            GameParticipant participant = createParticipant(room, new BigDecimal("10000000"));
            GameTurn turn = createGameTurn(room);
            StockInfo stockInfo = createStockInfo("005930", "삼성전자");
            GameHolding holding = GameHolding.create(participant, stockInfo, 10, new BigDecimal("55000"));

            setupCommonMocks(room, participant, turn);
            given(gameHoldingRepository.findAllByParticipantAndQuantityGreaterThan(participant, 0))
                    .willReturn(List.of(holding));
            given(stockDailyRepository.findAllByStockInfoInAndTradeDate(List.of(stockInfo), TEST_TRADE_DATE))
                    .willReturn(List.of());

            assertThatThrownBy(() -> portfolioService.getPortfolio(TEST_ROOM_ID, TEST_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GAME_STOCK_PRICE_NOT_FOUND);
        }
    }

    // === 보유종목 성공 테스트 ===

    @Nested
    @DisplayName("보유종목 조회 성공")
    class HoldingsSuccessTests {

        @Test
        @DisplayName("보유종목 없음 — 빈 리스트 반환")
        void holdings_noHoldings() {
            GameRoom room = createGameRoom();
            GameParticipant participant = createParticipant(room, new BigDecimal("10000000"));
            GameTurn turn = createGameTurn(room);

            setupCommonMocks(room, participant, turn);
            given(gameHoldingRepository.findAllByParticipantAndQuantityGreaterThan(participant, 0))
                    .willReturn(List.of());

            List<HoldingInfo> result = portfolioService.getHoldings(TEST_ROOM_ID, TEST_USER_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("보유종목 1개 — 종가 기준 평가금액, 수익률 계산")
        void holdings_oneHolding() {
            GameRoom room = createGameRoom();
            GameParticipant participant = createParticipant(room, new BigDecimal("5000000"));
            GameTurn turn = createGameTurn(room);
            StockInfo samsung = createStockInfo("005930", "삼성전자");
            GameHolding holding = GameHolding.create(participant, samsung, 10, new BigDecimal("55000"));
            StockDaily stockDaily = createStockDaily(samsung, new BigDecimal("60000"));

            setupCommonMocks(room, participant, turn);
            given(gameHoldingRepository.findAllByParticipantAndQuantityGreaterThan(participant, 0))
                    .willReturn(List.of(holding));
            given(stockDailyRepository.findAllByStockInfoInAndTradeDate(List.of(samsung), TEST_TRADE_DATE))
                    .willReturn(List.of(stockDaily));

            List<HoldingInfo> result = portfolioService.getHoldings(TEST_ROOM_ID, TEST_USER_ID);

            assertThat(result).hasSize(1);
            HoldingInfo info = result.get(0);
            assertThat(info.symbol()).isEqualTo("005930");
            assertThat(info.stockName()).isEqualTo("삼성전자");
            assertThat(info.quantity()).isEqualTo(10);
            assertThat(info.avgPrice()).isEqualByComparingTo(new BigDecimal("55000"));
            assertThat(info.currentPrice()).isEqualByComparingTo(new BigDecimal("60000"));
            // evalAmount = 10 × 60000 = 600000
            assertThat(info.evalAmount()).isEqualByComparingTo(new BigDecimal("600000"));
            // profitRate = (60000 - 55000) / 55000 × 100 = 9.09
            assertThat(info.profitRate()).isEqualByComparingTo(new BigDecimal("9.09"));
        }

        @Test
        @DisplayName("보유종목 여러 개 — 각 종목별 독립 계산")
        void holdings_multipleHoldings() {
            GameRoom room = createGameRoom();
            GameParticipant participant = createParticipant(room, new BigDecimal("3000000"));
            GameTurn turn = createGameTurn(room);

            StockInfo samsung = createStockInfo("005930", "삼성전자");
            StockInfo sk = createStockInfo("000660", "SK하이닉스");
            GameHolding holdingSamsung = GameHolding.create(participant, samsung, 10, new BigDecimal("55000"));
            GameHolding holdingSk = GameHolding.create(participant, sk, 5, new BigDecimal("120000"));
            StockDaily samsungDaily = createStockDaily(samsung, new BigDecimal("60000"));
            StockDaily skDaily = createStockDaily(sk, new BigDecimal("130000"));

            setupCommonMocks(room, participant, turn);
            given(gameHoldingRepository.findAllByParticipantAndQuantityGreaterThan(participant, 0))
                    .willReturn(List.of(holdingSamsung, holdingSk));
            given(stockDailyRepository.findAllByStockInfoInAndTradeDate(List.of(samsung, sk), TEST_TRADE_DATE))
                    .willReturn(List.of(samsungDaily, skDaily));

            List<HoldingInfo> result = portfolioService.getHoldings(TEST_ROOM_ID, TEST_USER_ID);

            assertThat(result).hasSize(2);

            // SK하이닉스: profitRate = (130000 - 120000) / 120000 × 100 = 8.33
            HoldingInfo skInfo = result.stream()
                    .filter(h -> h.symbol().equals("000660"))
                    .findFirst().orElseThrow();
            assertThat(skInfo.evalAmount()).isEqualByComparingTo(new BigDecimal("650000"));
            assertThat(skInfo.profitRate()).isEqualByComparingTo(new BigDecimal("8.33"));
        }
    }

    // === 보유종목 실패 테스트 ===

    @Nested
    @DisplayName("보유종목 조회 실패")
    class HoldingsFailTests {

        @Test
        @DisplayName("실패 — 방이 존재하지 않음")
        void fail_roomNotFound() {
            given(gameRoomRepository.findById(TEST_ROOM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> portfolioService.getHoldings(TEST_ROOM_ID, TEST_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROOM_NOT_FOUND);
        }

        @Test
        @DisplayName("실패 — 참가자가 아님")
        void fail_notParticipant() {
            GameRoom room = createGameRoom();
            given(gameRoomRepository.findById(TEST_ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, TEST_USER_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> portfolioService.getHoldings(TEST_ROOM_ID, TEST_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROOM_NOT_PARTICIPANT);
        }

        @Test
        @DisplayName("실패 — 게임 미시작 (WAITING 상태)")
        void fail_gameNotStarted() {
            GameRoom room = createWaitingRoom();
            given(gameRoomRepository.findById(TEST_ROOM_ID)).willReturn(Optional.of(room));

            assertThatThrownBy(() -> portfolioService.getHoldings(TEST_ROOM_ID, TEST_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GAME_NOT_STARTED);
        }

        @Test
        @DisplayName("실패 — ACTIVE 턴 없음")
        void fail_noActiveTurn() {
            GameRoom room = createGameRoom();
            GameParticipant participant = createParticipant(room, new BigDecimal("10000000"));

            given(gameRoomRepository.findById(TEST_ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, TEST_USER_ID))
                    .willReturn(Optional.of(participant));
            given(gameTurnRepository.findByGameRoomAndStatus(room, TurnStatus.ACTIVE))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> portfolioService.getHoldings(TEST_ROOM_ID, TEST_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GAME_NOT_STARTED);
        }

        @Test
        @DisplayName("실패 — 보유종목의 주가 데이터 없음")
        void fail_stockPriceNotFound() {
            GameRoom room = createGameRoom();
            GameParticipant participant = createParticipant(room, new BigDecimal("10000000"));
            GameTurn turn = createGameTurn(room);
            StockInfo stockInfo = createStockInfo("005930", "삼성전자");
            GameHolding holding = GameHolding.create(participant, stockInfo, 10, new BigDecimal("55000"));

            setupCommonMocks(room, participant, turn);
            given(gameHoldingRepository.findAllByParticipantAndQuantityGreaterThan(participant, 0))
                    .willReturn(List.of(holding));
            given(stockDailyRepository.findAllByStockInfoInAndTradeDate(List.of(stockInfo), TEST_TRADE_DATE))
                    .willReturn(List.of());

            assertThatThrownBy(() -> portfolioService.getHoldings(TEST_ROOM_ID, TEST_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GAME_STOCK_PRICE_NOT_FOUND);
        }
    }

    // === 헬퍼 메서드 ===

    private void setupCommonMocks(GameRoom room, GameParticipant participant, GameTurn turn) {
        given(gameRoomRepository.findById(TEST_ROOM_ID)).willReturn(Optional.of(room));
        given(gameParticipantRepository.findByGameRoomAndUserId(room, TEST_USER_ID))
                .willReturn(Optional.of(participant));
        given(gameTurnRepository.findByGameRoomAndStatus(room, TurnStatus.ACTIVE))
                .willReturn(Optional.of(turn));
    }

    private GameRoom createGameRoom() {
        GameRoom room = GameRoom.create(TEST_GROUP_ID, TEST_USER_ID, new BigDecimal("10000000"),
                6, 7, TEST_TRADE_DATE, TEST_TRADE_DATE.plusMonths(6));
        room.start();
        return room;
    }

    private GameRoom createWaitingRoom() {
        return GameRoom.create(TEST_GROUP_ID, TEST_USER_ID, new BigDecimal("10000000"),
                6, 7, TEST_TRADE_DATE, TEST_TRADE_DATE.plusMonths(6));
    }

    private GameTurn createGameTurn(GameRoom room) {
        return GameTurn.createFirst(room);
    }

    private GameParticipant createParticipant(GameRoom room, BigDecimal cash) {
        GameParticipant participant = GameParticipant.createLeader(room, TEST_USER_ID);
        participant.assignSeed(cash);
        return participant;
    }

    private StockInfo createStockInfo(String symbol, String name) {
        return StockInfo.create(symbol, name, "KOSPI", "전기전자");
    }

    private StockDaily createStockDaily(StockInfo stockInfo, BigDecimal closePrice) {
        return StockDaily.create(stockInfo, TEST_TRADE_DATE,
                closePrice, closePrice, closePrice, closePrice,
                BigDecimal.valueOf(1000000), BigDecimal.ZERO);
    }
}
