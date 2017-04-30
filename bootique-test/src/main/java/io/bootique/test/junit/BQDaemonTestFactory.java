package io.bootique.test.junit;

import io.bootique.BQRuntime;
import io.bootique.command.CommandOutcome;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.ExternalResource;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Manages a "daemon" Bootique stack within a lifecycle of the a JUnit test. This allows to start background servers so
 * that tests can execute requests against them, etc. Instances should be annotated within the unit tests with
 * {@link Rule} or {@link ClassRule}. E.g.:
 * <pre>
 * public class MyTest {
 *
 * 	&#64;Rule
 * 	public BQDaemonTestFactory testFactory = new BQDaemonTestFactory();
 * }
 * </pre>
 *
 * @since 0.15
 */
public class BQDaemonTestFactory extends ExternalResource {

    protected Map<BQRuntime, BQRuntimeDaemon> runtimes;

    @Override
    protected void after() {
        Map<BQRuntime, BQRuntimeDaemon> localRuntimes = this.runtimes;

        if (localRuntimes != null) {
            localRuntimes.values().forEach(runtime -> {
                try {
                    runtime.stop();
                } catch (Exception e) {
                    // ignore...
                }
            });
        }
    }

    @Override
    protected void before() {
        this.runtimes = new HashMap<>();
    }

    /**
     * @param <T>  a covariant builder type.
     * @param args a String vararg emulating shell arguments passed to a real app.
     * @return a new instance of builder for the test runtime stack.
     * @since 0.20
     */
    public <T extends Builder<T>> Builder<T> app(String... args) {
        return new Builder(runtimes, args);
    }

    // parameterization is needed to enable covariant return types in subclasses
    public static class Builder<T extends Builder<T>> extends BQTestRuntimeBuilder<T> {

        private static final Function<BQRuntime, Boolean> AFFIRMATIVE_STARTUP_CHECK = runtime -> true;

        private Map<BQRuntime, BQRuntimeDaemon> runtimes;
        private Function<BQRuntime, Boolean> startupCheck;
        private long startupTimeout;
        private TimeUnit startupTimeoutTimeUnit;

        protected Builder(Map<BQRuntime, BQRuntimeDaemon> runtimes, String[] args) {
            super(args);
            this.startupTimeout = 5;
            this.startupTimeoutTimeUnit = TimeUnit.SECONDS;
            this.runtimes = runtimes;
            this.startupCheck = AFFIRMATIVE_STARTUP_CHECK;
        }

        public T startupCheck(Function<BQRuntime, Boolean> startupCheck) {
            this.startupCheck = Objects.requireNonNull(startupCheck);
            return (T) this;
        }

        /**
         * @param runtime a runtime executing in the background.
         * @return an optional object wrapping the state of the runtime execution. If present, then the runtime
         * execution has finished.
         * @since 0.22
         */
        public Optional<CommandOutcome> getOutcome(BQRuntime runtime) {
            return Objects
                    .requireNonNull(runtimes.get(runtime), "Runtime is not registered with the factory.")
                    .getOutcome();
        }

        /**
         * Adds a startup check that waits till the runtime finishes, within the
         * startup timeout bounds.
         *
         * @return this builder
         * @since 0.16
         */
        public T startupAndWaitCheck() {
            this.startupCheck = runtime -> getOutcome(runtime).isPresent();
            return (T) this;
        }

        public T startupTimeout(long timeout, TimeUnit unit) {
            this.startupTimeout = timeout;
            this.startupTimeoutTimeUnit = unit;
            return (T) this;
        }

        /**
         * Starts the test app in a background thread, blocking the test thread until the startup checker succeeds.
         *
         * @return {@link BQRuntime} instance. The caller doesn't need to shut it down. JUnit lifecycle takes care of it.
         * @since 0.23
         */
        public BQRuntime start() {

            BQRuntime runtime = bootique.createRuntime();

            // wrap in BQRuntimeDaemon to handle thread pool shutdown and startup checks.
            BQRuntimeDaemon testRuntime = new BQRuntimeDaemon(runtime, startupCheck);
            runtimes.put(runtime, testRuntime);
            testRuntime.start(startupTimeout, startupTimeoutTimeUnit);

            return runtime;
        }
    }
}
