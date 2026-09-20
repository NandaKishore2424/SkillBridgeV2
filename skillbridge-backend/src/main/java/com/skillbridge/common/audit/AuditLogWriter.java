package com.skillbridge.common.audit;

import com.skillbridge.common.observability.CorrelationIdFilter;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.sql.Types;

/**
 * Writes audit rows through a small connection pool of their own, one
 * autocommit INSERT each.
 *
 * <p><b>Why its own pool.</b> An audit row must survive the rollback of the
 * transaction that produced it: the failed and denied events are the ones most
 * worth keeping, and they are exactly the ones that roll back. Until
 * 2026-09-19 that was done with {@code REQUIRES_NEW} on the main pool, which
 * means a second connection requested while the caller's first is held. Ten
 * concurrent logins on a pool of five then starved it completely: five held a
 * connection each and waited for a second that could never be free, and all
 * ten failed at the connection timeout ({@code ConcurrentLoginPoolTest}).
 *
 * <p>With a separate pool a request holds at most one connection from each,
 * and the audit pool never waits on the main one, so no cycle of waiting can
 * form. The row commits on its own connection whatever the caller's
 * transaction then does. {@code NestedTransactionRulesTest} keeps
 * {@code REQUIRES_NEW} from being reached from a transaction again.
 *
 * <p><b>Why so small and so impatient.</b> Two connections, one second to get
 * one. {@link AuditLogService} swallows a failed write, so under database
 * trouble an audit write costs at most a second and leaves a gap in the trail
 * rather than failing the request; the trade-off is described there.
 *
 * <p>Deliberately not a {@code DataSource} bean: declaring one would make Spring
 * Boot back off from creating the main pool.
 */
@Component
public class AuditLogWriter {

    private static final String INSERT = """
            INSERT INTO audit_log (occurred_at, actor_user_id, actor_email, college_id, action, resource_type,
                                   resource_id, outcome, ip_address, user_agent, metadata, trace_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """;

    private final HikariDataSource pool;
    private final JdbcTemplate jdbc;

    public AuditLogWriter(DataSourceProperties main, AuditProperties settings) {
        this.pool = main.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        pool.setPoolName("audit");
        pool.setMaximumPoolSize(settings.poolSize());
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(settings.connectionTimeout().toMillis());
        pool.setAutoCommit(true);
        this.jdbc = new JdbcTemplate(pool);
    }

    /** One row, committed on its own connection before this returns. */
    public void write(AuditLog entry) {
        jdbc.update(INSERT, ps -> {
            ps.setTimestamp(1, Timestamp.valueOf(entry.getOccurredAt()));
            ps.setObject(2, entry.getActorUserId(), Types.BIGINT);
            ps.setString(3, entry.getActorEmail());
            ps.setObject(4, entry.getCollegeId(), Types.BIGINT);
            ps.setString(5, entry.getAction());
            ps.setString(6, entry.getResourceType());
            ps.setString(7, entry.getResourceId());
            ps.setString(8, entry.getOutcome());
            ps.setString(9, entry.getIpAddress());
            ps.setString(10, entry.getUserAgent());
            ps.setString(11, entry.getMetadata());
            // The request's correlation id, which every log line of it carries too.
            ps.setString(12, MDC.get(CorrelationIdFilter.MDC_TRACE));
        });
    }

    @PreDestroy
    void close() {
        pool.close();
    }
}
