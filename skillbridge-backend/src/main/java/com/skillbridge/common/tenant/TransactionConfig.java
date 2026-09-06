package com.skillbridge.common.tenant;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Pins the transaction advisor's order so other advice can be placed relative
 * to it.
 *
 * <p>Spring Boot enables transaction management with the advisor at
 * {@code Ordered.LOWEST_PRECEDENCE} (={@code Integer.MAX_VALUE}), which is the
 * innermost position possible — nothing can be nested inside it, because no
 * order value is higher. {@link TenantFilterAspect} has to run inside the
 * transaction to reach the session it binds, so the advisor is moved to a
 * finite order here and the aspect sits after it.
 *
 * <p>Lower value = outermost in Spring AOP, so the ordering is:
 * transaction (100) wraps tenant filter (200) wraps the method body.
 */
@Configuration
@EnableTransactionManagement(order = TransactionConfig.TRANSACTION_ADVISOR_ORDER)
public class TransactionConfig {

    public static final int TRANSACTION_ADVISOR_ORDER = 100;
}
