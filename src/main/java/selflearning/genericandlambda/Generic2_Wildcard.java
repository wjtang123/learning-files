package selflearning.genericandlambda;

import java.util.*;

/**
 * 泛型进阶：通配符 + 类型擦除
 *
 * 这是泛型最难理解的部分，也是面试最爱考的地方。
 * 掌握两个核心规则就能解决大多数问题：
 *
 *   PECS 原则（Producer Extends, Consumer Super）：
 *     需要从集合"读数据"（生产者）→ 用 <? extends T>（上界）
 *     需要向集合"写数据"（消费者）→ 用 <? super T>（下界）
 *     既要读又要写 → 用具体类型 <T>，不用通配符
 */
public class Generic2_Wildcard {

    // 类层次结构（用于演示）
    static class Animal {
        String name;
        Animal(String name) { this.name = name; }
        String sound() { return "..."; }
        @Override public String toString() { return getClass().getSimpleName() + "(" + name + ")"; }
    }
    static class Dog extends Animal {
        Dog(String name) { super(name); }
        @Override String sound() { return "汪"; }
    }
    static class Cat extends Animal {
        Cat(String name) { super(name); }
        @Override String sound() { return "喵"; }
    }
    static class GuideDog extends Dog {
        GuideDog(String name) { super(name); }
        @Override String sound() { return "汪汪（导盲犬）"; }
    }

    // ============================================================
    // 无界通配符 <?>：只读，不关心具体类型
    // ============================================================
    static void printList(List<?> list) {
        // <?> 表示"某种未知类型的 List"
        // 可以读（得到 Object），但不能写（不知道具体类型，写什么都可能出错）
        for (Object item : list) {
            System.out.print(item + " ");
        }
        System.out.println();
    }

    // ============================================================
    // 上界通配符 <? extends T>：只读，类型是 T 或 T 的子类
    // 场景：只需要"读"数据，不需要"写"
    // ============================================================
    static double sumNumbers(List<? extends Number> numbers) {
        // numbers 可以是 List<Integer>、List<Double>、List<Float> 等
        // 但不能向里面 add（因为不知道具体是哪个子类）
        double sum = 0;
        for (Number n : numbers) { // 读出来当 Number 用，安全
            sum += n.doubleValue();
        }
        return sum;
    }

    // 更直观的例子：打印所有动物的叫声
    static void makeAllSound(List<? extends Animal> animals) {
        // 可以是 List<Dog>、List<Cat>、List<Animal>
        for (Animal a : animals) {  // 读出来当 Animal 用
            System.out.println(a.name + " 说：" + a.sound());
        }
        // animals.add(new Dog("旺财")); // 编译报错！不能写
        // 原因：传进来的可能是 List<Cat>，往里加 Dog 会破坏类型安全
    }

    // ============================================================
    // 下界通配符 <? super T>：可写，类型是 T 或 T 的父类
    // 场景：需要向集合"写入"数据
    // ============================================================
    static void addDogs(List<? super Dog> list) {
        // list 可以是 List<Dog>、List<Animal>、List<Object>
        // 往里加 Dog 一定安全（Dog 肯定是 Dog 或其父类的实例）
        list.add(new Dog("小黑"));
        list.add(new GuideDog("导盲犬小明")); // GuideDog 是 Dog 的子类，也安全

        // 但读出来只能当 Object，因为不知道具体父类是哪个
        // Dog d = list.get(0); // 编译报错！
        Object o = list.get(0); // 只能当 Object 读
        System.out.println("加入了：" + o);
    }

    // ============================================================
    // PECS 综合案例：集合复制
    // src 是生产者（读），用 extends；dst 是消费者（写），用 super
    // ============================================================
    static <T> void copy(List<? extends T> src, List<? super T> dst) {
        for (T item : src) {
            dst.add(item);
        }
    }

