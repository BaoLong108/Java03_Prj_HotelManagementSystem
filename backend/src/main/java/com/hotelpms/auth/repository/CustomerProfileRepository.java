package com.hotelpms.auth.repository;

import com.hotelpms.auth.entity.CustomerProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from CustomerProfile p where p.userId = :id")
    java.util.Optional<CustomerProfile> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}
