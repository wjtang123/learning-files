package selflearning.dbandmybatis;

import java.sql.*;
import java.util.*;

/**
 * JDBC 底层原理 + 连接池
 *
 * 为什么要学 JDBC？
 *   MyBatis/JPA 底层都是 JDBC。理解了 JDBC，
 *   你就知道 MyBatis 帮你省掉了什么，出了问题也能定位到根源。
 *
 * JDBC 的七个步骤：
 *   1. 加载驱动（现代 JDBC 自动加载，可省略）
 *   2. 获取连接（Connection）
 *   3. 创建语句（Statement / PreparedStatement）
 *   4. 执行 SQL
 *   5. 处理结果（ResultSet）
 *   6. 关闭资源
 *
 * 运行前提：本地有 MySQL，建一张 users 表：
 *   CREATE TABLE users (
 *     id       INT AUTO_INCREMENT PRIMARY KEY,
 *     username VARCHAR(50) NOT NULL,
 *     email    VARCHAR(100),
 *     age      INT,
 *     created_at DATETIME DEFAULT NOW()
 *   );
 */
public class DB1_JDBC {

    // 数据库连接配置（实际项目放在配置文件里）
    static final String URL      = "jdbc:mysql://localhost:3306/testdb" +
                                   "?useSSL=false&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
    static final String USERNAME = "root";
    static final String PASSWORD = "123456";

    // ============================================================
    // 一、最基础的 JDBC 操作
    // ============================================================
    static void basicJdbc() throws Exception {

        // 步骤1：获取连接（从驱动管理器建立一条到 MySQL 的 TCP 连接）
        Connection conn = DriverManager.getConnection(URL, USERNAME, PASSWORD);
        System.out.println("连接成功：" + conn.getMetaData().getDatabaseProductName());

        // 步骤2：使用 PreparedStatement（预编译，防 SQL 注入）
        // ? 是占位符，后面 setXxx 按位置填值（从1开始）
        String sql = "INSERT INTO users(username, email, age) VALUES(?, ?, ?)";
        PreparedStatement pstmt = conn.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS); // 返回自增 ID

        pstmt.setString(1, "Alice");
        pstmt.setString(2, "alice@example.com");
        pstmt.setInt(3, 25);

        int affected = pstmt.executeUpdate(); // 执行增删改，返回影响行数
        System.out.println("插入影响行数：" + affected);

        // 获取自增 ID
        ResultSet keys = pstmt.getGeneratedKeys();
        if (keys.next()) {
            System.out.println("新用户 ID：" + keys.getLong(1));
        }

        // 步骤3：查询
        PreparedStatement query = conn.prepareStatement(
                "SELECT id, username, email, age FROM users WHERE age > ?");
        query.setInt(1, 18);

        ResultSet rs = query.executeQuery(); // 执行查询，返回结果集
        while (rs.next()) {
            // 按列名或列索引取值（推荐按列名，不受 SELECT 顺序影响）
            System.out.printf("  id=%-4d username=%-10s age=%d%n",
                    rs.getInt("id"),
                    rs.getString("username"),
                    rs.getInt("age"));
        }

