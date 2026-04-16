package selflearning.jvmandgc;

import java.util.concurrent.CountDownLatch;

/**
 * Java 内存模型（JMM）+ JVM 调优
 *
 * JMM 回答的问题：多线程环境下，一个线程对变量的修改，
 * 什么时候、在什么条件下能被另一个线程看到？
 *
 * 注意区分：
 *   JVM 内存结构  → 堆、栈、方法区等物理分区（上面三个文件讲的）
 *   Java 内存模型 → 多线程可见性的抽象规则（这个文件讲的）
 */
public class JVM4_JMM_Tuning {

    // ============================================================
    // 一、JMM 的核心概念
    // ============================================================
    /*
     * JMM 抽象了一个模型：
     *   每个线程有自己的"工作内存"（类比 CPU 缓存）
     *   所有变量存在"主内存"（类比内存条）
     *   线程对变量的操作都在工作内存中进行
     *   线程间通信必须经过主内存
     *
     *   Thread A          主内存          Thread B
     *   工作内存         [x = 0]          工作内存
     *   [x = 1]  →写回→  [x = 1]  ←读→  [x = ?]
     *
     * 三个核心问题（JMM 要解决的）：
     *   可见性：A 写了 x=1，B 能看到吗？
     *   原子性：A 执行 x++ 的过程中，能被 B 打断吗？
     *   有序性：代码按写的顺序执行吗？（不一定！指令可以重排）
     *
     * 解决方案：
     *   可见性：volatile / synchronized / final
     *   原子性：synchronized / Lock / Atomic 类
     *   有序性：volatile（禁止特定重排）/ synchronized（临界区内有序）
     */

    // ============================================================
    // 二、指令重排序（Instruction Reordering）
    // ============================================================
    /*
     * 为了提高性能，编译器和 CPU 可以对指令重新排序，
     * 只要不影响单线程的执行结果（as-if-serial 原则）。
     *
     * 单线程没问题，多线程可能出问题：
     *
     * 经典案例：对象的不完全初始化
     *   instance = new Singleton(); 实际分三步：
     *     ① 分配内存
     *     ② 初始化对象（执行构造方法）
     *     ③ 把引用赋给 instance
     *
     *   CPU 可能重排为 ①③②：
     *     ① 分配内存
     *     ③ 把引用赋给 instance  ← 此时 instance != null，但对象未初始化！
     *     ② 初始化对象
     *
     *   另一个线程此时看到 instance != null，直接使用，
     *   但对象字段还是初始值（0/null），出 bug！
     *
     * 解决：volatile 禁止对象创建步骤的重排序
     */

    // 演示可见性问题（需要特定 JVM 优化才能稳定复现）
    static boolean flag = false;    // 不加 volatile
    static int     value = 0;

    static void visibilityDemo() throws InterruptedException {
        Thread writer = new Thread(() -> {
            value = 42;
            flag  = true;  // 可能被重排到 value=42 之前！
        });

        Thread reader = new Thread(() -> {
            while (!flag) { /* 等待 */ }
            // 如果发生重排，可能看到 flag=true 但 value 还是 0
            System.out.println("读到 value=" + value); // 期望42，可能是0
        });

        reader.start();
        writer.start();
        reader.join();
        writer.join();
    }

    // ============================================================
    // 三、happens-before 原则（JMM 的规则）
    // ============================================================
    /*
     * happens-before 是 JMM 对可见性的承诺：
     * "如果操作 A happens-before 操作 B，那么 A 的结果对 B 可见"
     *
     * JMM 定义的 happens-before 规则（记住这几个）：
     *
     * 1. 程序顺序规则：
     *    单线程内，前面的操作 happens-before 后面的操作
     *    （但只是逻辑顺序，CPU 仍可能乱序执行，只是结果等价）
     *
     * 2. volatile 规则：
     *    对 volatile 变量的写 happens-before 后续对该变量的读
     *    （这就是 volatile 保证可见性的依据）
     *
     * 3. 监视器锁规则（synchronized）：
     *    unlock happens-before 后续对同一个锁的 lock
     *    （这就是 synchronized 保证可见性的依据）
     *
     * 4. 线程启动规则：
     *    thread.start() happens-before 线程内的任何操作
     *    （start 之前的写，线程内都能看到）
     *
     * 5. 线程终止规则：
     *    线程的所有操作 happens-before thread.join() 返回
     *    （join 返回后，能看到线程内的所有写）
     *
     * 6. 传递性：
     *    A happens-before B，B happens-before C
     *    → A happens-before C
     */

