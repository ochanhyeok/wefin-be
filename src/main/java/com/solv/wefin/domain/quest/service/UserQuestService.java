package com.solv.wefin.domain.quest.service;

import com.solv.wefin.domain.trading.ledger.entity.CashLedgerType;
import com.solv.wefin.domain.auth.entity.User;
import com.solv.wefin.domain.auth.repository.UserRepository;
import com.solv.wefin.domain.quest.entity.DailyQuest;
import com.solv.wefin.domain.quest.entity.UserQuest;
import com.solv.wefin.domain.quest.repository.UserQuestRepository;
import com.solv.wefin.domain.trading.account.entity.VirtualAccount;
import com.solv.wefin.domain.trading.account.service.VirtualAccountService;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class UserQuestService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final UserRepository userRepository;
    private final UserQuestRepository userQuestRepository;
    private final DailyQuestService dailyQuestService;
    private final VirtualAccountService virtualAccountService;

    public List<UserQuest> getOrIssueTodayUserQuests(UUID userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        LocalDate today = LocalDate.now(KST);

        List<UserQuest> todayUserQuests =
                userQuestRepository.findTodayUserQuests(userId, today);

        if (!todayUserQuests.isEmpty()) {
            return todayUserQuests;
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        List<DailyQuest> todayDailyQuests = dailyQuestService.getOrCreateTodayDailyQuests();

        List<UserQuest> userQuests = todayDailyQuests.stream()
                .map(dailyQuest -> UserQuest.assign(user, dailyQuest))
                .toList();

        try {
            return userQuestRepository.saveAllAndFlush(userQuests);
        } catch (DataIntegrityViolationException e) {
            return userQuestRepository.findTodayUserQuests(userId, today);
        }
    }

    @Transactional(readOnly = true)
    public List<UserQuest> getTodayUserQuests(UUID userId) {
        return userQuestRepository.findTodayUserQuests(userId, LocalDate.now(KST));
    }

    public UserQuest claimReward(UUID userId, Long questId) {
        UserQuest userQuest = userQuestRepository.findByIdAndUserIdForUpdate(questId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_QUEST_NOT_FOUND));

        if (!userQuest.getDailyQuest().getQuestDate().isEqual(LocalDate.now(KST))) {
            throw new BusinessException(ErrorCode.USER_QUEST_NOT_FOUND);
        }

        VirtualAccount account = virtualAccountService.getAccountByUserId(userId);
        BigDecimal rewardAmount = BigDecimal.valueOf(userQuest.getDailyQuest().getReward());

        virtualAccountService.depositBalance(account.getVirtualAccountId(), rewardAmount, CashLedgerType.QUEST_REWARD);
        userQuest.markRewarded();

        return userQuest;
    }
}
