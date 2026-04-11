package selflearning.multithread;

import java.util.concurrent.atomic.*;

/**
 * AtomicInteger 及原子类家族：无锁线程安全操作
 *
 * 回顾问题：count++ 不是原子操作（读-改-写三步），多线程下会丢数据。
 * 解决方案对比：
 *   方案A：synchronized / ReentrantLock → 加锁，同一时刻只有一个线程执行
 *   方案B：AtomicInteger               → 无锁，用 CAS 硬件指令保证原子性
 *
 * CAS（Compare And Swap）原理：
 *   CAS(内存地址, 期望值, 新值)
 *   含义：只有当内存中的值 == 期望值时，才把它改成新值，否则什么都不做
 *   这是 CPU 级别的原子指令，不需要加锁，由硬件保证原子性
 *
 *   count++ 用 CAS 的过程：
 *     1. 读取 count 当前值，假设是 100（期望值）
 *     2. 计算新值 = 101
 *     3. CAS(count地址, 100, 101)：
 *        → 如果此刻 count 还是 100，成功写入 101，返回 true
 *        → 如果此刻 count 已被别人改成 101，失败，返回 false
 *     4. 失败就重试（自旋），直到成功
 *
 *   这种"失败重试"叫做自旋（spin），不需要线程挂起/唤醒，
 *   所以在竞争不激烈时比加锁快很多。
 */
public class JUC2_AtomicInteger {

    // ============================================================
    // 示例一：AtomicInteger 基本 API
    // ============================================================
    static void basicApi() {
        AtomicInteger ai = new AtomicInteger(0);

        System.out.println("初始值：" + ai.get());                    // 读取：0
        System.out.println("getAndIncrement（后++）：" + ai.getAndIncrement()); // 0，然后变1
        System.out.println("incrementAndGet（前++）：" + ai.incrementAndGet()); // 先变2，返回2
        System.out.println("getAndAdd(10)：" + ai.getAndAdd(10));    // 返回2，然后变12
        System.out.println("addAndGet(10)：" + ai.addAndGet(10));    // 先变22，返回22
        System.out.println("当前值：" + ai.get());                    // 22

        // compareAndSet：CAS 的直接暴露
        boolean success = ai.compareAndSet(22, 100); // 期望是22，改成100
        System.out.println("CAS(22→100) 成功？" + success + "，当前值：" + ai.get());

        boolean fail = ai.compareAndSet(22, 200); // 期望是22，但现在是100，失败
        System.out.println("CAS(22→200) 成功？" + fail + "，当前值：" + ai.get());
    }

    // ============================================================
    // 示例二：性能对比 —— 无锁 vs 加锁
    // ============================================================
    static int unsafeCount = 0;                          // 无保护（会出错）
    static synchronized void syncIncrement() {           // synchronized 锁
        unsafeCount++;
    }

    static void performanceCompare() throws InterruptedException {
        int threadCount = 10;
        int loopCount = 100_000;

        // --- AtomicInteger ---
        AtomicInteger atomicCount = new AtomicInteger(0);
        long t1 = System.currentTimeMillis();
        Thread[] threads1 = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads1[i] = new Thread(() -> {
                for (int j = 0; j < loopCount; j++) atomicCount.incrementAndGet();
            });
        }
        for (Thread t : threads1) t.start();
        for (Thread t : threads1) t.join();
        long atomicTime = System.currentTimeMillis() - t1;

