package selflearning.dbandmybatis;

import org.apache.ibatis.annotations.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/**
 * MyBatis 进阶：分页、多表查询、事务、常见坑
 */

// ============================================================
// 一、分页：PageHelper 插件（最常用的分页方案）
// ============================================================
/*
 * 手动分页的问题：
 *   ① 每个查询方法都要写 LIMIT offset, size（重复代码）
 *   ② 需要额外写 COUNT(*) 查总数（两次 SQL）
 *   ③ 不同数据库的分页语法不同（MySQL 用 LIMIT，Oracle 用 ROWNUM）
 *
 * PageHelper 的方案：
 *
 *   在执行查询 SQL 之前，PageHelper 拦截 SQL，
 *   自动添加 LIMIT 子句 和 COUNT 查询
 *   你只需写正常的查询 SQL，不用管分页细节
 *
 * 依赖（pom.xml）：
 *   <dependency>
 *     <groupId>com.github.pagehelper</groupId>
 *     <artifactId>pagehelper-spring-boot-starter</artifactId>
 *     <version>2.1.0</version>
 *   </dependency>
 *
 * 配置（application.yml）：
 *   pagehelper:
 *     helper-dialect: mysql        # 数据库类型
 *     reasonable: true             # pageNum<=0 时查第一页，>总页数时查最后一页
 *     support-methods-arguments: true
 */

// 导入说明（实际项目加这些依赖后解注释）
// import com.github.pagehelper.PageHelper;
// import com.github.pagehelper.PageInfo;

@Mapper
interface ArticleMapper {
    @Select("SELECT * FROM articles ORDER BY created_at DESC")
    List<Map<String, Object>> findAll();

    @Select("SELECT * FROM articles WHERE category = #{category} ORDER BY created_at DESC")
    List<Map<String, Object>> findByCategory(@Param("category") String category);
}

@Service
class ArticleService {
    @Autowired
    private ArticleMapper articleMapper;

    public Map<String, Object> getPage(int pageNum, int pageSize) {
        // PageHelper 用法：在查询前调用 startPage，之后的第一条查询自动分页
        // PageHelper.startPage(pageNum, pageSize);
        List<Map<String, Object>> list = articleMapper.findAll();
        // PageInfo 包含：list、total、pages、pageNum 等
        // PageInfo<Map<String, Object>> pageInfo = new PageInfo<>(list);

        Map<String, Object> result = new HashMap<>();
        result.put("list", list);
        // result.put("total", pageInfo.getTotal());
        // result.put("pages", pageInfo.getPages());
        result.put("pageNum", pageNum);
        result.put("pageSize", pageSize);
        return result;
    }

    /*
     * PageHelper 的注意事项：
     *   ① startPage 只对紧随其后的第一条 SQL 有效
     *      错误：PageHelper.startPage(); // 中间隔了其他代码
     *            doSomethingElse();
     *            mapper.findAll();   // 分页不生效！
     *      正确：PageHelper.startPage();
     *            mapper.findAll();   // 紧跟着调用
     *
     *   ② 不要在 Service 里包裹多次查询然后只对第一次分页
     *   ③ PageHelper 是基于 MyBatis 拦截器的，不支持 JDBC 原生 SQL
     */
}

// ============================================================
// 二、多表关联查询
// ============================================================

// 订单 + 订单项（一对多）
@Mapper
interface OrderMapper {

    // 方式一：联表查询 + resultMap（一次 SQL，推荐）
    // XML 中配置 resultMap（见 DB2_MyBatis.java 中的 XML 示例）
    // List<Order> findWithItems(@Param("orderId") Integer orderId);

    // 方式二：分步查询 + 懒加载（两次 SQL，大数据量时更高效）
    @Select("SELECT * FROM orders WHERE user_id = #{userId}")
    @Results({
        @Result(property = "id",     column = "id"),
        @Result(property = "amount", column = "amount"),
        // select：指定查询关联数据的方法，column：把 order_id 传给它
        // fetchType = FetchType.LAZY：懒加载，只有访问 items 时才执行
        @Result(property = "items",  column = "id",
                many = @Many(select = "com.example.mapper.OrderMapper.findItemsByOrderId",
                             fetchType = org.apache.ibatis.mapping.FetchType.LAZY))
    })
    List<Map<String, Object>> findOrdersByUserId(@Param("userId") Integer userId);

    @Select("SELECT * FROM order_items WHERE order_id = #{orderId}")
    List<Map<String, Object>> findItemsByOrderId(@Param("orderId") Integer orderId);
}

// ============================================================
// 三、Service 层事务：多个 Mapper 操作的原子性
// ============================================================
@Service
class OrderService {

    @Autowired private OrderMapper orderMapper;
    // @Autowired private UserMapper userMapper;  // 假设也注入了

    @Transactional  // 整个方法在一个事务里
    public void createOrderWithItems(Integer userId, double amount,
                                     List<Map<String, Object>> items) {
        // 1. 创建订单
        // orderMapper.insertOrder(userId, amount);

        // 2. 插入订单项（批量）
        // orderMapper.batchInsertItems(items);

        // 3. 更新用户积分
        // userMapper.addPoints(userId, (int)(amount / 10));

        // 任何一步抛 RuntimeException，整个事务回滚
        System.out.println("创建订单（事务保护）");
    }

