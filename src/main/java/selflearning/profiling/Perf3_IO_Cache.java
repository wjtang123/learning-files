package selflearning.profiling;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * IO 优化 + 缓存策略 + 性能测量
 *
 * IO 往往是系统最大的瓶颈。
 * 优化原则：减少 IO 次数 > 减少 IO 数据量 > 提升 IO 速度
 */
public class Perf3_IO_Cache {

    // ============================================================
    // 一、批量操作：减少 IO 次数
    // ============================================================

    // 模拟数据库操作
    static class MockDatabase {
        private Map<Integer, String> data = new HashMap<Integer, String>();
        private int queryCount = 0;
        private int batchCount = 0;

        // 单条查询（每次都是一次 "IO"）
        public String findById(int id) {
            queryCount++;
            return "User_" + id;
        }

        // 批量查询（一次 IO 查多条）
        public Map<Integer, String> findByIds(List<Integer> ids) {
            batchCount++;
            Map<Integer, String> result = new HashMap<Integer, String>();
            for (int id : ids) result.put(id, "User_" + id);
            return result;
        }

        // 单条插入
        public void insert(int id, String name) {
            queryCount++;
            data.put(id, name);
        }

        // 批量插入（一次 IO 插多条，效率远高于循环单条）
        public void batchInsert(Map<Integer, String> records) {
            batchCount++;
            data.putAll(records);
        }

        public int getQueryCount() { return queryCount; }
        public int getBatchCount() { return batchCount; }
    }

    // N+1 查询问题（最典型的 IO 性能陷阱）
    static void nPlusOneDemo() {
        MockDatabase db = new MockDatabase();
        List<Integer> userIds = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        // 坏：N+1 查询 ——先查列表（1次），再逐条查详情（N次），共 N+1 次 IO
        long t1 = System.nanoTime();
        List<String> badResult = new ArrayList<String>();
        for (int id : userIds) {
            badResult.add(db.findById(id)); // 每次循环一次 IO，10次 IO
        }
        long t2 = System.nanoTime();

        // 好：批量查询 ——一次 IO 查所有，共 1 次 IO
        Map<Integer, String> goodResult = db.findByIds(userIds); // 1次 IO
        long t3 = System.nanoTime();

        System.out.printf("N+1查询：%d 次IO，耗时 %,d ns%n", db.getQueryCount(), t2-t1);
        System.out.printf("批量查询：%d 次IO，耗时 %,d ns%n", db.getBatchCount(), t3-t2);
        System.out.println("→ 减少了 " + (userIds.size() - 1) + " 次IO");
    }

    // ============================================================
    // 二、缓存策略
    // ============================================================

    // 简单 LRU 缓存（最近最少使用淘汰策略）
    static class LRUCache<K, V> {
        private final int capacity;
        // LinkedHashMap 维护访问顺序：accessOrder=true
        private final LinkedHashMap<K, V> map;

        LRUCache(int capacity) {
            this.capacity = capacity;
            this.map = new LinkedHashMap<K, V>(capacity, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    return size() > capacity; // 超出容量自动淘汰最旧的
                }
            };
        }

        public synchronized V get(K key) { return map.get(key); }

        public synchronized void put(K key, V value) { map.put(key, value); }

