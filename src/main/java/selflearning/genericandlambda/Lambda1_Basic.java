package selflearning.genericandlambda;

import java.util.*;
import java.util.function.*;

/**
 * Lambda 表达式基础
 *
 * Lambda 本质：匿名函数的简写
 *   传统写法：new Comparator<String>() {
 *                @Override
 *                public int compare(String a, String b) { return a.compareTo(b); }
 *             }
 *   Lambda：(a, b) -> a.compareTo(b)
 *
 * Lambda 能用在哪里？
 *   只要需要"函数式接口"的地方（只有一个抽象方法的接口）
 *   Lambda 就是这个接口的一个实现，由编译器自动推断类型
 */
public class Lambda1_Basic {

    // ============================================================
    // Lambda 语法的五种形式
    // ============================================================
    static void syntaxDemo() {
        // 形式一：无参数，单行表达式
        Runnable r1 = () -> System.out.println("无参数 Lambda");

        // 形式二：单个参数（可以省略括号）
        Consumer<String> c1 = s -> System.out.println("单参数：" + s);
        // 等价于：Consumer<String> c1 = (s) -> System.out.println(s);

        // 形式三：多个参数
        Comparator<Integer> comp = (a, b) -> a - b;

        // 形式四：多行代码，需要大括号和 return
        Function<Integer, String> f1 = n -> {
            if (n > 0) return "正数";
            if (n < 0) return "负数";
            return "零";
        };

        // 形式五：显式写出参数类型（通常不需要，编译器能推断）
        Comparator<String> comp2 = (String a, String b) -> a.length() - b.length();

        // 调用
        r1.run();
        c1.accept("Hello Lambda");
        System.out.println("compare(3,5)=" + comp.compare(3, 5));
        System.out.println("f1(-3)=" + f1.apply(-3));
        System.out.println("comp2(\"hi\",\"hello\")=" + comp2.compare("hi", "hello"));
    }

    // ============================================================
    // JDK8 四大核心函数式接口（必须掌握）
    // ============================================================
    static void coreFunctionalInterfaces() {
        System.out.println("\n--- Predicate<T>：判断，返回 boolean ---");
        // T -> boolean
        Predicate<String> isLong     = s -> s.length() > 5;
        Predicate<String> startsWith = s -> s.startsWith("J");

        System.out.println("\"Hello\" 是长字符串？" + isLong.test("Hello"));
        System.out.println("\"Java\" 是长字符串？" + isLong.test("Java"));

        // Predicate 可以组合
        Predicate<String> longAndStartsWithJ = isLong.and(startsWith);
        Predicate<String> longOrStartsWithJ  = isLong.or(startsWith);
        Predicate<String> notLong            = isLong.negate();

        System.out.println("\"JavaScript\" 长且以J开头？" + longAndStartsWithJ.test("JavaScript"));
        System.out.println("\"Hi\" 长或以J开头？" + longOrStartsWithJ.test("Hi"));
        System.out.println("\"Hello\" 不长？" + notLong.test("Hello"));

        System.out.println("\n--- Function<T,R>：转换，T进R出 ---");
        // T -> R
        Function<String, Integer> strLen  = s -> s.length();
        Function<Integer, String> intToStr = n -> "数字" + n;

        // andThen：先执行前一个，结果作为后一个的输入（管道组合）
        Function<String, String> combined = strLen.andThen(intToStr);
        System.out.println("\"Hello\" -> 长度 -> 字符串：" + combined.apply("Hello"));

        // compose：相反方向，先执行后一个
        // Function<String, String> reversed = intToStr.compose(strLen);

        System.out.println("\n--- Consumer<T>：消费，T进，无返回值 ---");
        // T -> void
        Consumer<String> print  = s -> System.out.println("  打印：" + s);
        Consumer<String> upper  = s -> System.out.println("  大写：" + s.toUpperCase());

        // andThen：顺序执行两个 Consumer
        Consumer<String> both = print.andThen(upper);
        both.accept("hello");

        System.out.println("\n--- Supplier<T>：提供，无输入，T出 ---");
        // () -> T
        Supplier<String> greeting  = () -> "Hello, World!";
        Supplier<List<String>> newList = () -> new ArrayList<String>();

        System.out.println(greeting.get());
        List<String> list = newList.get();
        list.add("created by Supplier");
        System.out.println(list);
    }

    // ============================================================
    // 其他常用函数式接口
    // ============================================================
    static void otherInterfaces() {
        System.out.println("\n--- BiFunction<T,U,R>：两个输入 ---");
//        BiFunction<String, Integer, String> repeat = (s, n) -> s.repeat(n);
        // JDK8 没有 String.repeat，用循环代替：
        BiFunction<String, Integer, String> repeat8 = (s, n) -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append(s);
            return sb.toString();
        };
        System.out.println(repeat8.apply("ha", 3)); // hahaha

