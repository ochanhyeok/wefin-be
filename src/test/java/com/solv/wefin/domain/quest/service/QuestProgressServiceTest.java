package com.solv.wefin.domain.quest.service;

import com.solv.wefin.domain.auth.entity.User;
import com.solv.wefin.domain.quest.entity.DailyQuest;
import com.solv.wefin.domain.quest.entity.QuestCompleteType;
import com.solv.wefin.domain.quest.entity.QuestEventType;
import com.solv.wefin.domain.quest.entity.QuestStatus;
import com.solv.wefin.domain.quest.entity.QuestTemplate;
import com.solv.wefin.domain.quest.entity.UserQuest;
import com.solv.wefin.domain.quest.repository.UserQuestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestProgressServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private UserQuestRepository userQuestRepository;
    private QuestProgressService questProgressService;

    @BeforeEach
    void setUp() {
        userQuestRepository = mock(UserQuestRepository.class);
        questProgressService = new QuestProgressService(userQuestRepository);
    }

    @Test
    @DisplayName("이벤트 타입이 일치하는 퀘스트의 진행도를 증가시키고 목표 달성 시 완료 처리한다")
    void handleEvent_updates_matching_user_quest() {
        // given
        UUID userId = UUID.randomUUID();
        UserQuest matchingQuest = createUserQuest(userId, QuestEventType.SEND_GROUP_CHAT, QuestCompleteType.COUNT, 3, 2);
        UserQuest nonMatchingQuest = createUserQuest(userId, QuestEventType.SHARE_NEWS, QuestCompleteType.COUNT, 1, 0);

        when(userQuestRepository.findTodayUserQuestsForUpdate(eq(userId), any(LocalDate.class)))
                .thenReturn(List.of(matchingQuest, nonMatchingQuest));

        // when
        questProgressService.handleEvent(userId, QuestEventType.SEND_GROUP_CHAT);

        // then
        assertEquals(3, matchingQuest.getProgress());
        assertEquals(QuestStatus.COMPLETED, matchingQuest.getStatus());
        assertEquals(0, nonMatchingQuest.getProgress());
        assertEquals(QuestStatus.NOT_STARTED, nonMatchingQuest.getStatus());
        verify(userQuestRepository).findTodayUserQuestsForUpdate(eq(userId), any(LocalDate.class));
    }

    @Test
    @DisplayName("수익률 퀘스트는 현재 수익률 정수값으로 진행도를 반영하고 완료 처리한다")
    void handleProfitRate_updates_percent_quest() {
        // given
        UUID userId = UUID.randomUUID();
        UserQuest profitRateQuest = createUserQuest(userId, QuestEventType.CHECK_PROFIT_RATE, QuestCompleteType.PERCENT, 5, 0);
        UserQuest countQuest = createUserQuest(userId, QuestEventType.CHECK_PROFIT_RATE, QuestCompleteType.COUNT, 3, 0);

        when(userQuestRepository.findTodayUserQuestsForUpdate(eq(userId), any(LocalDate.class)))
                .thenReturn(List.of(profitRateQuest, countQuest));

        // when
        questProgressService.handleProfitRate(userId, new BigDecimal("5.8"));

        // then
        assertEquals(5, profitRateQuest.getProgress());
        assertEquals(QuestStatus.COMPLETED, profitRateQuest.getStatus());
        assertEquals(0, countQuest.getProgress());
        assertEquals(QuestStatus.NOT_STARTED, countQuest.getStatus());
        verify(userQuestRepository).findTodayUserQuestsForUpdate(eq(userId), any(LocalDate.class));
    }

    @Test
    @DisplayName("게임 순위가 목표 이내면 진행도를 기록하고 완료 처리한다")
    void handleGameRank_completes_when_rank_is_within_target() {
        // given
        UUID userId = UUID.randomUUID();
        UserQuest rankQuest = createUserQuest(userId, QuestEventType.CHECK_GAME_RANK, QuestCompleteType.COUNT, 3, 0);

        when(userQuestRepository.findTodayUserQuestsForUpdate(eq(userId), any(LocalDate.class)))
                .thenReturn(List.of(rankQuest));

        // when
        questProgressService.handleGameRank(userId, 2);

        // then
        assertEquals(2, rankQuest.getProgress());
        assertEquals(QuestStatus.COMPLETED, rankQuest.getStatus());
        verify(userQuestRepository).findTodayUserQuestsForUpdate(eq(userId), any(LocalDate.class));
    }

    @Test
    @DisplayName("게임 순위가 목표 밖이면 현재 순위만 반영하고 완료하지 않는다")
    void handleGameRank_updates_progress_when_rank_is_outside_target() {
        // given
        UUID userId = UUID.randomUUID();
        UserQuest rankQuest = createUserQuest(userId, QuestEventType.CHECK_GAME_RANK, QuestCompleteType.COUNT, 3, 0);

        when(userQuestRepository.findTodayUserQuestsForUpdate(eq(userId), any(LocalDate.class)))
                .thenReturn(List.of(rankQuest));

        // when
        questProgressService.handleGameRank(userId, 5);

        // then
        assertEquals(5, rankQuest.getProgress());
        assertEquals(QuestStatus.IN_PROGRESS, rankQuest.getStatus());
        verify(userQuestRepository).findTodayUserQuestsForUpdate(eq(userId), any(LocalDate.class));
    }

    private UserQuest createUserQuest(
            UUID userId,
            QuestEventType eventType,
            QuestCompleteType completeType,
            int targetValue,
            int progress
    ) {
        User user = User.createNormalAccount("test@test.com", "quest-user", "password");
        ReflectionTestUtils.setField(user, "userId", userId);

        QuestTemplate template = mock(QuestTemplate.class);
        when(template.getEventType()).thenReturn(eventType);
        when(template.getCompleteType()).thenReturn(completeType);
        when(template.getTargetValue()).thenReturn(targetValue);
        when(template.getReward()).thenReturn(100_000);

        DailyQuest dailyQuest = DailyQuest.create(template, LocalDate.now(KST), targetValue, 100_000);
        UserQuest userQuest = UserQuest.assign(user, dailyQuest);
        ReflectionTestUtils.setField(userQuest, "progress", progress);

        if (progress > 0) {
            ReflectionTestUtils.setField(userQuest, "status", QuestStatus.IN_PROGRESS);
        }

        return userQuest;
    }
}
