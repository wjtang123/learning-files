package selflearning.pattern;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 结构型设计模式：代理、装饰器、适配器、门面
 *
 * 结构型模式解决的核心问题：
 *   如何把类或对象组合成更大的结构，
 *   同时保持结构灵活和高效
 */
public class Pattern2_Structural {

    // ============================================================
    // 一、代理模式（Proxy）
    // 为对象提供一个代理，控制对原对象的访问
    // 场景：日志、权限校验、缓存、事务（Spring AOP 的底层就是代理）
    //
    // 静态代理：代理类手写，灵活性差
    // 动态代理：运行时生成，JDK 动态代理 / CGLIB
    // ============================================================

    interface UserService {
        String findUser(int id);
        boolean saveUser(String name);
    }

    static class UserServiceImpl implements UserService {
        @Override
        public String findUser(int id) {
            System.out.println("    [DB] 查询用户 id=" + id);
            return "User_" + id;
        }

        @Override
        public boolean saveUser(String name) {
            System.out.println("    [DB] 保存用户 name=" + name);
            return true;
        }
    }

    // 静态代理：手写代理类，为每个方法加日志
    static class LoggingUserServiceProxy implements UserService {
        private final UserService target; // 被代理的真实对象

        public LoggingUserServiceProxy(UserService target) {
            this.target = target;
        }

        @Override
        public String findUser(int id) {
            long start = System.currentTimeMillis();
            System.out.println("  [日志] 开始调用 findUser(" + id + ")");
            String result = target.findUser(id); // 调用真实方法
            System.out.println("  [日志] findUser 耗时 "
                    + (System.currentTimeMillis() - start) + "ms，结果=" + result);
            return result;
        }

        @Override
        public boolean saveUser(String name) {
            System.out.println("  [日志] 开始调用 saveUser(" + name + ")");
            boolean result = target.saveUser(name);
            System.out.println("  [日志] saveUser 结果=" + result);
            return result;
        }
        // 静态代理缺点：每个方法都要手写，接口方法一多，代理类代码爆炸
    }

    // 动态代理：运行时生成，一个 Handler 处理所有方法
    static class LoggingHandler implements InvocationHandler {
        private final Object target;

        public LoggingHandler(Object target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
                throws Throwable {
            long start = System.currentTimeMillis();
            System.out.println("  [动态代理] 调用 " + method.getName());

            Object result = method.invoke(target, args); // 调用真实方法

            System.out.println("  [动态代理] " + method.getName()
                    + " 耗时 " + (System.currentTimeMillis() - start)
                    + "ms，结果=" + result);
            return result;
        }
    }

    // 创建动态代理的工具方法
    @SuppressWarnings("unchecked")
    static <T> T createProxy(T target, Class<T> interfaceClass) {
        return (T) Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class[]{interfaceClass},
                new LoggingHandler(target)
        );
    }

    // ============================================================
    // 二、装饰器模式（Decorator）
    // 动态给对象添加额外功能，是继承的灵活替代
    // 场景：Java IO（BufferedInputStream 就是装饰器）
    //       给咖啡加糖、加奶的经典例子
    // ============================================================

    interface Coffee {
        String getDescription();
        double getCost();
    }

    // 基础咖啡（被装饰的原始对象）
    static class Espresso implements Coffee {
        @Override public String getDescription() { return "浓缩咖啡"; }
        @Override public double getCost() { return 15.0; }
    }

    static class Americano implements Coffee {
        @Override public String getDescription() { return "美式咖啡"; }
        @Override public double getCost() { return 12.0; }
    }

    // 装饰器基类：实现同一接口，持有被装饰的对象
    static abstract class CoffeeDecorator implements Coffee {
        protected final Coffee coffee; // 被装饰的咖啡

        public CoffeeDecorator(Coffee coffee) {
            this.coffee = coffee;
        }
    }

    // 具体装饰器：加牛奶
    static class MilkDecorator extends CoffeeDecorator {
        public MilkDecorator(Coffee coffee) { super(coffee); }

        @Override
        public String getDescription() {
            return coffee.getDescription() + " + 牛奶";
        }

        @Override
        public double getCost() {
            return coffee.getCost() + 3.0;
        }
    }

    // 具体装饰器：加糖
    static class SugarDecorator extends CoffeeDecorator {
        public SugarDecorator(Coffee coffee) { super(coffee); }

        @Override
        public String getDescription() {
            return coffee.getDescription() + " + 糖";
        }

        @Override
        public double getCost() {
            return coffee.getCost() + 1.0;
        }
    }

    // 具体装饰器：加奶油
    static class WhipDecorator extends CoffeeDecorator {
        public WhipDecorator(Coffee coffee) { super(coffee); }

        @Override
        public String getDescription() {
            return coffee.getDescription() + " + 奶油";
        }

        @Override
        public double getCost() {
            return coffee.getCost() + 5.0;
        }
    }

    // ============================================================
    // 三、适配器模式（Adapter）
    // 把一个接口转换成另一个接口，让不兼容的类可以一起工作
    // 场景：整合第三方库、旧系统改造、统一接口
    // ============================================================

    // 目标接口：系统期望的接口
    interface Logger {
        void log(String message);
        void error(String message);
    }

