package com.solv.wefin.domain.trading.matching.service;

import com.solv.wefin.domain.trading.ledger.service.CashLedgerService;
import com.solv.wefin.domain.trading.ledger.entity.CashLedgerType;
import com.solv.wefin.domain.trading.account.entity.VirtualAccount;
import com.solv.wefin.domain.trading.account.service.VirtualAccountService;
import com.solv.wefin.domain.trading.common.StockInfoProvider;
import com.solv.wefin.domain.trading.common.TradingConstants;
import com.solv.wefin.domain.trading.matching.event.OrderMatchedEvent;
import com.solv.wefin.domain.trading.order.entity.Order;
import com.solv.wefin.domain.trading.order.entity.OrderSide;
import com.solv.wefin.domain.trading.order.entity.OrderStatus;
import com.solv.wefin.domain.trading.order.entity.OrderType;
import com.solv.wefin.domain.trading.order.repository.OrderRepository;
import com.solv.wefin.domain.trading.portfolio.service.PortfolioService;
import com.solv.wefin.domain.trading.stock.entity.Stock;
import com.solv.wefin.domain.trading.trade.service.TradeService;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;


@Service
@RequiredArgsConstructor
public class LimitOrderMatchingService {

    private final StockInfoProvider stockInfoProvider;
    private final OrderRepository orderRepository;
    private final TradeService tradeService;
    private final ApplicationEventPublisher eventPublisher;
    private final VirtualAccountService virtualAccountService;
    private final PortfolioService portfolioService;
    private final CashLedgerService cashLedgerService;

    /**
     * 지정가 주문을 체결 처리한다.
     */
    @Transactional
    public void matchLimitOrders(String stockCode, BigDecimal currentPrice) {
        Stock stock = stockInfoProvider.getStockByCode(stockCode);
        List<Order> orders = getFilteredLimitOrderList(stock.getId(), currentPrice);

        for (Order order : orders) {
            if (order.getSide().equals(OrderSide.BUY)) {
                matchBuyOrder(order, stock, currentPrice);
            } else if (order.getSide().equals(OrderSide.SELL)) {
                matchSellOrder(order, stock, currentPrice);
            }
        }
    }

    private void matchBuyOrder(Order order, Stock stock, BigDecimal currentPrice) {
        int matchedQuantity = order.getQuantity() - order.getFilledQuantity();

        // 실제 거래 금액/수수료 (체결가 기준)
        BigDecimal actualCost = currentPrice.multiply(BigDecimal.valueOf(matchedQuantity));
        BigDecimal actualFee = actualCost
                .multiply(TradingConstants.FEE_RATE)
                .setScale(0, RoundingMode.DOWN);

        // 묶여있던 해당 몫 (주문가 기준)
        BigDecimal reservedCost = order.getRequestPrice()
                .multiply(BigDecimal.valueOf(matchedQuantity));
        BigDecimal reservedFee = reservedCost
                .multiply(TradingConstants.FEE_RATE)
                .setScale(0, RoundingMode.DOWN);

        // 차액 환급 (requestPrice ≥ currentPrice 이므로 항상 ≥ 0)
        BigDecimal refund = reservedCost.add(reservedFee)
                .subtract(actualCost).subtract(actualFee);

        VirtualAccount account = virtualAccountService.getAccountWithLock(
                order.getVirtualAccountId());
        if (refund.compareTo(BigDecimal.ZERO) > 0) {
            account.deposit(refund);
            cashLedgerService.record(order.getVirtualAccountId(), CashLedgerType.LIMIT_BUY_FILL_REFUND, refund,
                    order.getOrderId());
        }

        // 포트폴리오에 실제 보유 증가 (체결가가 새 매입 단가)
        portfolioService.addHolding(
                order.getVirtualAccountId(), stock.getId(), matchedQuantity,
                currentPrice, order.getCurrency()
        );

        // Trade 생성 (actualFee 사용)
        tradeService.createBuyTrade(
                order.getOrderId(), order.getVirtualAccountId(), stock.getId(),
                matchedQuantity, currentPrice, actualCost, actualFee,
                order.getCurrency(), order.getExchangeRate()
        );

        order.fillPartially(matchedQuantity);
        OffsetDateTime matchedAt = OffsetDateTime.now();

        eventPublisher.publishEvent(OrderMatchedEvent.ofBuy(
                OrderType.LIMIT, order.getOrderNo(), stock.getStockCode(), stock.getStockName(),
                matchedQuantity, currentPrice, actualFee, account.getBalance(), matchedAt
        ));
    }

