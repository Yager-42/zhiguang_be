package com.tongji.profile.service;

import com.tongji.profile.api.dto.ProfilePatchRequest;
import com.tongji.profile.api.dto.ProfileResponse;
import com.tongji.user.domain.User;

import java.util.List;
import java.util.Optional;

/**
 * 个人资料业务接口。
 */
public interface ProfileService {

    Optional<User> getById(long userId);

    List<User> listByIds(List<Long> userIds);

    ProfileResponse updateProfile(long userId, ProfilePatchRequest req);

    ProfileResponse updateAvatar(long userId, String avatarUrl);
}
