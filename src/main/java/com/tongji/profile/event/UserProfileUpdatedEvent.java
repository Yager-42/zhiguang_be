package com.tongji.profile.event;

import com.tongji.user.domain.User;

import java.time.LocalDate;

public record UserProfileUpdatedEvent(
        Long userId,
        String nickname,
        String avatar,
        String bio,
        String zgId,
        String gender,
        LocalDate birthday,
        String school,
        String phone,
        String email,
        String tagJson
) {
    public static UserProfileUpdatedEvent from(User user) {
        return new UserProfileUpdatedEvent(
                user.getId(),
                user.getNickname(),
                user.getAvatar(),
                user.getBio(),
                user.getZgId(),
                user.getGender(),
                user.getBirthday(),
                user.getSchool(),
                user.getPhone(),
                user.getEmail(),
                user.getTagsJson()
        );
    }
}
