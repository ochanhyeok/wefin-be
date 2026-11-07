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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GamePortfolioService {

    private final GameRoomRepository gameRoomRepository;
    private final GameParticipantRepository gameParticipantRepository;
    private final GameTurnRepository gameTurnRepository;
    private final GameHoldingRepository gameHoldingRepository;
    private final StockDailyRepository stockDailyRepository;
    private final QuestProgressService questProgressService;

    public PortfolioInfo getPortfolio(UUID roomId, UUID userId) {

        // 1. 방 조회
        GameRoom gameRoom = gameRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));

        if (gameRoom.getStatus() != RoomStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.GAME_NOT_STARTED);
        }

        // 2. 참가자 조회
        GameParticipant participant = gameParticipantRepository
                .findByGameRoomAndUserId(gameRoom, userId)
                .filter(p -> p.getStatus() == ParticipantStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_PARTICIPANT));

        // 3. 현재 턴 조회
        GameTurn currentTurn = gameTurnRepository.findByGameRoomAndStatus(gameRoom, TurnStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_STARTED));

        LocalDate turnDate = currentTurn.getTurnDate();

        // 4. 보유종목 평가금액 계산
        BigDecimal stockValue = calculateStockValue(participant, turnDate)
                .setScale(2, RoundingMode.HALF_UP);

        // 5. 포트폴리오 조립
        BigDecimal seedMoney = gameRoom.getSeed();
        BigDecimal cash = participant.getSeed();
        BigDecimal totalAsset = cash.add(stockValue).setScale(2, RoundingMode.HALF_UP);
        BigDecimal profitRate = seedMoney.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : totalAsset.subtract(seedMoney)
                        .multiply(new BigDecimal("100"))
                        .divide(seedMoney, 2, RoundingMode.HALF_UP);

        handleProfitRateSafely(userId, profitRate);

        return new PortfolioInfo(seedMoney, cash, stockValue, totalAsset, profitRate);
    }

    public List<HoldingInfo> getHoldings(UUID roomId, UUID userId) {

        // 1. 방 조회
        GameRoom gameRoom = gameRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));

        if (gameRoom.getStatus() != RoomStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.GAME_NOT_STARTED);
        }

        // 2. 참가자 조회
        GameParticipant participant = gameParticipantRepository
                .findByGameRoomAndUserId(gameRoom, userId)
                .filter(p -> p.getStatus() == ParticipantStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_PARTICIPANT));

        // 3. 현재 턴 조회
        GameTurn currentTurn = gameTurnRepository.findByGameRoomAndStatus(gameRoom, TurnStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_STARTED));

        LocalDate turnDate = currentTurn.getTurnDate();

        // 4. 보유종목별 상세 정보 계산
        List<GameHolding> holdings = gameHoldingRepository
                .findAllByParticipantAndQuantityGreaterThan(participant, 0);

        // N+1 방지: 보유종목 전체의 주가를 한 번에 조회
        Map<String, StockDaily> priceMap = buildPriceMap(holdings, turnDate);

        return holdings.stream()
                .map(holding -> {
                    StockDaily stockDaily = priceMap.get(holding.getStockInfo().getSymbol());
                    if (stockDaily == null) {
                        throw new BusinessException(ErrorCode.GAME_STOCK_PRICE_NOT_FOUND);
                    }

                    BigDecimal currentPrice = stockDaily.getClosePrice();
                    BigDecimal evalAmount = currentPrice.multiply(BigDecimal.valueOf(holding.getQuantity()))
                            .setScale(2, RoundingMode.HALF_UP);
                    BigDecimal avgPrice = holding.getAvgPrice();
                    BigDecimal profitRate = avgPrice.compareTo(BigDecimal.ZERO) == 0
                            ? BigDecimal.ZERO
                            : currentPrice.subtract(avgPrice)
                                    .multiply(new BigDecimal("100"))
                                    .divide(avgPrice, 2, RoundingMode.HALF_UP);

                    return new HoldingInfo(
                            holding.getStockInfo().getSymbol(),
                            holding.getStockName(),
                            holding.getQuantity(),
                            holding.getAvgPrice(),
                            currentPrice,
                            evalAmount,
                            profitRate
                    );
                })
                .toList();
    }

    private BigDecimal calculateStockValue(GameParticipant participant, LocalDate turnDate) {
        List<GameHolding> holdings = gameHoldingRepository
                .findAllByParticipantAndQuantityGreaterThan(participant, 0);

        if (holdings.isEmpty()) {
            return BigDecimal.ZERO;
        }

        // N+1 방지: 보유종목 전체의 주가를 한 번에 조회
        Map<String, StockDaily> priceMap = buildPriceMap(holdings, turnDate);

        BigDecimal stockValue = BigDecimal.ZERO;

        for (GameHolding holding : holdings) {
            StockDaily stockDaily = priceMap.get(holding.getStockInfo().getSymbol());
            if (stockDaily == null) {
                throw new BusinessException(ErrorCode.GAME_STOCK_PRICE_NOT_FOUND);
            }

            BigDecimal holdingValue = stockDaily.getClosePrice()
                    .multiply(BigDecimal.valueOf(holding.getQuantity()));
            stockValue = stockValue.add(holdingValue);
        }

        return stockValue;
    }

    /**
     * 보유종목 목록의 주가를 한 번의 쿼리로 일괄 조회하여 Map으로 반환한다.
     * key: symbol (StockInfo PK), value: StockDaily
     */
    private Map<String, StockDaily> buildPriceMap(List<GameHolding> holdings, LocalDate turnDate) {
        List<StockInfo> stockInfos = holdings.stream()
                .map(GameHolding::getStockInfo)
                .toList();

        return stockDailyRepository.findAllByStockInfoInAndTradeDate(stockInfos, turnDate)
                .stream()
                .collect(Collectors.toMap(
                        sd -> sd.getStockInfo().getSymbol(),
                        Function.identity()
                ));
    }

    private void handleProfitRateSafely(UUID userId, BigDecimal profitRate) {
        try {
            questProgressService.handleProfitRate(userId, profitRate);
        } catch (RuntimeException e) {
            log.warn("수익률 퀘스트 반영 실패 userId={}, profitRate={}", userId, profitRate, e);
        }
    }
}
