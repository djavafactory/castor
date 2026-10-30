package com.djavafactory.castor.gateway.filedrop.infrastructure;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.scheduler.Scheduled;
import org.jboss.logging.Logger;

import com.djavafactory.castor.gateway.filedrop.application.ProcessDrops;
import com.djavafactory.castor.gateway.shared.infrastructure.WorkersDisabled;

/** Looks at the drop folders every {@code castor.drop.interval} (5 s); a failed run is logged and tried again. */
@ApplicationScoped
public class DropJob {

    private static final Logger LOG = Logger.getLogger(DropJob.class);

    private final ProcessDrops drops;
    private final FileSystemDropFolder folder;

    public DropJob(ProcessDrops drops, FileSystemDropFolder folder) {
        this.drops = drops;
        this.folder = folder;
    }

    @Scheduled(
            identity = "file-drop",
            every = "{castor.drop.interval}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = WorkersDisabled.class)
    void tick() {
        if (!folder.enabled()) {
            return;
        }
        try {
            drops.runOnce();
        } catch (RuntimeException e) {
            LOG.error("File drop run failed, trying again", e);
        }
    }
}
