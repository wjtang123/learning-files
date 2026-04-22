package selflearning.dbandmybatis;

import org.apache.ibatis.annotations.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * 完整实战：用户模块 CRUD
 *
 * 这个文件把 Controller → Service → Mapper → DB 的完整链路串起来，
 * 包含真实项目中会遇到的所有场景。
 *
 * 配套 SQL（在 MySQL 执行）：
 *
 * CREATE DATABASE IF NOT EXISTS demo DEFAULT CHARSET utf8mb4;
 * USE demo;
 *
 * CREATE TABLE users (
 *   id          INT AUTO_INCREMENT PRIMARY KEY,
 *   username    VARCHAR(50)  NOT NULL UNIQUE,
 *   email       VARCHAR(100) NOT NULL,
 *   age         INT,
 *   status      TINYINT DEFAULT 1 COMMENT '1正常 0禁用',
 *   created_at  DATETIME DEFAULT NOW(),
 *   updated_at  DATETIME DEFAULT NOW() ON UPDATE NOW()
 * );
 *
 * INSERT INTO users(username, email, age) VALUES
 *   ('alice', 'alice@example.com', 25),
 *   ('bob', 'bob@example.com', 30),
 *   ('charlie', 'charlie@example.com', 22),
 *   ('diana', 'diana@example.com', 28);
 */

// ============================================================
// Entity
// ============================================================
class UserEntity {
    private Integer id;
    private String  username;
    private String  email;
    private Integer age;
    private Integer status;
    private String  createdAt;

    public UserEntity() {}

    // Getters & Setters
    public Integer getId()           { return id; }
    public void    setId(Integer id) { this.id = id; }
    public String  getUsername()           { return username; }
    public void    setUsername(String u)   { this.username = u; }
    public String  getEmail()              { return email; }
    public void    setEmail(String e)      { this.email = e; }
    public Integer getAge()                { return age; }
    public void    setAge(Integer age)     { this.age = age; }
    public Integer getStatus()             { return status; }
    public void    setStatus(Integer s)    { this.status = s; }
    public String  getCreatedAt()          { return createdAt; }
    public void    setCreatedAt(String t)  { this.createdAt = t; }

    @Override
    public String toString() {
        return "User{id=" + id + ",username=" + username
                + ",age=" + age + ",status=" + status + "}";
    }
}

// 查询参数封装（避免方法参数过多）
class UserQuery {
    private String  keyword;   // 用户名模糊搜索
    private Integer minAge;
    private Integer maxAge;
    private Integer status;
    private Integer pageNum  = 1;
    private Integer pageSize = 10;

    // Getters & Setters
    public String  getKeyword()           { return keyword; }
    public void    setKeyword(String k)   { this.keyword = k; }
    public Integer getMinAge()            { return minAge; }
    public void    setMinAge(Integer a)   { this.minAge = a; }
    public Integer getMaxAge()            { return maxAge; }
    public void    setMaxAge(Integer a)   { this.maxAge = a; }
    public Integer getStatus()            { return status; }
    public void    setStatus(Integer s)   { this.status = s; }
    public Integer getPageNum()           { return pageNum; }
    public void    setPageNum(Integer n)  { this.pageNum = n; }
    public Integer getPageSize()          { return pageSize; }
    public void    setPageSize(Integer s) { this.pageSize = s; }

    public int getOffset() { return (pageNum - 1) * pageSize; }
}

// ============================================================
// Mapper（注解 + XML 混合）
// ============================================================
@Mapper
interface UserEntityMapper {

    // 简单操作用注解
    @Select("SELECT * FROM users WHERE id = #{id}")
    UserEntity findById(@Param("id") Integer id);

    @Select("SELECT * FROM users WHERE username = #{username}")
    UserEntity findByUsername(@Param("username") String username);

    @Select("SELECT COUNT(*) FROM users WHERE status = 1")
    int countActive();

    @Insert("INSERT INTO users(username, email, age) VALUES(#{username}, #{email}, #{age})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserEntity user);

    @Update("UPDATE users SET status = 0 WHERE id = #{id}")
    int disable(@Param("id") Integer id);

    @Delete("DELETE FROM users WHERE id = #{id}")
    int deleteById(@Param("id") Integer id);

    // 复杂查询：在 XML 中写（search 方法对应 UserMapper.xml 中的 id="search"）
    // List<UserEntity> search(UserQuery query);

    // 批量插入（XML 中写 <foreach>）
    // int batchInsert(@Param("users") List<UserEntity> users);

    // 下面用纯注解模拟复杂查询（实际项目推荐 XML）
    @Select("SELECT * FROM users WHERE " +
            "status = 1 AND " +
            "age BETWEEN #{minAge} AND #{maxAge} " +
            "ORDER BY id DESC " +
            "LIMIT #{offset}, #{pageSize}")
    List<UserEntity> findByAgeAndPage(@Param("minAge") int minAge,
                                       @Param("maxAge") int maxAge,
                                       @Param("offset") int offset,
                                       @Param("pageSize") int pageSize);

    @Select("SELECT COUNT(*) FROM users WHERE status = 1 " +
            "AND age BETWEEN #{minAge} AND #{maxAge}")
    int countByAge(@Param("minAge") int minAge,
                    @Param("maxAge") int maxAge);
}

// ============================================================
// Service
// ============================================================
@Service
class UserEntityService {

