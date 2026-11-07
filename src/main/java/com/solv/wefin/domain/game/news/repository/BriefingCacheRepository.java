package com.solv.wefin.domain.game.news.repository;

import com.solv.wefin.domain.game.news.entity.BriefingCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface BriefingCacheRepository extends JpaRepository<BriefingCache, UUID> {

    Optional<BriefingCache> findByTargetDate(LocalDate targetDate);

    List<BriefingCache> findByTargetDateIn(Collection<LocalDate> targetDates);

    List<BriefingCache> findTop14ByTargetDateBeforeOrderByTargetDateDesc(LocalDate date);

    @Query("SELECT bc.targetDate " +
            "FROM BriefingCache bc " +
            "WHERE bc.targetDate IN :dates")
    Set<LocalDate> findExistingDates(@Param("dates") List<LocalDate> dates);

    @Query("SELECT bc.targetDate FROM BriefingCache bc WHERE bc.targetDate BETWEEN :from AND :to")
    Set<LocalDate> findExistingDatesBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

}
