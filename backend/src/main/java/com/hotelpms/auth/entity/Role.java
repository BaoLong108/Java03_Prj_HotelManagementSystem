package com.hotelpms.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "roles")
public class Role {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 30)
    private RoleCode code;

    @Column(nullable = false, length = 150)
    private String description;

    protected Role() { }

    public Long getId() { return id; }
    public RoleCode getCode() { return code; }
    public String getDescription() { return description; }

    @Override
    public boolean equals(Object value) {
        return this == value || value instanceof Role other
                && getCode() != null && getCode() == other.getCode();
    }

    @Override
    public int hashCode() { return getCode() == null ? 0 : getCode().hashCode(); }
}