    private void matchSellOrder(Order order, Stock stock, BigDecimal currentPrice) {
        int matchedQuantity = order.getQuantity() - order.getFilledQuantity();

        // 실제 거래 금액/수수료/세금 (체결가 기준)
        BigDecimal actualAmount = currentPrice.multiply(BigDecimal.valueOf(matchedQuantity));
        BigDecimal actualFee = actualAmount
                .multiply(TradingConstants.FEE_RATE)
                .setScale(0, RoundingMode.DOWN);
        BigDecimal actualTax = actualAmount
                .multiply(TradingConstants.TAX_RATE)
                .setScale(0, RoundingMode.DOWN);

        // 실현손익 = (체결가 - 주문 생성 시점의 avgPrice 스냅샷) × 수량
        BigDecimal realizedProfit = currentPrice
                .subtract(order.getReservedAvgPrice())
                .multiply(BigDecimal.valueOf(matchedQuantity));

        // 매도 대금에서 수수료/세금 차감 후 계좌 입금
        BigDecimal netDeposit = actualAmount.subtract(actualFee).subtract(actualTax);

        VirtualAccount account = virtualAccountService.getAccountWithLock(
                order.getVirtualAccountId());
        account.deposit(netDeposit);
        cashLedgerService.record(order.getVirtualAccountId(), CashLedgerType.LIMIT_SELL_FILL, netDeposit,
                order.getOrderId());
        account.addProfit(realizedProfit);

        // 포트폴리오는 건드리지 않음 (생성 시점에 이미 deductQuantity 완료)

        tradeService.createSellTrade(
                order.getOrderId(), order.getVirtualAccountId(), stock.getId(),
                matchedQuantity, currentPrice, actualAmount, actualFee, actualTax,
                realizedProfit, order.getCurrency(), order.getExchangeRate()
        );

        order.fillPartially(matchedQuantity);
        OffsetDateTime matchedAt = OffsetDateTime.now();

        eventPublisher.publishEvent(OrderMatchedEvent.ofSell(
                OrderType.LIMIT, order.getOrderNo(), stock.getStockCode(), stock.getStockName(),
                matchedQuantity, currentPrice, actualFee, actualTax,
                realizedProfit, account.getBalance(), matchedAt
        ));
    }

    /**
     * 매수/매도 조건에 맞는 주문을 필터링한다.
     */
    private List<Order> getFilteredLimitOrderList(Long stockId, BigDecimal currentPrice) {
        List<Order> orders = getLimitOrderList(stockId);

        return orders.stream()
                .filter(order -> {
                    if (order.getSide() == OrderSide.BUY) {
                        return currentPrice.compareTo(order.getRequestPrice()) <= 0;
                    } else {
                        return currentPrice.compareTo(order.getRequestPrice()) >= 0;
                    }
                })
                .toList();
    }

    /**
     * PENDING/PARTIAL 지정가 주문을 비관적 쓰기 락으로 조회한다.
     *
     * <p>동시 WebSocket 틱에서 같은 주문을 이중 체결하는 TOCTOU 경쟁과,
     * 매칭 도중 유저의 취소/수정 경로(findByOrderNoForUpdate)가 개입하는
     * race condition을 차단하기 위해 FOR UPDATE 조회를 사용한다.
     */
    private List<Order> getLimitOrderList(Long stockId) {
        return orderRepository.findAllByStatusInAndOrderTypeAndStockIdForUpdate(
                List.of(OrderStatus.PENDING, OrderStatus.PARTIAL),
                OrderType.LIMIT,
                stockId
        );
    }



}
