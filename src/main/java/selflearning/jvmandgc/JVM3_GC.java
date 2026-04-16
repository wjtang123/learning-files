package selflearning.jvmandgc;

import java.lang.ref.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 垃圾回收（GC）原理
 *
 * 三个核心问题：
 *   1. 哪些对象需要回收？（可达性分析）
 *   2. 怎么回收？（GC 算法）
 *   3. 什么时候回收？（GC 触发时机）
 */
public class JVM3_GC {

    // ============================================================
    // 一、如何判断对象可以回收
    // ============================================================
    /*
     * 方法一：引用计数（Reference Counting）—— JVM 不用这个
     *   每个对象维护一个引用计数，为0则回收
     *   致命缺陷：循环引用导致永远无法回收（A引用B，B引用A，都不为0）
     *
     * 方法二：可达性分析（Reachability Analysis）—— JVM 的做法
     *   从一组"GC Roots"出发，沿引用链遍历
     *   能到达的对象 = 存活；不能到达的对象 = 可回收
     *
     *   GC Roots 包括（记这几个）：
     *   ① 虚拟机栈中局部变量引用的对象（方法里正在用的对象）
     *   ② 堆中静态变量引用的对象（static 字段）
     *   ③ 方法区中常量引用的对象（final static 字段）
     *   ④ 本地方法栈中 JNI 引用的对象
     *   ⑤ JVM 内部引用（如 Class 对象、异常对象）
     *   ⑥ 被 synchronized 锁定的对象
     *
     * 即使不可达，对象也有"最后的机会"（了解即可）：
     *   如果对象重写了 finalize() 且还没调用过，会放入 F-Queue 执行一次
     *   如果在 finalize() 里把自己重新关联到 GC Root，就能"自救"
     *   但 finalize() 不推荐使用，执行时机不确定，JDK9 已标记为废弃
     */

    // ============================================================
    // 二、四种引用类型（面试常考）
    // ============================================================
    static void referenceTypesDemo() {
        System.out.println("--- 强引用（Strong Reference）---");
        // 默认的引用方式，只要有强引用，对象绝对不会被回收
        // 即使 OOM 也不回收
        Object obj = new Object(); // 强引用
        System.out.println("强引用存在，不会被回收：" + (obj != null));

        System.out.println("\n--- 软引用（Soft Reference）---");
        // 内存充足时不回收，内存不足时回收
        // 适合实现内存敏感的缓存（图片缓存、页面缓存）
        SoftReference<byte[]> softRef = new SoftReference<byte[]>(new byte[1024]);
        System.out.println("内存充足时，软引用有效：" + (softRef.get() != null));
        // 内存不足时，GC 会回收，softRef.get() 返回 null

        System.out.println("\n--- 弱引用（Weak Reference）---");
        // 只要发生 GC 就会被回收（不管内存够不够）
        // 适合防止内存泄漏（ThreadLocal 的 Entry 就是弱引用）
        WeakReference<Object> weakRef = new WeakReference<Object>(new Object());
        System.out.println("GC前，弱引用有效：" + (weakRef.get() != null));
        System.gc(); // 建议GC（不保证立刻执行）
        System.out.println("GC后，弱引用：" + weakRef.get()); // 可能是 null

        System.out.println("\n--- 虚引用（Phantom Reference）---");
        // 不能通过虚引用访问对象，只用来跟踪对象被回收的通知
        // get() 始终返回 null
        ReferenceQueue<Object> queue = new ReferenceQueue<Object>();
        PhantomReference<Object> phantomRef =
                new PhantomReference<Object>(new Object(), queue);
        System.out.println("虚引用 get()（始终null）：" + phantomRef.get());
        // 对象被回收后，PhantomReference 会被放入 queue
        // 可以监控对象回收事件（NIO 的 DirectByteBuffer 就用此机制）
    }

