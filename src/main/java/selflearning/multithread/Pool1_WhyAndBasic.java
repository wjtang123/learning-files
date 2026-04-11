package selflearning.multithread;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 线程池入门：为什么需要线程池 + ExecutorService 基本用法
 *
 * 为什么不直接 new Thread()？
 *   问题1 - 性能浪费：每次 new Thread() 都要向操作系统申请资源，
 *           用完再销毁，频繁创建销毁开销很大（像每次打车都要造一辆车）
 *   问题2 - 无法限制数量：如果同时来了 10000 个请求，创建 10000 个线程，
 *           内存直接耗尽，系统崩溃
 *   问题3 - 难以统一管理：线程散落各处，无法统一监控、取消、异常处理
 *
 * 线程池的思路（像出租车公司）：
 *   - 预先创建好一批线程（司机）待命
 *   - 任务来了（乘客），从池中取一个空闲线程执行
 *   - 任务完成后，线程不销毁，回到池中等待下一个任务
 *   - 同时限制最大线程数，超出的任务排队等候
 */
public class Pool1_WhyAndBasic {

    // 模拟一个耗时任务
    static class Task implements Runnable {
        private final int taskId;

        public Task(int taskId) {
            this.taskId = taskId;
        }

        @Override
        public void run() {
            System.out.printf("  任务 #%02d 开始 → 线程：%s%n",
                    taskId, Thread.currentThread().getName());
            try {
                Thread.sleep(500); // 模拟耗时 500ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.printf("  任务 #%02d 完成 ✓%n", taskId);
        }
    }

    public static void main(String[] args) throws InterruptedException {

        // ============================================================
        // 示例一：固定大小线程池 —— 最常用
        // Executors.newFixedThreadPool(n)
        //   始终保持 n 个线程，多余任务排队等候
        //   适合：任务量稳定、需要限制并发数的场景
        // ============================================================
        System.out.println("=== 固定大小线程池（3个线程，提交8个任务）===");
        ExecutorService fixedPool = Executors.newFixedThreadPool(3);

        for (int i = 1; i <= 8; i++) {
            fixedPool.execute(new Task(i)); // execute() 提交 Runnable 任务
        }

        // shutdown()：不再接受新任务，等待已提交任务全部完成后关闭
        // 注意：不调用 shutdown() 程序不会退出，线程池会一直等待新任务
        fixedPool.shutdown();
        // awaitTermination()：等待线程池完全关闭，最多等 10 秒
        fixedPool.awaitTermination(10, TimeUnit.SECONDS);
        System.out.println("固定线程池全部完成\n");

        // ============================================================
        // 示例二：单线程线程池
        // Executors.newSingleThreadExecutor()
        //   只有 1 个线程，任务严格按提交顺序执行（串行）
        //   适合：需要保证任务顺序执行的场景（如日志写入）
        // ============================================================
        System.out.println("=== 单线程线程池（任务按顺序执行）===");
        ExecutorService singlePool = Executors.newSingleThreadExecutor();

        for (int i = 1; i <= 4; i++) {
            final int id = i;
            singlePool.execute(() ->
                System.out.println("  顺序任务 #" + id + " 执行，线程：" + Thread.currentThread().getName())
            );
        }
        singlePool.shutdown();
        singlePool.awaitTermination(10, TimeUnit.SECONDS);
        System.out.println("单线程池完成\n");

        // ============================================================
        // 示例三：缓存线程池
        // Executors.newCachedThreadPool()
        //   线程数量不固定，按需创建，空闲 60s 后自动回收
        //   适合：任务量波动大、任务执行时间短的场景
        //   警告：任务量暴增时可能创建大量线程，生产环境慎用！
        // ============================================================
        System.out.println("=== 缓存线程池（按需创建线程）===");
        ExecutorService cachedPool = Executors.newCachedThreadPool();

        for (int i = 1; i <= 5; i++) {
            final int id = i;
            cachedPool.execute(() ->
                System.out.println("  缓存任务 #" + id + " 线程：" + Thread.currentThread().getName())
            );
        }
        cachedPool.shutdown();
        cachedPool.awaitTermination(10, TimeUnit.SECONDS);

        /*
         * 运行后观察：
         *   1. 固定线程池：同时只有 3 个线程在跑，其余排队；
         *      线程名字如 pool-1-thread-1/2/3，可以看到线程被复用
         *   2. 单线程池：任务严格按 1→2→3→4 顺序执行，不会乱序
         *   3. 缓存线程池：5个任务几乎同时开始，可能用了 5 个不同线程
         *
         * 重要：shutdown() 和 shutdownNow() 的区别：
         *   shutdown()     → 温和关闭，等队列里的任务都跑完再停
         *   shutdownNow()  → 强制关闭，尝试中断正在运行的线程，返回未执行任务列表
         */
    }
}
