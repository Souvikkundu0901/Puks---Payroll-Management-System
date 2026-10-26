package com.octopyder.payroll.people;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id AND u.deletedAt IS NULL")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);

    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findByCode(String code);

    long countByRoleAndStatus(Role role, UserStatus status);

    long countByRoleAndStatusAndDeletedAtIsNull(Role role, UserStatus status);

    List<User> findByManagerIdAndDeletedAtIsNull(UUID managerId);

    long countByManagerIdAndDeletedAtIsNull(UUID managerId);

    List<User> findByRoleAndDeletedAtIsNull(Role role);
}
