package me.jellysquid.mods.sodium.client.render.chunk.compile;

import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.compat.forge.ForgeBlockRenderer;
import me.jellysquid.mods.sodium.client.gl.compile.ChunkBuildContext;
import me.jellysquid.mods.sodium.client.model.vertex.type.ChunkVertexType;
import me.jellysquid.mods.sodium.client.render.chunk.passes.BlockRenderPassManager;
import me.jellysquid.mods.sodium.client.render.chunk.tasks.ChunkRenderBuildTask;
import me.jellysquid.mods.sodium.client.util.task.CancellationSource;
import me.jellysquid.mods.sodium.common.util.collections.QueueDrainingIterator;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

public class ChunkBuilder {
    private static final Logger LOGGER = LogManager.getLogger("ChunkBuilder");
    /**
     * Megabytes of heap required per chunk builder thread. This is used to cap the number of worker
     * threads when the game is given a small heap.
     */
    private static final int MBS_PER_CHUNK_BUILDER = 64;

    private final Deque<WrappedTask> buildQueue = new ConcurrentLinkedDeque<>();

    private final Object jobNotifier = new Object();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Thread> threads = new ArrayList<>();

    private Level world;
    private BlockRenderPassManager renderPassManager;

    private final int limitThreads;
    private final ChunkVertexType vertexType;

    private final Queue<ChunkBuildResult> deferredResultQueue = new ConcurrentLinkedDeque<>();
    private final Queue<Throwable> deferredFailureQueue = new ConcurrentLinkedDeque<>();
    private final ThreadLocal<ChunkBuildContext> localContexts = new ThreadLocal<>();

    public ChunkBuilder(ChunkVertexType vertexType) {
        this.vertexType = vertexType;
        this.limitThreads = getThreadCount();
    }

    /**
     * Returns the remaining number of build tasks which should be scheduled this frame. If an attempt is made to
     * spawn more tasks than the budget allows, it will block until resources become available.
     */
    public int getSchedulingBudget() {
        return Math.max(0, this.limitThreads - this.buildQueue.size());
    }

    /**
     * Spawns a number of work-stealing threads to process results in the build queue. If the builder is already
     * running, this method does nothing and exits.
     */
    public void startWorkers() {
        if (this.running.getAndSet(true)) {
            return;
        }

        if (!this.threads.isEmpty()) {
            throw new IllegalStateException("Threads are still alive while in the STOPPED state");
        }

        for (int i = 0; i < this.limitThreads; i++) {
            ChunkBuildContext context = new ChunkBuildContext(this.world, this.vertexType, this.renderPassManager);
            WorkerRunnable worker = new WorkerRunnable(context);

            Thread thread = new Thread(worker, "Chunk Render Task Executor #" + i);
            thread.setPriority(Math.max(0, Thread.NORM_PRIORITY - 2));
            thread.start();

            this.threads.add(thread);
        }

        LOGGER.info("Started {} worker threads", this.threads.size());
    }

    /**
     * Notifies all worker threads to stop and blocks until all workers terminate. After the workers have been shut
     * down, all tasks are cancelled and the pending queues are cleared. If the builder is already stopped, this
     * method does nothing and exits. This method implicitly calls {@link ChunkBuilder#doneStealingTasks()} on the
     * calling thread.
     */
    public void stopWorkers() {
        if (!this.running.getAndSet(false)) {
            return;
        }

        if (this.threads.isEmpty()) {
            throw new IllegalStateException("No threads are alive but the executor is in the RUNNING state");
        }

        LOGGER.info("Stopping worker threads");

        // Notify all worker threads to wake up, where they will then terminate
        synchronized (this.jobNotifier) {
            this.jobNotifier.notifyAll();
        }

        // Wait for every remaining thread to terminate
        for (Thread thread : this.threads) {
            try {
                thread.join();
            } catch (InterruptedException ignored) {
            }
        }

        this.threads.clear();

        // Delete any queued tasks and resources attached to them
        for (WrappedTask job : this.buildQueue) {
            job.cancel();
            job.task.releaseResources();
        }

        // Delete any results in the deferred queue
        while (!this.deferredResultQueue.isEmpty()) {
            this.deferredResultQueue.remove()
                    .delete();
        }

        this.deferredFailureQueue.clear();

        this.buildQueue.clear();

        this.world = null;
        
        this.doneStealingTasks();
    }
    
    /**
     * Cleans up resources allocated on the currently calling thread for the {@link ChunkBuilder#stealTask()} method.
     * This method should be called on a thread that has stolen tasks when it is done stealing to prevent resource
     * leaks.
     */
    public void doneStealingTasks() {
        this.localContexts.remove();
    }

    public WrappedTask schedule(ChunkRenderBuildTask task) {
        if (!this.running.get()) {
            throw new IllegalStateException("Executor is stopped");
        }

        WrappedTask job = new WrappedTask(task);

        this.buildQueue.add(job);

        synchronized (this.jobNotifier) {
            this.jobNotifier.notify();
        }

        return job;
    }

    /**
     * @return True if the build queue is empty
     */
    public boolean isBuildQueueEmpty() {
        return this.buildQueue.isEmpty();
    }

