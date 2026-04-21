package selflearning.spring;

import org.apache.ibatis.annotations.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/**
 * 数据访问层：MyBatis 入门 + 完整项目结构
 *
 * Spring Boot 项目的标准分层架构：
 *
 *   Controller → Service → Repository/Mapper → Database
 *
 *   Controller（表现层）：接收请求，参数校验，返回响应
 *   Service（业务层）：  业务逻辑，事务管理
 *   Mapper（数据层）：   SQL 操作，只和数据库打交道
 *
 * 为什么要分层？
 *   单一职责：每层只做自己的事
 *   可测试：Service 可以 Mock Mapper 单独测试
 *   可替换：换数据库只改 Mapper 层
 */

// ============================================================
// 一、实体类（Entity）
// ============================================================
class User {
    private Integer id;
    private String  username;
    private String  email;
    private Integer age;
    private java.time.LocalDateTime createdAt;

    // 无参构造器（MyBatis 反射创建对象需要）
    public User() {}

    public User(String username, String email, Integer age) {
        this.username = username;
        this.email    = email;
        this.age      = age;
    }

    // Getters and Setters
    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }

    @Override
    public String toString() {
        return "User{id=" + id + ", username=" + username + ", age=" + age + "}";
    }
}

// ============================================================
// 二、Mapper 接口（MyBatis 注解方式）
// ============================================================
@Mapper  // 声明这是 MyBatis Mapper，Spring 自动创建实现类（不需要 @Repository）
interface UserMapper {

    // 简单 SQL 用注解写，复杂 SQL 用 XML
    @Select("SELECT * FROM users WHERE id = #{id}")
    User findById(@Param("id") Integer id);

    @Select("SELECT * FROM users WHERE username = #{username}")
    User findByUsername(@Param("username") String username);

    @Select("SELECT * FROM users WHERE age >= #{minAge} AND age <= #{maxAge}")
    List<User> findByAgeRange(@Param("minAge") int minAge,
                               @Param("maxAge") int maxAge);

    @Select("SELECT * FROM users LIMIT #{offset}, #{limit}")
    List<User> findPage(@Param("offset") int offset,
                         @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM users")
    int count();

    // 插入：useGeneratedKeys 让 MyBatis 把自增 ID 回填到 user.id
    @Insert("INSERT INTO users(username, email, age, created_at) " +
            "VALUES(#{username}, #{email}, #{age}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    @Update("UPDATE users SET username=#{username}, email=#{email}, " +
            "age=#{age} WHERE id=#{id}")
    int update(User user);

    @Delete("DELETE FROM users WHERE id = #{id}")
    int deleteById(@Param("id") Integer id);

    // 动态 SQL 用 XML Mapper（见下方注释中的 XML 示例）
}

/*
 * XML Mapper 方式（适合复杂动态 SQL）：
 * 文件位置：src/main/resources/mapper/UserMapper.xml
 *
 * <?xml version="1.0" encoding="UTF-8"?>
 * <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
 *   "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
 *
 * <mapper namespace="com.example.mapper.UserMapper">
 *
 *   <!-- 动态查询：多条件组合 -->
 *   <select id="search" resultType="User">
 *     SELECT * FROM users
 *     <where>
 *       <if test="username != null and username != ''">
 *         AND username LIKE CONCAT('%', #{username}, '%')
 *       </if>
 *       <if test="minAge != null">
 *         AND age >= #{minAge}
 *       </if>
 *       <if test="maxAge != null">
 *         AND age &lt;= #{maxAge}
 *       </if>
 *     </where>
 *     ORDER BY created_at DESC
 *   </select>
 *
 *   <!-- 批量插入 -->
 *   <insert id="batchInsert">
 *     INSERT INTO users(username, email, age) VALUES
 *     <foreach collection="users" item="u" separator=",">
 *       (#{u.username}, #{u.email}, #{u.age})
 *     </foreach>
 *   </insert>
 *
 *   <!-- 关联查询（一对多） -->
 *   <resultMap id="OrderWithItems" type="Order">
 *     <id property="id" column="order_id"/>
 *     <result property="amount" column="amount"/>
 *     <collection property="items" ofType="OrderItem">
 *       <id property="id" column="item_id"/>
 *       <result property="productName" column="product_name"/>
 *     </collection>
 *   </resultMap>
 *
 * </mapper>
 */

// ============================================================
// 三、Service 层（业务逻辑）
// ============================================================
@Service
class UserServiceImpl {

    private final UserMapper userMapper;

    @Autowired
    public UserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public User getById(int id) {
        User user = userMapper.findById(id);
        if (user == null) {
            throw new RuntimeException("用户不存在：" + id);
        }
        return user;
    }

    @Transactional
    public User create(String username, String email, int age) {
        // 业务校验
        if (userMapper.findByUsername(username) != null) {
            throw new IllegalArgumentException("用户名已存在：" + username);
        }

        User user = new User(username, email, age);
        userMapper.insert(user);
        // insert 后，user.id 被 MyBatis 自动回填
        System.out.println("创建用户成功，ID：" + user.getId());
        return user;
    }

    @Transactional
    public void batchCreate(List<User> users) {
        // 批量创建，整个方法在一个事务里，任何一个失败全部回滚
        for (User u : users) {
            userMapper.insert(u);
        }
    }

    public List<User> search(int minAge, int maxAge) {
        return userMapper.findByAgeRange(minAge, maxAge);
    }