    // ============================================================
    // 类型擦除（Type Erasure）—— 面试必考！
    // ============================================================
    static void typeErasureDemo() {
        List<String>  strList = new ArrayList<String>();
        List<Integer> intList = new ArrayList<Integer>();

        // 运行时，两个 List 的类型是完全相同的！
        System.out.println("类型相同？" + (strList.getClass() == intList.getClass())); // true
        System.out.println("都是：" + strList.getClass().getName()); // java.util.ArrayList

        // 泛型信息只在编译阶段存在，编译后被"擦除"
        // Box<String> 在字节码里变成 Box（原始类型），T 变成 Object
        // 这就是为什么不能：
        //   new T()              // 不知道 T 的构造器
        //   new T[]              // 不能创建泛型数组
        //   instanceof List<String>  // 运行时没有泛型信息

        // 演示：通过反射绕过泛型检查（说明运行时没有类型保护）
        List<Integer> list = new ArrayList<Integer>();
        list.add(1);
        list.add(2);

        // 用反射强行往 List<Integer> 里塞字符串
        try {
            list.getClass().getMethod("add", Object.class).invoke(list, "偷偷混入的字符串");
            System.out.println("反射绕过泛型，list 内容：" + list);
            // 取出时才报 ClassCastException
            // Integer x = list.get(2); // 这里会报错
        } catch (Exception e) {
            System.out.println("反射操作异常：" + e.getMessage());
        }
    }

    // ============================================================
    // 泛型的限制（类型擦除导致的）
    // ============================================================
    static <T> void genericLimits(T t) {
        // 以下操作都会编译报错：

        // 1. 不能用 instanceof 判断泛型类型
        // if (t instanceof T) {}  // 报错

        // 2. 不能创建泛型类型的实例
        // T obj = new T();  // 报错

        // 3. 不能创建泛型数组
        // T[] arr = new T[10];  // 报错

        // 4. 不能用泛型类型作为异常
        // } catch (T e) {}  // 报错

        // 可以这样做：
        System.out.println(t.getClass().getSimpleName()); // 运行时获取实际类型
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) {
        System.out.println("=== 无界通配符 <?> ===");
        printList(Arrays.asList(1, 2, 3));
        printList(Arrays.asList("a", "b", "c"));

        System.out.println("\n=== 上界 <? extends T>：只读 ===");
        List<Integer> ints    = Arrays.asList(1, 2, 3, 4, 5);
        List<Double>  doubles = Arrays.asList(1.1, 2.2, 3.3);
        System.out.println("整数求和：" + sumNumbers(ints));
        System.out.println("浮点求和：" + sumNumbers(doubles));

        List<Dog> dogs = Arrays.asList(new Dog("旺财"), new GuideDog("小明"));
        List<Cat> cats = Arrays.asList(new Cat("咪咪"), new Cat("橘子"));
        makeAllSound(dogs); // List<Dog> 传给 List<? extends Animal>
        makeAllSound(cats); // List<Cat> 传给 List<? extends Animal>

        System.out.println("\n=== 下界 <? super T>：可写 ===");
        List<Animal> animalList = new ArrayList<Animal>();

        addDogs(animalList); // List<Animal> 传给 List<? super Dog>
        System.out.println("animalList：" + animalList);

        System.out.println("\n=== PECS：集合复制 ===");
        List<Dog>    src = Arrays.asList(new Dog("A"), new Dog("B"));
        List<Animal> dst = new ArrayList<Animal>();
        copy(src, dst);
        System.out.println("复制结果：" + dst);

        System.out.println("\n=== 类型擦除 ===");
        typeErasureDemo();

        System.out.println("\n=== 运行时类型 ===");
        genericLimits("hello");
        genericLimits(42);

        /*
         * 面试回答"类型擦除是什么"的标准答案：
         *
         * Java 泛型是在编译期实现的，编译后所有泛型信息都被擦除。
         * 类型参数 T 在字节码中被替换为 Object（或上界类型），
         * 编译器在需要的地方自动插入类型转换代码。
         * 这意味着：
         *   - List<String> 和 List<Integer> 在运行时是同一个类
         *   - 不能在运行时通过 instanceof 判断泛型类型
         *   - 不能直接 new T()，因为 T 在运行时是 Object
         */
    }
}
