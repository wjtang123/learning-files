package selflearning.multithread;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ConcurrentHashMap：线程安全的高性能 Map
 *
 * 三种 Map 的线程安全对比：
 *
 *   HashMap（不安全）
 *     - 多线程并发 put 会导致数据丢失，甚至死循环（JDK7 的 bug）
 *     - 单线程或外部加锁时才能用
 *
 *   Hashtable（安全但慢）
 *     - 所有方法都加了 synchronized，整个 Map 同一时刻只有一个线程操作
 *     - 相当于给整个 Map 加了一把大锁，并发性极差
 *
 *   ConcurrentHashMap（安全且快）
 *     - JDK8：对每个桶（bucket）的头节点用 CAS 或 synchronized 精细加锁
 *     - 不同桶的操作完全并发，同一时刻可以有多个线程同时读写不同桶
 *     - 读操作几乎完全无锁（volatile 保证可见性）
 *     - 理论并发度 = 桶的数量（默认16，最高可达数组长度）
 */
public class JUC3_ConcurrentHashMap {

    // ============================================================
    // 示例一：并发安全性验证 —— HashMap vs ConcurrentHashMap
    // ============================================================
    static void safetyDemo() throws InterruptedException {
        int threadCount = 10;
        int perThread = 1000;

        // HashMap：多线程 put 会丢数据
        Map<Integer, Integer> hashMap = new HashMap<>();
        Thread[] unsafeThreads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int base = i * perThread;
            unsafeThreads[i] = new Thread(() -> {
                for (int j = 0; j < perThread; j++) {
                    hashMap.put(base + j, base + j); // 并发 put，不安全
                }
            });
        }
        for (Thread t : unsafeThreads) t.start();
        for (Thread t : unsafeThreads) t.join();