    /**
     * Initializes this chunk builder for the given world. If the builder is already running (which can happen during
     * a world teleportation event), the worker threads will first be stopped and all pending tasks will be discarded
     * before being started again.
     * @param world The world instance
     * @param renderPassManager The render pass manager used for the world
     */
    public void init(ClientLevel world, BlockRenderPassManager renderPassManager) {
        if (world == null) {
            throw new NullPointerException("World is null");
        }

        this.stopWorkers();

        this.world = world;
        this.renderPassManager = renderPassManager;

        ForgeBlockRenderer.init();

        this.startWorkers();
    }

    /**
     * Returns the "optimal" number of threads to be used for chunk build tasks. This will always return at least one
     * thread.
     */
    private static int getOptimalThreadCount() {
        return Mth.clamp(Math.max(getMaxThreadCount() / 3, getMaxThreadCount() - 6), 1, 10);
    }

    private static int getThreadCount() {
        int requested = SodiumClientMod.options().performance.chunkBuilderThreads;
        return requested == 0 ? getOptimalThreadCount() : Math.min(requested, getMaxThreadCount());
    }

    public static int getMaxThreadCount() {
        int totalCores = Runtime.getRuntime().availableProcessors();
        long memoryMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        // always allow at least one builder regardless of heap size
        int maxBuilders = Math.max(1, (int)(memoryMb / MBS_PER_CHUNK_BUILDER));
        // choose the total CPU cores or the number of builders the heap permits, whichever is smaller
        return Math.min(totalCores, maxBuilders);
    }

    public WrappedTask scheduleDeferred(ChunkRenderBuildTask task) {
        var wrappedTask = this.schedule(task);
        wrappedTask.getFuture().whenComplete((res, ex) -> {
            if (ex != null) {
                this.deferredFailureQueue.add(ex);
            } else if (res != null) {
                this.deferredResultQueue.add(res);
            }
        });
        return wrappedTask;
    }

    public Iterator<ChunkBuildResult> createDeferredBuildResultDrain() {
        return new QueueDrainingIterator<>(this.deferredResultQueue);
    }

    public Iterator<Throwable> createDeferredBuildFailureDrain() {
        return new QueueDrainingIterator<>(this.deferredFailureQueue);
    }

    /**
     * "Steals" a task on the queue and allows the currently calling thread to execute it using locally-allocated
     * resources instead. While this function returns true, the caller should continually execute it so that additional
     * tasks can be processed.
     *
     * @return True if it was able to steal a task, otherwise false
     */
    public boolean stealTask() {
        WrappedTask task = this.getNextJob(false);

        if (task == null) {
            return false;
        }

        ChunkBuildContext context = this.localContexts.get();

        if (context == null) {
            this.localContexts.set(context = new ChunkBuildContext(this.world, this.vertexType, this.renderPassManager));
        }

        try {
            processJob(task, context);
        } finally {
            context.release();
        }

        return true;
    }

    /**
     * Returns the next task which this worker can work on or blocks until one becomes available. If no tasks are
     * currently available and {@param block} is true, it will wait on the {@link ChunkBuilder#jobNotifier} field
     * until it is notified of an incoming task.
     */
    private WrappedTask getNextJob(boolean block) {
        WrappedTask job = ChunkBuilder.this.buildQueue.poll();

        if (job == null && block) {
            synchronized (ChunkBuilder.this.jobNotifier) {
                try {
                    ChunkBuilder.this.jobNotifier.wait();
                } catch (InterruptedException ignored) {
                }
            }
        }

        return job;
    }

    private static void processJob(WrappedTask job, ChunkBuildContext context) {
        if (job.isCancelled()) {
            return;
        }

        ChunkBuildResult result;

        try {
            // Perform the build task with this worker's local resources and obtain the result
            result = job.task.performBuild(context, job);
        } catch (Throwable e) {
            // Propagate any exception from chunk building
            job.future.completeExceptionally(e);
            SodiumClientMod.logger().error("Chunk build failed", e);
            return;
        } finally {
            job.task.releaseResources();
        }

        // The result can be null if the task is cancelled
        if (result != null) {
            // Notify the future that the result is now available
            job.future.complete(result);
        } else if (!job.isCancelled()) {
            // If the job wasn't cancelled and no result was produced, we've hit a bug
            job.future.completeExceptionally(new RuntimeException("No result was produced by the task"));
        }
    }

    private class WorkerRunnable implements Runnable {
        private final AtomicBoolean running = ChunkBuilder.this.running;

        // Making this thread-local provides a small boost to performance by avoiding the overhead in synchronizing
        // caches between different CPU cores
        private final ChunkBuildContext context;

        public WorkerRunnable(ChunkBuildContext context) {
            this.context = context;
        }

        @Override
        public void run() {
            // Run until the chunk builder shuts down
            while (this.running.get()) {
                WrappedTask job = ChunkBuilder.this.getNextJob(true);

                if (job == null) {
                    continue;
                }

                try {
                    processJob(job, this.context);
                } finally {
                    this.context.release();
                }
            }
        }
    }

    public static class WrappedTask implements CancellationSource {
        private final ChunkRenderBuildTask task;
        private final CompletableFuture<ChunkBuildResult> future;
        private volatile boolean isCancelled;

        private WrappedTask(ChunkRenderBuildTask task) {
            this.task = task;
            this.future = new CompletableFuture<>();
        }

        @Override
        public boolean isCancelled() {
            return isCancelled;
        }

        public void cancel() {
            this.isCancelled = true;
        }

        public CompletableFuture<ChunkBuildResult> getFuture() {
            return this.future;
        }
    }
}