        System.out.println("\n--- BiPredicate<T,U>：两个输入的判断 ---");
        BiPredicate<String, String> contains = (s, sub) -> s.contains(sub);
        System.out.println("\"Hello World\" 含 \"World\"？" + contains.test("Hello World", "World"));

        System.out.println("\n--- UnaryOperator<T>：特殊的 Function，输入输出同类型 ---");
        UnaryOperator<String> trim  = s -> s.trim();
        UnaryOperator<String> upper = s -> s.toUpperCase();
        // 组合
        UnaryOperator<String> trimAndUpper = s -> upper.apply(trim.apply(s));
        System.out.println(trimAndUpper.apply("  hello world  "));

        System.out.println("\n--- BinaryOperator<T>：特殊的 BiFunction，两个同类型输入同类型输出 ---");
        BinaryOperator<Integer> add = (a, b) -> a + b;
        BinaryOperator<String>  concat = (a, b) -> a + b;
        System.out.println("3+4=" + add.apply(3, 4));
        System.out.println("\"Hi\"+\"Java\"=" + concat.apply("Hi", "Java"));
    }

    // ============================================================
    // 方法引用：Lambda 的进一步简化
    // 当 Lambda 体只是调用一个已有方法时，可以用方法引用代替
    // ============================================================
    static class Printer {
        void print(String s) { System.out.println("  [实例方法] " + s); }
        static void staticPrint(String s) { System.out.println("  [静态方法] " + s); }
    }

    static void methodReference() {
        System.out.println("\n=== 方法引用四种形式 ===");

        // 形式一：静态方法引用 → ClassName::staticMethod
        // Lambda：s -> Integer.parseInt(s)
        Function<String, Integer> parseInt = Integer::parseInt;
        System.out.println("\"42\" -> " + parseInt.apply("42"));

        // 形式二：实例方法引用（特定实例）→ instance::method
        // Lambda：s -> printer.print(s)
        Printer printer = new Printer();
        Consumer<String> printMethod = printer::print;
        printMethod.accept("hello");

        // 形式三：实例方法引用（任意实例）→ ClassName::instanceMethod
        // Lambda：(s) -> s.toUpperCase()
        // 第一个参数变成了调用者
        Function<String, String> toUpper = String::toUpperCase;
        System.out.println("  [任意实例] " + toUpper.apply("hello"));

        // 形式四：构造方法引用 → ClassName::new
        // Lambda：() -> new ArrayList<>()
        Supplier<ArrayList<String>> newList = ArrayList::new;
        ArrayList<String> list = newList.get();
        list.add("by constructor reference");
        System.out.println("  [构造引用] " + list);

        // 实际使用：排序时的方法引用
        List<String> names = new ArrayList<String>(
                Arrays.asList("Charlie", "Alice", "Bob"));

        // Lambda 写法
        Collections.sort(names, (a, b) -> a.compareTo(b));
        // 方法引用写法（等价，更简洁）
        Collections.sort(names, String::compareTo);
        System.out.println("  排序结果：" + names);
    }

    // ============================================================
    // Lambda 捕获外部变量（闭包）
    // ============================================================
    static void closureDemo() {
        System.out.println("\n=== Lambda 捕获外部变量 ===");

        String prefix = "Hello";    // 局部变量，必须是 effectively final
        // prefix = "Hi"; // 若加上这行，下面的 Lambda 编译报错

        Consumer<String> greet = name -> System.out.println(prefix + ", " + name + "!");
        greet.accept("Alice");
        greet.accept("Bob");

        // 可以捕获实例变量（没有 effectively final 限制）
        // 可以捕获静态变量（没有 effectively final 限制）
        // 不能修改捕获的局部变量（because：Lambda 可能在另一个线程执行）
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) {
        System.out.println("=== Lambda 语法五种形式 ===");
        syntaxDemo();

        System.out.println("\n=== 四大核心函数式接口 ===");
        coreFunctionalInterfaces();

        otherInterfaces();
        methodReference();
        closureDemo();

        /*
         * 运行后重点理解：
         *
         * 1. Predicate.and() / or() / negate() 组合逻辑判断，非常实用
         * 2. Function.andThen() 把多个转换串成管道
         * 3. 方法引用只是 Lambda 的语法糖，两者完全等价，选更可读的那个
         * 4. Lambda 捕获的局部变量必须是 effectively final（赋值后不再改变）
         *    这是因为 Lambda 可能在不同线程执行，如果允许修改局部变量会有并发问题
         */
    }
}