        // --- synchronized ---
        unsafeCount = 0;
        long t2 = System.currentTimeMillis();
        Thread[] threads2 = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads2[i] = new Thread(() -> {
                for (int j = 0; j < loopCount; j++) syncIncrement();
            });
        }
        for (Thread t : threads2) t.start();
        for (Thread t : threads2) t.join();
        long syncTime = System.currentTimeMillis() - t2;

        System.out.printf("  AtomicInteger  结果：%d，耗时：%d ms%n",
                atomicCount.get(), atomicTime);
        System.out.printf("  synchronized   结果：%d，耗时：%d ms%n",
                unsafeCount, syncTime);
        System.out.println("  （竞争越激烈，差距越小；低竞争时Atomic通常更快）");
    }

    // ============================================================
    // 示例三：原子类家族
    // ============================================================
    static void atomicFamily() throws InterruptedException {
        // AtomicLong：long 类型的原子操作
        AtomicLong atomicLong = new AtomicLong(0L);
        atomicLong.incrementAndGet();
        System.out.println("  AtomicLong：" + atomicLong.get());

        // AtomicBoolean：原子布尔，常用于"只执行一次"的标志位
        AtomicBoolean initialized = new AtomicBoolean(false);
        // compareAndSet 保证多线程下只有一个线程能把 false 改成 true
        if (initialized.compareAndSet(false, true)) {
            System.out.println("  AtomicBoolean：初始化任务，只会执行一次");
        }
        if (initialized.compareAndSet(false, true)) {
            System.out.println("  这行不会打印"); // 已经是 true 了
        }

        // AtomicReference：原子更新引用类型
        AtomicReference<String> atomicRef = new AtomicReference<>("初始值");
        boolean ok = atomicRef.compareAndSet("初始值", "新值");
        System.out.println("  AtomicReference CAS 成功？" + ok + "，当前：" + atomicRef.get());

        // LongAdder：高并发计数器，比 AtomicLong 在高竞争下更快
        // 原理：把一个计数器拆成多个"cell"，线程分散写不同 cell，读时汇总
        // 适合：只需要统计总数，不需要中间 CAS 结果的场景（如访问量统计）
        LongAdder adder = new LongAdder();
        Thread[] ts = new Thread[5];
        for (int i = 0; i < 5; i++) {
            ts[i] = new Thread(() -> {
                for (int j = 0; j < 1000; j++) adder.increment();
            });
        }
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
        System.out.println("  LongAdder 结果（5线程×1000次）：" + adder.sum());
    }

    // ============================================================
    // 示例四：ABA 问题（CAS 的经典坑）
    // ============================================================
    static void abaProblem() throws InterruptedException {
        System.out.println("\n--- ABA 问题演示 ---");
        // ABA 问题：值从 A 变成 B，再变回 A
        // CAS 看到值还是 A，以为没变，但中间其实发生过变化
        // 在某些业务场景（如链表节点）这会产生错误

        AtomicInteger val = new AtomicInteger(100);

        // 线程1：准备 CAS(100 → 200)，但先被暂停
        // 线程2：偷偷把 100→200→100，绕回来了
        // 线程1 恢复后，CAS 成功（看到还是100），但中间发生了变化！

        // 解决方案：AtomicStampedReference，加版本号
        AtomicStampedReference<Integer> stampedRef =
                new AtomicStampedReference<>(100, 0); // 值100，版本号0

        int[] stampHolder = {0};
        int currentVal = stampedRef.get(stampHolder); // 同时获取值和版本号，其中，版本号是放在stampHolder数组中返回的
        int currentStamp = stampHolder[0];

        System.out.println("  初始值：" + currentVal + "，版本号：" + currentStamp);

        // 正常更新：值和版本号都必须匹配
        boolean ok = stampedRef.compareAndSet(100, 200, 0, 1); // 值100→200，版本0→1
        System.out.println("  CAS(100→200, v0→v1)：" + ok
                + "，当前：" + stampedRef.getReference()
                + "，版本：" + stampedRef.getStamp());

        // 用旧版本号再次 CAS：失败！即使值被改回了 100，版本号已经不是 0 了
        stampedRef.compareAndSet(200, 100, 1, 2); // 版本变为2
        boolean aba = stampedRef.compareAndSet(100, 200, 0, 1); // 版本0已过期，失败
        System.out.println("  用旧版本号0 CAS：" + aba + "（ABA 问题被阻止）");

        /*
         * 运行后观察：
         *   1. 基本 API：体会 getAndXxx（先返回再改）和 xxxAndGet（先改再返回）的区别
         *   2. 性能对比：两者结果都是正确的，耗时差距体现了锁的开销
         *   3. AtomicBoolean 的 CAS 用于"只执行一次"场景，非常实用
         *   4. LongAdder 结果准确为 5000，在高并发统计场景替代 AtomicLong
         *   5. ABA：AtomicStampedReference 用版本号杜绝中间状态被忽视的问题
         *
         * 选择指南：
         *   简单计数/标志   → AtomicInteger / AtomicBoolean
         *   高并发纯计数    → LongAdder（性能更好）
         *   需要防 ABA      → AtomicStampedReference
         *   更新对象字段    → AtomicIntegerFieldUpdater（减少对象创建开销）
         */
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== 基本 API ===");
        basicApi();

        System.out.println("\n=== 性能对比 ===");
        performanceCompare();

        System.out.println("\n=== 原子类家族 ===");
        atomicFamily();

        abaProblem();
    }
}
