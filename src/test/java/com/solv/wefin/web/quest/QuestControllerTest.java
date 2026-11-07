package com.solv.wefin.web.quest;

import com.solv.wefin.domain.auth.entity.User;
import com.solv.wefin.domain.quest.entity.DailyQuest;
import com.solv.wefin.domain.quest.entity.QuestCompleteType;
import com.solv.wefin.domain.quest.entity.QuestEventType;
import com.solv.wefin.domain.quest.entity.QuestStatus;
import com.solv.wefin.domain.quest.entity.QuestTemplate;
import com.solv.wefin.domain.quest.entity.UserQuest;
import com.solv.wefin.domain.quest.service.UserQuestService;
import com.solv.wefin.global.config.security.JwtProvider;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import com.solv.wefin.global.error.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QuestController.class)
@Import(GlobalExceptionHandler.class)
class QuestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserQuestService userQuestService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @Test
    @DisplayName("오늘의 퀘스트 목록을 반환한다")
    void getTodayQuests_success() throws Exception {
        // given
        UUID userId = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        QuestTemplate template = buildTemplate();
        DailyQuest dailyQuest = DailyQuest.create(template, today, 3, 100);
        ReflectionTestUtils.setField(dailyQuest, "id", 11L);

        User user = User.createNormalAccount("test@test.com", "questUser", "password");
        ReflectionTestUtils.setField(user, "userId", userId);

        UserQuest userQuest = UserQuest.assign(user, dailyQuest);
        ReflectionTestUtils.setField(userQuest, "id", 21L);
        ReflectionTestUtils.setField(userQuest, "status", QuestStatus.IN_PROGRESS);
        ReflectionTestUtils.setField(userQuest, "progress", 1);

        when(userQuestService.getOrIssueTodayUserQuests(userId)).thenReturn(List.of(userQuest));

        // when // then
        mockMvc.perform(get("/api/quests/today")
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                userId,
                                null,
                                AuthorityUtils.NO_AUTHORITIES
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.questDate").value(today.toString()))
                .andExpect(jsonPath("$.data.expiresAt").exists())
                .andExpect(jsonPath("$.data.quests[0].questId").value(21))
                .andExpect(jsonPath("$.data.quests[0].dailyQuestId").value(11))
                .andExpect(jsonPath("$.data.quests[0].expiresAt").exists())
                .andExpect(jsonPath("$.data.quests[0].code").value("SEND_GROUP_CHAT"))
                .andExpect(jsonPath("$.data.quests[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.quests[0].progress").value(1))
                .andExpect(jsonPath("$.data.quests[0].targetValue").value(3))
                .andExpect(jsonPath("$.data.quests[0].reward").value(100));
    }

    @Test
    @DisplayName("활성 템플릿이 부족하면 500 에러를 반환한다")
    void getTodayQuests_fail_when_template_not_enough() throws Exception {
        // given
        UUID userId = UUID.randomUUID();

        when(userQuestService.getOrIssueTodayUserQuests(userId))
                .thenThrow(new BusinessException(ErrorCode.QUEST_TEMPLATE_NOT_ENOUGH));

        // when // then
        mockMvc.perform(get("/api/quests/today")
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                userId,
                                null,
                                AuthorityUtils.NO_AUTHORITIES
                        ))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("QUEST_TEMPLATE_NOT_ENOUGH"));
    }

    @Test
    @DisplayName("퀘스트 보상 수령 요청 시 REWARDED 상태를 반환한다")
    void claimReward_success() throws Exception {
        // given
        UUID userId = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        QuestTemplate template = buildTemplate();
        DailyQuest dailyQuest = DailyQuest.create(template, today, 3, 100);
        ReflectionTestUtils.setField(dailyQuest, "id", 11L);

        User user = User.createNormalAccount("test@test.com", "questUser", "password");
        ReflectionTestUtils.setField(user, "userId", userId);

        UserQuest userQuest = UserQuest.assign(user, dailyQuest);
        ReflectionTestUtils.setField(userQuest, "id", 21L);
        ReflectionTestUtils.setField(userQuest, "status", QuestStatus.REWARDED);
        ReflectionTestUtils.setField(userQuest, "progress", 3);

        when(userQuestService.claimReward(userId, 21L)).thenReturn(userQuest);

        // when // then
        mockMvc.perform(post("/api/quests/21/reward")
                        .with(csrf())
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                userId,
                                null,
                                AuthorityUtils.NO_AUTHORITIES
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.questId").value(21))
                .andExpect(jsonPath("$.data.dailyQuestId").value(11))
                .andExpect(jsonPath("$.data.expiresAt").exists())
                .andExpect(jsonPath("$.data.status").value("REWARDED"))
                .andExpect(jsonPath("$.data.progress").value(3))
                .andExpect(jsonPath("$.data.reward").value(100));
    }

    @Test
    @DisplayName("보상 수령 대상 퀘스트가 없으면 404 에러를 반환한다")
    void claimReward_fail_when_user_quest_not_found() throws Exception {
        // given
        UUID userId = UUID.randomUUID();

        when(userQuestService.claimReward(userId, 21L))
                .thenThrow(new BusinessException(ErrorCode.USER_QUEST_NOT_FOUND));

        // when // then
        mockMvc.perform(post("/api/quests/21/reward")
                        .with(csrf())
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                userId,
                                null,
                                AuthorityUtils.NO_AUTHORITIES
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("USER_QUEST_NOT_FOUND"));
    }

    private QuestTemplate buildTemplate() {
        QuestTemplate template = mock(QuestTemplate.class);

        when(template.getId()).thenReturn(1L);
        when(template.getCode()).thenReturn("SEND_GROUP_CHAT");
        when(template.getTitle()).thenReturn("그룹 채팅 보내기");
        when(template.getDescription()).thenReturn("그룹 채팅 메시지를 보내보세요.");
        when(template.getCompleteType()).thenReturn(QuestCompleteType.COUNT);
        when(template.getEventType()).thenReturn(QuestEventType.SEND_GROUP_CHAT);
        when(template.getTargetValue()).thenReturn(3);
        when(template.getReward()).thenReturn(100);
        when(template.getRepeatable()).thenReturn(true);
        when(template.getActive()).thenReturn(true);

        return template;
    }
}
