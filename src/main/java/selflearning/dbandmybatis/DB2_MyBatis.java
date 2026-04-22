package selflearning.dbandmybatis; /**
 * MyBatis 核心用法
 *
 * MyBatis = SQL Mapper Framework（SQL 映射框架）
 *
 * 核心思想：
 *   你写 SQL，MyBatis 负责：
 *   ① 把 Java 对象的字段填进 SQL 的参数里（Java → SQL）
 *   ② 把查询结果的列映射回 Java 对象的字段（SQL → Java）
 *   ③ 管理连接、Statement、ResultSet 的生命周期
 *
 * 两种写法：
 *   注解方式：SQL 写在 @Select/@Insert 等注解里，简单 SQL 用这个
 *   XML 方式：SQL 写在 XML 文件里，复杂动态 SQL 用这个
 *   实际项目：两者混用，简单 CRUD 用注解，复杂查询用 XML
 */

// ============================================================
// 一、实体类（对应数据库表结构）
// ============================================================

import org.apache.ibatis.annotations.*;
import org.apache.ibatis.type.JdbcType;
import java.time.LocalDateTime;
import java.util.*;

class User {
    private Integer       id;
    private String        username;
    private String        email;
    private Integer       age;
    private LocalDateTime createdAt;  // 对应数据库 created_at（下划线→驼峰）

    public User() {}

    public User(String username, String email, Integer age) {
        this.username = username;
        this.email    = email;
        this.age      = age;
    }

    // Getters & Setters（实际项目用 Lombok @Data 省略）
    public Integer getId()                 { return id; }
    public void    setId(Integer id)       { this.id = id; }
    public String  getUsername()           { return username; }
    public void    setUsername(String u)   { this.username = u; }
    public String  getEmail()              { return email; }
    public void    setEmail(String e)      { this.email = e; }
    public Integer getAge()                { return age; }
    public void    setAge(Integer age)     { this.age = age; }
    public LocalDateTime getCreatedAt()    { return createdAt; }
    public void setCreatedAt(LocalDateTime t) { this.createdAt = t; }

    @Override
    public String toString() {
        return "User{id=" + id + ", username=" + username
                + ", age=" + age + "}";
    }
}

// ============================================================
// 二、Mapper 接口（注解方式）
// ============================================================
@Mapper  // Spring Boot 扫描并创建代理实现类
interface UserMapper {

    // ---- 基础 CRUD ----

    @Select("SELECT * FROM users WHERE id = #{id}")
    // MyBatis 自动把 ResultSet 的列映射到 User 对象
    // 列名下划线 → 驼峰，需要开启 map-underscore-to-camel-case=true
    User findById(@Param("id") Integer id);

    @Select("SELECT * FROM users ORDER BY id")
    List<User> findAll();

    @Select("SELECT COUNT(*) FROM users")
    int count();

