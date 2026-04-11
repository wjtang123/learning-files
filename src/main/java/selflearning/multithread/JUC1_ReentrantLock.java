package selflearning.multithread;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ReentrantLock：比 synchronized 更灵活的显式锁
 *
 * synchronized 的局限：
 *   1. 抢不到锁只能死等，无法设置超时
 *   2. 无法知道锁当前被谁持有
 *   3. 只有一个等待队列（wait/notify），无法区分"生产者等待"和"消费者等待"
 *   4. 非公平锁，无法保证等待最久的线程优先获得锁
 *
 * ReentrantLock 逐一解决了上述问题：
 *   - tryLock()         → 尝试获取锁，失败立即返回 false，不死等
 *   - tryLock(time)     → 带超时的尝试，等一段时间还没拿到就放弃
 *   - lockInterruptibly()→ 等待过程中可被中断
 *   - new ReentrantLock(true) → 公平锁，按等待顺序分配
 *   - Condition         → 多个等待队列，精准唤醒
 *
 * 使用铁律：lock() 之后必须在 finally 里 unlock()！
 *   否则一旦 try 块里抛异常，锁永远不会释放，其他线程全部死锁。
 */
public class JUC1_ReentrantLock {

    // ============================================================
    // 示例一：基本用法 vs synchronized
    // ============================================================
    static class BasicLockDemo {
        private final ReentrantLock lock = new ReentrantLock();
        private int count = 0;

        public void increment() {
            lock.lock();          // 获取锁（阻塞直到拿到）
            try {
                count++;          // 临界区：同一时刻只有一个线程在这里
            } finally {
                lock.unlock();    // 必须在 finally 里释放！无论是否抛异常
            }
        }

        public int getCount() { return count; }
    }

    // ============================================================
    // 示例二：tryLock() —— 拿不到锁就去做别的，不死等
    // ============================================================
    static class TryLockDemo {
        private final ReentrantLock lock = new ReentrantLock();

        public void doWork(String name) {
            // tryLock() 立即返回：拿到返回 true，没拿到返回 false
            if (lock.tryLock()) {
                try {
                    System.out.println("  " + name + " 拿到锁，开始工作...");
                    Thread.sleep(500);
                    System.out.println("  " + name + " 工作完成，释放锁");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                }
            } else {
                // 拿不到锁时不阻塞，可以去做其他事
                System.out.println("  " + name + " 没拿到锁，去做别的事情...");
            }
        }

        public void doWorkWithTimeout(String name) throws InterruptedException {
            // tryLock(time, unit)：最多等 300ms，还没拿到就放弃
            if (lock.tryLock(300, TimeUnit.MILLISECONDS)) {
                try {
                    System.out.println("  " + name + " 等待后拿到锁");
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                }
            } else {
                System.out.println("  " + name + " 等待超时，放弃");
            }
        }
    }

    // ============================================================
    // 示例三：公平锁 —— 按等待顺序分配锁
    // ============================================================
    static class FairLockDemo {
        // true = 公平锁：等待最久的线程优先获得锁
        // false = 非公平锁（默认）：随机竞争，吞吐量更高但可能让某些线程饿死
        private final ReentrantLock fairLock = new ReentrantLock(true);

        public void access(String name) {
            fairLock.lock();
            try {
                System.out.println("  " + name + " 获得公平锁");
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                fairLock.unlock();
            }
        }
    }

    // ============================================================
    // 示例四：Condition —— 多个等待队列，精准唤醒
    // 改进版生产者消费者：生产者和消费者分别在不同队列等待
    // ============================================================
    static class BoundedBuffer {
        private final ReentrantLock lock = new ReentrantLock();
        // 两个独立的等待队列——这是 Condition 相比 wait/notify 的最大优势
        private final Condition notFull  = lock.newCondition(); // 生产者在这里等
        private final Condition notEmpty = lock.newCondition(); // 消费者在这里等

        private final Object[] items = new Object[5]; // 缓冲区，容量 5
        private int head, tail, count;

