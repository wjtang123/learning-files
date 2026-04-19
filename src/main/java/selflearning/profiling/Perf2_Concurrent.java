package selflearning.profiling;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/**
 * 并发层性能优化
 *
 * 并发优化的核心目标：
 *   1. 减少锁竞争（降低 synchronized 的使用范围和频率）
 *   2. 充分利用多核（任务并行化）
 *   3. 合理配置线程池（既不浪费也不饿死）
 */
public class Perf2_Concurrent {

    // ============================================================
    // 一、减小锁粒度
    // ============================================================

    // 粗粒度锁：整个方法加锁，并发度低
    static class CoarseCounter {
        private int count = 0;
        private final Object lock = new Object();

        public synchronized void increment() { count++; } // 整个对象只有一把锁
        public synchronized int get()        { return count; }
    }

    // 细粒度锁：分段锁，提高并发度
    // 思路类似 ConcurrentHashMap：把数据分成多段，每段一把锁
    static class SegmentedCounter {
        private static final int SEGMENTS = 16;
        private final int[] counts   = new int[SEGMENTS];
        private final Object[] locks = new Object[SEGMENTS];

        SegmentedCounter() {
            for (int i = 0; i < SEGMENTS; i++) locks[i] = new Object();
        }

        public void increment(int key) {
            int seg = Math.abs(key % SEGMENTS); // 根据 key 确定分段
            synchronized (locks[seg]) {         // 只锁对应的段
                counts[seg]++;
            }
        }

        public int total() {
            int sum = 0;
            for (int c : counts) sum += c;
            return sum;
        }
    }

    // ============================================================
    // 二、无锁化：用原子类替代 synchronized
    // ============================================================
    static void lockFreeDemo() throws InterruptedException {
        int threads = 8, loopsEach = 100000;

        // synchronized 版本
        final int[] syncCount = {0};
        final Object lock = new Object();
        Thread[] syncThreads = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            syncThreads[i] = new Thread(() -> {
                for (int j = 0; j < loopsEach; j++) {
                    synchronized (lock) { syncCount[0]++; }
                }
            });
        }
        long t1 = System.nanoTime();
        for (Thread t : syncThreads) t.start();
        for (Thread t : syncThreads) t.join();
        long syncTime = System.nanoTime() - t1;

