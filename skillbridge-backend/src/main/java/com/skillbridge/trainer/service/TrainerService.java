package com.skillbridge.trainer.service;

import com.skillbridge.auth.entity.Role;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.RoleRepository;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.college.entity.College;
import com.skillbridge.college.repository.CollegeRepository;
import com.skillbridge.trainer.dto.CreateTrainerRequest;
import com.skillbridge.trainer.dto.TrainerDTO;
import com.skillbridge.trainer.dto.UpdateTrainerProfileRequest;
import com.skillbridge.trainer.entity.Trainer;
import com.skillbridge.trainer.repository.TrainerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.skillbridge.common.exception.ConflictException;
import com.skillbridge.common.exception.InternalServerException;
import com.skillbridge.common.tenant.TenantGuard;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.common.dto.IdGrouping;
import com.skillbridge.common.exception.ResourceNotFoundException;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrainerService {
    private final TrainerRepository trainerRepository;
    private final BatchRepository batchRepository;
    private final UserRepository userRepository;
    private final CollegeRepository collegeRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public TrainerDTO createTrainer(CreateTrainerRequest request) {
        // Validate college
        College college = collegeRepository.findById(request.getCollegeId())
                .orElseThrow(() -> new ResourceNotFoundException("College not found"));

        // Check if user with email already exists
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ConflictException("User with this email already exists");
        }

        // Get TRAINER role
        Role trainerRole = roleRepository.findByName("TRAINER")
                .orElseThrow(() -> new InternalServerException("Required role not found"));

        Set<Role> roles = new HashSet<>();
        roles.add(trainerRole);

        // Create User
        User newUser = User.builder()
                .collegeId(request.getCollegeId())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .isActive(true)
                .roles(roles)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        User savedUser = userRepository.save(newUser);

        // Create Trainer profile
        Trainer trainer = Trainer.builder()
                .user(savedUser)
                .college(college)
                .fullName(request.getFullName())
                .phone(request.getPhone())
                .department(request.getDepartment())
                .specialization(request.getSpecialization())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Trainer savedTrainer = trainerRepository.save(trainer);

        return mapToDTO(savedTrainer);
    }

    public TrainerDTO getTrainerProfile(Long userId) {
        Trainer trainer = trainerRepository.findByUserIdWithUser(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Trainer profile not found"));
        return mapToDTO(trainer);
    }

    /** Serves both {@code GET /admin/trainers/{id}} and {@code GET /trainers/{id}}. */
    public TrainerDTO getTrainerById(Long trainerId) {
        Trainer trainer = trainerRepository.findByIdWithUser(trainerId)
                .filter(t -> TenantGuard.isVisible(t.getCollege().getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Trainer", trainerId));
        return mapToDTO(trainer);
    }

    public List<TrainerDTO> getAllTrainersByCollege(Long collegeId) {
        return mapPage(trainerRepository.findByCollegeIdWithUser(collegeId));
    }

    public Page<TrainerDTO> getTrainersByCollege(Long collegeId, Pageable pageable) {
        Page<Trainer> page = trainerRepository.findByCollegeIdWithUser(collegeId, pageable);
        return new org.springframework.data.domain.PageImpl<>(
                mapPage(page.getContent()), pageable, page.getTotalElements());
    }

    /**
     * Maps a page of trainers, resolving assigned batches in one grouped query.
     *
     * <p>The list page renders a batch count per trainer. Fetching that per row
     * would be an N+1; this is the same shape used for students.
     */
    private List<TrainerDTO> mapPage(List<Trainer> trainers) {
        if (trainers.isEmpty()) {
            return List.of();
        }
        List<Long> ids = trainers.stream().map(Trainer::getId).toList();
        Map<Long, List<Long>> batchesByTrainer =
                IdGrouping.byOwner(batchRepository.findBatchIdsByTrainerIds(ids));

        return trainers.stream().map(trainer -> {
            TrainerDTO dto = mapToDTO(trainer);
            dto.setAssignedBatchIds(IdGrouping.forOwner(batchesByTrainer, trainer.getId()));
            return dto;
        }).collect(Collectors.toList());
    }

    @Transactional
    public TrainerDTO updateTrainerProfile(Long userId, UpdateTrainerProfileRequest request) {
        Trainer trainer = trainerRepository.findByUser_Id(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Trainer profile not found"));

        if (request.getFullName() != null)
            trainer.setFullName(request.getFullName());
        if (request.getPhone() != null)
            trainer.setPhone(request.getPhone());
        if (request.getDepartment() != null)
            trainer.setDepartment(request.getDepartment());
        if (request.getSpecialization() != null)
            trainer.setSpecialization(request.getSpecialization());
        if (request.getBio() != null)
            trainer.setBio(request.getBio());
        if (request.getLinkedinUrl() != null)
            trainer.setLinkedinUrl(request.getLinkedinUrl());
        if (request.getYearsOfExperience() != null)
            trainer.setYearsOfExperience(request.getYearsOfExperience());

        trainer.setUpdatedAt(LocalDateTime.now());
        Trainer updated = trainerRepository.save(trainer);
        return mapToDTO(updated);
    }

    @Transactional
    public void updateTrainerStatus(Long trainerId, boolean isActive) {
        Trainer trainer = trainerRepository.findById(trainerId)
                .orElseThrow(() -> new ResourceNotFoundException("Trainer not found"));

        User user = trainer.getUser();
        user.setIsActive(isActive);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
    }

    private TrainerDTO mapToDTO(Trainer trainer) {
        return TrainerDTO.builder()
                .id(trainer.getId())
                .userId(trainer.getUser().getId())
                .email(trainer.getUser().getEmail())
                .isActive(trainer.getUser().getIsActive()) // Added: frontend needs this
                .fullName(trainer.getFullName())
                .phone(trainer.getPhone())
                .department(trainer.getDepartment())
                .specialization(trainer.getSpecialization())
                .bio(trainer.getBio())
                .linkedinUrl(trainer.getLinkedinUrl())
                .yearsOfExperience(trainer.getYearsOfExperience())
                .createdAt(trainer.getCreatedAt())
                .updatedAt(trainer.getUpdatedAt())
                .build();
    }
}
