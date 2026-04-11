package selflearning.multithread;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/**
 * 创建线程方式三：Callable + FutureTask
 *
 * 前两种方式的 run() 返回值是 void，无法拿到线程的执行结果。
 * Callable 解决了这个问题：
 *   - Runnable.run()  → 无返回值，不能抛受检异常
 *   - Callable.call() → 有返回值（泛型），可以抛异常
 *
 * 使用场景：需要线程做完计算后把结果带回来，比如并行计算、异步请求等。
 *
 * 关系链：Callable → FutureTask → Thread
 *   FutureTask 是中间桥梁：它实现了 Runnable（所以能传给Thread），
 *   同时持有 Callable，执行时调用 call() 并保存结果。
 */
public class Method3_CallableFuture {

    // 第一步：实现 Callable 接口，泛型指定返回值类型
    static class SumTask implements Callable<Integer> {

        private int start;
        private int end;

        public SumTask(int start, int end) {
            this.start = start;
            this.end = end;
        }

        // 第二步：实现 call() 方法，有返回值
        @Override
        public Integer call() throws Exception {
            int sum = 0;
            for (int i = start; i <= end; i++) {
                sum += i;
                Thread.sleep(10); // 模拟耗时计算
            }
            System.out.println(Thread.currentThread().getName()
                    + " 计算完成，结果=" + sum);
            return sum; // 返回计算结果
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 单个 Callable 示例 ===");

        // 第三步：把 Callable 包进 FutureTask
        FutureTask<Integer> futureTask = new FutureTask<>(new SumTask(1, 100));

        // 第四步：把 FutureTask 交给 Thread 启动
        Thread t = new Thread(futureTask, "计算线程");
        t.start();

        System.out.println("主线程继续做其他事情...");
        Thread.sleep(500); // 模拟主线程在做别的工作

        // 第五步：get() 获取结果
        // 如果线程还没算完，get() 会阻塞等待，直到拿到结果
        Integer result = futureTask.get();
        System.out.println("1+2+...+100 = " + result);

        // -----------------------------------------------
        System.out.println("\n=== 多线程并行计算示例 ===");
        // 把 1~1000 的求和拆分给两个线程并行计算，最后汇总
        // （体现多线程加速的思路）

        FutureTask<Integer> task1 = new FutureTask<>(new SumTask(1, 500));
        FutureTask<Integer> task2 = new FutureTask<>(new SumTask(501, 1000));

        new Thread(task1, "线程-前半段").start();
        new Thread(task2, "线程-后半段").start();

        // 分别等待两个线程的结果，再相加
        int total = task1.get() + task2.get();
        System.out.println("1+2+...+1000 = " + total); // 期望：500500

        // -----------------------------------------------
        System.out.println("\n=== isDone() 非阻塞查询 ===");

        FutureTask<Integer> slowTask = new FutureTask<>(new SumTask(1, 50));
        new Thread(slowTask, "慢线程").start();

        // 轮询检查，不强制等待
        while (!slowTask.isDone()) {
            System.out.println("任务还没完成，主线程等一下...");
            Thread.sleep(100);
        }
        System.out.println("任务完成！结果：" + slowTask.get());

        /*
         * 运行后观察：
         *   - 主线程和计算线程是并发执行的
         *   - get() 调用时如果结果还没好，主线程会在此暂停等待
         *   - 两个线程并行计算的速度比单线程顺序计算更快
         */
    }
}
