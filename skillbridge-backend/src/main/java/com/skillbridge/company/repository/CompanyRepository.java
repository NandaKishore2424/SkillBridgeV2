package com.skillbridge.company.repository;

import com.skillbridge.company.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CompanyRepository extends JpaRepository<Company, Long> {
    long countByCollegeId(Long collegeId);
}
