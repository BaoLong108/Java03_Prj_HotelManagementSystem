package com.hotelpms.auth.repository;

import com.hotelpms.auth.entity.Role;
import com.hotelpms.auth.entity.RoleCode;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByCode(RoleCode code);
}
