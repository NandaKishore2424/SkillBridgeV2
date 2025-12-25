package com.skillbridge.college.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "colleges")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class College {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "name", nullable = false, length = 255)
    private String name;
    
    @Column(name = "code", unique = true, nullable = false, length = 50)
    private String code;
    
    @Column(name = "email", length = 255)
    private String email;
    
    @Column(name = "phone", length = 20)
    private String phone;
    
    @Column(name = "address", columnDefinition = "TEXT")
    private String address;
    
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE"; // ACTIVE, INACTIVE
    
    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
    
    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

