package selflearning.spring;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring AOP（面向切面编程）和事务管理
 *
 * AOP 解决的问题：
 *   日志、权限校验、事务、缓存、性能监控... 这些"横切关注点"
 *   散落在每个业务方法里，代码重复且难以维护。
 *
 *   没有 AOP：
 *     void createOrder() {
 *       log.info("开始...");        // 重复代码
 *       checkPermission();          // 重复代码
 *       beginTransaction();         // 重复代码
 *       // 真正的业务逻辑（3行）
 *       commitTransaction();        // 重复代码
 *       log.info("结束...");        // 重复代码
 *     }
 *
 *   有了 AOP：
 *     @Transactional  // 一个注解，事务自动管理
 *     void createOrder() {
 *       // 只有业务逻辑
 *     }
 *
 * AOP 核心概念：
 *   切面（Aspect）：把"横切关注点"封装成一个类
 *   切点（Pointcut）：定义"在哪些方法上"生效（表达式）
 *   通知（Advice）：定义"在方法执行的哪个时机"做什么
 *   连接点（JoinPoint）：实际被拦截的方法调用
 */

// ============================================================
// 被增强的业务类
// ============================================================
@Service
class ProductService {

    public String findProduct(int id) {
        System.out.println("  [业务] 查询商品 " + id);
        return "Product_" + id;
    }

    public void createProduct(String name, double price) {
        if (price <= 0) throw new IllegalArgumentException("价格必须大于0");
        System.out.println("  [业务] 创建商品：" + name + ", ¥" + price);
    }

    @Transactional  // 加了这个注解，事务由 Spring 自动管理
    public void updatePrice(int id, double newPrice) {
        System.out.println("  [业务] 更新价格：商品" + id + " -> ¥" + newPrice);
        // Spring 会在方法开始前 beginTransaction()
        // 方法正常结束后 commit()
        // 抛出 RuntimeException 时 rollback()
    }
}

// ============================================================
// 切面类：统一实现日志、性能监控
// ============================================================
@Aspect  // 声明这是一个切面
@org.springframework.stereotype.Component
class LoggingAspect {

    // 切点表达式：匹配 selflearning.spring 包下所有类的所有方法
    // execution(返回类型 包名.类名.方法名(参数类型))
    // * 匹配任意，.. 匹配任意参数
    @Pointcut("execution(* selflearning.spring.*.*(..))")
    public void serviceLayer() {} // 切点的名字，供后续通知引用

    // Before：方法执行前
    @Before("serviceLayer()")
    public void logBefore(JoinPoint jp) {
        System.out.println("[切面-Before] 调用：" + jp.getSignature().getName()
                + "，参数：" + java.util.Arrays.toString(jp.getArgs()));
    }

    // AfterReturning：方法正常返回后（能拿到返回值）
    @AfterReturning(pointcut = "serviceLayer()", returning = "result")
    public void logAfterReturn(JoinPoint jp, Object result) {
        System.out.println("[切面-AfterReturn] " + jp.getSignature().getName()
                + " 返回：" + result);
    }

    // AfterThrowing：方法抛出异常后
    @AfterThrowing(pointcut = "serviceLayer()", throwing = "ex")
    public void logException(JoinPoint jp, Exception ex) {
        System.out.println("[切面-AfterThrow] " + jp.getSignature().getName()
                + " 异常：" + ex.getMessage());
    }

    // After：方法执行后（无论正常还是异常，类似 finally）
    @After("serviceLayer()")
    public void logAfter(JoinPoint jp) {
        System.out.println("[切面-After] " + jp.getSignature().getName() + " 结束");
    }

    // Around：最强大，完全包裹方法执行（可以控制是否执行原方法）
    @Around("serviceLayer()")
    public Object measureTime(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        String methodName = pjp.getSignature().getName();

        try {
            Object result = pjp.proceed();  // 调用原方法（不调用则方法不执行！）
            System.out.println("[切面-Around] " + methodName
                    + " 耗时：" + (System.currentTimeMillis() - start) + "ms");
            return result;
        } catch (Throwable e) {
            System.out.println("[切面-Around] " + methodName + " 异常，耗时："
                    + (System.currentTimeMillis() - start) + "ms");
            throw e; // 不重新抛出则异常被吞掉！
        }
    }
}

