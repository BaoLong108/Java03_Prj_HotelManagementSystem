package com.hotelpms.auth.repository;

import com.hotelpms.auth.entity.CustomerProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, Long> { }