    // ============================================================
    // 三、GC 算法（面试必考四种）
    // ============================================================
    /*
     * ① 标记-清除（Mark-Sweep）
     *   步骤：标记所有存活对象 → 清除未标记对象
     *   缺点：产生大量内存碎片，大对象找不到连续空间
     *   场景：老年代（CMS 使用）
     *
     *   [A][  ][B][  ][  ][C]  ← 清除后有碎片
     *
     * ② 标记-复制（Mark-Copy）
     *   步骤：把存活对象复制到另一块区域，清掉原区域
     *   优点：无碎片，分配快（指针碰撞）
     *   缺点：需要额外一半空间
     *   场景：新生代（S0/S1 就是两块 Survivor 区轮流使用）
     *
     *   [A][ ][B][ ][ ][C]  →复制→  [A][B][C][ ][ ][ ]
     *
     * ③ 标记-整理（Mark-Compact）
     *   步骤：标记存活对象 → 把存活对象移到一端 → 清除边界外
     *   优点：无碎片
     *   缺点：移动对象要更新引用，开销大
     *   场景：老年代（Serial Old、G1 的 Full GC 阶段）
     *
     *   [A][ ][B][ ][ ][C]  →整理→  [A][B][C][ ][ ][ ]
     *
     * ④ 分代收集（Generational Collection）
     *   基于"大多数对象朝生夕死"的观察，按对象年龄分区
     *   新生代（生命周期短）→ 标记-复制（Minor GC，频繁但快）
     *   老年代（生命周期长）→ 标记-清除/整理（Major GC，不频繁但慢）
     */

    // ============================================================
    // 四、常见垃圾收集器（面试必考）
    // ============================================================
    /*
     * 新生代收集器：
     *   Serial       → 单线程，STW，Client 模式默认
     *   ParNew       → Serial 的多线程版，配合 CMS 使用
     *   Parallel Scavenge → 关注吞吐量，-XX:GCTimeRatio 控制
     *
     * 老年代收集器：
     *   Serial Old   → Serial 的老年代版，单线程
     *   Parallel Old → Parallel Scavenge 的老年代版
     *   CMS          → 低停顿，并发标记，有碎片问题
     *
     * 全堆收集器（重点！）：
     *   G1（JDK9 默认）
     *   ZGC（JDK15 生产可用）
     *
     * CMS（Concurrent Mark Sweep）—— 面试高频
     *   目标：最短 STW 时间
     *   四个阶段：
     *     1. 初始标记（STW）：标记 GC Root 直接关联的对象（很快）
     *     2. 并发标记：和用户线程同时运行，遍历整个引用链（耗时但不停顿）
     *     3. 重新标记（STW）：修正并发标记期间变动的对象（比初始标记稍长）
     *     4. 并发清除：和用户线程同时运行，清除垃圾对象
     *   缺点：
     *     - 浮动垃圾：并发清除时用户线程产生的新垃圾，当次GC无法回收
     *     - 内存碎片：标记-清除算法
     *     - 占用 CPU：并发阶段占用部分 CPU
     *
     * G1（Garbage First）—— 面试必须掌握
     *   目标：可预测的停顿时间（-XX:MaxGCPauseMillis=200）
     *   核心思想：把堆划分为大量等大的 Region（默认 1~32MB）
     *             不再区分固定的新生代/老年代物理边界
     *             每个 Region 可以是 Eden/Survivor/Old/Humongous
     *   优点：
     *     - 可设置停顿时间目标（软实时）
     *     - 不产生内存碎片（复制算法）
     *     - 大对象（Humongous）单独处理
     *   G1 的四个阶段：
     *     1. Young GC：只回收新生代 Region
     *     2. 并发标记：和 CMS 类似，标记老年代中存活对象
     *     3. 混合回收（Mixed GC）：同时回收新生代和部分老年代 Region
     *     4. Full GC（兜底）：G1 兜不住时触发，单线程整理
     *
     * ZGC（JDK11 引入，JDK15 生产可用）
     *   目标：毫秒级（<10ms）停顿，支持 TB 级堆
     *   技术：染色指针、读屏障、并发整理
     *   JDK21：引入分代 ZGC，性能进一步提升
     */