// ============================================================
// 权限切面：基于自定义注解
// ============================================================

// 自定义注解
@java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@interface RequirePermission {
    String value(); // 所需权限名
}

@Aspect
@org.springframework.stereotype.Component
class PermissionAspect {

    // 切点：有 @RequirePermission 注解的方法
    @Around("@annotation(requirePermission)")
    public Object checkPermission(ProceedingJoinPoint pjp,
                                  RequirePermission requirePermission) throws Throwable {
        String permission = requirePermission.value();
        // 检查当前用户是否有该权限（实际项目从 SecurityContext 获取）
        boolean hasPermission = checkUserPermission(permission);

        if (!hasPermission) {
            throw new SecurityException("无权限：" + permission);
        }
        return pjp.proceed();
    }

    private boolean checkUserPermission(String permission) {
        // 模拟权限检查
        return !"ADMIN_ONLY".equals(permission);
    }
}

// 使用自定义注解
@Service
class AdminService {

    @RequirePermission("USER_READ")
    public String getUsers() { return "用户列表"; }

    @RequirePermission("ADMIN_ONLY")
    public void deleteAll() { System.out.println("删除所有数据"); }
}

// ============================================================
// 事务管理详解
// ============================================================
@Service
class TransactionDemo {

    // 最常用：默认事务（RuntimeException 回滚，Checked Exception 不回滚）
    @Transactional
    public void defaultTx() {
        // 正常 RuntimeException 回滚
    }

    // 只读事务：提示数据库优化，用于查询
    @Transactional(readOnly = true)
    public String queryOnly() {
        return "查询结果";
    }

    // 指定回滚的异常类型
    @Transactional(rollbackFor = Exception.class) // 所有异常都回滚（包括 Checked）
    public void rollbackForAll() throws Exception {}

    @Transactional(noRollbackFor = IllegalArgumentException.class) // 这个异常不回滚
    public void noRollbackForBizError() {}

    // 超时设置（秒）
    @Transactional(timeout = 30)
    public void withTimeout() {}

    // 事务传播行为（最重要的是这两个）
    @Transactional(propagation = Propagation.REQUIRED)
    // 默认值：有事务就加入，没有就新建（最常用）
    public void required() {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    // 总是新建事务，挂起外层事务（用于"必须独立提交"的操作，如审计日志）
    public void requiresNew() {}

    // 隔离级别（解决并发读写问题）
    @Transactional(isolation = Isolation.READ_COMMITTED) // 读已提交，防脏读
    public void withIsolation() {}
}

/*
 * AOP 的实现原理（面试必考）：
 *
 * Spring AOP 是基于动态代理实现的：
 *   ① 目标类实现了接口 → 用 JDK 动态代理（Proxy.newProxyInstance）
 *   ② 目标类没有接口 → 用 CGLIB 代理（继承目标类，生成子类）
 *
 * 所以 @Transactional 的底层就是代理：
 *   Spring 为 OrderService 创建一个代理类
 *   调用 orderService.save() 实际调用的是代理类的方法
 *   代理类 → 开启事务 → 调用真实方法 → 提交/回滚
 *
 * @Transactional 常见失效场景（面试必考）：
 *   ① 方法不是 public → AOP 无法拦截
 *   ② 同类内部调用 → this.xxx() 不走代理，事务失效
 *      解决：注入自己，或用 AopContext.currentProxy()
 *   ③ 异常被 catch 吃掉 → Spring 感知不到异常，不会回滚
 *   ④ 非 Spring 管理的类 → 不是 Bean，没有代理
 *   ⑤ Checked Exception → 默认不回滚，需要 rollbackFor=Exception.class
 */
