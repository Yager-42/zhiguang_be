package com.tongji.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.profile.api.dto.ProfilePatchRequest;
import com.tongji.profile.event.UserProfileUpdatedEvent;
import com.tongji.profile.event.UserProfileUpdatedProducer;
import com.tongji.profile.service.impl.ProfileServiceImpl;
import com.tongji.outbox.OutboxMapper;
import com.tongji.user.domain.User;
import com.tongji.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileRecommendationConsumerPublisherTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private UserProfileUpdatedProducer userProfileUpdatedProducer;
    @Mock
    private OutboxMapper outboxMapper;
    @Mock
    private IdService idService;

    @Test
    void updateProfilePublishesUpdatedUserSnapshot() {
        ProfileServiceImpl service = new ProfileServiceImpl(userMapper, userProfileUpdatedProducer);
        User current = user(7L, "old", "old-avatar");
        User updated = user(7L, "neo", "new-avatar");
        updated.setBio("bio");
        updated.setZgId("zg007");
        updated.setGender("MALE");
        updated.setBirthday(LocalDate.parse("2000-01-02"));
        updated.setSchool("Tongji");
        updated.setPhone("13800000000");
        updated.setEmail("neo@example.com");
        updated.setTagsJson("[\"java\"]");

        when(userMapper.findById(7L)).thenReturn(current, updated);

        service.updateProfile(7L, new ProfilePatchRequest(
                "neo", "bio", "male", LocalDate.parse("2000-01-02"),
                "zg007", "Tongji", "[\"java\"]"));

        ArgumentCaptor<UserProfileUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(UserProfileUpdatedEvent.class);
        verify(userProfileUpdatedProducer).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isEqualTo(UserProfileUpdatedEvent.from(updated));
    }

    @Test
    void updateAvatarPublishesUpdatedUserSnapshot() {
        ProfileServiceImpl service = new ProfileServiceImpl(userMapper, userProfileUpdatedProducer);
        User current = user(7L, "neo", "old-avatar");
        User updated = user(7L, "neo", "new-avatar");

        when(userMapper.findById(7L)).thenReturn(current, updated);

        service.updateAvatar(7L, "new-avatar");

        ArgumentCaptor<UserProfileUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(UserProfileUpdatedEvent.class);
        verify(userProfileUpdatedProducer).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isEqualTo(UserProfileUpdatedEvent.from(updated));
    }

    @Test
    void producerWritesUserProfileUpdatedToOutbox() {
        UserProfileUpdatedProducer producer = new UserProfileUpdatedProducer(outboxMapper, idService, new ObjectMapper().findAndRegisterModules());
        when(idService.nextId(IdNamespace.OUTBOX_EVENT)).thenReturn(7001L);
        UserProfileUpdatedEvent event = new UserProfileUpdatedEvent(
                7L, "neo", "https://img", "bio", "zg007", "MALE",
                LocalDate.parse("2000-01-02"), "Tongji", "13800000000",
                "neo@example.com", "[\"java\"]");

        producer.publish(event);

        verify(idService).nextId(IdNamespace.OUTBOX_EVENT);
        verify(outboxMapper).insert(eq(7001L), eq("user"), eq(7L), eq("user_profile_updated"), contains("\"userId\":7"));
    }

    private User user(long id, String nickname, String avatar) {
        User user = new User();
        user.setId(id);
        user.setNickname(nickname);
        user.setAvatar(avatar);
        return user;
    }
}
