package selflearning.multithread;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 线程池进阶：submit() 获取返回值 + 监控 + 生产实践
 *
 * execute() vs submit() 的区别：
 *   execute(Runnable)  → 提交无返回值任务，异常会直接打印到控制台
 *   submit(Runnable)   → 提交无返回值任务，返回 Future<?> 可感知完成
 *   submit(Callable)   → 提交有返回值任务，返回 Future<T> 可拿结果/异常
 */
public class Pool3_AdvancedAndPractice {

    public static void main(String[] args) throws Exception {

        // ============================================================
        // 示例一：submit(Callable) 获取返回值
        // ============================================================
        System.out.println("=== submit() 获取返回值 ===");

        ExecutorService pool = Executors.newFixedThreadPool(3);

        // 提交多个有返回值的任务，收集 Future
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            final int num = i * 10;
            Future<Integer> future = pool.submit(() -> {
                Thread.sleep(300);
                return num * num; // 返回平方值
            });
            futures.add(future);
        }

        // 收集所有结果（get() 会阻塞直到对应任务完成）
        int total = 0;
        for (int i = 0; i < futures.size(); i++) {
            int result = futures.get(i).get(); // 拿到每个任务的结果
            System.out.printf("  任务%d 结果：%d%n", i + 1, result);
            total += result;
        }
        System.out.println("  汇总结果：" + total);

        // ============================================================
        // 示例二：submit() 的异常处理（重要！）
        // ============================================================
        System.out.println("\n=== submit() 的异常捕获 ===");

        // 用 execute() 提交：异常直接打印，无法在主线程捕获
        // 用 submit() 提交：异常被包在 Future 里，调用 get() 时才抛出
        Future<?> badFuture = pool.submit(() -> {
            throw new RuntimeException("任务内部出错了！");
        });

        try {
            badFuture.get(); // 异常在这里被抛出（包装成 ExecutionException）
        } catch (ExecutionException e) {
            System.out.println("  捕获到任务异常：" + e.getCause().getMessage());
        }

        // ============================================================
        // 示例三：invokeAll() 等待所有任务完成
        // ============================================================
        System.out.println("\n=== invokeAll()：批量提交，等全部完成 ===");

        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            final int id = i;
            tasks.add(() -> {
                Thread.sleep(id * 100L); // 不同任务耗时不同
                return "任务" + id + "的结果";
            });
        }

        // invokeAll() 会阻塞，直到所有任务都完成（或超时）
        List<Future<String>> results = pool.invokeAll(tasks);
        for (Future<String> f : results) {
            System.out.println("  " + f.get());
        }

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        // ============================================================
        // 示例四：线程池状态监控（运维必备）
        // ============================================================
        System.out.println("\n=== 线程池监控 ===");

        ThreadPoolExecutor monitorPool = new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // 提交一批任务
        for (int i = 0; i < 8; i++) {
            monitorPool.execute(() -> {
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
        }

        // 任务运行期间采样监控数据
        Thread.sleep(100);
        printPoolStatus(monitorPool, "运行中");

        monitorPool.shutdown();
        monitorPool.awaitTermination(5, TimeUnit.SECONDS);
        printPoolStatus(monitorPool, "关闭后");

        // ============================================================
        // 生产实践：推荐的参数配置公式
        // ============================================================
        System.out.println("\n=== 生产实践：核心线程数怎么设？===");
        int cpuCores = Runtime.getRuntime().availableProcessors();
        System.out.println("  当前机器 CPU 核心数：" + cpuCores);
        System.out.println();
        System.out.println("  CPU 密集型任务（大量计算，如加密、压缩）：");
        System.out.println("    核心线程数 = CPU核心数 + 1  → " + (cpuCores + 1));
        System.out.println("    原因：线程数超过核心数没有意义，CPU已满负荷，多线程只会增加切换开销");
        System.out.println();
        System.out.println("  IO 密集型任务（数据库、网络请求，大量等待）：");
        System.out.println("    核心线程数 = CPU核心数 × 2  → " + (cpuCores * 2));
        System.out.println("    原因：线程等待IO时CPU空闲，可以多开线程充分利用CPU");
        System.out.println();
        System.out.println("  精确公式（需压测调整）：");
        System.out.println("    核心线程数 = CPU核心数 × (1 + 等待时间/计算时间)");

        /*
         * 运行后观察：
         *   1. submit() 的结果按提交顺序返回（get(0) 拿第一个任务结果），
         *      即使任务不按顺序完成，get() 会等到对应任务结束
         *   2. submit() 的异常不会直接打印，必须调用 get() 才能发现
         *      这是生产中异常被吞掉的常见原因！养成习惯处理 Future 的异常
         *   3. 监控数据帮助判断线程池是否健康：
         *      - 队列积压多 → 考虑增大核心线程数或最大线程数
         *      - 线程数经常到最大值 → 考虑扩容或优化任务执行速度
         *
         * 生产中一定要避免的坑：
         *   ❌ Executors.newFixedThreadPool(n)   队列无界，可能 OOM
         *   ❌ Executors.newCachedThreadPool()   线程无上限，可能 OOM
         *   ✅ 手动 new ThreadPoolExecutor(...)  所有参数可控，推荐
         */
    }

    // 打印线程池当前状态的工具方法
    static void printPoolStatus(ThreadPoolExecutor pool, String tag) {
        System.out.printf("  [%s] 核心线程数:%d | 当前线程数:%d | 活跃线程数:%d | 队列积压:%d | 完成任务数:%d%n",
                tag,
                pool.getCorePoolSize(),       // 核心线程数配置值
                pool.getPoolSize(),           // 当前实际线程数
                pool.getActiveCount(),        // 正在执行任务的线程数
                pool.getQueue().size(),       // 队列中等待的任务数
                pool.getCompletedTaskCount()  // 历史累计完成任务数
        );
    }
}