    public Map<String, Object> listPage(int page, int size) {
        int offset = page * size;
        List<User> users = userMapper.findPage(offset, size);
        int total = userMapper.count();

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("list",  users);
        result.put("total", total);
        result.put("page",  page);
        result.put("size",  size);
        return result;
    }
}

// ============================================================
// 四、完整项目结构说明
// ============================================================
/*
 * 标准 Spring Boot 项目目录结构：
 *
 * src/
 * ├── main/
 * │   ├── java/com/example/
 * │   │   ├── Application.java          启动类
 * │   │   ├── controller/               Controller 层
 * │   │   │   ├── UserController.java
 * │   │   │   └── GlobalExceptionHandler.java
 * │   │   ├── service/                  Service 层（接口 + 实现）
 * │   │   │   ├── UserService.java      接口
 * │   │   │   └── UserServiceImpl.java  实现
 * │   │   ├── mapper/                   MyBatis Mapper
 * │   │   │   └── UserMapper.java
 * │   │   ├── entity/                   数据库实体
 * │   │   │   └── User.java
 * │   │   ├── dto/                      请求参数 DTO
 * │   │   │   └── CreateUserRequest.java
 * │   │   ├── vo/                       返回数据 VO
 * │   │   │   └── UserVO.java
 * │   │   ├── config/                   配置类
 * │   │   │   ├── MybatisConfig.java
 * │   │   │   └── SwaggerConfig.java
 * │   │   └── common/                   公共类
 * │   │       ├── ApiResponse.java      统一响应格式
 * │   │       └── exception/            自定义异常
 * │   └── resources/
 * │       ├── application.yml           主配置文件
 * │       ├── application-dev.yml       开发环境配置
 * │       ├── application-prod.yml      生产环境配置
 * │       └── mapper/                   MyBatis XML
 * │           └── UserMapper.xml
 * └── test/
 *     └── java/com/example/
 *         ├── service/
 *         │   └── UserServiceTest.java  单元测试
 *         └── controller/
 *             └── UserControllerTest.java  集成测试
 *
 *
 * pom.xml 核心依赖：
 *
 * <dependencies>
 *   <!-- Web（包含 Spring MVC + Tomcat） -->
 *   <dependency>
 *     <groupId>org.springframework.boot</groupId>
 *     <artifactId>spring-boot-starter-web</artifactId>
 *   </dependency>
 *
 *   <!-- MyBatis -->
 *   <dependency>
 *     <groupId>org.mybatis.spring.boot</groupId>
 *     <artifactId>mybatis-spring-boot-starter</artifactId>
 *     <version>3.0.3</version>
 *   </dependency>
 *
 *   <!-- MySQL 驱动 -->
 *   <dependency>
 *     <groupId>com.mysql</groupId>
 *     <artifactId>mysql-connector-j</artifactId>
 *   </dependency>
 *
 *   <!-- 参数校验 -->
 *   <dependency>
 *     <groupId>org.springframework.boot</groupId>
 *     <artifactId>spring-boot-starter-validation</artifactId>
 *   </dependency>
 *
 *   <!-- Lombok（简化 getter/setter/builder） -->
 *   <dependency>
 *     <groupId>org.projectlombok</groupId>
 *     <artifactId>lombok</artifactId>
 *     <optional>true</optional>
 *   </dependency>
 *
 *   <!-- 测试 -->
 *   <dependency>
 *     <groupId>org.springframework.boot</groupId>
 *     <artifactId>spring-boot-starter-test</artifactId>
 *     <scope>test</scope>
 *   </dependency>
 * </dependencies>
 */

/*
 * 面试高频问题汇总：
 *
 * Q：Spring Boot 和 Spring 的区别？
 * A：Spring 是框架，需要大量手动配置。
 *    Spring Boot 是 Spring 的"快速启动器"：
 *    ① 自动配置：根据 classpath 自动配好常用组件
 *    ② 起步依赖：一个 starter 引入一组配套依赖，解决版本冲突
 *    ③ 内嵌服务器：不需要部署到外部 Tomcat，直接 java -jar 启动
 *    ④ Actuator：健康检查、指标监控、环境信息等运维接口
 *
 * Q：Spring Boot 自动配置的原理？
 * A：@EnableAutoConfiguration → 读取 AutoConfiguration.imports
 *    → 加载配置类 → @ConditionalOnClass/@ConditionalOnMissingBean 等条件注解判断是否生效
 *    → 满足条件则自动注册相关 Bean（如 DataSource、JdbcTemplate）
 *    用户的 Bean 优先级高于自动配置（@ConditionalOnMissingBean 保证）
 *
 * Q：@RestController 和 @Controller 的区别？
 * A：@Controller 返回视图名（模板渲染）。
 *    @RestController = @Controller + @ResponseBody，
 *    所有方法返回值直接序列化为 JSON 写入响应体，适合写接口。
 *
 * Q：@PathVariable 和 @RequestParam 的区别？
 * A：@PathVariable：从 URL 路径中取值，/users/{id} 中的 {id}
 *    @RequestParam：从查询字符串或表单中取值，/users?page=1 中的 page
 *
 * Q：MyBatis #{} 和 ${} 的区别？
 * A：#{} 是预编译占位符，底层用 PreparedStatement，防止 SQL 注入，参数会加引号
 *    ${} 是字符串拼接，直接替换进 SQL，有 SQL 注入风险，
 *    只用在表名/列名动态变化时（参数不能加引号的场景）
 */