        public synchronized int size() { return map.size(); }
    }

    // 带过期时间的本地缓存
    static class LocalCache<K, V> {
        private static class Entry<V> {
            V value;
            long expireAt; // 过期时间戳（毫秒）

            Entry(V value, long ttlMs) {
                this.value    = value;
                this.expireAt = System.currentTimeMillis() + ttlMs;
            }

            boolean isExpired() {
                return System.currentTimeMillis() > expireAt;
            }
        }

        private final ConcurrentHashMap<K, Entry<V>> store =
                new ConcurrentHashMap<K, Entry<V>>();
        private final long defaultTtlMs;

        LocalCache(long defaultTtlMs) {
            this.defaultTtlMs = defaultTtlMs;
        }

        public void put(K key, V value) {
            store.put(key, new Entry<V>(value, defaultTtlMs));
        }

        public V get(K key) {
            Entry<V> entry = store.get(key);
            if (entry == null) return null;
            if (entry.isExpired()) {
                store.remove(key);
                return null;    // 返回 null 触发重新加载
            }
            return entry.value;
        }

        public boolean containsKey(K key) {
            return get(key) != null;
        }
    }

    // 双重检查的缓存加载（防止缓存击穿）
    static class SafeCache<K, V> {
        private final ConcurrentHashMap<K, V> cache =
                new ConcurrentHashMap<K, V>();
        private final ConcurrentHashMap<K, Object> locks =
                new ConcurrentHashMap<K, Object>();

        public V getOrLoad(K key, Supplier<V> loader) {
            // 第一次检查（不加锁，大多数情况走这里，性能好）
            V value = cache.get(key);
            if (value != null) return value;

            // 缓存未命中，按 key 加细粒度锁，防止多线程同时 load
            Object keyLock = locks.computeIfAbsent(key, k -> new Object());
            synchronized (keyLock) {
                // 第二次检查（加锁后再确认，防止重复 load）
                value = cache.get(key);
                if (value != null) return value;

                // 真正去加载（数据库查询 / HTTP 请求）
                value = loader.get();
                cache.put(key, value);
            }
            locks.remove(key);
            return value;
        }
    }

    // ============================================================
    // 三、懒加载（Lazy Initialization）
    // ============================================================

    // 场景：初始化代价高的对象，只在真正需要时才创建
    static class LazyHeavyResource {
        private volatile byte[] data = null; // 用 volatile 保证可见性

        public byte[] getData() {
            if (data == null) {                      // 第一次检查（无锁）
                synchronized (this) {
                    if (data == null) {              // 第二次检查（有锁）
                        System.out.println("  初始化大对象（只执行一次）...");
                        data = new byte[1024 * 1024]; // 模拟耗时初始化
                    }
                }
            }
            return data;
        }
    }

    // 更优雅的懒加载：利用类加载机制（Holder 模式，无锁，线程安全）
    static class ElegantLazy {
        private static class Holder {
            // 静态内部类只有在被访问时才加载，加载时执行初始化，JVM 保证线程安全
            static final byte[] DATA = new byte[1024 * 1024];
            static { System.out.println("  Holder 初始化（只执行一次）..."); }
        }

        public static byte[] getData() {
            return Holder.DATA; // 第一次调用时触发 Holder 类加载和初始化
    }
    }

    // ============================================================
    // 四、性能测量：正确的基准测试方法
    // ============================================================

    // 简单计时器（适合粗略对比，不如 JMH 精确）
    static class StopWatch {
        private long startNs;
        private String name;

        StopWatch start(String name) {
            this.name    = name;
            this.startNs = System.nanoTime();
            return this;
        }

        void stop() {
            long elapsed = System.nanoTime() - startNs;
            System.out.printf("[%s] %,d ns（%.3f ms）%n",
                    name, elapsed, elapsed / 1_000_000.0);
        }
    }

    // 注意：准确的基准测试应该用 JMH（Java Microbenchmark Harness）
    // 下面展示正确的测量姿势
    static void measureDemo() {
        int warmupRounds = 3;   // 预热轮次（让 JIT 充分编译）
        int measureRounds = 5;  // 正式测量轮次

        System.out.println("--- 正确的性能测量流程 ---");
        System.out.println("步骤一：预热（让 JIT 编译热点代码）");
        for (int i = 0; i < warmupRounds; i++) {
            runTarget();        // 预热，丢弃结果
        }

        System.out.println("步骤二：正式测量（取多次平均）");
        long[] times = new long[measureRounds];
        for (int i = 0; i < measureRounds; i++) {
            long t = System.nanoTime();
            runTarget();
            times[i] = System.nanoTime() - t;
            System.out.printf("  第%d次：%,d ns%n", i+1, times[i]);
        }

        // 去掉最高最低，取中间值（减少噪声）
        Arrays.sort(times);
        long median = times[measureRounds / 2];
        System.out.printf("中位数：%,d ns（%.3f ms）%n",
                median, median / 1_000_000.0);
    }

    static void runTarget() {
        // 模拟被测试的代码
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) sb.append(i);
    }

    // ============================================================
    // 五、常见反模式汇总
    // ============================================================
    static void antiPatterns() {
        System.out.println("\n=== 常见性能反模式 ===");

        // 反模式一：在循环里创建 SimpleDateFormat（非线程安全且创建开销大）
        // 坏：for (each) { new SimpleDateFormat("yyyy-MM-dd").format(date); }
        // 好：static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd");
        //     但 SimpleDateFormat 非线程安全，多线程用 ThreadLocal 或 DateTimeFormatter
        System.out.println("① 避免在循环内创建 SimpleDateFormat，用 DateTimeFormatter（线程安全）");

        // 反模式二：异常控制流程（try-catch 比 if 慢很多）
        // 坏：try { Integer.parseInt(s); return true; } catch (e) { return false; }
        // 好：先用 matches() 校验格式，再 parse
        System.out.println("② 不要用 try-catch 控制业务流程，异常捕获有性能开销");

        // 反模式三：频繁的 System.out.println（同步 IO，高并发下严重阻塞）
        // 生产环境用异步日志（Log4j2 AsyncLogger / Logback AsyncAppender）
        System.out.println("③ 生产环境用异步日志框架，不直接用 System.out");

        // 反模式四：HashMap 在多线程下并发修改（死循环/数据丢失）
        // 用 ConcurrentHashMap
        System.out.println("④ 多线程共享 Map 用 ConcurrentHashMap，不用 HashMap");

        // 反模式五：过度使用反射（reflect.invoke 比直接调用慢10~100倍）
        // 能用接口/Lambda 替代的，不用反射
        System.out.println("⑤ 热路径避免反射，用接口 / Lambda / 代码生成替代");

        // 反模式六：字符串 split 用 char 比用正则快
        // "a,b,c".split(",")   → 被当正则处理
        // "a,b,c".split("\\,") → 避免正则，更快（或用 StringTokenizer）
        System.out.println("⑥ split 单字符时考虑用 indexOf + substring 手动分割，比正则快");
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) throws Exception {
        System.out.println("=== N+1 查询问题 ===");
        nPlusOneDemo();

        System.out.println("\n=== LRU 缓存 ===");
        LRUCache<Integer, String> lru = new LRUCache<Integer, String>(3);
        lru.put(1, "a"); lru.put(2, "b"); lru.put(3, "c");
        lru.get(1);      // 访问1，1变成最近使用
        lru.put(4, "d"); // 容量超出，淘汰最旧的（2）
        System.out.println("缓存大小：" + lru.size() + "（容量3，插入4个，淘汰1个）");

        System.out.println("\n=== 带 TTL 的本地缓存 ===");
        LocalCache<String, String> cache =
                new LocalCache<String, String>(200); // 200ms 过期
        cache.put("key", "value");
        System.out.println("缓存命中：" + cache.get("key"));
        Thread.sleep(250); // 等过期
        System.out.println("过期后：" + cache.get("key")); // null

        System.out.println("\n=== 懒加载 ===");
        LazyHeavyResource lazy = new LazyHeavyResource();
        System.out.println("对象已创建，但还没初始化...");
        byte[] data = lazy.getData(); // 第一次调用才初始化
        System.out.println("长度：" + data.length);
        lazy.getData(); // 第二次：直接返回，不再初始化

        System.out.println("\n=== 性能测量示例 ===");
        measureDemo();

        antiPatterns();

        /*
         * IO 优化核心原则：
         *   1. 批量操作：能批量绝不单条（INSERT/SELECT 批量化）
         *   2. 缓存热点数据：减少数据库压力（多级缓存：本地→Redis→DB）
         *   3. 懒加载：不用的资源不初始化
         *   4. 连接池：数据库连接、HTTP 连接复用（不要每次都新建）
         *   5. 异步 IO：不阻塞线程（CompletableFuture、响应式编程）
         *
         * 测量原则：
         *   先预热再测量（JIT 需要热身）
         *   多次测量取中位数（排除噪声）
         *   生产环境用 JMH，不要用简单的 System.nanoTime() 对比
         */
    }
}
