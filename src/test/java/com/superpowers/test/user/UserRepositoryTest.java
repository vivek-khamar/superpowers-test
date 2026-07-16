package com.superpowers.test.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DataJpaTest
class UserRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    @Autowired
    private UserRepository userRepository;

    @Test
    void findsUserByEmailCaseInsensitively() {
        userRepository.save(new User("user@example.com", "hash", "Alex Doe"));

        assertThat(userRepository.findByEmailIgnoreCase("USER@EXAMPLE.COM")).isPresent();
        assertThat(userRepository.findByEmailIgnoreCase("nobody@example.com")).isEmpty();
    }

    @Test
    void populatesGeneratedIdAndTimestampsOnSave() {
        User saved = userRepository.save(new User("fresh@example.com", "hash", "Fresh User"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void rejectsDuplicateEmailAtTheDatabaseLevelRegardlessOfCase() {
        userRepository.saveAndFlush(new User("dup@example.com", "hash", "First"));

        User duplicate = new User("DUP@example.com", "hash", "Second");
        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void defaultsFailedAttemptsToZeroAndLockedUntilToNull() {
        User saved = userRepository.save(new User("locked-default@example.com", "hash", "Fresh User", "USER"));

        assertThat(saved.getFailedAttempts()).isZero();
        assertThat(saved.getLockedUntil()).isNull();
    }

    @Test
    void defaultsStatusToRegisteredAndOnboardingFieldsToNull() {
        User saved = userRepository.save(new User("onboarding-default@example.com", "hash", "Fresh User"));

        assertThat(saved.getStatus()).isEqualTo(UserStatus.REGISTERED);
        assertThat(saved.getJobPreference()).isNull();
        assertThat(saved.getPreferredJobFunctions()).isEmpty();
        assertThat(saved.getPreferredLocations()).isEmpty();
        assertThat(saved.getProfilePictureUrl()).isNull();
        assertThat(saved.getResumeUrl()).isNull();
    }

    @Test
    void persistsOnboardingFieldsIncludingListCollections() {
        User user = new User("onboarding-complete@example.com", "hash", "Onboarded User");
        user.setJobPreference(JobPreference.FULL_TIME);
        user.setPreferredJobFunctions(List.of("Backend Engineer", "Solution Architect"));
        user.setPreferredLocations(List.of("Mumbai", "Bangalore"));
        user.setProfilePictureUrl("profile-pictures/1/abc.png");
        user.setResumeUrl("resumes/1/abc.pdf");
        user.setStatus(UserStatus.ONBOARDING_COMPLETED);

        User saved = userRepository.saveAndFlush(user);
        User reloaded = userRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getJobPreference()).isEqualTo(JobPreference.FULL_TIME);
        assertThat(reloaded.getPreferredJobFunctions()).containsExactlyInAnyOrder("Backend Engineer", "Solution Architect");
        assertThat(reloaded.getPreferredLocations()).containsExactlyInAnyOrder("Mumbai", "Bangalore");
        assertThat(reloaded.getProfilePictureUrl()).isEqualTo("profile-pictures/1/abc.png");
        assertThat(reloaded.getResumeUrl()).isEqualTo("resumes/1/abc.pdf");
        assertThat(reloaded.getStatus()).isEqualTo(UserStatus.ONBOARDING_COMPLETED);
    }

    @Test
    void supportsSpecificationBasedQueriesWithPagination() {
        userRepository.save(new User("spec-a@example.com", "hash", "Spec User A", "ADMIN"));
        userRepository.save(new User("spec-b@example.com", "hash", "Spec User B", "USER"));

        Specification<User> adminOnly = (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("role"), "ADMIN");
        Page<User> result = userRepository.findAll(adminOnly, PageRequest.of(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getEmail()).isEqualTo("spec-a@example.com");
    }
}
