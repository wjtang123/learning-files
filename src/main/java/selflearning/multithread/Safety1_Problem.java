package selflearning.multithread;

/**
 * 线程安全问题演示：不加锁时，多线程并发修改共享变量会出错
 *
 * 场景：模拟 10 个线程，每个线程对同一个计数器执行 1000 次 +1
 * 预期结果：10 * 1000 = 10000
 * 实际结果：每次运行都可能小于 10000，且结果不固定
 *
 * 为什么会出错？—— 理解"原子性"
 *
 *   count++ 看起来是一行代码，但 CPU 实际执行的是三步：
 *     1. 从内存读取 count 的值到寄存器（READ）
 *     2. 在寄存器中 +1（ADD）
 *     3. 把结果写回内存（WRITE）
 *
 *   当两个线程同时执行：
 *     线程A 读到 count=100
 *     线程B 读到 count=100   ← 此时A还没写回
 *     线程A 写回 count=101
 *     线程B 写回 count=101   ← B覆盖了A的结果！
 *
 *   两次 +1 的操作，count 只增加了 1，丢失了一次更新，
 *   这就叫"竞态条件"（Race Condition）。
 */
public class Safety1_Problem {

    // 共享变量：多个线程都会读写它
    static int count = 0;

    // 每个线程执行的任务：循环 +1 一千次
    static class CountTask implements Runnable {
        @Override
        public void run() {
            for (int i = 0; i < 1000; i++) {
                count++; // 问题就在这里！不是原子操作
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // 创建 10 个线程，共享同一个任务对象
        Thread[] threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(new CountTask(), "线程-" + i);
        }

        // 启动所有线程
        for (Thread t : threads) {
            t.start();
        }

        // 等所有线程执行完
        for (Thread t : threads) {
            t.join();
        }

        // 打印最终结果
        System.out.println("期望结果：10000");
        System.out.println("实际结果：" + count);
        System.out.println(count == 10000 ? "结果正确！（运气好，没触发竞态）" : "结果错误！出现了竞态条件");

        /*
         * 运行多次观察：
         *   - 结果几乎每次都不同，且绝大多数情况小于 10000
         *   - 偶尔会等于 10000（线程恰好没有交叉执行），但这是运气，不是正确性
         *   - 多运行几次（Run 5~10次），感受结果的不确定性
         *
         * 这就是线程安全问题的根本：共享可变状态 + 并发访问 = 数据错误
         */
    }
}