    static volatile int sharedValue = 0; // volatile 保证 happens-before

    static void happensBefore() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        Thread writer = new Thread(() -> {
            sharedValue = 100;      // 写 volatile 变量
            latch.countDown();
        });

        Thread reader = new Thread(() -> {
            try {
                latch.await();      // 等待写线程完成
                // 因为 volatile 写 happens-before volatile 读
                // 所以这里一定能看到 sharedValue = 100
                System.out.println("happens-before 保证可见，value=" + sharedValue);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        reader.start();
        writer.start();
        reader.join();
        writer.join();
    }

    // ============================================================
    // 四、JVM 调优参数（工程实践必备）
    // ============================================================
    /*
     * 内存大小参数：
     *   -Xms512m          初始堆大小（建议和 -Xmx 相同，避免动态扩容）
     *   -Xmx2g            最大堆大小（生产通常是物理内存的 1/2~3/4）
     *   -Xmn512m          新生代大小（通常是堆的 1/3）
     *   -Xss256k          每个线程栈大小（高并发时减小以支持更多线程）
     *   -XX:MetaspaceSize=128m      元空间初始大小
     *   -XX:MaxMetaspaceSize=256m   元空间最大大小（必须设置，防无限增长）
     *   -XX:MaxDirectMemorySize=256m 堆外内存上限
     *
     * 垃圾收集器选择：
     *   -XX:+UseSerialGC            Serial + Serial Old（单CPU、内存小）
     *   -XX:+UseParallelGC          Parallel Scavenge + Parallel Old（JDK8默认，吞吐量优先）
     *   -XX:+UseConcMarkSweepGC     ParNew + CMS（低延迟，JDK9废弃）
     *   -XX:+UseG1GC                G1（JDK9+ 默认，平衡吞吐和延迟）
     *   -XX:+UseZGC                 ZGC（JDK15+，超低延迟）
     *
     * G1 相关：
     *   -XX:MaxGCPauseMillis=200    GC 停顿目标（软实时，G1 会尽力满足）
     *   -XX:G1HeapRegionSize=4m     Region 大小（1~32MB，是2的幂）
     *   -XX:InitiatingHeapOccupancyPercent=45  老年代占比达到此值触发并发标记
     *
     * GC 日志（调优必须开启）：
     *   JDK8：
     *     -XX:+PrintGCDetails
     *     -XX:+PrintGCDateStamps
     *     -Xloggc:/logs/gc.log
     *   JDK9+：
     *     -Xlog:gc*:file=/logs/gc.log:time,uptime,level,tags
     *
     * 问题排查：
     *   -XX:+HeapDumpOnOutOfMemoryError          OOM 时自动 dump 堆
     *   -XX:HeapDumpPath=/logs/heap.hprof        dump 文件路径
     *   -XX:+PrintCompilation                    打印 JIT 编译信息
     *   -XX:+PrintSafepointStatistics            打印 STW 信息
     *
     * 生产常用组合（G1，低延迟）：
     *   java -Xms4g -Xmx4g -Xmn2g -Xss512k
     *        -XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=256m
     *        -XX:+UseG1GC -XX:MaxGCPauseMillis=200
     *        -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/logs/
     *        -Xlog:gc*:file=/logs/gc.log:time,uptime,level,tags
     *        -jar app.jar
     */

    // ============================================================
    // 五、常用 JVM 诊断工具
    // ============================================================
    /*
     * 命令行工具（JDK 自带）：
     *   jps         列出所有 Java 进程及 PID
     *   jstat       监控 GC 统计信息
     *     jstat -gcutil <pid> 1000  每秒打印一次GC使用率
     *   jmap        堆内存分析
     *     jmap -heap <pid>          打印堆概要
     *     jmap -histo <pid>         打印对象统计（按大小排序）
     *     jmap -dump:format=b,file=heap.hprof <pid>  生成堆转储文件
     *   jstack      线程快照（排查死锁、CPU 高占用）
     *     jstack <pid>
     *   jinfo       查看/修改 JVM 参数
     *     jinfo -flags <pid>
     *
     * 图形化工具：
     *   JVisualVM   JDK 自带，堆/线程/GC 可视化（JDK8 含，JDK9+ 需单独下载）
     *   JConsole    JDK 自带，基础监控
     *   MAT（Memory Analyzer Tool）  分析 heap.hprof，找内存泄漏神器
     *   Arthas       阿里开源，线上诊断利器，不停机分析
     *
     * Arthas 常用命令（面试加分）：
     *   dashboard   实时查看线程、堆、GC 概况
     *   thread      查看线程状态，找 CPU 占用最高的线程
     *   jad         反编译运行中的类（确认是否加载了正确版本）
     *   watch       监控方法入参和返回值（不重启！）
     *   trace       方法调用链路及耗时分析
     */

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) throws Exception {
        System.out.println("=== JMM 可见性问题演示 ===");
        // 注意：visibilityDemo 中不加 volatile，在 JIT 优化后可能死循环
        // 这里先跑 happens-before 演示
        happensBefore();

        System.out.println("\n=== JVM 运行时信息 ===");
        // 获取 JVM 参数信息
        System.out.println("JVM 版本：" + System.getProperty("java.version"));
        System.out.println("JVM 名称：" + System.getProperty("java.vm.name"));
        System.out.println("操作系统：" + System.getProperty("os.name"));

        // 内存信息
        Runtime rt = Runtime.getRuntime();
        long maxMem   = rt.maxMemory()   / 1024 / 1024;
        long totalMem = rt.totalMemory() / 1024 / 1024;
        long freeMem  = rt.freeMemory()  / 1024 / 1024;
        long usedMem  = totalMem - freeMem;
        System.out.println("堆内存：已用 " + usedMem + "MB / 当前 "
                + totalMem + "MB / 最大 " + maxMem + "MB");

        System.out.println("\n=== GC 诊断建议 ===");
        System.out.println("运行时加以下参数可以观察 GC 行为：");
        System.out.println("  -Xms64m -Xmx64m -XX:+PrintGCDetails -XX:+UseG1GC");

        /*
         * 面试必答：
         *
         * Q：JMM 和 JVM 内存结构的区别？
         * A：JVM 内存结构（堆、栈、方法区）是物理上的内存划分，描述 JVM 如何管理内存。
         *    JMM（Java Memory Model）是逻辑上的规范，描述多线程下变量的可见性规则，
         *    定义了 happens-before 等保证，与硬件无关。
         *
         * Q：happens-before 是什么？
         * A：JMM 定义的一套规则，如果操作 A happens-before 操作 B，
         *    则 A 的结果对 B 可见且 A 在 B 之前执行。
         *    常见规则：程序顺序、volatile 写读、锁的解锁加锁、线程 start/join。
         *
         * Q：CPU 密集型和 IO 密集型服务，JVM 调优有什么不同？
         * A：CPU 密集型（大量计算）：
         *    - 线程数 ≈ CPU 核数，减少上下文切换
         *    - 关注 GC 停顿对计算的影响，考虑 ZGC
         *    IO 密集型（数据库、网络）：
         *    - 线程数 = CPU × (1 + 等待时间/计算时间)，可以多开
         *    - 堆大小合理，避免 Full GC 拖慢响应
         *    - 关注老年代增长速度，合理配置 G1 的回收目标
         */
    }
}
