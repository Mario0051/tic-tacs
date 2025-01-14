package net.gegy1000.tictacs.chunk.upgrade;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;
import net.gegy1000.tictacs.AtomicPool;
import net.gegy1000.tictacs.async.lock.JoinLock;
import net.gegy1000.tictacs.async.lock.Lock;
import net.gegy1000.tictacs.chunk.ChunkAccess;
import net.gegy1000.tictacs.chunk.ChunkLockType;
import net.gegy1000.tictacs.chunk.entry.ChunkAccessLock;
import net.gegy1000.tictacs.chunk.entry.ChunkEntry;
import net.gegy1000.tictacs.chunk.entry.ChunkEntryState;
import net.gegy1000.tictacs.chunk.step.ChunkRequirement;
import net.gegy1000.tictacs.chunk.step.ChunkRequirements;
import net.gegy1000.tictacs.chunk.step.ChunkStep;
import net.minecraft.util.math.ChunkPos;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.function.Function;

final class AcquireChunks {
    private static final AtomicPool<AcquireChunks>[] STEP_TO_POOL = initPools();

    @SuppressWarnings("unchecked")
    private static AtomicPool<AcquireChunks>[] initPools() {
        int poolCapacity = 512;

        AtomicPool<AcquireChunks>[] stepToPool = new AtomicPool[ChunkStep.STEPS.size()];
        for (int i = 0; i < stepToPool.length; i++) {
            ChunkStep step = ChunkStep.byIndex(i);
            stepToPool[i] = new AtomicPool<>(poolCapacity, () -> new AcquireChunks(step));
        }

        return stepToPool;
    }

    private final ChunkStep targetStep;
    private final ChunkUpgradeKernel kernel;

    private final Result result;

    private final Lock[] upgradeLocks;
    private final Lock[] locks;

    private final Lock joinLock;
    private final Future<Unit> acquireJoinLock;

    volatile boolean acquired;

    private AcquireChunks(ChunkStep targetStep) {
        this.targetStep = targetStep;

        this.kernel = ChunkUpgradeKernel.forStep(targetStep);
        this.upgradeLocks = this.kernel.create(Lock[]::new);
        this.locks = this.kernel.create(Lock[]::new);

        this.joinLock = new JoinLock(new Lock[] {
                new JoinLock(this.upgradeLocks),
                new JoinLock(this.locks)
        });
        this.acquireJoinLock = new Lock.AcquireFuture(this.joinLock);

        this.result = this.kernel.create(Result::new);
    }

    private static AtomicPool<AcquireChunks> poolFor(ChunkStep step) {
        return STEP_TO_POOL[step.getIndex()];
    }

    public static AcquireChunks open(ChunkStep step) {
        return poolFor(step).acquire();
    }

    private void clearBuffers() {
        Arrays.fill(this.upgradeLocks, null);
        Arrays.fill(this.locks, null);
        Arrays.fill(this.result.entries, null);

        this.result.empty = true;
    }

    @Nullable
    public Result poll(Waker waker, ChunkAccess chunks, ChunkPos pos, ChunkStep step) {
        if (this.acquired) {
            return this.result;
        }

        this.clearBuffers();

        if (this.collectChunks(chunks, pos, step)) {
            this.result.empty = false;
        } else {
            this.result.empty = true;
            this.acquired = true;
            return this.result;
        }

        if (this.acquireJoinLock.poll(waker) != null) {
            this.acquired = true;
            return this.result;
        } else {
            return null;
        }
    }

    private boolean collectChunks(ChunkAccess chunks, ChunkPos pos, ChunkStep step) {
        Lock[] upgradeLocks = this.upgradeLocks;
        Lock[] locks = this.locks;
        ChunkEntryState[] entries = this.result.entries;

        ChunkUpgradeKernel kernel = this.kernel;
        int radiusForStep = kernel.getRadiusFor(step);

        ChunkRequirements requirements = step.getRequirements();

        boolean added = false;

        for (int z = -radiusForStep; z <= radiusForStep; z++) {
            for (int x = -radiusForStep; x <= radiusForStep; x++) {
                ChunkEntry entry = chunks.getEntry(x + pos.x, z + pos.z);

                if (entry != null && entry.canUpgradeTo(step)) {
                    entry.trySpawnUpgradeTo(step);

                    int idx = kernel.index(x, z);

                    entries[idx] = entry.getState();

                    upgradeLocks[idx] = entry.getLock().upgrade();
                    locks[idx] = entry.getLock().write(step.getLock());

                    this.collectContextMargin(chunks, pos, x, z, requirements);

                    added = true;
                }
            }
        }

        return added;
    }

    private void collectContextMargin(ChunkAccess chunks, ChunkPos pos, int centerX, int centerZ, ChunkRequirements requirements) {
        int contextRadius = requirements.getRadius();
        if (contextRadius <= 0) {
            return;
        }

        ChunkEntryState[] entries = this.result.entries;
        Lock[] locks = this.locks;

        ChunkUpgradeKernel kernel = this.kernel;

        int kernelRadius = kernel.getRadius();

        int minX = Math.max(centerX - contextRadius, -kernelRadius);
        int maxX = Math.min(centerX + contextRadius, kernelRadius);
        int minZ = Math.max(centerZ - contextRadius, -kernelRadius);
        int maxZ = Math.min(centerZ + contextRadius, kernelRadius);

        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                int idx = kernel.index(x, z);

                if (entries[idx] == null) {
                    int distance = Math.max(Math.abs(x - centerX), Math.abs(z - centerZ));
                    ChunkRequirement requirement = requirements.byDistance(distance);

                    if (requirement != null) {
                        ChunkEntry entry = chunks.expectEntry(x + pos.x, z + pos.z);
                        ChunkAccessLock lock = entry.getLock();

                        ChunkLockType resource = requirement.step.getLock();
                        boolean requireWrite = requirement.write;

                        entries[idx] = entry.getState();
                        locks[idx] = requireWrite ? lock.write(resource) : lock.read(resource);
                    }
                }
            }
        }
    }

    public void release() {
        if (this.acquired) {
            this.acquired = false;

            this.joinLock.release();

            this.clearBuffers();
        }

        poolFor(this.targetStep).release(this);
    }

    public final class Result {
        final ChunkEntryState[] entries;
        boolean empty = true;

        Result(int bufferSize) {
            this.entries = new ChunkEntryState[bufferSize];
        }

        public <T> void openUpgradeTasks(Future<T>[] tasks, Function<ChunkEntryState, Future<T>> function) {
            ChunkEntryState[] entries = this.entries;

            for (int i = 0; i < entries.length; i++) {
                ChunkEntryState entry = entries[i];
                if (entry != null && AcquireChunks.this.upgradeLocks[i] != null) {
                    tasks[i] = function.apply(entry);
                }
            }
        }

        @Nullable
        public ChunkEntryState getEntry(int x, int z) {
            int idx = AcquireChunks.this.kernel.index(x, z);
            return this.entries[idx];
        }

        public ChunkUpgradeKernel getKernel() {
            return AcquireChunks.this.kernel;
        }
    }
}
