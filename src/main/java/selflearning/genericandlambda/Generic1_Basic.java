package selflearning.genericandlambda;

import java.util.*;

/**
 * 泛型基础：泛型类、泛型方法、泛型接口
 *
 * 为什么需要泛型？
 *   没有泛型时，写一个"通用容器"只能用 Object：
 *     List list = new ArrayList();
 *     list.add("hello");
 *     list.add(123);          // 编译通过，但混进了不同类型
 *     String s = (String) list.get(1); // 运行时 ClassCastException！
 *
 *   有了泛型：
 *     List<String> list = new ArrayList<>();
 *     list.add(123);          // 编译直接报错，问题在编译期暴露
 *     String s = list.get(0); // 不需要强转，编译器知道类型
 *
 * 泛型的本质：把"类型"作为参数，在使用时才确定具体类型
 */
public class Generic1_Basic {

    // ============================================================
    // 泛型类：类型参数写在类名后面的 <T>
    // T 只是惯例命名，可以是任何字母，但约定：
    //   T = Type（通用类型）
    //   E = Element（集合元素）
    //   K = Key，V = Value（键值对）
    //   N = Number（数字）
    // ============================================================
    static class Box<T> {
        private T value;

        public Box(T value) {
            this.value = value;
        }

        public T getValue() { return value; }

        public void setValue(T value) { this.value = value; }

        @Override
        public String toString() {
            return "Box<" + value.getClass().getSimpleName() + ">(" + value + ")";
        }
    }

    // 多个类型参数
    static class Pair<K, V> {
        private K key;
        private V value;

        public Pair(K key, V value) {
            this.key   = key;
            this.value = value;
        }

        public K getKey()   { return key; }
        public V getValue() { return value; }

        @Override
        public String toString() {
            return "Pair(" + key + ", " + value + ")";
        }
    }

    // ============================================================
    // 泛型接口：接口也可以有类型参数
    // ============================================================
    interface Transformer<I, O> {
        O transform(I input);
    }

    // 实现时指定具体类型
    static class StringToInt implements Transformer<String, Integer> {
        @Override
        public Integer transform(String input) {
            return input.length();
        }
    }

    // 实现时保留泛型（延迟到使用时决定）
    static class IdentityTransformer<T> implements Transformer<T, T> {
        @Override
        public T transform(T input) {
            return input; // 原样返回
        }
    }

    // ============================================================
    // 泛型方法：类型参数写在返回值前面的 <T>
    // 注意：泛型方法和泛型类是独立的，
    //       非泛型类里也可以有泛型方法
    // ============================================================
    static class Utils {

        // 泛型方法：<T> 声明类型参数，T 作为参数和返回值类型
        public static <T> T getFirst(List<T> list) {
            if (list == null || list.isEmpty()) {
                return null;
            }
            return list.get(0);
        }

        // 多类型参数的泛型方法
        public static <K, V> Map<V, K> invertMap(Map<K, V> original) {
            Map<V, K> inverted = new HashMap<V, K>();
            for (Map.Entry<K, V> entry : original.entrySet()) {
                inverted.put(entry.getValue(), entry.getKey());
            }
            return inverted;
        }

        // 有界类型参数：<T extends Number> 限制 T 必须是 Number 或其子类
        // 这样方法体内就可以调用 Number 的方法（如 doubleValue()）
        public static <T extends Number> double sum(List<T> numbers) {
            double total = 0;
            for (T n : numbers) {
                total += n.doubleValue(); // T 一定是 Number，可以调用此方法
            }
            return total;
        }

        // 多重边界：<T extends Comparable<T> & Cloneable>
        // T 必须同时实现 Comparable 和 Cloneable
        public static <T extends Comparable<T>> T max(T a, T b) {
            return a.compareTo(b) >= 0 ? a : b;
        }
    }

    // ============================================================
    // 泛型的继承：子类如何处理父类的类型参数
    // ============================================================
    static class NumberBox<T extends Number> extends Box<T> {
        public NumberBox(T value) { super(value); }

        // 子类可以使用父类的泛型约束
        public double doubled() {
            return getValue().doubleValue() * 2;
        }
    }

    // 子类固定父类的类型参数（不再是泛型类）
    static class StringBox extends Box<String> {
        public StringBox(String value) { super(value); }

        public int length() {
            return getValue().length();
        }
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) {

        System.out.println("=== 泛型类 ===");
        Box<String>  strBox = new Box<String>("Hello");
        Box<Integer> intBox = new Box<Integer>(42);
        // Box<int> error = new Box<int>(1); // 泛型不能用基本类型，用包装类
        System.out.println(strBox);
        System.out.println(intBox);

        System.out.println("\n=== 多类型参数 ===");
        Pair<String, Integer> pair = new Pair<String, Integer>("age", 25);
        System.out.println(pair);
        System.out.println("key=" + pair.getKey() + ", value=" + pair.getValue());

        System.out.println("\n=== 泛型接口 ===");
        Transformer<String, Integer> t1 = new StringToInt();
        System.out.println("\"hello\" 长度：" + t1.transform("hello"));

        IdentityTransformer<Double> t2 = new IdentityTransformer<Double>();
        System.out.println("原样返回：" + t2.transform(3.14));

        System.out.println("\n=== 泛型方法 ===");
        List<String> names = Arrays.asList("Alice", "Bob", "Charlie");
        System.out.println("第一个：" + Utils.getFirst(names));

        Map<String, Integer> scores = new HashMap<String, Integer>();
        scores.put("Alice", 95);
        scores.put("Bob",   87);
        Map<Integer, String> inverted = Utils.invertMap(scores);
        System.out.println("反转 Map：" + inverted);

        List<Integer> nums = Arrays.asList(1, 2, 3, 4, 5);
        System.out.println("求和：" + Utils.sum(nums));
        System.out.println("max(3,7)=" + Utils.max(3, 7));
        System.out.println("max(\"apple\",\"banana\")=" + Utils.max("apple", "banana"));

        System.out.println("\n=== 泛型继承 ===");
        NumberBox<Double> numBox = new NumberBox<Double>(3.14);
        System.out.println("doubled=" + numBox.doubled());

        StringBox sBox = new StringBox("Java");
        System.out.println("length=" + sBox.length());

        /*
         * 运行后重点理解：
         *   1. Box<String> 和 Box<Integer> 是同一个类，但类型安全地分离了
         *   2. 泛型方法的 <T> 由调用时传入的参数类型自动推断，不需要显式写
         *   3. <T extends Number> 让你在方法体里安全地调用 Number 的方法
         *   4. 泛型不能用 int/double 这类基本类型，必须用 Integer/Double 包装类
         */
    }
}