    // 被适配者：第三方日志库，接口和我们的不同
    static class ThirdPartyLogger {
        public void writeInfo(String msg) {
            System.out.println("  [ThirdParty-INFO] " + msg);
        }
        public void writeError(String msg, Exception e) {
            System.out.println("  [ThirdParty-ERROR] " + msg
                    + (e != null ? ": " + e.getMessage() : ""));
        }
    }

    // 适配器：把 ThirdPartyLogger 包装成 Logger 接口
    static class LoggerAdapter implements Logger {
        private final ThirdPartyLogger thirdParty;

        public LoggerAdapter(ThirdPartyLogger thirdParty) {
            this.thirdParty = thirdParty;
        }

        @Override
        public void log(String message) {
            thirdParty.writeInfo(message); // 转发，转换方法签名
        }

        @Override
        public void error(String message) {
            thirdParty.writeError(message, null); // 适配参数
        }
    }

    // ============================================================
    // 四、门面模式（Facade）
    // 为复杂子系统提供一个简单接口，隐藏内部复杂度
    // 场景：Service 层封装多个 DAO 的操作，SDK 的统一入口
    // ============================================================

    // 多个复杂子系统
    static class InventoryService {
        public boolean checkStock(String productId, int qty) {
            System.out.println("    [库存] 检查 " + productId + " 库存 " + qty);
            return true;
        }
        public void deductStock(String productId, int qty) {
            System.out.println("    [库存] 扣减 " + productId + " x" + qty);
        }
    }

    static class PaymentService {
        public boolean charge(String userId, double amount) {
            System.out.println("    [支付] 用户 " + userId + " 扣款 ¥" + amount);
            return true;
        }
    }

    static class NotificationService {
        public void sendSms(String userId, String msg) {
            System.out.println("    [短信] 发给 " + userId + ": " + msg);
        }
    }

    static class LogisticsService {
        public String createShipment(String orderId, String address) {
            String trackNo = "SF" + System.currentTimeMillis() % 10000;
            System.out.println("    [物流] 创建快递单 " + trackNo);
            return trackNo;
        }
    }

    // 门面：封装下单的全部流程，调用方只需调用这一个方法
    static class OrderFacade {
        private final InventoryService  inventory    = new InventoryService();
        private final PaymentService    payment      = new PaymentService();
        private final NotificationService notify     = new NotificationService();
        private final LogisticsService  logistics    = new LogisticsService();

        public boolean placeOrder(String userId, String productId,
                                  int qty, double price, String address) {
            System.out.println("  [下单] 开始...");

            // 调用方完全不知道下单背后有多少个子系统
            if (!inventory.checkStock(productId, qty)) {
                System.out.println("  [下单] 失败：库存不足");
                return false;
            }
            if (!payment.charge(userId, price * qty)) {
                System.out.println("  [下单] 失败：支付失败");
                return false;
            }
            inventory.deductStock(productId, qty);
            String trackNo = logistics.createShipment("ORD" + userId, address);
            notify.sendSms(userId, "订单已发货，快递单号：" + trackNo);

            System.out.println("  [下单] 成功！");
            return true;
        }
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) {
        System.out.println("=== 静态代理 ===");
        UserService realService = new UserServiceImpl();
        UserService proxy = new LoggingUserServiceProxy(realService);
        proxy.findUser(42);
        proxy.saveUser("Alice");

        System.out.println("\n=== JDK 动态代理 ===");
        UserService dynamicProxy = createProxy(new UserServiceImpl(), UserService.class);
        dynamicProxy.findUser(99);
        // 动态代理：一个 Handler 覆盖所有方法，无需为每个方法手写代理逻辑

        System.out.println("\n=== 装饰器模式：咖啡 ===");
        Coffee c1 = new Espresso();
        System.out.println(c1.getDescription() + " ¥" + c1.getCost());

        // 层层包装，动态组合，每层只加自己负责的东西
        Coffee c2 = new MilkDecorator(new Espresso());
        System.out.println(c2.getDescription() + " ¥" + c2.getCost());

        Coffee c3 = new WhipDecorator(new SugarDecorator(new MilkDecorator(new Americano())));
        System.out.println(c3.getDescription() + " ¥" + c3.getCost());

        System.out.println("\n=== 适配器模式 ===");
        ThirdPartyLogger thirdParty = new ThirdPartyLogger();
        Logger logger = new LoggerAdapter(thirdParty); // 适配成统一接口
        logger.log("用户登录成功");       // 内部调用 writeInfo
        logger.error("数据库连接失败");   // 内部调用 writeError

        System.out.println("\n=== 门面模式：一键下单 ===");
        OrderFacade orderFacade = new OrderFacade();
        orderFacade.placeOrder("user001", "iPhone15", 1, 6999.0, "北京市朝阳区");

        /*
         * 代理 vs 装饰器：看起来很像，区别在于意图：
         *   代理：控制访问，代理和被代理实现同一接口，调用方感知不到代理的存在
         *         关注：谁能访问、访问前后做什么（日志、权限、缓存）
         *   装饰器：增强功能，层层叠加，调用方知道自己用的是增强版
         *           关注：功能组合，动态扩展（不改原类就能加新功能）
         *
         * 适配器 vs 门面：
         *   适配器：转换接口，让不兼容的东西能合作（已有代码的整合）
         *   门面：简化接口，隐藏复杂度（让调用更简单）
         */
    }
}
