package selflearning.jvmandgc;

import java.io.*;
import java.lang.reflect.Field;

/**
 * 类加载机制：类是如何被 JVM 加载的
 *
 * 面试高频：双亲委派模型是什么？为什么这样设计？能不能打破？
 */
public class JVM2_ClassLoader {

    // ============================================================
    // 一、类的生命周期（7个阶段）
    // ============================================================
    /*
     * 加载(Loading) → 验证(Verification) → 准备(Preparation)
     *   → 解析(Resolution) → 初始化(Initialization)
     *   → 使用(Using) → 卸载(Unloading)
     *
     * 前五个阶段合称"类加载"，面试重点在其中三个：
     *
     * ① 加载（Loading）
     *   - 通过类的全限定名找到 .class 文件（或从 jar/网络/动态生成获取）
     *   - 将字节码的二进制数据读入内存
     *   - 在堆中创建 java.lang.Class 对象（类的元数据入口）
     *
     * ② 准备（Preparation）
     *   - 为类的静态变量分配内存，赋"零值"
     *   - 注意：static int x = 10; 在准备阶段 x=0，不是10！
     *   - static final int Y = 10; 例外，常量在准备阶段直接赋 10
     *
     * ③ 初始化（Initialization）
     *   - 执行类构造器 <clinit>（由静态变量赋值和 static{} 块合并而来）
     *   - 这才是 static int x = 10; 变成 10 的时刻
     *   - JVM 保证 <clinit> 线程安全（多线程同时初始化同一个类，只有一个能执行）
     *
     * 触发类初始化的时机（主动引用，记住这几个）：
     *   1. new 一个类的实例
     *   2. 访问类的静态变量（非常量）或调用静态方法
     *   3. 反射调用该类
     *   4. 初始化子类时，父类还没初始化
     *   5. JVM 启动时的主类（含 main 方法的类）
     */

    // 演示准备阶段 vs 初始化阶段
    static class StaticInitDemo {
        // 准备阶段：value = 0；初始化阶段：value = 10
        static int value = 10;

        // 常量：准备阶段就直接赋值 100（编译期确定，inline 到调用处）
        static final int CONSTANT = 100;

        // static 块：在初始化阶段执行
        static {
            System.out.println("StaticInitDemo 初始化，value=" + value);
        }
    }

    // ============================================================
    // 二、类加载器和双亲委派模型（面试核心！）
    // ============================================================
    /*
     * JDK8 有三层类加载器：
     *
     * BootstrapClassLoader（启动类加载器）
     *   ├─ C++ 实现，不是 Java 对象
     *   ├─ 加载 JRE/lib/rt.jar（String、Object 等核心类）
     *   └─ getClassLoader() 返回 null
     *
     * ExtClassLoader（扩展类加载器）
     *   ├─ 加载 JRE/lib/ext/*.jar
     *   └─ 父加载器是 BootstrapClassLoader
     *
     * AppClassLoader（应用类加载器）
     *   ├─ 加载 classpath 下的类（你写的代码）
     *   └─ 父加载器是 ExtClassLoader
     *
     * 自定义类加载器：继承 ClassLoader，重写 findClass()
     *
     * 双亲委派模型（Parent Delegation Model）：
     *   加载一个类时，先委托父加载器去加载，父加载器找不到才自己加载
     *
     *   AppClassLoader 要加载 com.example.User：
     *     → 委托 ExtClassLoader
     *       → 委托 BootstrapClassLoader
     *         → Bootstrap 在 rt.jar 里找不到 User
     *       → Ext 在 ext/*.jar 里找不到 User
     *     → App 在 classpath 里找到 User，加载它
     *
     * 为什么要双亲委派？
     *   安全性：防止核心类被替换。
     *   你写了一个 java.lang.String，AppClassLoader 会先委托 Bootstrap，
     *   Bootstrap 找到了真正的 String 并加载，你写的那个永远不会被加载。
     *   如果没有双亲委派，恶意代码可以替换 String 等核心类！
     *
     * 打破双亲委派的情况：
     *   1. 热部署（Tomcat）：同一个 JVM 跑多个 Web 应用，
     *      每个应用的类相互隔离，Tomcat 自定义了 WebAppClassLoader
     *   2. SPI 机制：BootstrapClassLoader 加载接口，但实现类在 classpath，
     *      需要子加载器（线程上下文类加载器）来加载实现类
     *   3. OSGi：模块化框架，类加载是网状结构而非树状
     */

    // ============================================================
    // 三、自定义类加载器
    // ============================================================
    static class MyClassLoader extends ClassLoader {
        private String classPath;

