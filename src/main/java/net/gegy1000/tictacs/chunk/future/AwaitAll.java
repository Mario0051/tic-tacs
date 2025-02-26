package net.gegy1000.tictacs.chunk.future;

import net.gegy1000.justnow.Waker;
import net.gegy1000.justnow.future.Future;
import net.gegy1000.justnow.tuple.Unit;

import javax.annotation.Nullable;

public final class AwaitAll<T> implements Future<Unit> {
    private final Future<T>[] futures;

    public AwaitAll(Future<T>[] futures) {
        this.futures = futures;
    }

    @Nullable
    @Override
    public Unit poll(Waker waker) {
        return AwaitAll.poll(waker, this.futures);
    }

    public static <T> Unit poll(Waker waker, Future<T>[] futures) {
        boolean pending = false;

        for (int i = 0; i < futures.length; i++) {
            Future<T> future = futures[i];
            if (future == null) continue;

            T result = future.poll(waker);
            if (result != null) {
                futures[i] = null;
            } else {
                pending = true;
            }
        }

        return pending ? null : Unit.INSTANCE;
    }
}
