package com.solv.wefin.domain.game.news.service;

import com.solv.wefin.domain.game.news.dto.BriefingInfo;
import com.solv.wefin.domain.game.news.entity.BriefingCache;
import com.solv.wefin.domain.game.news.repository.BriefingCacheRepository;
import com.solv.wefin.domain.game.openai.OpenAiBriefingClient.BriefingParts;
import com.solv.wefin.domain.game.participant.entity.ParticipantStatus;
import com.solv.wefin.domain.game.participant.repository.GameParticipantRepository;
import com.solv.wefin.domain.game.room.entity.GameRoom;
import com.solv.wefin.domain.game.room.repository.GameRoomRepository;
import com.solv.wefin.domain.game.turn.entity.GameTurn;
import com.solv.wefin.domain.game.turn.entity.TurnStatus;
import com.solv.wefin.domain.game.turn.repository.GameTurnRepository;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 게임 컨텍스트의 AI 브리핑 조회 서비스.
 * 방/참가자/활성 턴 검증 후 {@link BriefingService}에 위임한다.
 *
 * <p>{@code @Transactional} 미부착: 내부 BriefingService가 캐시 미스 시 DB write +
 * 외부 API 호출(~30초)을 수행하므로, 상위에서 트랜잭션으로 감싸면 long-running tx 위험.
 * 검증 쿼리 3건은 각 auto-tx로 충분.
 */
@Service
@RequiredArgsConstructor
public class GameBriefingService {

    private final GameRoomRepository gameRoomRepository;
    private final GameParticipantRepository gameParticipantRepository;
    private final GameTurnRepository gameTurnRepository;
    private final BriefingService briefingService;
    private final BriefingCacheRepository briefingCacheRepository;

    public BriefingInfo getBriefingForRoom(UUID roomId, UUID userId) {
        // 1. 게임방 확인
        GameRoom gameRoom = gameRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));

        // 2. 참가자 검증
        gameParticipantRepository.findByGameRoomAndUserId(gameRoom, userId)
                .filter(p -> p.getStatus() == ParticipantStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_PARTICIPANT));

        // 3. ACTIVE 턴 → 턴 날짜
        GameTurn activeTurn = gameTurnRepository.findByGameRoomAndStatus(gameRoom, TurnStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_STARTED));

        // 4. 기존 BriefingService에 위임 (캐시 히트/미스 처리)
        BriefingParts parts = briefingService.getBriefingForDate(activeTurn.getTurnDate());

        return new BriefingInfo(
                activeTurn.getTurnDate(),
                parts.marketOverview(),
                parts.keyIssues(),
                parts.investmentHint()
        );
    }

    /**
     * 당일 브리핑 + 과거 최대 14개 브리핑을 함께 반환한다.
     * 당일: 캐시 미스 시 크롤링+AI 생성 (기존 로직).
     * 과거: DB에 있는 것만 최신순 14개 조회. 크롤링하지 않는다.
     */
    public List<BriefingInfo> getBriefingsForRoom(UUID roomId, UUID userId) {
        // 당일 브리핑 (검증 + 크롤링 포함)
        BriefingInfo today = getBriefingForRoom(roomId, userId);

        // 과거 14개 — DB에 존재하는 것만
        List<BriefingCache> pastCaches =
                briefingCacheRepository.findTop14ByTargetDateBeforeOrderByTargetDateDesc(today.targetDate());

        List<BriefingInfo> result = new ArrayList<>(1 + pastCaches.size());
        result.add(today);
        for (BriefingCache cache : pastCaches) {
            result.add(new BriefingInfo(
                    cache.getTargetDate(),
                    cache.getMarketOverview(),
                    cache.getKeyIssues(),
                    cache.getInvestmentHint()
            ));
        }

        return result;
    }
}