        public MyClassLoader(String classPath) {
            this.classPath = classPath;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            // 1. 把类名转换为文件路径
            String fileName = classPath + File.separator
                    + name.replace('.', File.separatorChar) + ".class";

            // 2. 读取字节码文件
            byte[] classData;
            try {
                File file = new File(fileName);
                if (!file.exists()) {
                    throw new ClassNotFoundException(name);
                }
                classData = readFile(file);
            } catch (IOException e) {
                throw new ClassNotFoundException("无法读取类文件：" + name, e);
            }

            // 3. 把字节码转成 Class 对象
            return defineClass(name, classData, 0, classData.length);
        }

        private byte[] readFile(File file) throws IOException {
            try (FileInputStream fis = new FileInputStream(file);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = fis.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
                return baos.toByteArray();
            }
        }
    }

    // ============================================================
    // 四、类加载器与类的唯一性
    // ============================================================
    /*
     * 同一个类被不同类加载器加载，得到的是不同的 Class 对象！
     * 即使字节码完全相同，用不同加载器加载的"同一个类"也不相等。
     *
     * JVM 中类的唯一性由：类加载器 + 类的全限定名 共同确定
     *
     * 这是 Tomcat 实现应用隔离的基础：
     *   app1/WEB-INF/lib 里的 Foo.class 和 app2/WEB-INF/lib 里的 Foo.class
     *   各自用自己的 WebAppClassLoader 加载，互不干扰
     */

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) throws Exception {

        System.out.println("=== 类加载器层次 ===");
        // 获取各层类加载器
        ClassLoader appLoader = JVM2_ClassLoader.class.getClassLoader();
        ClassLoader extLoader = appLoader.getParent();
        ClassLoader bootLoader = extLoader.getParent(); // null（C++实现）

        System.out.println("AppClassLoader：" + appLoader);
        System.out.println("ExtClassLoader：" + extLoader);
        System.out.println("BootstrapClassLoader：" + bootLoader); // null

        // String 由 Bootstrap 加载
        ClassLoader stringLoader = String.class.getClassLoader();
        System.out.println("String 的类加载器：" + stringLoader); // null（Bootstrap）

        System.out.println("\n=== 双亲委派演示 ===");
        // 加载自己的类，由 AppClassLoader 加载
        System.out.println("JVM2_ClassLoader 由：" + JVM2_ClassLoader.class.getClassLoader());
        // 加载 JDK 核心类，由 Bootstrap 加载
        System.out.println("Object 由：" + Object.class.getClassLoader()); // null

        System.out.println("\n=== 类初始化时机 ===");
        System.out.println("访问 CONSTANT 前（编译器内联，不触发初始化）");
        int c = StaticInitDemo.CONSTANT; // 不触发初始化！常量编译时已inline
        System.out.println("CONSTANT=" + c);
        System.out.println("访问 value（触发初始化）：");
        int v = StaticInitDemo.value;    // 触发初始化，打印 static 块的内容
        System.out.println("value=" + v);

        System.out.println("\n=== 反射获取类信息 ===");
        Class<?> clazz = Class.forName("selflearning.jvmandgc.JVM2_ClassLoader$StaticInitDemo");
        System.out.println("类名：" + clazz.getName());
        System.out.println("简单名：" + clazz.getSimpleName());
        Field[] fields = clazz.getDeclaredFields();
        System.out.println("字段数：" + fields.length);
        for (Field f : fields) {
            System.out.println("  字段：" + f.getName() + " 类型：" + f.getType().getSimpleName());
        }

        /*
         * 面试必答：
         *
         * Q：什么是双亲委派模型？为什么要这样设计？
         * A：类加载时先委托父加载器，父加载器找不到才自己加载。
         *    目的：保证核心类不被覆盖（安全性），保证同一个类只加载一次（一致性）。
         *
         * Q：能打破双亲委派吗？怎么做？
         * A：可以。继承 ClassLoader 并重写 loadClass()（不是 findClass()）。
         *    findClass 是在父加载器找不到时才调 findClass，所以只重写 findClass
         *    不会打破委派模型。要打破必须重写 loadClass，不先委托父加载器。
         *    Tomcat 就是这么做的，实现多应用隔离。
         *
         * Q：static 变量什么时候初始化？
         * A：类初始化阶段（<clinit>），不是准备阶段。
         *    准备阶段只赋零值（0/null/false），
         *    初始化阶段执行赋值语句和 static{} 块。
         */
    }
}
