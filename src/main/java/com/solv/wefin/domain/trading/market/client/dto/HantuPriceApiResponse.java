package com.solv.wefin.domain.trading.market.client.dto;

public record HantuPriceApiResponse(
        String rt_cd,          // 성공 "0". 업무 오류도 HTTP 200 으로 온다
        String msg_cd,
        String msg1,
        Output output
) {
    public record Output(
            String stck_prpr,      // 현재가
            String prdy_vrss,      // 전일 대비
            String prdy_vrss_sign, // 전일 대비 부호
            String prdy_ctrt,      // 전일 대비율
            String acml_vol,       // 누적 거래량
            String acml_tr_pbmn,   // 누적 거래대금
            String stck_oprc,      // 시가
            String stck_hgpr,      // 최고가
            String stck_lwpr,      // 최저가
            String stck_mxpr,      // 상한가
            String stck_llam,      // 하한가
            String per,            // PER
            String pbr,            // PBR
            String hts_avls        // 시가총액
    ) {}
}
