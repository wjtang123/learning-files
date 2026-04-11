package selflearning.multithread;

import java.util.LinkedList;
import java.util.Queue;

/**
 * wait() / notify() 实现生产者消费者模式
 *
 * 场景：
 *   一个仓库（容量=5），生产者往里放商品，消费者从里取商品。
 *   - 仓库满了：生产者必须等待，直到消费者取走商品
 *   - 仓库空了：消费者必须等待，直到生产者放入商品
 *
 * wait() 和 notify() 的规则（必须记住！）：
 *   1. 必须在 synchronized 块内调用，否则抛 IllegalMonitorStateException
 *   2. wait()     → 释放锁 + 线程进入 WAITING 状态，等待被唤醒
 *   3. notify()   → 唤醒一个等待该锁的线程（随机选一个）
 *   4. notifyAll()→ 唤醒所有等待该锁的线程（推荐，更安全）
 *   5. 被唤醒后，线程重新竞争锁，拿到锁后从 wait() 处继续执行
 *
 * wait() vs sleep() 的核心区别：
 *   - wait()  释放锁，让其他线程能进入同步块
 *   - sleep() 不释放锁，其他线程依然进不来
 */
public class Safety3_ProducerConsumer {

    // ============================================================
    // 仓库：线程安全的共享缓冲区
    // ============================================================
    static class Warehouse {
        private final Queue<Integer> buffer = new LinkedList<>();
        private final int capacity; // 仓库最大容量
        private int productId = 0;  // 商品编号，自增

        public Warehouse(int capacity) {
            this.capacity = capacity;
        }

        /**
         * 生产者调用：向仓库放入一个商品
         * 如果仓库满了，等待消费者取走后再放
         */
        public synchronized void produce() throws InterruptedException {
            // 用 while 而不是 if！
            // 原因：notifyAll 会唤醒所有等待线程，线程醒来后需要重新检查条件
            // 如果用 if，可能出现"虚假唤醒"问题（条件其实没满足就继续执行）
            while (buffer.size() == capacity) {
                System.out.println("  [生产者-" + Thread.currentThread().getName()
                        + "] 仓库已满（" + capacity + "），等待消费者...");
                wait(); // 释放锁，进入 WAITING，等待 notify
            }

            // 走到这里说明仓库有空位
            productId++;
            buffer.offer(productId);
            System.out.println("  [生产者-" + Thread.currentThread().getName()
                    + "] 生产了商品 #" + productId
                    + "，当前库存：" + buffer.size() + "/" + capacity);

            // 通知所有等待的消费者：仓库有货了，可以来取
            notifyAll();
        }

        /**
         * 消费者调用：从仓库取出一个商品
         * 如果仓库空了，等待生产者放入后再取
         */
        public synchronized void consume() throws InterruptedException {
            // 同样用 while 检查条件
            while (buffer.isEmpty()) {
                System.out.println("  [消费者-" + Thread.currentThread().getName()
                        + "] 仓库已空，等待生产者...");
                wait(); // 释放锁，进入 WAITING，等待 notify
            }

            // 走到这里说明仓库有货
            int product = buffer.poll();
            System.out.println("  [消费者-" + Thread.currentThread().getName()
                    + "] 消费了商品 #" + product
                    + "，剩余库存：" + buffer.size() + "/" + capacity);

            // 通知所有等待的生产者：仓库有空位了，可以来放
            notifyAll();
        }
    }

    // ============================================================
    // 生产者线程
    // ============================================================
    static class Producer implements Runnable {
        private final Warehouse warehouse;
        private final int produceCount; // 生产几次

        public Producer(Warehouse warehouse, int produceCount) {
            this.warehouse = warehouse;
            this.produceCount = produceCount;
        }

        @Override
        public void run() {
            for (int i = 0; i < produceCount; i++) {
                try {
                    Thread.sleep(100); // 模拟生产耗时
                    warehouse.produce();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // ============================================================
    // 消费者线程
    // ============================================================
    static class Consumer implements Runnable {
        private final Warehouse warehouse;
        private final int consumeCount; // 消费几次

        public Consumer(Warehouse warehouse, int consumeCount) {
            this.warehouse = warehouse;
            this.consumeCount = consumeCount;
        }

        @Override
        public void run() {
            for (int i = 0; i < consumeCount; i++) {
                try {
                    Thread.sleep(150); // 消费比生产慢一点，模拟供需不平衡
                    warehouse.consume();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) throws InterruptedException {
        Warehouse warehouse = new Warehouse(5); // 仓库容量为 5

        // 2个生产者，每人生产 8 次，共 16 个商品
        Thread p1 = new Thread(new Producer(warehouse, 8), "P1");
        Thread p2 = new Thread(new Producer(warehouse, 8), "P2");

        // 3个消费者，前两个消费 5 次，最后一个消费 6 次，共 16 次
        Thread c1 = new Thread(new Consumer(warehouse, 5), "C1");
        Thread c2 = new Thread(new Consumer(warehouse, 5), "C2");
        Thread c3 = new Thread(new Consumer(warehouse, 6), "C3");

        System.out.println("=== 生产者消费者启动（仓库容量：5）===\n");

        // 先启动消费者（仓库为空，消费者会进入等待）
        c1.start(); c2.start(); c3.start();
        // 再启动生产者
        p1.start(); p2.start();

        // 等所有线程结束
        p1.join(); p2.join();
        c1.join(); c2.join(); c3.join();

        System.out.println("\n=== 全部完成！===");

        /*
         * 运行后观察：
         *   1. 消费者先启动，立刻打印"仓库已空，等待生产者"
         *   2. 生产者放入商品后，调用 notifyAll() 唤醒消费者
         *   3. 当仓库满（5个）时，生产者会打印"仓库已满，等待消费者"
         *   4. 生产和消费总数平衡（各16次），最终全部完成，不会死锁
         *
         * 尝试改动：
         *   - 把 notifyAll() 改成 notify()，观察是否会偶尔卡住（死锁风险）
         *   - 把 while 改成 if，多运行几次看是否出现异常
         *   - 调整生产者/消费者的 sleep 时间，模拟不同的供需节奏
         */
    }
}
