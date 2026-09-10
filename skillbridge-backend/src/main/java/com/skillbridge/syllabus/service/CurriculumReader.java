package com.skillbridge.syllabus.service;

import com.skillbridge.common.cache.L1CacheConfig;
import com.skillbridge.syllabus.dto.SyllabusModuleDTO;
import com.skillbridge.syllabus.entity.SyllabusModule;
import com.skillbridge.syllabus.entity.SyllabusSubmodule;
import com.skillbridge.syllabus.repository.SyllabusModuleRepository;
import com.skillbridge.syllabus.repository.SyllabusSubmoduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Assembles a batch's curriculum tree, and nothing else.
 *
 * <p><b>This class exists because of what must not be in it.</b> The tree was
 * built inside {@code SyllabusService.getCurriculumByBatchId}, in the same method
 * as {@code requireBatch} — the tenant check. Putting {@code @Cacheable} on that
 * method would have cached the check away: on a hit the method body never runs,
 * so a trainer from another college asking for the same batch id would be handed
 * the tree instead of a 404. The key is not the problem — batch ids are globally
 * unique — the <em>skipped authorisation</em> is, and it is invisible in the
 * annotation.
 *
 * <p>So the check stays in the caller and only the assembly is cached. It has to
 * be a separate bean rather than a private method: Spring's caching is
 * proxy-based, and a call from one method of a class to another never leaves the
 * object, so the proxy never sees it and {@code @Cacheable} is silently inert.
 *
 * <p>Read {@code docs/CACHING_STRATEGY.md} § 4 for why this entry earned a cache:
 * 3 statements and ~450 ms, read by every student and trainer who opens the
 * batch, written only when a trainer edits the syllabus. Best read-to-write ratio
 * in the application.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class CurriculumReader {

    private final SyllabusModuleRepository moduleRepository;
    private final SyllabusSubmoduleRepository submoduleRepository;

    /**
     * The tree for one batch. <b>Performs no authorisation — the caller must
     * already have established that this batch is visible.</b>
     *
     * <p>Keyed by batch id alone, which is safe because batch ids are globally
     * unique rather than per-tenant: two colleges cannot collide on one. That is a
     * property of the schema, not a convention, so it is worth stating — if
     * batch ids ever become tenant-scoped this key becomes a cross-tenant leak.
     *
     * <p>The returned list is shared by every caller on a hit. Nothing may mutate
     * it or anything inside it.
     */
    @Cacheable(cacheNames = L1CacheConfig.CURRICULUM, key = "#batchId", sync = true)
    @Transactional(readOnly = true)
    public List<SyllabusModuleDTO> byBatchId(Long batchId) {
        log.debug("Cache miss: assembling curriculum for batch {}", batchId);

        List<SyllabusModule> modules = moduleRepository.findByBatchIdWithSubmodulesAndTopics(batchId);

        // Second query: initialise every sub-module's topics at once. Walking
        // the tree without this is one query per sub-module -- 8 modules cost
        // 18 statements where 2 cost 6. It cannot be folded into the query
        // above because both associations are Lists and Hibernate rejects two
        // collection fetches in one query. The result is ignored on purpose:
        // the point is the side effect on the persistence context, which leaves
        // the mapper below able to read getTopics() for free.
        List<Long> submoduleIds = modules.stream()
                .flatMap(m -> m.getSubmodules().stream())
                .map(SyllabusSubmodule::getId)
                .toList();
        if (!submoduleIds.isEmpty()) {
            submoduleRepository.fetchTopicsFor(submoduleIds);
        }

        return modules.stream().map(SyllabusDtoMapper::toModuleDto).toList();
    }
}
