package selflearning.ioandfile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * IO 实战场景
 *
 * 把前三个文件学到的知识用到真实业务场景：
 *   1. CSV 文件读写（报表、数据导入导出）
 *   2. 大文件逐行处理（日志分析、数据处理）
 *   3. Properties 配置文件读写
 *   4. 临时文件的正确使用
 *   5. 常见踩坑总结
 */
public class IO4_Practice {

    // ============================================================
    // 场景一：CSV 文件读写
    // ============================================================
    static class Order implements Serializable {
        private static final long serialVersionUID = 1L;
        String orderId;
        String product;
        int quantity;
        double price;

        Order(String orderId, String product, int quantity, double price) {
            this.orderId   = orderId;
            this.product   = product;
            this.quantity  = quantity;
            this.price     = price;
        }

        // 转成 CSV 一行（注意：字段包含逗号时要加引号）
        String toCsvLine() {
            return String.join(",",
                    orderId,
                    "\"" + product + "\"", // 用引号保护可能含逗号的字段
                    String.valueOf(quantity),
                    String.valueOf(price));
        }

        @Override
        public String toString() {
            return "Order{" + orderId + ", " + product
                    + ", qty=" + quantity + ", price=" + price + "}";
        }
    }

    static void csvWriteDemo() throws IOException {
        String path = "orders.csv";
        List<Order> orders = Arrays.asList(
                new Order("ORD001", "苹果手机", 2, 5999.0),
                new Order("ORD002", "笔记本,电脑", 1, 8888.0), // 包含逗号
                new Order("ORD003", "耳机", 3, 299.5)
        );

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(path), StandardCharsets.UTF_8))) {
            // 写表头
            writer.write("订单号,商品名,数量,单价");
            writer.newLine();
            // 写数据
            for (Order order : orders) {
                writer.write(order.toCsvLine());
                writer.newLine();
            }
        }
        System.out.println("CSV 写入完成：" + path);
    }

    static List<Order> csvReadDemo() throws IOException {
        String path = "orders.csv";
        List<Order> result = new ArrayList <Order>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(path), StandardCharsets.UTF_8))) {

            String line;
            boolean isHeader = true;
            while ((line = reader.readLine()) != null) {
                if (isHeader) { isHeader = false; continue; } // 跳过表头

                // 简单 CSV 解析（生产环境建议用 Apache Commons CSV 库）
                // 先处理带引号的字段
                String[] parts = parseCsvLine(line);
                if (parts.length == 4) {
                    result.add(new Order(
                            parts[0],
                            parts[1],
                            Integer.parseInt(parts[2]),
                            Double.parseDouble(parts[3])
                    ));
                }
            }
        }
        return result;
    }

    // 简单 CSV 行解析（处理引号包裹的字段）
    static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields.toArray(new String[0]);
    }

    // ============================================================
    // 场景二：大文件逐行处理（日志分析）
    // ============================================================
    static void largeFileDemo() throws IOException {
        String logPath = "app.log";

        // 生成测试日志文件（模拟10万行）
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(logPath), StandardCharsets.UTF_8))) {
            String[] levels = {"INFO", "WARN", "ERROR", "INFO", "INFO"};
            Random rand = new Random(42);
            for (int i = 0; i < 100000; i++) {
                String level = levels[rand.nextInt(levels.length)];
                writer.write("2024-01-01 " + String.format("%05d", i)
                        + " [" + level + "] 日志消息 " + i);
                writer.newLine();
            }
        }

        // 逐行处理：统计各级别日志数量
        // 关键：不是一次性读入内存，而是一行一行处理
        // 这样无论文件多大，内存占用都是固定的（只有一行的大小）
        Map<String, Integer> levelCount = new LinkedHashMap<String, Integer>();
        long lineCount = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(logPath), StandardCharsets.UTF_8),
                64 * 1024)) { // 自定义缓冲区 64KB（默认 8KB）

            String line;
            while ((line = reader.readLine()) != null) {
                lineCount++;
                // 提取日志级别
                int start = line.indexOf('[');
                int end   = line.indexOf(']');
                if (start != -1 && end != -1) {
                    String level = line.substring(start + 1, end);
                    Integer count = levelCount.get(level);
                    levelCount.put(level, count == null ? 1 : count + 1);
                }
            }
        }

        System.out.println("共处理 " + lineCount + " 行日志：");
        for (Map.Entry<String, Integer> entry : levelCount.entrySet()) {
            System.out.println("  " + entry.getKey() + ": " + entry.getValue());
        }

        new File(logPath).delete();
    }

    // ============================================================
    // 场景三：Properties 配置文件
    // ============================================================
    static void propertiesDemo() throws IOException {
        String path = "app.properties";

        // 写配置
        Properties props = new Properties();
        props.setProperty("db.host", "localhost");
        props.setProperty("db.port", "3306");
        props.setProperty("db.name", "mydb");
        props.setProperty("app.timeout", "30");

        try (OutputStream out = new FileOutputStream(path)) {
            // 第二个参数是注释，写在文件开头
            props.store(out, "应用配置文件，修改后重启生效");
        }
        System.out.println("配置文件已生成：" + path);

        // 读配置
        Properties loaded = new Properties();
        try (InputStream in = new FileInputStream(path)) {
            loaded.load(in);
        }

        String host    = loaded.getProperty("db.host");
        String port    = loaded.getProperty("db.port", "3306"); // 带默认值
        int    timeout = Integer.parseInt(loaded.getProperty("app.timeout", "60"));

        System.out.println("db.host=" + host + ", port=" + port
                + ", timeout=" + timeout);

        new File(path).delete();
    }

    // ============================================================
    // 场景四：临时文件
    // ============================================================
    static void tempFileDemo() throws IOException {
        // 创建临时文件（系统临时目录，程序退出后不会自动删除！）
        File temp = File.createTempFile("myapp_", ".tmp");
        // 设置 JVM 退出时自动删除
        temp.deleteOnExit();

        System.out.println("临时文件路径：" + temp.getAbsolutePath());

        // 写入临时数据
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(temp))) {
            writer.write("临时数据");
        }

        // 读取
        try (BufferedReader reader = new BufferedReader(new FileReader(temp))) {
            System.out.println("临时文件内容：" + reader.readLine());
        }
        // temp.deleteOnExit() 已注册，JVM 退出时自动清理
    }

    // ============================================================
    // 常见踩坑演示
    // ============================================================
    static void pitfalls() throws IOException {
        System.out.println("\n=== 常见踩坑 ===");

        // 坑1：忘记关闭流（用 try-with-resources 彻底避免）
        // 错误写法：
        // FileWriter fw = new FileWriter("test.txt");
        // fw.write("xxx");
        // // 如果这里抛异常，fw 永远不会关闭，文件句柄泄漏
        // fw.close();
        System.out.println("坑1：始终用 try-with-resources，永不忘记关流");

        // 坑2：忘记 flush（try-with-resources 会自动 flush+close）
        // 手动用流时，写完记得 flush()，否则缓冲区数据可能没写进文件
        System.out.println("坑2：手动管理流时，写完调 flush()");

        // 坑3：路径分隔符写死
        // 错误：new File("dir\\file.txt")  只在 Windows 正确
        // 正确：
        String sep = File.separator; // Windows: \，Linux/Mac: /
        System.out.println("坑3：路径分隔符用 File.separator = '" + sep + "'");
        // 更好的写法：Paths.get("dir", "file.txt") 自动处理分隔符

        // 坑4：读大文件一次性 readAllBytes（OOM 风险）
        // 错误：byte[] all = Files.readAllBytes(hugeFile); // 几GB文件直接OOM
        // 正确：逐行 readLine() 或固定大小 buffer 分批读
        System.out.println("坑4：大文件用逐行读，不要 readAllBytes");

        // 坑5：字符流读二进制文件（图片会损坏）
        // 原因：字符流做编码转换，二进制数据经转换后字节会变
        // 正确：二进制文件只用字节流
        System.out.println("坑5：二进制文件（图片/zip）只用字节流");

        // 坑6：中文乱码
        // 原因：写用 GBK，读用 UTF-8（或系统默认编码不一致）
        // 正确：读写统一指定 StandardCharsets.UTF_8
        System.out.println("坑6：读写统一指定 StandardCharsets.UTF_8");
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) throws Exception {
        System.out.println("=== CSV 写入 ===");
        csvWriteDemo();

        System.out.println("\n=== CSV 读取 ===");
        List<Order> orders = csvReadDemo();
        for (Order o : orders) {
            System.out.println("  " + o);
        }
        new File("orders.csv").delete();

        System.out.println("\n=== 大文件逐行处理（10万行日志）===");
        largeFileDemo();

        System.out.println("\n=== Properties 配置文件 ===");
        propertiesDemo();

        System.out.println("\n=== 临时文件 ===");
        tempFileDemo();

        pitfalls();
    }
}
