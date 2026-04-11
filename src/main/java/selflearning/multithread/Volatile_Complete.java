package selflearning.multithread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * volatile 完整讲解
 *
 * volatile 保证：
 *   ① 可见性：一个线程的修改，其他线程立刻可见
 *   ② 有序性：禁止指令重排序（通过内存屏障实现）
 *
 * volatile 不保证：
 *   ③ 原子性：复合操作（如 count++）仍然线程不安全
 *
 * 记住这个口诀：volatile 保证"看得见、不乱序"，但"不保平安（原子性）"
 */
public class Volatile_Complete {

    // ============================================================
    // 演示一：可见性问题 —— 没有 volatile 时线程可能永远看不到修改
    // ============================================================

    // 去掉 volatile 试试：子线程可能永远不会停止（死循环）
    // 因为子线程把 running 缓存在寄存器里，看不到主线程的修改
    static volatile boolean running = true;  // 加了 volatile：修改立刻可见

    static void visibilityDemo() throws InterruptedException {
        Thread worker = new Thread(() -> {
            int count = 0;
            while (running) {   // 依赖 running 的可见性
                count++;
            }
            System.out.println("  工作线程停止，count=" + count);
        });

        worker.start();
        Thread.sleep(10);     // 让工作线程跑一会儿

        running = false;       // 主线程修改 running
        System.out.println("  主线程设置 running=false");

        worker.join(1000);     // 最多等1秒
        if (worker.isAlive()) {
            System.out.println("  工作线程还在跑！（可见性失效）");
            worker.interrupt();
        } else {
            System.out.println("  工作线程正常退出（volatile 可见性生效）");
        }

        /*
         * 去掉 volatile 后运行（需在 -server 模式下更明显）：
         *   工作线程可能永远不退出，因为 JIT 把 running 优化进了寄存器，
         *   再也不去主内存读，主线程的修改对它不可见。
         */
    }

    // ============================================================
    // 演示二：volatile 不保证原子性！
    // ============================================================
    static volatile int volatileCount = 0;  // volatile 修饰
    static AtomicInteger atomicCount = new AtomicInteger(0); // 对照组

    static void atomicityDemo() throws InterruptedException {
        int threadNum = 10, loopNum = 1000;

        // 10个线程各对 volatile 变量 ++ 一千次
        Thread[] vThreads = new Thread[threadNum];
        for (int i = 0; i < threadNum; i++) {
            vThreads[i] = new Thread(() -> {
                for (int j = 0; j < loopNum; j++) {
                    volatileCount++;    // ← 不是原子操作！
                    // 等价于：
                    //   int tmp = volatileCount;   // 读（volatile 保证读到最新值）
                    //   tmp = tmp + 1;              // 加
                    //   volatileCount = tmp;        // 写（volatile 保证写回主内存）
                    // 但读和写之间可以被其他线程插入，仍然有竞态条件！
                }
            });
        }
        for (Thread t : vThreads) t.start();
        for (Thread t : vThreads) t.join();

        // 对照：AtomicInteger 的 incrementAndGet 保证原子性
        Thread[] aThreads = new Thread[threadNum];
        for (int i = 0; i < threadNum; i++) {
            aThreads[i] = new Thread(() -> {
                for (int j = 0; j < loopNum; j++) atomicCount.incrementAndGet();
            });
        }
        for (Thread t : aThreads) t.start();
        for (Thread t : aThreads) t.join();

        System.out.println("  期望结果：" + (threadNum * loopNum));
        System.out.printf("  volatile count：%d  %s%n", volatileCount,
                volatileCount == threadNum * loopNum ? "（运气好）" : "← 数据丢失！");
        System.out.printf("  AtomicInteger  ：%d  ✓%n", atomicCount.get());
    }

    // ============================================================
    // 演示三：有序性 —— 双重检查锁（DCL）单例，面试必考！
    // ============================================================
    static class Singleton {
        // 必须加 volatile！否则其他线程可能拿到"未初始化完成"的对象
        private static volatile Singleton instance;

        private int value;

        private Singleton() {
            this.value = 42;   // 构造过程
        }

        public static Singleton getInstance() {
            if (instance == null) {              // 第一次检查（不加锁，性能好）
                synchronized (Singleton.class) {
                    if (instance == null) {      // 第二次检查（加锁后再确认）
                        instance = new Singleton();
                        // 没有 volatile 时，这一行可能被重排序为：
                        //   ① 分配内存
                        //   ② 把引用写入 instance  ← 此刻 instance != null，但对象未初始化！
                        //   ③ 执行构造方法
                        // 另一个线程在②之后进来，第一次检查 instance != null，
                        // 直接返回了一个"半初始化"的对象，value 还是 0！
                        //
                        // 加了 volatile 后：
                        // volatile 写操作前会插入 StoreStore 屏障，
                        // 保证构造方法一定在 instance 引用写入前完成，
                        // 其他线程看到 instance != null 时，对象一定是完整的。
                    }
                }
            }
            return instance;
        }

        public int getValue() { return value; }
    }

    // ============================================================
    // 演示四：volatile 的正确使用场景
    // ============================================================

    // 场景A：状态标志位（最经典的正确用法）
    // 一个线程写，其他线程只读——满足 volatile 的使用条件
    static volatile boolean shutdownRequested = false;

    static void correctUsage() throws InterruptedException {
        // 场景A：开关控制
        Thread service = new Thread(() -> {
            while (!shutdownRequested) {
                // 模拟服务运行
            }
            System.out.println("  服务收到关闭信号，正常退出");
        });
        service.start();
        Thread.sleep(50);
        shutdownRequested = true;  // 一个线程写
        service.join(500);

        // 场景B：double/long 的原子读写
        // 64位变量在32位JVM上读写不是原子的，volatile 可以修复
        // volatile double price; // 保证 price 的读写是原子的

        // 场景C：配合 synchronized 的"发布"模式
        // volatile 保证对象引用的可见性，synchronized 保证构造的完整性
        // → 即上面的 DCL 单例模式
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== 演示一：可见性 ===");
        visibilityDemo();

        // 重置
        volatileCount = 0;
        System.out.println("\n=== 演示二：不保证原子性 ===");
        atomicityDemo();

        System.out.println("\n=== 演示三：DCL 单例 ===");
        Singleton s1 = Singleton.getInstance();
        Singleton s2 = Singleton.getInstance();
        System.out.println("  同一个对象？" + (s1 == s2));
        System.out.println("  value = " + s1.getValue() + "（应为 42，不是 0）");

        System.out.println("\n=== 演示四：正确使用场景 ===");
        correctUsage();
    }
}
