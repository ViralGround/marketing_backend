package com.viralground.backend.service;

import com.viralground.backend.dto.profile.UpdateProfileRequest;
import com.viralground.backend.entity.CreatorProfile;
import com.viralground.backend.entity.EditingSkill;
import com.viralground.backend.repository.CreatorProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {
    @Mock CreatorProfileRepository repository;

    @Test
    void publicDirectoryIsPrivateByDefaultAndNullOptionalFieldsRemainReadable() {
        CreatorProfile profile = CreatorProfile.builder()
                .memberId(7).canEdit(false).editingSkill(EditingSkill.LOW)
                .faceExposure(false).build();
        when(repository.findByMemberId(7)).thenReturn(Optional.of(profile));

        Map<String, Object> result = new ProfileService(repository).getProfile(7);

        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) result.get("profile");
        assertThat(values).containsEntry("publicProfileOptIn", false)
                .containsEntry("editingTool", null);
    }

    @Test
    void explicitOptInSetsConsentTimestampAndCanBeRevoked() {
        CreatorProfile profile = CreatorProfile.builder().memberId(7)
                .publicProfileOptIn(false).build();
        when(repository.findByMemberId(7)).thenReturn(Optional.of(profile));
        UpdateProfileRequest enable = request(true);
        ProfileService service = new ProfileService(repository);

        service.updateProfile(7, enable);

        ArgumentCaptor<CreatorProfile> saved = ArgumentCaptor.forClass(CreatorProfile.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPublicProfileOptIn()).isTrue();
        assertThat(saved.getValue().getPublicProfileConsentedAt()).isNotNull();

        UpdateProfileRequest disable = request(false);
        service.updateProfile(7, disable);
        assertThat(profile.getPublicProfileOptIn()).isFalse();
        assertThat(profile.getPublicProfileConsentedAt()).isNull();
    }

    private static UpdateProfileRequest request(boolean publicProfileOptIn) {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setCanEdit(false);
        request.setEditingSkill(EditingSkill.LOW);
        request.setFaceExposure(false);
        request.setPublicProfileOptIn(publicProfileOptIn);
        return request;
    }
}
