package com.skillbridge.college.service;

import com.skillbridge.college.dto.CollegeDTO;
import com.skillbridge.college.entity.College;
import com.skillbridge.college.repository.CollegeRepository;
import com.skillbridge.common.cache.L1CacheConfig;
import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.common.dto.Pagination;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes of the college directory, in one place so the cache cannot
 * go stale behind a write nobody remembered to evict on.
 *
 * <p>That is the whole reason this class exists rather than a {@code @Cacheable}
 * on the repository call. The three college writes lived on
 * {@code CollegeController} and went straight to the repository; a cached read
 * plus writes scattered across controllers is a directory that shows a
 * deactivated college on the public registration form for half an hour. Both
 * sides are here now, and {@code CollegeCacheEvictionTest} fails the build if a
 * fourth writer appears elsewhere.
 *
 * <p>Evicting <em>everything</em> on any write, rather than the one key: the
 * cache holds at most a handful of pages of a list whose ordering any write can
 * change, so a targeted eviction would have to reason about which pages an
 * insert shifted. Over-evicting costs one 150 ms read; under-evicting is the
 * bug.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CollegeDirectoryService {

    private final CollegeRepository collegeRepository;

    /**
     * Active colleges, for the registration form's picker.
     *
     * <p>Cached at L1 for 30 minutes. This is the one unauthenticated list on
     * the platform, so it is the one read an anonymous flood lands on, and it is
     * measured at 1 statement and ~154 ms — the cost is the round trip to
     * another region, not the query. See {@code docs/CACHING_STRATEGY.md} § 4.
     *
     * <p><b>No tenant in the key, deliberately.</b> The active-college list is
     * the same for everybody and is served to callers who have no tenant at all.
     * {@code ACTIVE_COLLEGES} is named in {@code L1CacheConfig.GLOBAL_CACHES} for
     * exactly that reason, which is what stops the tenant-key rule failing the
     * build on it.
     *
     * <p><b>Keyed on the page, not cached as one list.</b> There are no filter
     * dimensions here — only {@code page} and {@code size} — so this is not the
     * cardinality explosion that rules out caching the batch list, and in
     * practice the registration form asks for exactly one of them. Slicing a
     * cached list in memory instead would reintroduce the in-memory pagination
     * this project has a detector for.
     *
     * <p>The returned object is shared by every caller. Nothing may mutate it.
     */
    @Cacheable(cacheNames = L1CacheConfig.ACTIVE_COLLEGES, key = "#page + ':' + #size")
    @Transactional(readOnly = true)
    public PagedResponse<CollegeDTO> activeColleges(int page, int size) {
        log.debug("Cache miss: reading active colleges page {} size {}", page, size);
        return PagedResponse.from(
                collegeRepository.findByStatus("ACTIVE", Pagination.of(page, size)),
                CollegeDTO::from);
    }

    @CacheEvict(cacheNames = L1CacheConfig.ACTIVE_COLLEGES, allEntries = true)
    @Transactional
    public College save(College college) {
        return collegeRepository.save(college);
    }
}
