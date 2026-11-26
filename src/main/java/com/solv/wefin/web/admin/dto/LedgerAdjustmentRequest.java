package com.solv.wefin.web.admin.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * @param amount         입금 +, 출금 -. 0은 받지 않는다(서비스에서 거절)
 * @param applyToBalance true 면 잔고도 같이 바꾸고, false 면 원장에만 남긴다
 * @param memo           조정 사유. 원장에 그대로 남는다
 */
public record LedgerAdjustmentRequest(
	@NotNull @Positive Long virtualAccountId,
	@NotNull @Digits(integer = 13, fraction = 2) BigDecimal amount,
	boolean applyToBalance,
	@NotBlank @Size(max = 500) String memo
) {
}
