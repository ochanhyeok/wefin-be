package com.solv.wefin.domain.trading.account.service;

import static com.solv.wefin.domain.trading.common.TradingConstants.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.solv.wefin.domain.trading.account.entity.VirtualAccount;
import com.solv.wefin.domain.trading.account.repository.VirtualAccountRepository;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VirtualAccountService {

	private final VirtualAccountRepository accountRepository;

	/**
	 * 계좌 생성
	 */
	@Transactional
	public VirtualAccount createAccount(UUID userId) {
		Optional<VirtualAccount> account = accountRepository.findByUserId(userId);
		if (account.isPresent()) {
			throw new BusinessException(ErrorCode.ACCOUNT_ALREADY_EXISTS);
		}

		try {
			return accountRepository.save(new VirtualAccount(userId));
		} catch (DataIntegrityViolationException e) {
			throw new BusinessException(ErrorCode.ACCOUNT_ALREADY_EXISTS);
		}
	}

	/**
	 * userId로 계좌 조회
	 */
	public VirtualAccount getAccountByUserId(UUID userId) {
		return accountRepository.findByUserId(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
	}

	/**
	 * 주문 가능 수량 조회
	 */
	public Integer calculateBuyingPower(Long virtualAccountId, BigDecimal price) {
		VirtualAccount account = accountRepository.findById(virtualAccountId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
		BigDecimal priceWithFee = price.multiply(BigDecimal.ONE.add(FEE_RATE));
		return account.getBalance().divide(priceWithFee, 0, RoundingMode.DOWN).intValue();
	}

	/**
	 * 잔고 차감 — 원자적 조건부 UPDATE.
	 *
	 * <p>기존엔 비관적 락으로 읽어(check) 엔티티에서 검증 후 차감(act)했으나, 이 check-then-act 는
	 * 읽은 값이 쓰기 시점엔 stale 이라 동시 매수 시 Lost Update 위험이 있었다. 3-way 벤치마크
	 * (비관적/낙관적/원자적) 결과 원자적 조건부 UPDATE 가 전 경합도 최고 처리량이라, 단순 잔고 가드인
	 * 이 경로를 원자적으로 전환했다. (deposit/포트폴리오 등 다단계 경로는 비관적 락 유지.)</p>
	 */
	@Transactional
	public VirtualAccount deductBalance(Long virtualAccountId, BigDecimal amount) {
		if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
			throw new BusinessException(ErrorCode.ORDER_INVALID_AMOUNT);
		}

		int updated = accountRepository.deductBalanceIfEnough(virtualAccountId, amount);
		if (updated == 0) {
			// 0행 = 계좌 없음 or 잔고 부족 → 원인을 구분해 정확한 예외를 던진다 (원자적 UPDATE 의 트레이드오프)
			boolean exists = accountRepository.existsById(virtualAccountId);
			throw new BusinessException(
				exists ? ErrorCode.ORDER_INSUFFICIENT_BALANCE : ErrorCode.ACCOUNT_NOT_FOUND);
		}

		return getAccount(virtualAccountId);
	}

	/**
	 * 잔고 입금
	 */
	@Transactional
	public VirtualAccount depositBalance(Long virtualAccountId, BigDecimal amount) {
		VirtualAccount account = getAccountForUpdate(virtualAccountId);

		account.deposit(amount);
		return account;
	}

	/**
	 * 실현손익 누적
	 */
	@Transactional
	public VirtualAccount addRealizedProfit(Long virtualAccountId, BigDecimal realizedProfit) {
		VirtualAccount account = getAccountForUpdate(virtualAccountId);

		account.addProfit(realizedProfit);
		return account;
	}

	/**
	 * userId로 계좌 조회 (없으면 Optional.empty)
	 */
	public Optional<VirtualAccount> findByUserId(UUID userId) {
		return accountRepository.findByUserId(userId);
	}

	/**
	 * 전체 유저의 계좌 조회
	 */
	public List<VirtualAccount> getAllAccounts() {
		return accountRepository.findAll();
	}

	/**
	 * 여러 계좌를 ID 일괄 조회 (랭킹 닉네임 매핑 등 배치 용도)
	 */
	public List<VirtualAccount> findAllByIdIn(Collection<Long> virtualAccountIds) {
		return accountRepository.findAllById(virtualAccountIds);
	}

	/**
	 * 비관적 락으로 계좌 조회
	 */
	private VirtualAccount getAccountForUpdate(Long virtualAccountId) {
		return accountRepository.findByIdForUpdate(virtualAccountId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
	}

	/**
	 * 락을 잡고 계좌 조회 (락 순서 보장용)
	 */
	public VirtualAccount getAccountWithLock(Long virtualAccountId) {
		return getAccountForUpdate(virtualAccountId);
	}

	/**
	 * 단건 조회 (락 없음)
	 */
	public VirtualAccount getAccount(Long virtualAccountId) {
		return accountRepository.findById(virtualAccountId)
				.orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
	}
}