    // ============================================================
    // 五、内存泄漏的常见场景
    // ============================================================
    /*
     * 内存泄漏：对象不再使用，但仍然被引用，GC 无法回收
     *
     * 场景一：静态集合持有对象
     *   static List<Object> cache = new ArrayList<>();
     *   // 不断往里加，从不清理 → 泄漏
     *
     * 场景二：ThreadLocal 没有 remove()
     *   ThreadLocal 的 Entry 是弱引用 key，value 是强引用
     *   key 被回收后，value 无法被 GC，形成泄漏
     *   使用后必须调 threadLocal.remove()
     *
     * 场景三：监听器没有注销
     *   addListener() 了但退出时没有 removeListener()
     *
     * 场景四：未关闭的资源
     *   Connection、InputStream 未关闭，内部缓冲区无法释放
     *   用 try-with-resources 彻底解决
     */

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== 四种引用类型 ===");
        referenceTypesDemo();

        System.out.println("\n=== GC 触发演示（小心 OOM）===");
        // 演示 Minor GC：快速创建大量短生命周期对象
        System.out.println("创建大量短生命周期对象...");
        for (int i = 0; i < 100; i++) {
            // 每次循环创建的数组在循环结束后就不再被引用
            // 很快会被 Minor GC 回收
            byte[] garbage = new byte[1024 * 100]; // 100KB
        }
        System.out.println("完成，这些对象已被 Minor GC 回收");

        System.out.println("\n=== 主动触发 GC（只是建议，不保证）===");
        System.gc();
        // Runtime.getRuntime().gc(); // 等价
        Thread.sleep(100); // 给 GC 一点时间

        Runtime rt = Runtime.getRuntime();
        System.out.println("GC 后空闲内存：" + rt.freeMemory() / 1024 / 1024 + " MB");

        System.out.println("\n=== 对象晋升老年代演示 ===");
        // 长期被引用的对象会在多次 Minor GC 中存活，最终晋升老年代
        // 运行时加上 -XX:+PrintGCDetails 可以看到详细的 GC 日志
        List<byte[]> longLived = new ArrayList<byte[]>();
        for (int i = 0; i < 5; i++) {
            longLived.add(new byte[1024 * 200]); // 200KB，持续被 longLived 引用
        }
        System.out.println("长期存活对象会晋升老年代（需要 GC 日志才能观察）");

        /*
         * 建议配合 JVM 参数运行本程序来观察 GC 行为：
         *   -Xms64m -Xmx64m          限制堆大小，触发 GC
         *   -XX:+PrintGCDetails      打印 GC 详情
         *   -XX:+PrintGCDateStamps   打印时间戳
         *   -Xlog:gc*               JDK9+ 的日志方式
         *
         * 面试必答：
         *
         * Q：Minor GC 和 Full GC 的区别？
         * A：Minor GC 只回收新生代（Eden + S0/S1），快，STW 时间短（毫秒级）。
         *    Full GC 回收整个堆（新生代+老年代+元空间），慢，STW 时间长（秒级）。
         *    Full GC 触发条件：老年代空间不足、System.gc()、元空间不足等。
         *
         * Q：对象什么时候进入老年代？
         * A：①年龄达到阈值（默认15次 Minor GC 存活）
         *    ②大对象直接进老年代（-XX:PretenureSizeThreshold）
         *    ③动态年龄判断：S区相同年龄对象大小超过 S 区一半，该年龄及以上直接晋升
         *    ④Minor GC 后存活对象 S 区放不下，直接进老年代（担保机制）
         *
         * Q：为什么新生代用复制算法，老年代用标记-清除/整理？
         * A：新生代对象"朝生夕死"，存活率低，复制的对象少，效率高；
         *    老年代对象存活率高，如果用复制算法要复制大量对象，代价太高，
         *    所以用标记-整理（无碎片）或标记-清除（有碎片但 CMS 接受）。
         */
    }
}
