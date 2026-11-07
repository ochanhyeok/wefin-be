package com.solv.wefin.web.game.briefing;

import com.solv.wefin.domain.game.news.dto.BriefingInfo;
import com.solv.wefin.domain.game.news.service.GameBriefingService;
import com.solv.wefin.global.common.ApiResponse;
import com.solv.wefin.web.game.briefing.dto.response.BriefingResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rooms/{roomId}")
public class BriefingController {

    private final GameBriefingService gameBriefingService;

    @GetMapping("/briefing")
    public ResponseEntity<ApiResponse<BriefingResponse>> getBriefing(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID roomId) {

        BriefingInfo info = gameBriefingService.getBriefingForRoom(roomId, userId);
        BriefingResponse response = BriefingResponse.from(info);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/briefings")
    public ResponseEntity<ApiResponse<List<BriefingResponse>>> getBriefings(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID roomId) {

        List<BriefingInfo> infos = gameBriefingService.getBriefingsForRoom(roomId, userId);
        List<BriefingResponse> responses = infos.stream()
                .map(BriefingResponse::from)
                .toList();

        return ResponseEntity.ok(ApiResponse.success(responses));
    }
}
