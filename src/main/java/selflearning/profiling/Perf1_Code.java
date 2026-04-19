package selflearning.profiling;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 代码层面性能优化
 *
 * 这是最高 ROI 的优化层：改动小，收益大，不需要架构变化。 （ROI, Return on Investment）
 * 优化前先用 JProfiler / Arthas / jmh 定位热点，不要瞎猜。
 */
public class Perf1_Code {

    // ============================================================
    // 一、字符串优化
    // ============================================================

    // 场景：循环拼接字符串
    static void stringConcatDemo() {
        int n = 10000;

        // 慢：每次 + 都创建新的 String 对象，O(n²) 内存复制
        long t1 = System.nanoTime();
        String bad = "";
        for (int i = 0; i < n; i++) {
            bad += i;               // 每次都 new String，产生大量垃圾对象
        }
        long t2 = System.nanoTime();

        // 快：StringBuilder 预分配容量，O(n) 内存复制
        StringBuilder sb = new StringBuilder(n * 3); // 预估容量，避免扩容
        for (int i = 0; i < n; i++) {
            sb.append(i);
        }
        String good = sb.toString();
        long t3 = System.nanoTime();

        System.out.printf("String+：%,d ns%n", t2 - t1);
        System.out.printf("StringBuilder：%,d ns%n", t3 - t2);
        System.out.printf("加速比：%.1fx%n", (double)(t2-t1)/(t3-t2));
    }

    // 场景：频繁使用的正则表达式
    static void regexDemo() {
        String text = "user@example.com";

        // 慢：每次调用都编译正则（Pattern.compile 很耗时）
        long t1 = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            boolean bad = text.matches("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
        }
        long t2 = System.nanoTime();

        // 快：提前编译，复用 Pattern 对象（static final 保证只编译一次）
        final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
        for (int i = 0; i < 10000; i++) {
            boolean good = EMAIL_PATTERN.matcher(text).matches();
        }
        long t3 = System.nanoTime();

        System.out.printf("每次编译正则：%,d ns%n", t2 - t1);
        System.out.printf("预编译复用：%,d ns%n", t3 - t2);
    }

    // ============================================================
    // 二、集合优化
    // ============================================================