        // 步骤4：必须关闭！顺序：ResultSet → Statement → Connection
        rs.close();
        query.close();
        pstmt.close();
        conn.close();
        // 实际开发用 try-with-resources 自动关闭（见下方）
    }

    // ============================================================
    // 二、正确写法：try-with-resources 自动关闭
    // ============================================================
    static List<Map<String, Object>> queryUsers(int minAge) throws Exception {
        String sql = "SELECT id, username, email, age FROM users WHERE age >= ? ORDER BY id";

        // 自动关闭：代码块结束自动调用 close()，无论是否抛异常
        try (Connection conn = DriverManager.getConnection(URL, USERNAME, PASSWORD);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, minAge);

            try (ResultSet rs = pstmt.executeQuery()) {
                List<Map<String, Object>> results = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id",       rs.getInt("id"));
                    row.put("username", rs.getString("username"));
                    row.put("email",    rs.getString("email"));
                    row.put("age",      rs.getInt("age"));
                    results.add(row);
                }
                return results;
            }
        }
    }

    // ============================================================
    // 三、事务控制
    // ============================================================
    static void transactionDemo() throws Exception {
        try (Connection conn = DriverManager.getConnection(URL, USERNAME, PASSWORD)) {

            // 关闭自动提交（默认是每条 SQL 自动提交）
            conn.setAutoCommit(false);

            try {
                PreparedStatement deduct = conn.prepareStatement(
                        "UPDATE accounts SET balance = balance - ? WHERE id = ?");
                deduct.setDouble(1, 100.0);
                deduct.setInt(2, 1);
                deduct.executeUpdate();

                PreparedStatement add = conn.prepareStatement(
                        "UPDATE accounts SET balance = balance + ? WHERE id = ?");
                add.setDouble(1, 100.0);
                add.setInt(2, 2);
                add.executeUpdate();

                conn.commit();   // 两步都成功，提交
                System.out.println("转账成功");

            } catch (SQLException e) {
                conn.rollback(); // 任何一步失败，回滚
                System.out.println("转账失败，已回滚：" + e.getMessage());
                throw e;
            }
        }
    }

    // ============================================================
    // 四、为什么需要连接池？
    // ============================================================
    /*
     * 问题：每次 DriverManager.getConnection() 都要：
     *   1. 建立 TCP 连接（三次握手）
     *   2. MySQL 认证（用户名密码验证）
     *   3. 分配资源（线程、内存）
     *   耗时：通常 50~200ms（本地）或更长（远程）
     *
     * 如果每次查询都新建连接，100 个并发就要等 100 个连接建立完成，
     * 每个连接 100ms，光建连接就花了 10 秒！
     *
     * 连接池的思路：预先建立一批连接，请求来了从池里取，
     * 用完放回池里，不销毁。就像共享单车。
     *
     * ┌─────────────────────────────────────────┐
     * │            连接池（最大20个连接）          │
     * │  [conn1] [conn2] [conn3] ... [conn20]    │
     * │    ↑取出             ↑归还                │
     * └─────────────────────────────────────────┘
     *   请求A    请求B    请求C    请求D（排队等待）
     *
     * HikariCP（Spring Boot 默认）：最快的连接池
     * Druid（阿里开源）：功能最全，有 SQL 监控、慢查询日志
     *
     * Spring Boot 配置（application.yml）：
     *
     * spring:
     *   datasource:
     *     url: jdbc:mysql://localhost:3306/testdb
     *     username: root
     *     password: 123456
     *     hikari:
     *       maximum-pool-size: 20      # 最大连接数（核心参数）
     *       minimum-idle: 5            # 最小空闲连接数
     *       connection-timeout: 30000  # 等待连接超时（ms）
     *       idle-timeout: 600000       # 空闲连接回收时间（ms）
     *       max-lifetime: 1800000      # 连接最长存活时间（ms），要小于 MySQL wait_timeout
     *
     * maximum-pool-size 怎么设？
     *   经验公式：核心数 * 2 + 磁盘数（SSD 时用 核心数 * 2）
     *   实际建议：从小开始（10~20），压测后根据监控调整
     *   注意：连接数不是越多越好！MySQL 每个连接占内存，太多反而慢
     */

    // ============================================================
    // 五、Statement vs PreparedStatement（面试必考）
    // ============================================================
    static void statementVsPrepared() throws Exception {
        try (Connection conn = DriverManager.getConnection(URL, USERNAME, PASSWORD)) {

            // Statement：字符串拼接 SQL（危险！）
            String userInput = "'; DROP TABLE users; --"; // SQL 注入攻击
            Statement stmt = conn.createStatement();
            // 下面这行如果执行，users 表会被删除！
            String badSql = "SELECT * FROM users WHERE username = '" + userInput + "'";
            System.out.println("危险SQL：" + badSql);
            // stmt.execute(badSql); // 不要真的执行！

            // PreparedStatement：参数化查询（安全）
            PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT * FROM users WHERE username = ?");
            pstmt.setString(1, userInput); // 特殊字符会被转义，不会注入
            // 实际执行的 SQL：SELECT * FROM users WHERE username = '\'; DROP TABLE users; --'
            // MySQL 把整个字符串当成 username 的值，不会执行 DROP

            System.out.println("PreparedStatement 防止了 SQL 注入");

            // PreparedStatement 的另一个优点：预编译
            // 同样的 SQL 多次执行，MySQL 只编译一次，后续执行更快
            for (int i = 0; i < 100; i++) {
                pstmt.setInt(1, i); // 换参数，不重新编译 SQL
                // pstmt.executeQuery();
            }
        }
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) throws Exception {
        System.out.println("=== JDBC 底层原理演示 ===");
        System.out.println("（需要本地 MySQL 才能真正运行，这里展示代码结构）");

        // 演示 Statement vs PreparedStatement（不需要真实数据库）
        System.out.println("\n=== SQL 注入演示 ===");
        // statementVsPrepared(); // 取消注释并配好数据库后运行

        /*
         * JDBC 的核心知识点：
         *   Connection  → 一个到数据库的连接，很贵，要复用（连接池）
         *   Statement   → 执行 SQL，字符串拼接，有注入风险
         *   PreparedStatement → 预编译，参数化，安全且快，几乎永远用这个
         *   ResultSet   → 查询结果集，游标模式（next() 逐行移动）
         *   事务控制    → setAutoCommit(false) + commit/rollback
         *
         * MyBatis 帮你做的事：
         *   ① 获取/归还连接（你只写 SQL，不写 getConnection/close）
         *   ② ? 的填充（你写 #{username}，它自动 setString）
         *   ③ ResultSet 映射（它自动把列映射到对象字段）
         *   ④ 异常转换（SQLException → Spring 的 DataAccessException）
         */
    }
}
