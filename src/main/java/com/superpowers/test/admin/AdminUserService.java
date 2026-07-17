package com.superpowers.test.admin;

import com.superpowers.test.admin.dto.AdminUserPageResponse;
import com.superpowers.test.admin.dto.AdminUserResponse;
import com.superpowers.test.admin.dto.PageableInfo;
import com.superpowers.test.user.User;
import com.superpowers.test.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
public class AdminUserService {

    private static final String ROLE_PREFIX = "ROLE_";

    private final UserRepository userRepository;

    public AdminUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public AdminUserPageResponse listUsers(int page, int size, AdminUserRole role, String search) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Specification<User> spec = buildSpecification(role, search);

        Page<User> result = userRepository.findAll(spec, pageable);

        List<AdminUserResponse> content = result.getContent().stream()
                .map(this::toResponse)
                .toList();

        PageableInfo pageableInfo = new PageableInfo(
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());

        return new AdminUserPageResponse(content, pageableInfo);
    }

    private Specification<User> buildSpecification(AdminUserRole role, String search) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (role != null) {
                String dbRole = role.name().substring(ROLE_PREFIX.length());
                predicates.add(criteriaBuilder.equal(root.get("role"), dbRole));
            }

            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.toLowerCase(Locale.ROOT) + "%";
                Predicate nameMatch = criteriaBuilder.like(criteriaBuilder.lower(root.get("name")), pattern);
                Predicate emailMatch = criteriaBuilder.like(criteriaBuilder.lower(root.get("email")), pattern);
                predicates.add(criteriaBuilder.or(nameMatch, emailMatch));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    private AdminUserResponse toResponse(User user) {
        return new AdminUserResponse(
                String.valueOf(user.getId()),
                user.getName(),
                user.getEmail(),
                ROLE_PREFIX + user.getRole(),
                user.getStatus().name(),
                user.getCreatedAt());
    }
}