        // AtomicInteger 版本（CAS 无锁）
        AtomicInteger atomicCount = new AtomicInteger(0);
        Thread[] atomicThreads = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            atomicThreads[i] = new Thread(() -> {
                for (int j = 0; j < loopsEach; j++) {
                    atomicCount.incrementAndGet();
                }
            });
        }
        long t2 = System.nanoTime();
        for (Thread t : atomicThreads) t.start();
        for (Thread t : atomicThreads) t.join();
        long atomicTime = System.nanoTime() - t2;

        // LongAdder 版本（分槽累加，高竞争最优）
        LongAdder adder = new LongAdder();
        Thread[] adderThreads = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            adderThreads[i] = new Thread(() -> {
                for (int j = 0; j < loopsEach; j++) {
                    adder.increment();
                }
            });
        }
        long t3 = System.nanoTime();
        for (Thread t : adderThreads) t.start();
        for (Thread t : adderThreads) t.join();
        long adderTime = System.nanoTime() - t3;

        System.out.printf("synchronized：%,d ms，结果：%d%n",
                syncTime/1_000_000, syncCount[0]);
        System.out.printf("AtomicInteger：%,d ms，结果：%d%n",
                atomicTime/1_000_000, atomicCount.get());
        System.out.printf("LongAdder：%,d ms，结果：%d%n",
                adderTime/1_000_000, adder.sum());
        /*
         * 选择指南：
         *   低竞争（线程少）：三种差不多，synchronized 语义最清晰
         *   中等竞争：AtomicInteger（CAS无锁，但高竞争时自旋浪费CPU）
         *   高竞争（线程多）：LongAdder（分槽减少竞争，吞吐最高）
         *   需要读最新值：AtomicInteger（sum() 不保证实时性，LongAdder 只适合统计）
         */
    }

    // ============================================================
    // 三、读写分离：ReadWriteLock
    // ============================================================
    static class Cache {
        private final Map<String, String> data = new HashMap<String, String>();
        private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
        private final Lock readLock  = rwLock.readLock();
        private final Lock writeLock = rwLock.writeLock();

        // 读操作：多个线程可以同时读（读锁共享）
        public String get(String key) {
            readLock.lock();
            try {
                return data.get(key);
            } finally {
                readLock.unlock();
            }
        }

        // 写操作：独占，写时不能有任何读或写（写锁排他）
        public void put(String key, String value) {
            writeLock.lock();
            try {
                data.put(key, value);
            } finally {
                writeLock.unlock();
            }
        }
        // 适合场景：读多写少（如配置缓存、元数据查询）
        // synchronized 会让读操作也互斥，浪费并发度
    }

    // ============================================================
    // 四、线程池调优
    // ============================================================
    static void threadPoolDemo() throws Exception {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        System.out.println("CPU 核数：" + cpuCores);

        // ---- IO 密集型线程池（数据库查询、网络请求）----
        // 线程大部分时间在等待，多开线程让 CPU 不闲置
        ThreadPoolExecutor ioPool = new ThreadPoolExecutor(
                cpuCores * 2,                    // 核心线程：CPU*2
                cpuCores * 4,                    // 最大线程：CPU*4
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(200),  // 有界队列，防 OOM
                new ThreadFactory() {
                    int count = 0;
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "io-pool-" + (++count));
                        t.setDaemon(true);       // 守护线程，主线程退出自动结束
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy() // 队列满了主线程自己跑，实现反压
        );

        // ---- CPU 密集型线程池（图像处理、加解密、压缩）----
        // 线程太多反而增加切换开销，对齐 CPU 核数
        ThreadPoolExecutor cpuPool = new ThreadPoolExecutor(
                cpuCores + 1,                    // 核心线程：CPU+1（+1防止某线程偶发阻塞）
                cpuCores + 1,                    // 最大线程=核心线程（不需要动态扩容）
                0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(50),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()     // 拒绝时抛异常，让调用方感知
        );

        // 使用 IO 线程池
        List<Future<String>> futures = new ArrayList<Future<String>>();
        for (int i = 0; i < 10; i++) {
            final int id = i;
            futures.add(ioPool.submit(() -> {
                Thread.sleep(10); // 模拟 IO 等待
                return "任务" + id + "完成（线程：" + Thread.currentThread().getName() + ")";
            }));
        }
        for (Future<String> f : futures) {
            System.out.println(f.get());
        }

        // 关闭线程池
        ioPool.shutdown();
        cpuPool.shutdown();
        ioPool.awaitTermination(5, TimeUnit.SECONDS);
        cpuPool.awaitTermination(5, TimeUnit.SECONDS);
    }

    // ============================================================
    // 五、并行计算（Fork/Join 分治）
    // ============================================================
    static class ParallelSum extends RecursiveTask<Long> {
        private static final int THRESHOLD = 1000; // 低于此值直接计算，不再分割
        private final long[] array;
        private final int start, end;

        ParallelSum(long[] array, int start, int end) {
            this.array = array;
            this.start = start;
            this.end   = end;
        }

        @Override
        protected Long compute() {
            if (end - start <= THRESHOLD) {
                // 足够小，直接顺序计算
                long sum = 0;
                for (int i = start; i < end; i++) sum += array[i];
                return sum;
            }
            // 一分为二，并行计算
            int mid = start + (end - start) / 2;
            ParallelSum left  = new ParallelSum(array, start, mid);
            ParallelSum right = new ParallelSum(array, mid, end);
            left.fork();             // 左半部分异步提交给线程池
            long rightResult = right.compute();  // 右半部分当前线程计算
            long leftResult  = left.join();      // 等待左半部分结果
            return leftResult + rightResult;
        }
    }

    static void forkJoinDemo() throws Exception {
        int n = 10_000_000;
        long[] array = new long[n];
        for (int i = 0; i < n; i++) array[i] = i + 1;

        // 顺序求和
        long t1 = System.nanoTime();
        long seqSum = 0;
        for (long v : array) seqSum += v;
        long seqTime = System.nanoTime() - t1;

        // Fork/Join 并行求和
        ForkJoinPool pool = new ForkJoinPool(); // 默认并行度=CPU核数
        long t2 = System.nanoTime();
        long parSum = pool.invoke(new ParallelSum(array, 0, n));
        long parTime = System.nanoTime() - t2;

        pool.shutdown();

        System.out.printf("顺序求和：%,d ms，结果：%d%n", seqTime/1_000_000, seqSum);
        System.out.printf("并行求和：%,d ms，结果：%d%n", parTime/1_000_000, parSum);
        System.out.printf("加速比：%.1fx（CPU核数：%d）%n",
                (double)seqTime/parTime, Runtime.getRuntime().availableProcessors());
    }

    // ============================================================
    // 六、避免锁的常见错误
    // ============================================================
    static void lockMistakes() {
        // 错误一：锁 String 字面量（不同对象可能是同一个常量池对象，范围失控）
        String key = "userLock";
        synchronized (key) { /* 危险！ */ }
        // 应该用：private final Object lock = new Object();

        // 错误二：锁 Integer/Boolean 等包装类型（缓存导致意外共享）
        Integer id = 1;
        synchronized (id) { /* 危险！-128~127 的 Integer 是共享缓存对象 */ }

        // 错误三：锁 this，但 this 被外部持有（锁被外部代码干扰）
        // 应该用私有锁对象

        // 正确做法：始终用私有的 final Object 作为锁
        final Object privateLock = new Object();
        synchronized (privateLock) { /* 安全 */ }

        System.out.println("锁错误演示完毕（看注释）");
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) throws Exception {
        System.out.println("=== 无锁化对比 ===");
        lockFreeDemo();

        System.out.println("\n=== 线程池演示 ===");
        threadPoolDemo();

        System.out.println("\n=== Fork/Join 并行求和 ===");
        forkJoinDemo();

        System.out.println("\n=== 锁使用注意 ===");
        lockMistakes();

        /*
         * 并发优化核心原则：
         *   1. 能无锁就无锁（AtomicXxx / LongAdder / ConcurrentHashMap）
         *   2. 必须加锁时，锁粒度尽量小（只锁临界区，不锁整个方法）
         *   3. 读多写少时用 ReadWriteLock，提升读并发度
         *   4. 线程池大小要匹配任务类型：IO密集=CPU*2~4，CPU密集=CPU+1
         *   5. 大数据量计算考虑 Fork/Join 分治并行
         *   6. 线程池队列必须有界，防止任务堆积导致 OOM
         */
    }
}
