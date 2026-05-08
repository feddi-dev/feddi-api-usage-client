package dev.feddi.api.usage;

import java.time.Duration;

interface ReporterScheduler extends AutoCloseable {

    void execute(Runnable task);

    Cancellable scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period);

    @Override
    void close();

    interface Cancellable {
        void cancel();
    }
}
