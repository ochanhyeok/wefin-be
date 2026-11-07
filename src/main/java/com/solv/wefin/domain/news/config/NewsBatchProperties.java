package com.solv.wefin.domain.news.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 뉴스 파이프라인 배치 1회당 처리 건수 설정
 *
 * 각 배치의 페이지 크기를 한 곳에서 관리하기 위해 도입되었다.
 * 운영 중 트래픽/OpenAI 비용에 따라 환경변수에서 조정할 수 있도록 외부화한다.
 *
 * 상한은 운영 중 오설정(공란/음수/과대값)으로 인한 런타임 장애와 비용 폭주를 막기 위한
 * 방어 한계선이다. 상한 자체가 정상 운영 기준치를 뜻하지는 않는다.
 *
 * @param crawlSize       크롤링 배치 1회당 처리 기사 수
 * @param embeddingSize   임베딩 배치 1회당 처리 기사 수.
 *                        OpenAI embedding batch 단일 호출 2048 한도 대비 보수적 상한
 * @param taggingSize     태깅 배치 1회당 처리 기사 수.
 *                        OpenAI chat API TPM(600K) 대비 1000건 × 평균 300토큰 ≈ 300K 기준 상한
 * @param clusteringSize  클러스터링 배치 1회당 처리 기사 수
 * @param summarySize       요약 배치 1회당 처리 클러스터 수.
 *                          OpenAI chat API 호출 비용 방어 (클러스터당 기사 N건 합산)
 * @param rejudgeMaxLimit   관련성 재판단 수동 실행 시 허용 상한 (admin endpoint 보호용)
 * @param rejudgeBatchSize  관련성 재판단 cron 1회당 처리 기사 수.
 *                          태깅 retry 한도 초과 등으로 PENDING으로 남은 기사를 정기 구제하는 용도
 */
@Validated
@ConfigurationProperties(prefix = "batch.news")
public record NewsBatchProperties(
        @Min(1) @Max(5000) int crawlSize,
        @Min(1) @Max(1000) int embeddingSize,
        @Min(1) @Max(1000) int taggingSize,
        @Min(1) @Max(5000) int clusteringSize,
        @Min(1) @Max(200) int summarySize,
        @Min(1) @Max(2000) int rejudgeMaxLimit,
        @Min(1) @Max(2000) int rejudgeBatchSize
) {
}
