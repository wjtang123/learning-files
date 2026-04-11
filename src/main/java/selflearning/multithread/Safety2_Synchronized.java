package selflearning.multithread;

/**
 * synchronized 解决线程安全问题
 *
 * synchronized 的本质：
 *   给一段代码加"互斥锁"，同一时刻只允许一个线程进入。
 *   其他线程到达锁门口时，进入 BLOCKED 状态等待，
 *   直到持有锁的线程退出，才有机会竞争进入。
 *
 * synchronized 有三种写法：
 *   1. 修饰实例方法   → 锁是"this"（当前对象）
 *   2. 修饰静态方法   → 锁是"类的 Class 对象"（所有实例共用同一把锁）
 *   3. 修饰代码块     → 锁是括号里指定的对象（最灵活，粒度最小）
 */
public class Safety2_Synchronized {

    // ============================================================
    // 示例一：synchronized 修饰方法
    // ============================================================
    static class SafeCounter {
        private int count = 0;

        // synchronized 加在方法上，锁 = this（当前 SafeCounter 对象）
        // 同一时刻只有一个线程能执行这个方法
        public synchronized void increment() {
            count++; // 现在是安全的：读-加-写 三步被锁保护，不会被打断
        }

        public synchronized int getCount() {
            return count;
        }
    }

    // ============================================================
    // 示例二：synchronized 修饰代码块（推荐！粒度更小，性能更好）
    // ============================================================
    static class SafeCounterBlock {
        private int count = 0;
        private final Object lock = new Object(); // 专门的锁对象

        public void increment() {
            // synchronized 只锁住必要的代码，方法里其他不涉及共享变量的代码不受影响
            synchronized (lock) {
                count++;
            }
            // 这里的代码可以被多个线程同时执行（不在锁里）
        }

        public int getCount() {
            synchronized (lock) {
                return count;
            }
        }
    }

    // ============================================================
    // 示例三：可重入性演示
    // synchronized 是可重入锁：同一个线程可以多次获得同一把锁
    // ============================================================
    static class ReentrantDemo {
        public synchronized void methodA() {
            System.out.println(Thread.currentThread().getName() + " 进入 methodA");
            methodB(); // 在持有锁的情况下，再次调用需要同一把锁的方法
            System.out.println(Thread.currentThread().getName() + " 离开 methodA");
        }

        public synchronized void methodB() {
            // 如果不可重入，这里会死锁（等自己释放锁）
            // 因为可重入，所以同一线程能直接进入，不会卡住
            System.out.println(Thread.currentThread().getName() + " 进入 methodB（可重入）");
        }
    }

    // ============================================================
    // main：对比不加锁 vs 加锁的结果
    // ============================================================
    public static void main(String[] args) throws InterruptedException {

        // --- 测试：方法级 synchronized ---
        System.out.println("=== 方法级 synchronized ===");
        SafeCounter counter = new SafeCounter();
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 1000; j++) {
                    counter.increment();
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        System.out.println("期望：10000，实际：" + counter.getCount());

        // --- 测试：代码块 synchronized ---
        System.out.println("\n=== 代码块 synchronized ===");
        SafeCounterBlock blockCounter = new SafeCounterBlock();
        Thread[] threads2 = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads2[i] = new Thread(() -> {
                for (int j = 0; j < 1000; j++) {
                    blockCounter.increment();
                }
            });
        }
        for (Thread t : threads2) t.start();
        for (Thread t : threads2) t.join();
        System.out.println("期望：10000，实际：" + blockCounter.getCount());

        // --- 测试：可重入性 ---
        System.out.println("\n=== 可重入锁演示 ===");
        ReentrantDemo demo = new ReentrantDemo();
        new Thread(() -> demo.methodA(), "演示线程").start();

        /*
         * 运行后观察：
         *   - 两个计数器的结果都稳定是 10000，不再出错
         *   - 可重入演示中，同一线程顺利进入了 methodB，没有死锁
         *
         * synchronized 的性能代价：
         *   - 加锁/解锁有开销，且锁外的线程会阻塞等待
         *   - 所以锁的粒度越小越好：只锁真正需要保护的代码
         *   - 代码块写法比方法级写法粒度更细，性能通常更好
         */
    }
}