        /** 生产者：放入一个元素 */
        public void put(Object item) throws InterruptedException {
            lock.lock();
            try {
                while (count == items.length) {
                    System.out.println("  [生产者] 缓冲区满，等待消费者...");
                    notFull.await(); // 只在 notFull 队列等，不影响消费者
                }
                items[tail] = item;
                tail = (tail + 1) % items.length;
                count++;
                System.out.println("  [生产者] 放入 " + item + "，当前数量：" + count);
                notEmpty.signal(); // 精准唤醒消费者，不唤醒其他生产者
            } finally {
                lock.unlock();
            }
        }

        /** 消费者：取出一个元素 */
        public Object take() throws InterruptedException {
            lock.lock();
            try {
                while (count == 0) {
                    System.out.println("  [消费者] 缓冲区空，等待生产者...");
                    notEmpty.await(); // 只在 notEmpty 队列等，不影响生产者
                }
                Object item = items[head];
                head = (head + 1) % items.length;
                count--;
                System.out.println("  [消费者] 取出 " + item + "，剩余数量：" + count);
                notFull.signal(); // 精准唤醒生产者，不唤醒其他消费者
                return item;
            } finally {
                lock.unlock();
            }
        }
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) throws InterruptedException {

        // --- 示例一：基本用法，结果必须是 10000 ---
        System.out.println("=== 基本用法（结果应为 10000）===");
        BasicLockDemo basic = new BasicLockDemo();

        Thread[] ts = new Thread[10];
        for (int i = 0; i < 10; i++) {
            ts[i] = new Thread(() -> { for (int j = 0; j < 1000; j++) basic.increment(); });
        }
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
        System.out.println("结果：" + basic.getCount());

        // --- 示例二：tryLock 不死等 ---
        System.out.println("\n=== tryLock：拿不到就去做别的 ===");
        TryLockDemo tryDemo = new TryLockDemo();
        Thread ta = new Thread(() -> tryDemo.doWork("线程A"));
        Thread tb = new Thread(() -> tryDemo.doWork("线程B")); // B大概率拿不到锁

        ta.start();
        Thread.sleep(50); // 让A先拿到锁
        tb.start();
        ta.join(); tb.join();

        System.out.println("\n=== tryLock 带超时 ===");
        TryLockDemo timeoutDemo = new TryLockDemo();
        Thread tc = new Thread(() -> {
            timeoutDemo.doWork("线程C"); // C拿到锁，持有500ms
        });
        Thread td = new Thread(() -> {
            try { timeoutDemo.doWorkWithTimeout("线程D"); } // D等300ms，但C要500ms
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        tc.start();
        Thread.sleep(50);
        td.start();
        tc.join(); td.join();

        // --- 示例三：公平锁 ---
        System.out.println("\n=== 公平锁（按等待顺序获得锁）===");
        FairLockDemo fairDemo = new FairLockDemo();
        for (int i = 1; i <= 5; i++) {
            final String name = "线程-" + i;
            new Thread(() -> fairDemo.access(name)).start();
            Thread.sleep(10); // 让线程按顺序启动和等待
        }
        Thread.sleep(500);

        // --- 示例四：Condition 精准唤醒 ---
        System.out.println("\n=== Condition：精准唤醒生产者/消费者 ===");
        BoundedBuffer buffer = new BoundedBuffer();

        Thread producer = new Thread(() -> {
            for (int i = 1; i <= 7; i++) {
                try {
                    buffer.put("商品" + i);
                    Thread.sleep(100);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });

        Thread consumer = new Thread(() -> {
            for (int i = 0; i < 7; i++) {
                try {
                    buffer.take();
                    Thread.sleep(200); // 消费比生产慢
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });

        producer.start();
        consumer.start();
        producer.join();
        consumer.join();

        /*
         * 运行后观察：
         *   1. 基本用法：结果稳定是 10000，说明锁生效
         *   2. tryLock：线程B"拿不到锁"后立刻去做别的，不卡住
         *   3. tryLock超时：线程D等了300ms还没拿到（C要500ms），主动放弃
         *   4. 公平锁：获得锁的顺序和线程启动顺序基本一致（1→2→3→4→5）
         *   5. Condition：生产和消费交替，缓冲区满时只有生产者等待，消费者不受影响
         *
         * synchronized vs ReentrantLock 怎么选？
         *   - 简单场景、代码块加锁 → synchronized（更简洁，JVM自动优化）
         *   - 需要 tryLock/超时/公平锁/多个等待队列 → ReentrantLock
         */
    }
}
