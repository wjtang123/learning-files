package selflearning.abstractandinterface;

/**
 * 抽象类基础用法
 *
 * 抽象类的两个核心能力：
 *   1. 用 abstract 方法强制子类必须实现某个行为（否则编译报错）
 *   2. 提供普通方法，让子类直接继承复用代码（避免重复）
 *
 * 什么时候用抽象类？
 *   - 多个类有"共同的本质"（is-a 关系）
 *   - 它们有大量相同的逻辑可以复用
 *   - 同时又有部分行为必须由各自决定
 *
 * 经典场景：模板方法模式
 *   父类定义"做事的步骤"，某些步骤的具体实现交给子类
 */
public class Abstract1_Basic {

    // ============================================================
    // 抽象类：动物
    // ============================================================
    static abstract class Animal {

        private String name;

        public Animal(String name) {
            this.name = name;
        }

        // abstract 方法：只有声明，没有方法体
        // 子类必须实现，否则子类也得是抽象类
        public abstract String makeSound();    // 每种动物叫声不同
        public abstract String getType();      // 每种动物类型不同

        // 普通方法：所有子类共享这段逻辑，不需要各自重写
        public void introduce() {
            System.out.println("我是" + getType() + "，名叫"
                    + name + "，我的叫声是：" + makeSound());
        }

        // 模板方法：定义"睡觉"的完整流程，某些步骤由子类决定
        public final void sleep() {           // final：不允许子类改变这个流程
            findSleepPlace();                 // 第一步：找地方（各自不同）
            System.out.println(name + " 闭上眼睛...");  // 第二步：固定行为
            System.out.println(name + " 进入梦乡");      // 第三步：固定行为
        }

        // 子类可以覆盖，也可以不覆盖（有默认实现）
        protected void findSleepPlace() {
            System.out.println(name + " 随便找个地方");
        }

        public String getName() { return name; }
    }

    // ============================================================
    // 具体子类：必须实现所有 abstract 方法
    // ============================================================
    static class Dog extends Animal {
        public Dog(String name) { super(name); }

        @Override
        public String makeSound() { return "汪汪汪"; }

        @Override
        public String getType() { return "狗"; }

        @Override
        protected void findSleepPlace() {
            System.out.println(getName() + " 趴在狗窝里");  // 覆盖了默认实现
        }
    }

    static class Cat extends Animal {
        public Cat(String name) { super(name); }

        @Override
        public String makeSound() { return "喵喵喵"; }

        @Override
        public String getType() { return "猫"; }
        // 没有覆盖 findSleepPlace()，用父类的默认实现
    }

    // 抽象类也可以继承抽象类
    // 中间层可以只实现部分方法，剩下的继续交给下一层
    static abstract class Bird extends Animal {
        public Bird(String name) { super(name); }

        @Override
        public String getType() { return "鸟"; }  // 这里实现了 getType

        // makeSound() 仍然是 abstract，留给具体鸟类实现
        // 新增抽象方法
        public abstract double getWingSpan();
    }

    static class Eagle extends Bird {
        public Eagle(String name) { super(name); }

        @Override
        public String makeSound() { return "啾啾"; }

        @Override
        public double getWingSpan() { return 2.1; }
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) {
        // Animal animal = new Animal("x");  // 编译报错！抽象类不能实例化

        Animal dog = new Dog("旺财");
        Animal cat = new Cat("咪咪");
        Eagle eagle = new Eagle("雄鹰");

        // 多态：统一调用，各自执行自己的实现
        dog.introduce();
        cat.introduce();
        eagle.introduce();

        System.out.println("\n--- 模板方法：睡觉流程 ---");
        dog.sleep();   // findSleepPlace() 走 Dog 的实现
        System.out.println();
        cat.sleep();   // findSleepPlace() 走父类默认实现

        System.out.println("\n翼展：" + eagle.getWingSpan() + "m");

        /*
         * 运行后观察：
         *   1. introduce() 方法在父类只写了一次，所有子类都能用
         *   2. makeSound() 各自返回不同结果，多态生效
         *   3. sleep() 流程固定（final），但 findSleepPlace() 可以定制
         *   4. 抽象类不能 new，只能 new 它的具体子类
         */
    }
}
