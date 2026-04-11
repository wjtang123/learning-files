package selflearning.ioandfile;

import java.io.*;

/**
 * 字节流 & 字符流 —— IO 的基础
 *
 * 先搞清楚两个核心问题：
 *
 * 问题一：字节流 vs 字符流，选哪个？
 *   字节流（InputStream/OutputStream）：
 *     - 以字节（byte）为单位，读写任何文件（图片、视频、zip…）
 *     - 读文本时不处理编码，可能出现乱码
 *   字符流（Reader/Writer）：
 *     - 以字符（char）为单位，专门处理文本
 *     - 内部自动处理编码转换，推荐读写文本时使用
 *   选择原则：文本文件 → 字符流；二进制文件 → 字节流
 *
 * 问题二：为什么要用 Buffered 包装？
 *   FileInputStream 每次 read() 都是一次系统调用（很慢）
 *   BufferedInputStream 在内存里维护一个缓冲区（默认8KB），
 *   一次系统调用读一大块，后续从内存缓冲里取，快很多
 *   原则：几乎所有 IO 都应该套一层 Buffered
 *
 * 流的装饰器结构（重要！理解这个就理解了 Java IO 的设计）：
 *   FileInputStream          ← 数据源（从哪读）
 *   └─ BufferedInputStream   ← 加缓冲（提速）
 *      └─ DataInputStream    ← 加能力（读基本类型）
 *   每一层都是对上一层的"包装"，可以任意组合
 */
public class IO1_Stream {

    static final String FILE_PATH = "test_stream.txt";

    // ============================================================
    // 示例一：字符流写文件（文本推荐）
    // ============================================================
    static void writeWithCharStream() throws IOException {
        // FileWriter：字符流，直接写字符
        // BufferedWriter：包一层缓冲，提升性能
        // true：追加模式，false（默认）：覆盖模式
        try (BufferedWriter writer = new BufferedWriter(
                new FileWriter(FILE_PATH, false))) {

            writer.write("第一行：Hello Java IO");
            writer.newLine();           // 跨平台换行（比直接写 \n 更好）
            writer.write("第二行：字符流写文本很方便");
            writer.newLine();
            writer.write("第三行：自动处理编码");

            // try-with-resources：代码块结束自动调用 close()
            // 不需要手写 finally { writer.close(); }
        }
        System.out.println("字符流写入完成：" + FILE_PATH);
    }

    // ============================================================
    // 示例二：字符流读文件
    // ============================================================
    static void readWithCharStream() throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new FileReader(FILE_PATH))) {

            // 方式一：逐行读（最常用）
            System.out.println("--- 逐行读取 ---");
            String line;
            int lineNum = 1;
            // readLine() 返回 null 表示到达文件末尾
            while ((line = reader.readLine()) != null) {
                System.out.println("  第" + lineNum++ + "行：" + line);
            }
        }
    }

    // ============================================================
    // 示例三：字节流读写（适合任何文件，如图片复制）
    // ============================================================
    static void copyFileWithByteStream(String src, String dst) throws IOException {
        // 字节流：不处理编码，原封不动地复制每一个字节
        try (BufferedInputStream in  = new BufferedInputStream(
                                            new FileInputStream(src));
             BufferedOutputStream out = new BufferedOutputStream(
                                            new FileOutputStream(dst))) {

            byte[] buffer = new byte[8192]; // 8KB 缓冲区
            int bytesRead;
            long total = 0;

            // read() 返回实际读到的字节数，-1 表示结束
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead); // 只写实际读到的字节
                total += bytesRead;
            }
            // out.flush() 在 close() 时会自动调用，try-with-resources 保证

            System.out.println("文件复制完成，共 " + total + " 字节");
        }
    }

    // ============================================================
    // 示例四：指定编码（解决乱码问题）
    // ============================================================
    static void writeWithEncoding() throws IOException {
        String path = "test_utf8.txt";
        // 用 OutputStreamWriter 桥接字节流和字符流，同时指定编码
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(path), "UTF-8"))) {
            writer.write("中文内容，UTF-8 编码");
            writer.newLine();
            writer.write("避免乱码的关键：读写用同一种编码");
        }

        // 读时也要指定相同编码
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(path), "UTF-8"))) {
            System.out.println("指定编码读取：" + reader.readLine());
        }
    }

    // ============================================================
    // 示例五：DataStream 读写基本类型
    // ============================================================
    static void dataStreamDemo() throws IOException {
        String path = "test_data.bin";

        // 写入基本类型
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(path)))) {
            out.writeInt(42);
            out.writeDouble(3.14);
            out.writeBoolean(true);
            out.writeUTF("UTF编码字符串"); // writeUTF 自动处理编码
        }

        // 读出时顺序必须和写入时完全一致！
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(path)))) {
            System.out.println("int：" + in.readInt());
            System.out.println("double：" + in.readDouble());
            System.out.println("boolean：" + in.readBoolean());
            System.out.println("String：" + in.readUTF());
        }
    }

    // ============================================================
    // 示例六：序列化 —— 把对象存到文件
    // ============================================================
    // 实现 Serializable 接口才能被序列化（接口里没有任何方法，只是标记）
    static class User implements Serializable {
        // serialVersionUID：版本号，类结构变了后 UID 不变就能读旧文件
        // 强烈建议手动定义，否则 JVM 自动生成，类稍有变化就读取失败
        private static final long serialVersionUID = 1L;

        String name;
        int age;
        transient String password; // transient：不参与序列化（敏感字段）

        User(String name, int age, String password) {
            this.name = name;
            this.age = age;
            this.password = password;
        }

        @Override
        public String toString() {
            return "User{name=" + name + ", age=" + age
                    + ", password=" + password + "}";
        }
    }

    static void serializationDemo() throws IOException, ClassNotFoundException {
        String path = "test_user.ser";
        User original = new User("张三", 25, "secret123");
        System.out.println("原始对象：" + original);

        // 序列化：对象 → 文件
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new BufferedOutputStream(new FileOutputStream(path)))) {
            oos.writeObject(original);
        }

        // 反序列化：文件 → 对象
        try (ObjectInputStream ois = new ObjectInputStream(
                new BufferedInputStream(new FileInputStream(path)))) {
            User restored = (User) ois.readObject();
            System.out.println("反序列化：" + restored);
            // 注意：password 是 transient，反序列化后是 null
        }
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) throws Exception {
        System.out.println("=== 字符流写文件 ===");
        writeWithCharStream();

        System.out.println("\n=== 字符流读文件 ===");
        readWithCharStream();

        System.out.println("\n=== 字节流复制文件 ===");
        copyFileWithByteStream(FILE_PATH, "test_copy.txt");

        System.out.println("\n=== 指定编码读写 ===");
        writeWithEncoding();

        System.out.println("\n=== DataStream 读写基本类型 ===");
        dataStreamDemo();

        System.out.println("\n=== 序列化 ===");
        serializationDemo();

        // 清理测试文件
        new File(FILE_PATH).delete();
        new File("test_copy.txt").delete();
        new File("test_utf8.txt").delete();
        new File("test_data.bin").delete();
        new File("test_user.ser").delete();

        /*
         * 运行后观察：
         *   1. try-with-resources 自动关闭流，再也不用写 finally
         *   2. 字节流复制文件：不关心内容，原样复制每个字节
         *   3. transient 字段序列化后变成 null，保护敏感数据
         *   4. DataStream 读写顺序必须完全一致，否则数据错乱
         */
    }
}
