package net.gegy1000.tictacs.async;

import net.gegy1000.justnow.Waker;
import net.gegy1000.tictacs.util.UnsafeAccess;
import sun.misc.Unsafe;

import javax.annotation.Nullable;

public class LinkedWaiter {
    // use unsafe for atomic operations without allocating an AtomicReference
    private static final Unsafe UNSAFE = UnsafeAccess.get();

    private static final long WAKER_OFFSET;
    private static final long NEXT_OFFSET;

    static {
        try {
            WAKER_OFFSET = UNSAFE.objectFieldOffset(LinkedWaiter.class.getDeclaredField("waker"));
            NEXT_OFFSET = UNSAFE.objectFieldOffset(LinkedWaiter.class.getDeclaredField("next"));
        } catch (NoSuchFieldException e) {
            throw new Error("Failed to get waiter field offsets", e);
        }
    }

    private volatile Waker waker;
    private volatile LinkedWaiter next = this.closed();

    final void setWaker(Waker waker) {
        if (!UNSAFE.compareAndSwapObject(this, WAKER_OFFSET, null, waker)) {
            throw new IllegalStateException("tried to swap existing waker");
        }
    }

    final boolean tryLink(LinkedWaiter next) {
        return UNSAFE.compareAndSwapObject(this, NEXT_OFFSET, this.open(), next);
    }

    final boolean tryOpenLink() {
        return UNSAFE.compareAndSwapObject(this, NEXT_OFFSET, this.closed(), this.open());
    }

    @Nullable
    final LinkedWaiter unlinkAndClose() {
        return (LinkedWaiter) UNSAFE.getAndSetObject(this, NEXT_OFFSET, this.closed());
    }

    final void wake() {
        LinkedWaiter waiter = this;

        while (waiter != null) {
            LinkedWaiter next = waiter.unlinkAndClose();
            Waker waker = (Waker) UNSAFE.getAndSetObject(waiter, WAKER_OFFSET, null);

            if (waker != null) {
                waker.wake();
            }

            waiter = next;
        }
    }

    public final void invalidateWaker() {
        this.waker = null;
    }

    final boolean isClosed(LinkedWaiter waiter) {
        return waiter == this.closed();
    }

    private LinkedWaiter open() {
        return null;
    }

    private LinkedWaiter closed() {
        return this;
    }
}
