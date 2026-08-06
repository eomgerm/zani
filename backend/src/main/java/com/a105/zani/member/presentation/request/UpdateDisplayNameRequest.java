package com.a105.zani.member.presentation.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.a105.zani.member.domain.model.Member;

@Schema(description = "표시 이름 변경 요청")
public record UpdateDisplayNameRequest(
        @Schema(description = "새 표시 이름. 앞뒤 공백은 다듬어 저장합니다.", example = "김강사")
        @NotBlank(message = "displayName 은 필수입니다.") @Size(max = Member.DISPLAY_NAME_MAX_LENGTH, message = "displayName 은 100자를 넘을 수 없습니다.") String displayName) {}
