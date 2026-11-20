package com.solv.wefin.domain.trading.account.service;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.solv.wefin.domain.trading.account.entity.VirtualAccount;
import com.solv.wefin.domain.trading.account.repository.VirtualAccountRepository;
import com.solv.wefin.global.error.BusinessException;

@ExtendWith(MockitoExtension.class)
class VirtualAccountServiceTest {

	@Mock
	private VirtualAccountRepository accountRepository;

	@InjectMocks
	private VirtualAccountService accountService;

	@Test
	void 계좌_생성_정상() {
		// given
		UUID userId = UUID.randomUUID();
		when(accountRepository.findByUserId(userId)).thenReturn(Optional.empty());
		when(accountRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		// when
		VirtualAccount account = accountService.createAccount(userId);

		// then
		assertThat(account.getUserId()).isEqualTo(userId);
		assertThat(account.getBalance()).isEqualTo(new BigDecimal("10000000"));
	}

	@Test
	void 차감_정상() {
		// given — 원자적 조건부 UPDATE 가 1행 갱신(차감 성공) 후 갱신된 계좌를 재조회
		VirtualAccount account = new VirtualAccount(UUID.randomUUID());
		when(accountRepository.deductBalanceIfEnough(1L, new BigDecimal("1200000"))).thenReturn(1);
		when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

		// when
		VirtualAccount result = accountService.deductBalance(1L, new BigDecimal("1200000"));

		// then — 실제 차감은 DB(원자적 UPDATE)가 수행, 서비스는 조건부 UPDATE 호출 + 재조회 반환을 보장
		assertThat(result).isSameAs(account);
		verify(accountRepository).deductBalanceIfEnough(1L, new BigDecimal("1200000"));
	}

	@Test
	void 잔고_부족_시_예외() {
		// given — 0행 갱신(잔고 부족), 계좌는 존재
		when(accountRepository.deductBalanceIfEnough(1L, new BigDecimal("12000000"))).thenReturn(0);
		when(accountRepository.existsById(1L)).thenReturn(true);

		// when & then
		assertThatThrownBy(() -> accountService.deductBalance(1L, new BigDecimal("12000000")))
			.isInstanceOf(BusinessException.class);
	}

	@Test
	void 입금_정상() {
		// given
		VirtualAccount account = new VirtualAccount(UUID.randomUUID());
		when(accountRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(account));

		// when
		accountService.depositBalance(1L, new BigDecimal("120000"));

		// then
		assertThat(account.getBalance()).isEqualTo(new BigDecimal("10120000"));
	}
}