    @Autowired
    private UserEntityMapper mapper;

    public UserEntity getById(Integer id) {
        UserEntity user = mapper.findById(id);
        if (user == null) {
            throw new RuntimeException("用户不存在：" + id);
        }
        return user;
    }

    @Transactional
    public UserEntity create(String username, String email, Integer age) {
        // 业务校验：用户名唯一
        if (mapper.findByUsername(username) != null) {
            throw new IllegalArgumentException("用户名已存在：" + username);
        }

        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setEmail(email);
        user.setAge(age);

        int rows = mapper.insert(user);
        System.out.println("插入影响行数：" + rows + "，新用户ID：" + user.getId());
        return user;
    }

    public Map<String, Object> search(int minAge, int maxAge, int page, int size) {
        int offset = (page - 1) * size;
        List<UserEntity> list = mapper.findByAgeAndPage(minAge, maxAge, offset, size);
        int total = mapper.countByAge(minAge, maxAge);

        Map<String, Object> result = new HashMap<>();
        result.put("list",     list);
        result.put("total",    total);
        result.put("page",     page);
        result.put("size",     size);
        result.put("pages",    (total + size - 1) / size);
        return result;
    }

    @Transactional
    public void disableUser(Integer id) {
        int rows = mapper.disable(id);
        if (rows == 0) {
            throw new RuntimeException("用户不存在或已禁用：" + id);
        }
        System.out.println("用户 " + id + " 已禁用");
        // 这里可以再做其他操作，如：发通知、写审计日志等
        // 事务保证这些操作要么全成功，要么全回滚
    }
}

// ============================================================
// Controller
// ============================================================
@RestController
@RequestMapping("/users")
class UserEntityController {

    @Autowired
    private UserEntityService service;

    @GetMapping("/{id}")
    public Map<String, Object> getUser(@PathVariable Integer id) {
        UserEntity user = service.getById(id);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", user.toString());
        return result;
    }

    @PostMapping
    public Map<String, Object> createUser(
            @RequestParam String username,
            @RequestParam String email,
            @RequestParam(defaultValue = "0") Integer age) {
        UserEntity user = service.create(username, email, age);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", user.toString());
        return result;
    }

    @GetMapping("/search")
    public Map<String, Object> search(
            @RequestParam(defaultValue = "0")  int minAge,
            @RequestParam(defaultValue = "99") int maxAge,
            @RequestParam(defaultValue = "1")  int page,
            @RequestParam(defaultValue = "10") int size) {
        return service.search(minAge, maxAge, page, size);
    }
}

// ============================================================
// 调试技巧
// ============================================================
/*
 * 一、打印实际执行的 SQL（开发必开）
 *   application.yml：
 *     mybatis:
 *       configuration:
 *         log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
 *
 *   或者精确控制某个 Mapper 的日志级别：
 *     logging:
 *       level:
 *         com.example.mapper.UserMapper: DEBUG
 *
 * 二、查看 SQL 参数和结果
 *   开启 DEBUG 日志后，控制台会打印：
 *     ==>  Preparing: SELECT * FROM users WHERE id = ?
 *     ==> Parameters: 1(Integer)
 *     <==      Total: 1
 *
 * 三、慢 SQL 监控（使用 Druid）
 *   Druid 内置 SQL 统计，访问 /druid/sql.html 看慢查询
 *   配置：spring.datasource.druid.stat-view-servlet.enabled=true
 *
 * 四、SQL 性能分析
 *   在 MySQL 执行 EXPLAIN SELECT ... 看执行计划
 *   关注 type 列：const > eq_ref > ref > range > index > ALL
 *   ALL 是全表扫描，大表上要避免
 *
 * 五、测试 Mapper（不启动整个 Spring 容器）
 *   @MybatisTest  // 只加载 MyBatis 相关配置，不启动 Web
 *   class UserMapperTest {
 *     @Autowired UserMapper mapper;
 *
 *     @Test
 *     void testFindById() {
 *       User user = mapper.findById(1);
 *       assertNotNull(user);
 *     }
 *   }
 */

class DB4_Practice {
    public static void main(String[] args) {
        System.out.println("本文件是完整的 CRUD 实战示例");
        System.out.println("在 Spring Boot 项目中配置好数据库后可以直接运行");
        System.out.println();
        System.out.println("=== 完整链路 ===");
        System.out.println("POST /users?username=alice&email=a@b.com&age=25");
        System.out.println("  → UserController.createUser()");
        System.out.println("  → UserService.create()（事务保护）");
        System.out.println("  → UserMapper.findByUsername() 检查唯一性");
        System.out.println("  → UserMapper.insert() 插入数据");
        System.out.println("  → 返回新用户（含自增ID）");
        System.out.println();
        System.out.println("GET /users/search?minAge=20&maxAge=30&page=1&size=5");
        System.out.println("  → 分页查询，返回列表+总数+页码信息");
    }
}