    // ---- 插入：获取自增ID ----
    @Insert("INSERT INTO users(username, email, age, created_at) " +
            "VALUES(#{username}, #{email}, #{age}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    // useGeneratedKeys=true：告诉 MyBatis 要获取自增ID
    // keyProperty="id"：把自增ID回填到 user.id 字段
    int insert(User user);

    // ---- 更新 ----
    @Update("UPDATE users SET username=#{username}, email=#{email}, " +
            "age=#{age} WHERE id=#{id}")
    int update(User user);

    // ---- 删除 ----
    @Delete("DELETE FROM users WHERE id = #{id}")
    int deleteById(@Param("id") Integer id);

    // ---- 条件查询 ----
    @Select("SELECT * FROM users WHERE age BETWEEN #{minAge} AND #{maxAge}")
    List<User> findByAgeRange(@Param("minAge") int minAge,
                               @Param("maxAge") int maxAge);

    // ---- 模糊查询 ----
    // CONCAT 拼接 % 通配符，不能直接写 LIKE '%#{keyword}%'
    @Select("SELECT * FROM users WHERE username LIKE CONCAT('%', #{keyword}, '%')")
    List<User> searchByUsername(@Param("keyword") String keyword);

    // ---- 分页查询 ----
    @Select("SELECT * FROM users LIMIT #{offset}, #{size}")
    List<User> findPage(@Param("offset") int offset,
                         @Param("size") int size);

    // ---- 批量查询（IN 子句）----
    // 注意：IN 子句不能用简单的 @Select 注解，必须用动态 SQL（见XML部分）

    // ---- 返回 Map ----
    @Select("SELECT * FROM users WHERE id = #{id}")
    @MapKey("id")  // 结果按 id 字段作为 Map 的 key
    Map<Integer, User> findAsMap(@Param("id") Integer id);
}

// ============================================================
// 三、XML Mapper（复杂 SQL 的正确姿势）
// ============================================================
/*
 * 文件路径：src/main/resources/mapper/UserMapper.xml
 * Mapper 接口方法签名要和 XML 里的 id 对应
 *
 * <?xml version="1.0" encoding="UTF-8"?>
 * <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
 *   "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
 *
 * <mapper namespace="com.example.mapper.UserMapper">
 *
 *   <!-- ① resultMap：精确控制列名和字段名的映射 -->
 *   <resultMap id="UserMap" type="User">
 *     <id     property="id"        column="id"/>       <!-- 主键 -->
 *     <result property="username"  column="username"/>
 *     <result property="createdAt" column="created_at"/> <!-- 名字不同时手动映射 -->
 *   </resultMap>
 *
 *   <!-- ② 动态查询：<if>、<where>、<choose> -->
 *   <select id="search" resultMap="UserMap">
 *     SELECT * FROM users
 *     <where>
 *       <!-- <where> 标签自动去掉多余的 AND/OR，比手写 WHERE 1=1 更优雅 -->
 *       <if test="username != null and username != ''">
 *         AND username LIKE CONCAT('%', #{username}, '%')
 *       </if>
 *       <if test="minAge != null">
 *         AND age >= #{minAge}
 *       </if>
 *       <if test="maxAge != null">
 *         AND age &lt;= #{maxAge}   <!-- XML里 < 要写成 &lt; -->
 *       </if>
 *     </where>
 *     ORDER BY id DESC
 *   </select>
 *
 *   <!-- ③ <choose>：类似 switch，满足第一个 when 就停止 -->
 *   <select id="findByCondition" resultMap="UserMap">
 *     SELECT * FROM users WHERE
 *     <choose>
 *       <when test="id != null">id = #{id}</when>
 *       <when test="username != null">username = #{username}</when>
 *       <otherwise>1=1</otherwise>
 *     </choose>
 *   </select>
 *
 *   <!-- ④ IN 子句：<foreach> 遍历集合 -->
 *   <select id="findByIds" resultMap="UserMap">
 *     SELECT * FROM users WHERE id IN
 *     <foreach collection="ids" item="id" open="(" separator="," close=")">
 *       #{id}
 *     </foreach>
 *     <!-- 生成：WHERE id IN (1, 2, 3) -->
 *   </select>
 *
 *   <!-- ⑤ 批量插入：性能远优于循环单条插入 -->
 *   <insert id="batchInsert" useGeneratedKeys="true" keyProperty="id">
 *     INSERT INTO users(username, email, age) VALUES
 *     <foreach collection="users" item="u" separator=",">
 *       (#{u.username}, #{u.email}, #{u.age})
 *     </foreach>
 *   </insert>
 *
 *   <!-- ⑥ 动态更新：<set> 标签，只更新非 null 字段 -->
 *   <update id="updateSelective">
 *     UPDATE users
 *     <set>
 *       <!-- <set> 标签自动去掉最后多余的逗号 -->
 *       <if test="username != null">username = #{username},</if>
 *       <if test="email != null">email = #{email},</if>
 *       <if test="age != null">age = #{age},</if>
 *     </set>
 *     WHERE id = #{id}
 *   </update>
 *
 *   <!-- ⑦ SQL 片段复用：<sql> + <include> -->
 *   <sql id="baseColumns">id, username, email, age, created_at</sql>
 *
 *   <select id="findAllColumns" resultMap="UserMap">
 *     SELECT <include refid="baseColumns"/> FROM users
 *   </select>
 *
 *   <!-- ⑧ 关联查询：一对多 -->
 *   <resultMap id="OrderWithItems" type="Order">
 *     <id     property="id"     column="order_id"/>
 *     <result property="amount" column="amount"/>
 *     <!-- collection：一对多，一个订单有多个商品 -->
 *     <collection property="items" ofType="OrderItem">
 *       <id     property="id"          column="item_id"/>
 *       <result property="productName" column="product_name"/>
 *       <result property="qty"         column="qty"/>
 *     </collection>
 *   </resultMap>
 *
 *   <select id="findOrderWithItems" resultMap="OrderWithItems">
 *     SELECT o.id AS order_id, o.amount,
 *            i.id AS item_id, i.product_name, i.qty
 *     FROM orders o
 *     LEFT JOIN order_items i ON o.id = i.order_id
 *     WHERE o.id = #{orderId}
 *   </select>
 *
 * </mapper>
 */

// ============================================================
// 四、MyBatis 关键配置（application.yml）
// ============================================================
/*
 * mybatis:
 *   mapper-locations: classpath:mapper/*.xml     # XML 文件位置
 *   type-aliases-package: com.example.entity     # 实体类包，XML 里可以直接写类名
 *   configuration:
 *     map-underscore-to-camel-case: true          # 下划线转驼峰（created_at → createdAt）
 *     log-impl: org.apache.ibatis.logging.stdout.StdOutImpl  # 打印 SQL（开发时开启）
 *     cache-enabled: false                        # 关闭二级缓存（通常不用 MyBatis 缓存）
 *     default-fetch-size: 100                     # 游标查询时每次获取行数
 *     default-statement-timeout: 30               # SQL 执行超时（秒）
 */

// ============================================================
// 五、#{} 和 ${} 的本质区别（面试必考）
// ============================================================
/*
 * #{username}
 *   → PreparedStatement 的 ?
 *   → 实际 SQL：SELECT * FROM users WHERE username = ?
 *   → MySQL 把值当数据处理，不会解析 SQL 特殊字符
 *   → 防 SQL 注入 ✓，几乎所有情况用这个
 *
 * ${username}
 *   → 字符串直接拼接
 *   → 实际 SQL：SELECT * FROM users WHERE username = 'alice'
 *   → 字符串替换，有 SQL 注入风险
 *   → 只用在：动态表名、动态列名、ORDER BY 列名（这些不能加引号）
 *
 * 合法的 ${} 使用场景：
 *   @Select("SELECT * FROM ${tableName} WHERE id = #{id}")
 *   // tableName 不能用 #{} 因为会被加引号变成 'users'，语法错误
 *   // 但此时要在代码里校验 tableName 是合法值，防止注入
 *
 *   @Select("SELECT * FROM users ORDER BY ${sortColumn} ${sortOrder}")
 *   // sortColumn 和 sortOrder 只能是预定义的合法值
 */

class DB2_MyBatis_Placeholder {
    public static void main(String[] args) {
        System.out.println("本文件是 MyBatis 用法的注释文档");
        System.out.println("核心内容见文件中的注释和 XML 示例");
        System.out.println("需要在 Spring Boot 项目中配合 @Mapper 接口使用");

        System.out.println("\n=== #{} 和 ${} 区别 ===");
        System.out.println("#{} → PreparedStatement 参数化，防注入，绝大多数用这个");
        System.out.println("${} → 字符串拼接，有注入风险，只用于表名/列名");

        System.out.println("\n=== 动态 SQL 标签 ===");
        System.out.println("<if>        → 条件判断，满足才拼接");
        System.out.println("<where>     → 智能去掉多余的 AND/OR");
        System.out.println("<set>       → 智能去掉最后的逗号，用于 UPDATE");
        System.out.println("<foreach>   → 遍历集合，用于 IN 子句和批量插入");
        System.out.println("<choose>    → switch 语句");
        System.out.println("<sql>+<include> → SQL 片段复用");
    }
}