    // 注意：@Transactional 的几个关键点（上一章详细讲过）
    // ① 只对 RuntimeException 回滚（默认）
    // ② 同类内 this.xxx() 调用事务失效（不走代理）
    // ③ 方法必须是 public
}

// ============================================================
// 四、常见坑和最佳实践
// ============================================================
class MyBatisBestPractices {

    /*
     * 坑一：N+1 查询问题（最常见的性能杀手）
     *
     * 问题代码：
     *   List<Order> orders = orderMapper.findAll();  // 1次 SQL
     *   for (Order order : orders) {
     *     order.setUser(userMapper.findById(order.getUserId())); // N次 SQL！
     *   }
     *   // 100 个订单 → 101 次 SQL
     *
     * 解决方案：
     *   ① 联表查询：一次 SQL 查出所有数据
     *      SELECT o.*, u.username FROM orders o JOIN users u ON o.user_id = u.id
     *
     *   ② 批量查询：先查订单，再按 user_id 集合批量查用户
     *      List<Integer> userIds = orders.stream().map(o -> o.getUserId()).collect(...);
     *      Map<Integer, User> userMap = userMapper.findByIds(userIds); // 1次 SQL
     *      orders.forEach(o -> o.setUser(userMap.get(o.getUserId())));
     *      // 总共 2 次 SQL，而不是 N+1 次
     *
     * -------------------------------------------------------
     *
     * 坑二：MyBatis 一级缓存的"幽灵数据"
     *
     * MyBatis 一级缓存（默认开启，SqlSession 级别）：
     *   同一个 SqlSession 内，相同 SQL 和参数的查询结果会被缓存
     *   第二次查询直接返回缓存，不走数据库
     *
     * 问题场景：
     *   User user1 = mapper.findById(1);    // 查数据库，缓存结果
     *   // 另一个线程更新了 user1 的数据
     *   User user2 = mapper.findById(1);    // 返回缓存的旧数据！
     *
     * 解决：
     *   ① 在 Spring 中，每次请求通常对应新的 SqlSession（事务结束缓存清空）
     *   ② 在同一事务内需要读最新数据时，调用 mapper.clearCache() 或用 @Options(flushCache=...)
     *   ③ 不要依赖 MyBatis 一级缓存做业务缓存，用 Redis 替代
     *
     * -------------------------------------------------------
     *
     * 坑三：大量数据查询 OOM
     *
     * 问题：SELECT * FROM logs（表有 100 万行）
     *   MyBatis 默认把所有结果加载到内存 → List 撑爆堆内存
     *
     * 解决一：分页查询（推荐）
     *   每次只查一批，处理完再查下一批
     *
     * 解决二：游标模式（ResultHandler）
     *   MyBatis 流式返回，每次处理一行，不积累内存
     *   @Options(resultSetType = ResultSetType.FORWARD_ONLY, fetchSize = 1000)
     *   @Select("SELECT * FROM large_table")
     *   void scanAll(ResultHandler<Row> handler);
     *
     * -------------------------------------------------------
     *
     * 坑四：Integer/Long 主键返回的坑
     *
     * @Insert(...) @Options(useGeneratedKeys=true, keyProperty="id")
     * int insert(User user);
     *
     * 调用后：
     *   int affected = mapper.insert(user);
     *   Integer id = user.getId(); // ← 自增ID在这里，不是 affected 的返回值！
     *   // affected 是影响行数（通常是1）
     *
     * -------------------------------------------------------
     *
     * 坑五：空集合 IN 子句
     *
     * SELECT * FROM users WHERE id IN ()  ← MySQL 语法错误！
     *
     * 用 <foreach> 前必须判断集合不为空：
     *   <if test="ids != null and ids.size() > 0">
     *     AND id IN
     *     <foreach collection="ids" item="id" open="(" separator="," close=")">
     *       #{id}
     *     </foreach>
     *   </if>
     *
     * -------------------------------------------------------
     *
     * 最佳实践总结：
     *   ① 简单 CRUD → @Select/@Insert/@Update/@Delete 注解
     *   ② 动态 SQL / 关联查询 → XML Mapper
     *   ③ 分页 → PageHelper，不要手写 LIMIT
     *   ④ 批量操作 → <foreach> 批量插入，比循环单条快 10 倍以上
     *   ⑤ 大数据量 → 分批处理或游标模式，不要全量加载
     *   ⑥ 开发时开启 SQL 日志：log-impl: StdOutImpl（生产关掉）
     *   ⑦ 永远用 #{} 不用 ${} 处理用户输入
     */

    public static void main(String[] args) {
        System.out.println("本文件是 MyBatis 进阶知识的文档");
        System.out.println("重点看注释中的坑和最佳实践");

        System.out.println("\n=== MyBatis 核心知识点 ===");
        System.out.println("1. 分页：PageHelper.startPage(page, size) 紧跟查询方法");
        System.out.println("2. 关联查询：联表 SQL + resultMap collection/association");
        System.out.println("3. 批量插入：<foreach> 一次 SQL 插多条，比循环快10倍");
        System.out.println("4. 动态 SQL：<if><where><set><foreach> 四个标签");
        System.out.println("5. N+1：用联表或批量查询代替循环查询");
    }
}
