package com.supriya.accreditation.platform.jobs;

import io.micronaut.aop.InterceptorBean;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.jdbc.DataSourceResolver;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Holds {@code GET_LOCK(name, 0)} on a dedicated connection for the length of the run. The lock belongs to the
 * connection, so it is released when the run ends, when the pod dies or when the connection drops: there is no
 * lease that can expire mid-run. The job's own transactions use other connections.
 */
@Singleton
@InterceptorBean(ScheduledJobLock.class)
public class ScheduledJobLockInterceptor implements MethodInterceptor<Object, Object> {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobLockInterceptor.class);

    private final DataSource dataSource;

    ScheduledJobLockInterceptor(
            @Named("write") final DataSource dataSource,
            final DataSourceResolver resolver) {
        // The raw pool, outside connection and transaction management: the lock connection must not join any
        // transaction. Micronaut Data's resolver bean unwraps its contextual wrapper; DataSourceResolver.DEFAULT does not.
        this.dataSource = resolver.resolve(dataSource);
    }

    @Override
    public Object intercept(final MethodInvocationContext<Object, Object> context) {
        final String lockName = lockName(context.getDeclaringType().getSimpleName(), context.getMethodName());
        try (Connection connection = dataSource.getConnection()) {
            if (!tryLock(connection, lockName)) {
                log.debug("{} is running on another instance; skipping this run", lockName);
                return null;
            }
            try {
                return context.proceed();
            } finally {
                release(connection, lockName);
            }
        } catch (SQLException e) {
            log.warn("Could not take {}; skipping this run: {}", lockName, e.toString());
            return null;
        }
    }

    static String lockName(
            final String type,
            final String method) {
        final String name = "job:" + type + "." + method;
        return name.length() <= 64 ? name : name.substring(0, 64); // MySQL's limit for lock names
    }

    private static boolean tryLock(
            final Connection connection,
            final String lockName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            statement.setString(1, lockName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }

    private static void release(
            final Connection connection,
            final String lockName) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, lockName);
            statement.execute();
        } catch (SQLException e) {
            // Closing the connection releases it anyway
            log.warn("Could not release {}: {}", lockName, e.toString());
        }
    }
}
