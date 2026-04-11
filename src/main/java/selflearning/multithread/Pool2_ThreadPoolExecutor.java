package selflearning.multithread;

import java.util.concurrent.*;

/**
 * ThreadPoolExecutor 七个核心参数详解
 *
 * Executors 工厂方法内部都是用 ThreadPoolExecutor 实现的，
 * 手动构建可以精确控制每一个参数，生产环境推荐这种方式。
 *
 * 构造器签名：
 * new ThreadPoolExecutor(
 *     int corePoolSize,          // 参数1：核心线程数
 *     int maximumPoolSize,       // 参数2：最大线程数
 *     long keepAliveTime,        // 参数3：空闲线程存活时间
 *     TimeUnit unit,             // 参数4：时间单位
 *     BlockingQueue<Runnable> workQueue,  // 参数5：任务队列
 *     ThreadFactory threadFactory,        // 参数6：线程工厂
 *     RejectedExecutionHandler handler    // 参数7：拒绝策略
 * )
 *
 * 线程池处理任务的完整流程（重点！）：
 *
 *   新任务到来
 *      │
 *      ▼
 *   当前线程数 < corePoolSize？
 *      ├─ 是 → 创建新的核心线程执行任务
 *      └─ 否 ▼
 *         任务队列未满？
 *            ├─ 是 → 任务放入队列等待
 *            └─ 否 ▼
 *               当前线程数 < maximumPoolSize？
 *                  ├─ 是 → 创建临时线程执行任务
 *                  └─ 否 → 触发拒绝策略！
 */
public class Pool2_ThreadPoolExecutor {

    // 带颜色标记的打印，方便区分不同事件
    static void log(String msg) {
        System.out.println("  [" + Thread.currentThread().getName() + "] " + msg);
    }

    public static void main(String[] args) throws InterruptedException {

        // ============================================================
        // 参数5：三种任务队列对比
        // ============================================================
        System.out.println("=== 参数5：任务队列类型 ===\n");

        // LinkedBlockingQueue(n)：有界队列，推荐！能控制内存上限
        // ArrayBlockingQueue(n)：有界队列，基于数组，内存更紧凑
        // SynchronousQueue：不存储任务，直接交给线程，队列容量=0
        // LinkedBlockingQueue()：无界队列（Integer.MAX_VALUE），危险！可能OOM

        System.out.println("队列类型选择建议：");
        System.out.println("  LinkedBlockingQueue(100)  → 有界队列，最常用");
        System.out.println("  ArrayBlockingQueue(100)   → 有界队列，内存紧凑");
        System.out.println("  SynchronousQueue()        → 零容量，适合 cachedThreadPool");
        System.out.println("  LinkedBlockingQueue()     → 无界队列，慎用（OOM风险）\n");

        // ============================================================
        // 参数7：四种拒绝策略对比（队列满 + 线程达到最大值时触发）
        // ============================================================
        System.out.println("=== 参数7：四种拒绝策略实验 ===");

        // 构建一个极小的线程池来快速触发拒绝策略
        // 核心线程=1，最大线程=2，队列容量=2 → 最多同时处理 4 个任务
        // 第 5 个任务开始触发拒绝

        // --- 策略一：AbortPolicy（默认）直接抛异常 ---
        System.out.println("\n--- AbortPolicy：抛出 RejectedExecutionException ---");
        ThreadPoolExecutor pool1 = new ThreadPoolExecutor(
                1, 2, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy() // 默认策略
        );
        submitTasks(pool1, 6, "AbortPolicy");
        pool1.shutdown();
        Thread.sleep(1500);

        // --- 策略二：CallerRunsPolicy 谁提交谁执行 ---
        System.out.println("\n--- CallerRunsPolicy：由提交任务的线程自己执行 ---");
        ThreadPoolExecutor pool2 = new ThreadPoolExecutor(
                1, 2, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy() // main线程自己跑溢出任务
        );
        submitTasks(pool2, 6, "CallerRunsPolicy");
        pool2.shutdown();
        pool2.awaitTermination(5, TimeUnit.SECONDS);

        // --- 策略三：DiscardPolicy 静默丢弃 ---
        System.out.println("\n--- DiscardPolicy：悄悄丢弃，不报错 ---");
        ThreadPoolExecutor pool3 = new ThreadPoolExecutor(
                1, 2, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.DiscardPolicy() // 多余任务直接丢弃
        );
        submitTasks(pool3, 6, "DiscardPolicy");
        pool3.shutdown();
        pool3.awaitTermination(5, TimeUnit.SECONDS);

        // --- 策略四：DiscardOldestPolicy 丢弃队列中最老的任务 ---
        System.out.println("\n--- DiscardOldestPolicy：丢弃队列头部最旧的任务 ---");
        ThreadPoolExecutor pool4 = new ThreadPoolExecutor(
                1, 2, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );
        submitTasks(pool4, 6, "DiscardOldestPolicy");
        pool4.shutdown();
        pool4.awaitTermination(5, TimeUnit.SECONDS);

        // ============================================================
        // 参数6：自定义 ThreadFactory（给线程起有意义的名字）
        // ============================================================
        System.out.println("\n=== 参数6：自定义 ThreadFactory ===");

        // 默认线程名是 pool-1-thread-1，出问题时很难定位
        // 自定义工厂可以给线程起有业务含义的名字，方便排查
        ThreadFactory namedFactory = new ThreadFactory() {
            private int count = 0;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("order-processor-" + (++count)); // 有业务含义的名字
                t.setDaemon(false); // 非守护线程
                return t;
            }
        };

        ThreadPoolExecutor namedPool = new ThreadPoolExecutor(
                2, 2, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10),
                namedFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );

        for (int i = 1; i <= 4; i++) {
            namedPool.execute(() ->
                System.out.println("  执行中，线程名：" + Thread.currentThread().getName())
            );
        }
        namedPool.shutdown();
        namedPool.awaitTermination(5, TimeUnit.SECONDS);

        /*
         * 运行后重点观察：
         *   1. AbortPolicy：第 5/6 个任务直接抛异常，但池子里的任务正常完成
         *   2. CallerRunsPolicy：溢出任务由 main 线程执行（线程名是 main）
         *      这种策略有"反压"效果：main 线程在执行时无法提交新任务，自动降速
         *   3. DiscardPolicy：只有 4 个任务完成，第 5/6 个任务消失，没有任何提示
         *   4. DiscardOldestPolicy：队列中最先等待的任务被踢掉，换新任务进来
         *   5. 自定义工厂：线程名变成 order-processor-1/2，日志一眼知道是哪个业务
         */
    }

    // 提交 n 个任务，捕获拒绝异常
    static void submitTasks(ThreadPoolExecutor pool, int count, String label) {
        for (int i = 1; i <= count; i++) {
            final int id = i;
            try {
                pool.execute(() -> {
                    log(label + " 任务#" + id + " 执行");
                    try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
                System.out.println("  任务 #" + id + " 已提交");
            } catch (RejectedExecutionException e) {
                System.out.println("  任务 #" + id + " 被拒绝！→ " + e.getClass().getSimpleName());
            }
        }
    }
}