    static void collectionDemo() {
        int n = 100000;

        // 技巧一：指定初始容量，避免动态扩容
        // ArrayList 默认容量10，每次扩容复制所有元素（1.5倍扩容）
        long t1 = System.nanoTime();
        List<Integer> withoutCap = new ArrayList<Integer>();   // 默认容量10
        for (int i = 0; i < n; i++) withoutCap.add(i);
        long t2 = System.nanoTime();

        List<Integer> withCap = new ArrayList<Integer>(n);     // 指定容量
        for (int i = 0; i < n; i++) withCap.add(i);
        long t3 = System.nanoTime();

        System.out.printf("无初始容量：%,d ns%n", t2 - t1);
        System.out.printf("有初始容量：%,d ns%n", t3 - t2);

        // 技巧二：选对数据结构
        List<Integer>  arrayList  = new ArrayList<Integer>(n);   // 随机访问 O(1)，插入删除 O(n)
        LinkedList<Integer> linkedList = new LinkedList<Integer>();  // 随机访问 O(n)，头尾操作 O(1)
        Set<Integer>   hashSet    = new HashSet<Integer>(n);     // 查找 O(1)
        Set<Integer>   treeSet    = new TreeSet<Integer>();      // 查找 O(logn)，有序

        for (int i = 0; i < n; i++) { arrayList.add(i); hashSet.add(i); }

        // 技巧三：contains 用 Set 而不是 List
        // List.contains() 是 O(n)，HashSet.contains() 是 O(1)
        long t4 = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            arrayList.contains(n / 2);    // O(n) 线性查找
        }
        long t5 = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            hashSet.contains(n / 2);      // O(1) 哈希查找
        }
        long t6 = System.nanoTime();

        System.out.printf("List.contains：%,d ns%n", t5 - t4);
        System.out.printf("Set.contains：%,d ns%n",  t6 - t5);

        // 技巧四：遍历用 for-each / iterator，不要用 index 遍历 LinkedList
        // linkedList.get(i) 是 O(n)，index 遍历是 O(n²)
    }

    // ============================================================
    // 三、对象创建优化
    // ============================================================

    // 慢：每次调用都 new 对象
    static boolean isAdultBad(int age) {
        Integer boxed = new Integer(age); // 手动装箱，每次 new（已废弃的写法）
        return boxed >= 18;
    }

    // 快：自动装箱有缓存（-128~127），超出范围才 new
    static boolean isAdultGood(int age) {
        return age >= 18; // 直接用基本类型，零对象创建
    }

    // 对象池：重用代价高的对象
    static class ExpensiveObject {
        byte[] data;
        ExpensiveObject() {
            data = new byte[1024 * 1024]; // 模拟初始化开销
        }
        void reset() { Arrays.fill(data, (byte)0); } // 重置状态
    }

    static class ObjectPool {
        private final Queue<ExpensiveObject> pool = new ArrayDeque<ExpensiveObject>();
        private final int maxSize;

        ObjectPool(int maxSize) {
            this.maxSize = maxSize;
            for (int i = 0; i < maxSize; i++) {
                pool.offer(new ExpensiveObject()); // 预热
            }
        }

        ExpensiveObject acquire() {
            ExpensiveObject obj = pool.poll();
            return obj != null ? obj : new ExpensiveObject(); // 池空了才新建
        }

        void release(ExpensiveObject obj) {
            if (pool.size() < maxSize) {
                obj.reset();         // 清理状态后归还
                pool.offer(obj);
            }
            // 超出最大值直接丢弃，让 GC 回收
        }
    }

    // ============================================================
    // 四、减少重复计算
    // ============================================================

    // 技巧：计算结果缓存（记忆化）
    static class Fibonacci {
        private Map<Integer, Long> cache = new HashMap<Integer, Long>();

        // 慢：指数级重复计算
        long slowFib(int n) {
            if (n <= 1) return n;
            return slowFib(n - 1) + slowFib(n - 2);
        }

        // 快：缓存已计算的结果
        long fastFib(int n) {
            if (n <= 1) return n;
            if (cache.containsKey(n)) return cache.get(n);
            long result = fastFib(n - 1) + fastFib(n - 2);
            cache.put(n, result);
            return result;
        }
    }

    // 技巧：循环不变量外提
    static void loopInvariantDemo() {
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < 10000; i++) names.add("name" + i);

        // 慢：每次循环都调 size()（虽然 JIT 可能优化，但养成习惯）
        long t1 = System.nanoTime();
        int sumBad = 0;
        for (int i = 0; i < names.size(); i++) { // size() 每次调用
            sumBad += names.get(i).length();
        }
        long t2 = System.nanoTime();

        // 快：把循环不变量提到循环外
        int sumGood = 0;
        int size = names.size(); // 外提，只调一次
        for (int i = 0; i < size; i++) {
            sumGood += names.get(i).length();
        }
        long t3 = System.nanoTime();

        System.out.printf("每次调size()：%,d ns，外提size：%,d ns%n",
                t2 - t1, t3 - t2);
    }

    // ============================================================
    // 五、Integer/int 装箱拆箱陷阱
    // ============================================================
    static void autoboxingTrap() {
        // 陷阱一：用 Integer 累加，每次都 new Integer 对象
        long t1 = System.nanoTime();
        Integer sum1 = 0;                    // Integer
        for (int i = 0; i < 100000; i++) {
            sum1 += i;  // 先拆箱，加法，再装箱 → 每次循环 new Integer
        }
        long t2 = System.nanoTime();

        int sum2 = 0;                        // int（基本类型）
        for (int i = 0; i < 100000; i++) {
            sum2 += i;  // 纯基本类型运算，零对象创建
        }
        long t3 = System.nanoTime();

        System.out.printf("Integer累加：%,d ns%n", t2 - t1);
        System.out.printf("int累加：%,d ns%n",     t3 - t2);

        // 陷阱二：Integer 缓存范围（-128~127）
        Integer a = 100, b = 100;
        Integer c = 200, d = 200;
        System.out.println("100==100：" + (a == b)); // true（缓存）
        System.out.println("200==200：" + (c == d)); // false（超出缓存范围，new 了两个对象）
        System.out.println("用 equals：" + c.equals(d)); // true（比较值）
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) {
        System.out.println("=== 字符串拼接 ===");
        stringConcatDemo();

        System.out.println("\n=== 正则预编译 ===");
        regexDemo();

        System.out.println("\n=== 集合优化 ===");
        collectionDemo();

        System.out.println("\n=== 斐波那契缓存 ===");
        Fibonacci fib = new Fibonacci();
        long t1 = System.nanoTime();
        long r1 = fib.slowFib(40);
        long t2 = System.nanoTime();
        long r2 = fib.fastFib(40);
        long t3 = System.nanoTime();
        System.out.printf("slowFib(40)=%d，耗时：%,d ns%n", r1, t2-t1);
        System.out.printf("fastFib(40)=%d，耗时：%,d ns%n", r2, t3-t2);

        System.out.println("\n=== 循环不变量 ===");
        loopInvariantDemo();

        System.out.println("\n=== 装箱拆箱陷阱 ===");
        autoboxingTrap();

        /*
         * 核心原则：
         *   1. 减少对象创建：复用 / 基本类型 / 对象池
         *   2. 减少重复计算：缓存结果 / 循环不变量外提
         *   3. 选对数据结构：查找用 Set，随机访问用 ArrayList，
         *                     频繁头尾增删用 LinkedList 或 ArrayDeque
         *   4. 字符串拼接用 StringBuilder，指定合理初始容量
         *   5. 正则在静态常量中预编译，不要在循环里 matches()
         */
    }
}
