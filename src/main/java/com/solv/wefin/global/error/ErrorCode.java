package com.solv.wefin.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // User
    USER_NOT_FOUND(404, "사용자를 찾을 수 없습니다."),

    // Group
    GROUP_NOT_FOUND(404, "그룹을 찾을 수 없습니다."),
    GROUP_MEMBER_FORBIDDEN(403, "해당 그룹의 멤버만 조회할 수 있습니다."),
    GROUP_MEMBER_NOT_FOUND(404, "그룹 멤버를 찾을 수 없습니다."),
    GROUP_MEMBER_ALREADY_INACTIVE(400, "이미 비활성화된 그룹 멤버입니다."),
    GROUP_INVITE_FORBIDDEN(403, "해당 그룹의 초대 코드를 조회하거나 생성할 권한이 없습니다."),
    GROUP_INVITE_NOT_FOUND(404, "초대 코드를 찾을 수 없습니다."),
    GROUP_INVITE_EXPIRED(400, "만료된 초대 코드입니다."),
    GROUP_FULL(400, "그룹 인원이 가득 찼습니다."),
    GROUP_ALREADY_JOINED(409, "이미 참여한 그룹입니다."),
    GROUP_HOME_INVITE_NOT_ALLOWED(400, "홈 그룹에는 초대 코드를 생성할 수 없습니다."),
    GROUP_HOME_JOIN_NOT_ALLOWED(400, "홈 그룹에는 참여할 수 없습니다."),
    GROUP_HOME_LEAVE_NOT_ALLOWED(400, "홈 그룹은 탈퇴할 수 없습니다."),
    GROUP_HOME_MEMBERSHIP_NOT_FOUND(404, "홈 그룹 멤버십을 찾을 수 없습니다."),
    GROUP_CREATE_REQUIRES_HOME(400, "단체 그룹을 생성하려면 기존 그룹에서 탈퇴해야 합니다."),
    GROUP_LEADER_TRANSFER_FAILED(500, "리더 권한 위임에 실패했습니다."),

    // Chat
    CHAT_MESSAGE_EMPTY(400, "메시지 내용은 비어 있을 수 없습니다."),
    CHAT_MESSAGE_TOO_LONG(400, "메시지는 1000자를 초과할 수 없습니다."),
    CHAT_SPAM_DETECTED(429, "메시지를 너무 빠르게 전송하고 있습니다."),
    INVALID_PROFIT_AMOUNT(400, "손익 금액은 0이 될 수 없습니다."),
    CHAT_MESSAGE_NOT_FOUND(404, "채팅 메시지를 찾을 수 없습니다."),
    AI_CHAT_REQUEST_FAILED(503, "AI 응답 생성에 실패했습니다."),
    AI_CHAT_TIMEOUT(504, "AI 응답 시간이 초과되었습니다."),
    NEWS_CLUSTER_NOT_FOUND(404, "뉴스 기사를 찾을 수 없습니다."),

    // quest
    QUEST_TEMPLATE_NOT_ENOUGH(500, "활성 퀘스트 템플릿 수가 부족합니다."),
    DAILY_QUEST_NOT_FOUND(404, "오늘의 퀘스트를 찾을 수 없습니다."),
    QUEST_PROGRESS_INVALID(400, "퀘스트 진행도는 0 이상이어야 합니다."),
    QUEST_REWARD_NOT_ALLOWED(400, "완료된 퀘스트만 보상 처리할 수 있습니다."),
    QUEST_TARGET_VALUE_INVALID(400, "퀘스트 목표치는 1 이상이어야 합니다."),
    QUEST_REWARD_INVALID(400, "퀘스트 보상은 0 이상이어야 합니다."),
    USER_QUEST_NOT_FOUND(404, "사용자 퀘스트를 찾을 수 없습니다."),

    // Common
    INVALID_INPUT(400, "잘못된 입력입니다."),
    DUPLICATE_RESOURCE(409, "중복된 리소스입니다."),
    INTERNAL_SERVER_ERROR(500, "서버 내부 오류가 발생했습니다."),

    // Admin
    ADMIN_FORBIDDEN(403, "관리자 권한이 필요합니다."),

    // Auth - SIGNUP
    AUTH_EMAIL_DUPLICATED(409, "이미 사용 중인 이메일입니다."),
    AUTH_NICKNAME_DUPLICATED(409, "이미 사용 중인 닉네임입니다."),
    AUTH_VALIDATION_FAILED(400, "잘못된 입력입니다."),
    AUTH_EMAIL_NOT_VERIFIED(400, "이메일 인증이 완료되지 않았습니다."),
    AUTH_VERIFICATION_CODE_INVALID(400, "인증코드가 올바르지 않습니다."),
    AUTH_VERIFICATION_CODE_EXPIRED(400, "인증코드가 만료되었습니다."),
    AUTH_EMAIL_SEND_FAILED(500, "인증 메일 발송에 실패했습니다."),
    AUTH_VERIFICATION_TOO_FAST_REQUEST(400, "인증코드 재발송 요청이 너무 빠릅니다."),
    AUTH_VERIFICATION_TOO_MANY_REQUESTS(400, "인증코드 재발송 횟수를 초과했습니다."),
    AUTH_VERIFICATION_TOO_MANY_ATTEMPTS(400, "인증 시도 횟수를 초과했습니다. 잠시 후 다시 시도해주세요."),
    AUTH_VERIFICATION_CONCURRENT_REQUEST(409, "동시에 여러 인증 요청이 처리되어 다시 시도해주세요."),

    // Auth - LOGIN
    AUTH_LOGIN_FAILED(401, "이메일 또는 비밀번호가 올바르지 않습니다."),
    ACCOUNT_LOCKED(423, "계정이 잠금 상태입니다."),
    AUTH_INVALID_TOKEN(401, "유효하지 않은 인증 토큰입니다."),
    AUTH_UNAUTHORIZED(401, "인증이 필요합니다."),

    // Auth - PASSWORD
    AUTH_PASSWORD_MISMATCH(400, "현재 비밀번호가 일치하지 않습니다."),
    AUTH_PASSWORD_SAME_AS_OLD(400, "새 비밀번호는 기존 비밀번호와 달라야 합니다."),

    // Payment
    PLAN_NOT_FOUND(404, "구독 상품을 찾을 수 없습니다."),
    PLAN_INACTIVE(400, "비활성화된 구독 상품입니다."),
    ACTIVE_SUBSCRIPTION_ALREADY_EXISTS(409, "이미 활성 구독이 존재합니다."),
    ACTIVE_SUBSCRIPTION_NOT_FOUND(404, "활성 구독 정보를 찾을 수 없습니다."),
    INVALID_PROVIDER(400, "지원하지 않는 결제사입니다."),
    PAYMENT_NOT_FOUND(404, "결제 정보를 찾을 수 없습니다."),
    PAYMENT_OWNERSHIP_MISMATCH(403, "본인의 결제만 처리할 수 있습니다."),
    PAYMENT_NOT_READY(400, "승인 가능한 결제 상태가 아닙니다."),
    PAYMENT_AMOUNT_MISMATCH(400, "결제 금액이 일치하지 않습니다."),
    PAYMENT_CONFIRM_FAILED(502, "토스 결제 승인에 실패했습니다."),
    PAYMENT_ALREADY_CONFIRMED(409, "이미 승인된 결제입니다."),
    PAYMENT_CANCELED(400, "사용자가 결제를 취소했습니다."),
    PAYMENT_CANCEL_FAILED(502, "토스 결제 취소에 실패했습니다."),
    PAYMENT_CONFIRM_TIMEOUT(504, "토스 결제 승인 요청 시간이 초과되었습니다."),
    PAYMENT_CONFIRM_BAD_REQUEST(400, "토스 결제 승인 요청이 올바르지 않습니다."),
    PAYMENT_CONFIRM_UNAUTHORIZED(502, "토스 결제 승인 인증에 실패했습니다."),
    PAYMENT_CANCEL_TIMEOUT(504, "토스 결제 취소 요청 시간이 초과되었습니다."),

    // Order - BUY
    ORDER_INSUFFICIENT_BALANCE(400, "예수금이 부족합니다."),
    ORDER_INVALID_QUANTITY(400, "주문 수량은 1 이상이어야 합니다."),
    ORDER_STOCK_NOT_FOUND(400, "존재하지 않는 종목입니다."),
    ORDER_MARKET_CLOSED(400, "장 마감 시간에는 주문할 수 없습니다."),
    ORDER_INVALID_AMOUNT(400, "금액은 0보다 커야 합니다."),
    ORDER_DUPLICATE_REQUEST(409, "이미 처리 중이거나 처리된 주문 요청입니다."),

    // Order - SELL
    ORDER_INSUFFICIENT_HOLDINGS(400, "보유 수량이 부족합니다."),
    ORDER_STOCK_NOT_HELD(400, "해당 종목을 보유하고 있지 않습니다."),

    // Order - MODIFY/CANCEL
    ORDER_ALREADY_FILLED(400, "이미 체결된 주문입니다."),
    ORDER_NOT_FOUND(404, "주문을 찾을 수 없습니다."),
    ORDER_ALREADY_CANCELLED(400, "이미 취소된 주문입니다."),
    ORDER_NOT_MODIFIABLE(400, "수정 가능한 상태가 아닙니다."),
    ORDER_PARTIAL_NOT_MODIFIABLE(400, "부분 체결된 주문은 수정할 수 없습니다. 취소 후 재주문해주세요."),
    ORDER_OWNERSHIP_MISMATCH(403, "소유권이 일치하지 않습니다."),
    ORDER_NOT_CANCELLABLE(400, "주문을 취소할 수 없습니다."),

    // Account
    ACCOUNT_NOT_FOUND(404, "계좌를 찾을 수 없습니다."),
    ACCOUNT_ALREADY_EXISTS(400, "이미 계좌가 존재합니다."),

    // Market
    MARKET_STOCK_NOT_FOUND(404, "종목을 찾을 수 없습니다."),
    MARKET_API_FAILED(503, "한투 API 호출에 실패했습니다."),
    MARKET_INVALID_PERIOD_CODE(400, "유효하지 않은 기간 옵션입니다."),
    MARKET_INVALID_DATE(400, "조회 시작일자는 종료일자 이전이어야 합니다."),
    MARKET_SUBSCRIPTION_LIMIT_EXCEEDED(400, "실시간 구독 종목은 최대 20개까지 가능합니다."),
    MARKET_INVALID_REQUEST(400, "유효하지 않은 랭킹 유형입니다."),
    MARKET_INVALID_INTERVAL(400, "유효하지 않은 분봉 간격입니다. (1m, 5m, 15m 만 지원)"),

    // Interest
    INTEREST_ALREADY_EXISTS(400, "이미 등록된 관심사입니다."),
    INTEREST_LIMIT_EXCEEDED(400, "관심사는 타입별로 최대 10개까지 등록할 수 있습니다."),
    INTEREST_TAG_NOT_FOUND(404, "등록할 수 없는 관심사입니다."),

    // Embedding
    EMBEDDING_ARTICLE_NOT_FOUND(500, "임베딩 대상 기사를 찾을 수 없습니다."),

    // Tagging
    TAGGING_ARTICLE_NOT_FOUND(500, "태깅 대상 기사를 찾을 수 없습니다."),
    TAGGING_ALREADY_RUNNING(409, "태깅 생성이 이미 실행 중입니다."),

    // Clustering
    CLUSTERING_ALREADY_RUNNING(409, "클러스터링이 이미 실행 중입니다."),
    CLUSTERING_ARTICLE_NOT_FOUND(500, "클러스터링 대상 기사를 찾을 수 없습니다."),
    CLUSTERING_NO_EMBEDDING(500, "기사의 임베딩이 존재하지 않습니다."),

    // Summary
    SUMMARY_ALREADY_RUNNING(409, "요약 생성이 이미 실행 중입니다."),
    SUMMARY_CLUSTER_NOT_FOUND(500, "요약 대상 클러스터를 찾을 수 없습니다."),
    SUMMARY_EMPTY_RESULT(500, "AI 요약 결과가 비어있습니다."),
    SUMMARY_NO_SECTIONS(500, "AI 요약에 섹션이 포함되지 않았습니다."),
    SUMMARY_NO_VALID_SECTIONS(500, "AI 요약에 유효한 출처를 가진 섹션이 없습니다."),
    SUMMARY_ARTICLE_MISMATCH(500, "프롬프트 기사 수와 실제 조회 기사 수가 불일치합니다."),
    SUGGESTED_QUESTION_CLUSTER_ID_NULL(400, "추천 질문의 클러스터 ID는 null일 수 없습니다."),
    SUGGESTED_QUESTION_ORDER_INVALID(400, "추천 질문의 순서는 0 이상이어야 합니다."),
    SUGGESTED_QUESTION_TEXT_BLANK(400, "추천 질문 텍스트는 비어 있을 수 없습니다."),
    CLUSTER_NOT_FOUND(404, "클러스터를 찾을 수 없습니다."),

    // Feed Filter
    FEED_TAG_PARAMS_INCOMPLETE(400, "tagType과 tagCodes는 반드시 함께 제공해야 합니다."),
    FEED_TAG_TYPE_UNSUPPORTED(400, "지원하지 않는 tagType입니다."),
    FEED_TAG_AND_TAB_CONFLICT(400, "tab과 tagType/tagCodes는 동시에 사용할 수 없습니다."),
    FEED_TAG_CODES_EMPTY(400, "유효한 tagCodes가 없습니다."),
    FEED_SORT_UNSUPPORTED(400, "지원하지 않는 정렬 기준입니다."),
    TAG_TYPE_UNSUPPORTED(400, "지원하지 않는 태그 유형입니다."),
    TAG_LIMIT_INVALID(400, "limit은 0 이상이어야 합니다."),

    // GameRoom
    ROOM_NOT_FOUND(404, "게임장을 찾을 수 없습니다."),
    ROOM_ALREADY_EXISTS(409, "이미 진행 중이거나 대기 중인 게임방이 있습니다."),
    ROOM_HOST_ALREADY_EXISTS(409, "이미 방장으로 참여 중인 게임방이 있습니다."),
    ROOM_HOST_DAILY_LIMIT(409, "하루 방 생성 가능 횟수 초과"),
    ROOM_ALREADY_JOINED(409, "이미 참가 중인 방입니다."),
    ROOM_FULL(400, "인원 초과"),
    ROOM_FINISHED(400, "종료된 방입니다."),
    ROOM_NOT_PARTICIPANT(404, "참가자가 아닙니다."),
    ROOM_NOT_HOST(403, "방장만 게임을 시작할 수 있습니다."),
    ROOM_MIN_PLAYERS(400, "2명 이상의 참가자가 필요합니다."),
    ROOM_NOT_WAITING(400, "대기 상태가 아닙니다."),

    // GameTurn
    GAME_NOT_STARTED(400, "게임이 시작되지 않았습니다."),
    GAME_ALREADY_FINISHED(400, "이미 종료된 게임입니다."),
    GAME_NOT_FINISHED(400, "게임이 아직 종료되지 않았습니다."),

    // GameParticipant - 개별 종료
    PARTICIPANT_ALREADY_FINISHED(400, "이미 게임을 종료한 참가자입니다."),
    PARTICIPANT_NOT_FINISHED(400, "아직 게임을 종료하지 않은 참가자입니다."),

    // GameAnalysisReport
    ANALYSIS_GENERATION_FAILED(503, "AI 분석 리포트 생성에 실패했습니다."),

    // GameStock
    GAME_STOCK_NOT_FOUND(404, "해당 종목을 찾을 수 없습니다."),
    GAME_STOCK_PRICE_NOT_FOUND(404, "해당 날짜의 주가 데이터가 없습니다."),

    // Vote
    VOTE_ALREADY_CAST(409, "이미 투표하였습니다."),
    VOTE_NOT_IN_PROGRESS(400, "진행 중인 투표가 없습니다."),
    VOTE_ALREADY_IN_PROGRESS(409, "이미 투표가 진행 중입니다."),
    VOTE_NOT_FOUND(404, "투표를 찾을 수 없습니다."),
    VOTE_CLOSED(409, "이미 마감된 투표입니다."),
    VOTE_OPTION_INVALID(400, "유효하지 않은 투표 선택지입니다."),
    VOTE_MAX_SELECT_EXCEEDED(400, "선택 가능한 개수를 초과했습니다."),

    // MarketTrend
    MARKET_TREND_ALREADY_RUNNING(409, "금융 동향 생성이 이미 실행 중입니다."),

    // DART
    DART_CORP_CODE_NOT_FOUND(404, "DART 고유번호를 찾을 수 없습니다."),
    DART_CORP_CODE_FETCH_FAILED(503, "DART 고유번호 조회에 실패했습니다."),
    DART_COMPANY_NOT_FOUND(404, "DART 기업 정보가 존재하지 않습니다."),
    DART_COMPANY_FETCH_FAILED(503, "DART 기업 정보 조회에 실패했습니다."),
    DART_FINANCIAL_NOT_FOUND(404, "DART 재무제표가 존재하지 않습니다."),
    DART_FINANCIAL_FETCH_FAILED(503, "DART 재무제표 조회에 실패했습니다."),
    DART_DIVIDEND_NOT_FOUND(404, "DART 배당 정보가 존재하지 않습니다."),
    DART_DIVIDEND_FETCH_FAILED(503, "DART 배당 정보 조회에 실패했습니다."),
    DART_DISCLOSURE_FETCH_FAILED(503, "DART 공시 조회에 실패했습니다."),

    // Stock News
    STOCK_NEWS_FETCH_FAILED(503, "종목 뉴스 조회에 실패했습니다."),

    // Recommendation
    RECOMMENDATION_REFRESH_LIMIT_EXCEEDED(429, "하루에 더 불러오기는 5회까지 가능합니다.");

    private final int status;
    private final String message;
}