        // ConcurrentHashMap：多线程 put 安全
        Map<Integer, Integer> concurrentMap = new ConcurrentHashMap<>();
        Thread[] safeThreads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int base = i * perThread;
            safeThreads[i] = new Thread(() -> {
                for (int j = 0; j < perThread; j++) {
                    concurrentMap.put(base + j, base + j);
                }
            });
        }
        for (Thread t : safeThreads) t.start();
        for (Thread t : safeThreads) t.join();

        System.out.printf("  HashMap       期望 %d，实际 %d %s%n",
                threadCount * perThread, hashMap.size(),
                hashMap.size() == threadCount * perThread ? "（运气好没出错）" : "（数据丢失！）");
        System.out.printf("  ConcurrentHashMap 期望 %d，实际 %d ✓%n",
                threadCount * perThread, concurrentMap.size());
    }

    // ============================================================
    // 示例二：ConcurrentHashMap 的原子复合操作（重点！）
    // ============================================================
    static void atomicOpsDemo() throws InterruptedException {
        System.out.println("\n=== 原子复合操作 ===");

        ConcurrentHashMap<String, AtomicInteger> wordCount = new ConcurrentHashMap<>();

        // 场景：统计单词出现次数，多线程并发统计
        String[] words = {"apple", "banana", "apple", "cherry",
                          "banana", "apple", "cherry", "apple"};

        // 错误写法（非原子，有竞态条件）：
        // if (!map.containsKey(word)) map.put(word, 0);  // 判断和插入之间可能被打断
        // map.put(word, map.get(word) + 1);              // get 和 put 之间可能被打断

        // 正确写法一：putIfAbsent + AtomicInteger
        Thread[] threads = new Thread[4];
        for (int t = 0; t < 4; t++) {
            final int start = t * 2;
            threads[t] = new Thread(() -> {
                for (int i = start; i < start + 2 && i < words.length; i++) {
                    String word = words[i];
                    // putIfAbsent：原子操作，只有 key 不存在时才插入
                    wordCount.putIfAbsent(word, new AtomicInteger(0));
                    wordCount.get(word).incrementAndGet(); // AtomicInteger 保证原子自增
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        System.out.println("  单词计数：" + wordCount);

        // 正确写法二：compute 系列方法（JDK8+，更优雅）
        ConcurrentHashMap<String, Integer> wordCount2 = new ConcurrentHashMap<>();
        for (String word : words) {
            // compute：原子地"读-改-写"，Lambda 里的操作是原子执行的
            wordCount2.compute(word, (k, v) -> v == null ? 1 : v + 1);
        }
        System.out.println("  compute 计数：" + wordCount2);

        // merge：合并操作，key 不存在时用初始值，存在时用函数结果
        ConcurrentHashMap<String, Integer> wordCount3 = new ConcurrentHashMap<>();
        for (String word : words) {
            wordCount3.merge(word, 1, Integer::sum); // 初始值1，已存在则累加
        }
        System.out.println("  merge 计数：" + wordCount3);

        // getOrDefault：取值时提供默认值，避免 null 检查
        System.out.println("  apple出现次数：" + wordCount3.getOrDefault("apple", 0));
        System.out.println("  grape出现次数：" + wordCount3.getOrDefault("grape", 0));
    }

    // ============================================================
    // 示例三：性能对比 —— Hashtable vs ConcurrentHashMap
    // ============================================================
    static void performanceDemo() throws InterruptedException {
        System.out.println("\n=== 性能对比（10线程各读写10000次）===");
        int threadCount = 10;
        int ops = 10_000;

        // Hashtable：全局锁，读写都锁整个表
        Hashtable<Integer, Integer> hashtable = new Hashtable<>();
        long t1 = System.currentTimeMillis();
        Thread[] ht = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int base = i;
            ht[i] = new Thread(() -> {
                for (int j = 0; j < ops; j++) {
                    hashtable.put(base * ops + j, j);   // 写
                    hashtable.get(base * ops + j / 2);  // 读
                }
            });
        }
        for (Thread t : ht) t.start();
        for (Thread t : ht) t.join();
        long htTime = System.currentTimeMillis() - t1;

        // ConcurrentHashMap：分段/桶级锁，高并发读写
        ConcurrentHashMap<Integer, Integer> chm = new ConcurrentHashMap<>();
        long t2 = System.currentTimeMillis();
        Thread[] ct = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int base = i;
            ct[i] = new Thread(() -> {
                for (int j = 0; j < ops; j++) {
                    chm.put(base * ops + j, j);
                    chm.get(base * ops + j / 2);
                }
            });
        }
        for (Thread t : ct) t.start();
        for (Thread t : ct) t.join();
        long chmTime = System.currentTimeMillis() - t2;

        System.out.printf("  Hashtable         耗时：%d ms%n", htTime);
        System.out.printf("  ConcurrentHashMap 耗时：%d ms%n", chmTime);
        System.out.printf("  ConcurrentHashMap 快了约 %.1fx%n", (double) htTime / chmTime);
    }

    // ============================================================
    // 示例四：注意事项 —— 复合操作的陷阱
    // ============================================================
    static void pitfalls() {
        System.out.println("\n=== 注意：复合操作的陷阱 ===");

        ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
        map.put("count", 0);

        // 陷阱：虽然 get/put 各自是线程安全的，但两步合在一起不是原子的
        // 多线程下这段代码依然有竞态条件：
        //   Integer val = map.get("count");   // 步骤1
        //   map.put("count", val + 1);        // 步骤2（步骤1和2之间可能被打断）

        // 正确做法：用 compute 保证整个"读-改-写"是原子的
        map.compute("count", (k, v) -> v + 1); // 原子！

        System.out.println("  用 compute 正确更新：" + map.get("count"));
        System.out.println("  结论：单个操作是线程安全的，复合操作要用 compute/merge/putIfAbsent+原子类");

        /*
         * 运行后观察：
         *   1. HashMap 的 size 几乎每次都小于 10000（数据丢失），偶尔相等是运气
         *   2. ConcurrentHashMap 的 size 始终准确是 10000
         *   3. compute/merge 让"读-改-写"变成一个原子步骤，是最优雅的写法
         *   4. 性能对比：线程越多、竞争越激烈，ConcurrentHashMap 优势越明显
         *
         * 使用场景总结：
         *   单线程或外部加锁   → HashMap（性能最好）
         *   需要线程安全       → ConcurrentHashMap（首选）
         *   遗留代码/不得不用  → Hashtable（不推荐新项目使用）
         *   需要强一致性遍历   → Collections.synchronizedMap(new LinkedHashMap<>())
         */
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== 安全性验证 ===");
        safetyDemo();
        atomicOpsDemo();
        performanceDemo();
        pitfalls();
    }
}
