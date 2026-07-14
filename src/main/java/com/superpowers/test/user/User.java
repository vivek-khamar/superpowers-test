package com.superpowers.test.user;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "users")
public class User {

    private static final String DEFAULT_ROLE = "USER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String role;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_preference")
    private JobPreference jobPreference;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_job_functions", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "value")
    private List<String> preferredJobFunctions = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_preferred_locations", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "value")
    private List<String> preferredLocations = new ArrayList<>();

    @Column(name = "profile_picture_url")
    private String profilePictureUrl;

    @Column(name = "resume_url")
    private String resumeUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status = UserStatus.REGISTERED;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
    }

    public User(String email, String passwordHash, String name) {
        this(email, passwordHash, name, DEFAULT_ROLE);
    }

    public User(String email, String passwordHash, String name, String role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.role = role;
        this.failedAttempts = 0;
        this.lockedUntil = null;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public String getRole() {
        return role;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public void setFailedAttempts(int failedAttempts) {
        this.failedAttempts = failedAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public JobPreference getJobPreference() {
        return jobPreference;
    }

    public void setJobPreference(JobPreference jobPreference) {
        this.jobPreference = jobPreference;
    }

    public List<String> getPreferredJobFunctions() {
        return Collections.unmodifiableList(preferredJobFunctions);
    }

    public void setPreferredJobFunctions(List<String> preferredJobFunctions) {
        this.preferredJobFunctions = new ArrayList<>(preferredJobFunctions);
    }

    public List<String> getPreferredLocations() {
        return Collections.unmodifiableList(preferredLocations);
    }

    public void setPreferredLocations(List<String> preferredLocations) {
        this.preferredLocations = new ArrayList<>(preferredLocations);
    }

    public String getProfilePictureUrl() {
        return profilePictureUrl;
    }

    public void setProfilePictureUrl(String profilePictureUrl) {
        this.profilePictureUrl = profilePictureUrl;
    }

    public String getResumeUrl() {
        return resumeUrl;
    }

    public void setResumeUrl(String resumeUrl) {
        this.resumeUrl = resumeUrl;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }
}
