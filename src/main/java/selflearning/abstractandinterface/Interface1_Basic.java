package selflearning.abstractandinterface;

import java.util.Arrays;
import java.util.List;

/**
 * 接口基础用法
 *
 * 接口描述"能做什么"，不关心"是什么"
 *
 * 接口的规则：
 *   - 接口里的方法默认是 public abstract（可以省略这两个关键字）
 *   - 接口里的变量默认是 public static final（常量）
 *   - 一个类可以实现多个接口（解决了 Java 单继承的限制）
 *   - JDK8 起接口可以有 default 方法（有方法体，子类可选择是否覆盖）
 *   - JDK8 起接口可以有 static 方法
 *
 * 什么时候用接口？
 *   - 不同类族的类需要共享同一种"能力"
 *   - 需要一个类同时具备多种能力
 *   - 定义回调、插件、策略等扩展点
 */
public class Interface1_Basic {

    // ============================================================
    // 定义接口：描述"能力"
    // ============================================================

    interface Flyable {
        // 接口常量（隐含 public static final）
        int MAX_ALTITUDE = 10000;

        // 抽象方法（隐含 public abstract）
        void fly();
        int getMaxSpeed();

        // default 方法（JDK8+）：有默认实现，实现类可以选择覆盖
        default String describe() {
            return "我能飞，最高时速 " + getMaxSpeed() + " km/h";
        }

        // static 方法（JDK8+）：通过接口名调用，不能被覆盖
        static void showMaxAltitude() {
            System.out.println("最大飞行高度：" + MAX_ALTITUDE + "m");
        }
    }

    interface Swimmable {
        void swim();
        int getSwimSpeed();

        default String describe() {
            return "我能游泳，时速 " + getSwimSpeed() + " km/h";
        }
    }

    interface Runnable2 {    // 避免和 java.lang.Runnable 冲突，加个2
        void run2();
    }

    // ============================================================
    // 实现类：可以同时实现多个接口
    // ============================================================

    // 鸟：会飞、会跑
    static class Bird implements Flyable, Runnable2 {
        private String name;
        public Bird(String name) { this.name = name; }

        @Override
        public void fly() {
            System.out.println(name + " 展翅飞翔");
        }

        @Override
        public int getMaxSpeed() { return 120; }

        @Override
        public void run2() {
            System.out.println(name + " 在地上跑");
        }
        // describe() 用 Flyable 的 default 实现，不需要写
    }

    // 鸭子：会飞、会游泳、会跑（三个接口都实现）
    static class Duck implements Flyable, Swimmable, Runnable2 {
        private String name;
        public Duck(String name) { this.name = name; }

        @Override
        public void fly()  { System.out.println(name + " 低飞"); }

        @Override
        public void swim() { System.out.println(name + " 扑腾游泳"); }

        @Override
        public void run2() { System.out.println(name + " 摇摇晃晃跑"); }

        @Override
        public int getMaxSpeed()  { return 50; }

        @Override
        public int getSwimSpeed() { return 8; }

        // 两个接口都有 describe()，产生冲突，必须手动覆盖
        @Override
        public String describe() {
            // 可以选择调用某一个接口的 default 实现
            return Flyable.super.describe() + "，" + Swimmable.super.describe();
        }
    }

    // 飞机：会飞，但不是动物——接口跨越了类族限制
    static class Airplane implements Flyable {
        private String model;
        public Airplane(String model) { this.model = model; }

        @Override
        public void fly() { System.out.println(model + " 起飞"); }

        @Override
        public int getMaxSpeed() { return 900; }
    }

    // ============================================================
    // 接口作为类型：统一处理不同的实现类
    // ============================================================
    static void letItFly(Flyable f) {
        f.fly();
        System.out.println(f.describe());
    }

    // ============================================================
    // 函数式接口（FunctionalInterface）：只有一个抽象方法，可用 Lambda
    // ============================================================
    @FunctionalInterface
    interface Validator<T> {
        boolean validate(T value);

        // default 方法不算抽象方法，不影响函数式接口
        default Validator<T> and(Validator<T> other) {
            return value -> this.validate(value) && other.validate(value);
        }
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) {

        System.out.println("=== 多态：统一处理不同实现 ===");
        Bird bird = new Bird("麻雀");
        Duck duck = new Duck("唐老鸭");
        Airplane plane = new Airplane("波音737");

        // 鸟、鸭子、飞机都能当 Flyable 来用
        List<Flyable> flyers = Arrays.asList(bird, duck, plane);
        flyers.forEach(Interface1_Basic::letItFly);

        System.out.println("\n=== 鸭子的多接口能力 ===");
        duck.fly();
        duck.swim();
        duck.run2();
        System.out.println(duck.describe());  // 覆盖后的 describe

        System.out.println("\n=== 接口静态方法 ===");
        Flyable.showMaxAltitude();

        System.out.println("\n=== 函数式接口 + Lambda ===");
        // Lambda 实现 Validator 接口
        Validator<String> notEmpty = s -> !s.isEmpty();
        Validator<String> notTooLong = s -> s.length() <= 10;
        // and() 组合两个验证器
        Validator<String> combined = notEmpty.and(notTooLong);

        String[] inputs = {"", "Hello", "这个字符串超过了十个字符限制"};
        for (String input : inputs) {
            System.out.printf("  \"%s\" 验证%s%n",
                    input, combined.validate(input) ? "通过" : "失败");
        }

        /*
         * 运行后观察：
         *   1. letItFly 接受 Flyable 类型，鸟/鸭子/飞机都能传进去
         *      这就是接口作为"类型"使用的威力
         *   2. 鸭子实现了3个接口，具备3种能力，而 Java 只允许继承1个类
         *   3. 两个接口都有 describe() 时，Duck 必须手动解决冲突
         *   4. Lambda 直接写成箭头函数，比匿名内部类简洁得多
         */
    }
}
