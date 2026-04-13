package selflearning.pattern;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 行为型设计模式：观察者、策略、模板方法、责任链
 *
 * 行为型模式解决的核心问题：
 *   对象之间如何通信、如何分配职责，
 *   让系统在保持松耦合的同时完成复杂的交互
 */
public class Pattern3_Behavioral {

    // ============================================================
    // 一、观察者模式（Observer）
    // 一对多依赖：一个对象状态变化时，自动通知所有依赖它的对象
    // 场景：事件系统、消息推送、MVC 中的 Model 变化通知 View
    //       Spring 的 ApplicationEvent / EventListener 就是这个模式
    // ============================================================

    // 事件类（被观察的变化）
    static class OrderEvent {
        enum Type { CREATED, PAID, SHIPPED, CANCELLED }
        final Type type;
        final String orderId;
        final double amount;

        OrderEvent(Type type, String orderId, double amount) {
            this.type    = type;
            this.orderId = orderId;
            this.amount  = amount;
        }
    }

    // 观察者接口
    interface OrderObserver {
        void onOrderEvent(OrderEvent event);
    }

    // 具体观察者：各自只关注自己感兴趣的事件
    static class EmailNotifier implements OrderObserver {
        @Override
        public void onOrderEvent(OrderEvent event) {
            if (event.type == OrderEvent.Type.CREATED
                    || event.type == OrderEvent.Type.SHIPPED) {
                System.out.println("  [邮件] 订单 " + event.orderId
                        + " 状态变为 " + event.type);
            }
        }
    }

    static class InventoryUpdater implements OrderObserver {
        @Override
        public void onOrderEvent(OrderEvent event) {
            if (event.type == OrderEvent.Type.PAID) {
                System.out.println("  [库存] 订单 " + event.orderId
                        + " 已支付，扣减库存");
            }
            if (event.type == OrderEvent.Type.CANCELLED) {
                System.out.println("  [库存] 订单 " + event.orderId
                        + " 已取消，归还库存");
            }
        }
    }

    static class AccountingSystem implements OrderObserver {
        @Override
        public void onOrderEvent(OrderEvent event) {
            if (event.type == OrderEvent.Type.PAID) {
                System.out.println("  [财务] 记录收款 ¥" + event.amount);
            }
        }
    }

    // 被观察者（Subject/Publisher）
    static class OrderService {
        private final List<OrderObserver> observers = new ArrayList<OrderObserver>();

        // 注册观察者
        public void addObserver(OrderObserver observer) {
            observers.add(observer);
        }

        // 注销观察者
        public void removeObserver(OrderObserver observer) {
            observers.remove(observer);
        }

        // 状态变化时通知所有观察者
        private void notify(OrderEvent event) {
            for (OrderObserver obs : observers) {
                obs.onOrderEvent(event); // 每个观察者自己决定要不要响应
            }
        }

        // 业务方法：状态变化后发通知
        public void createOrder(String orderId, double amount) {
            System.out.println("创建订单 " + orderId);
            notify(new OrderEvent(OrderEvent.Type.CREATED, orderId, amount));
        }

        public void payOrder(String orderId, double amount) {
            System.out.println("订单 " + orderId + " 支付成功");
            notify(new OrderEvent(OrderEvent.Type.PAID, orderId, amount));
        }

        public void cancelOrder(String orderId) {
            System.out.println("取消订单 " + orderId);
            notify(new OrderEvent(OrderEvent.Type.CANCELLED, orderId, 0));
        }
    }

    // ============================================================
    // 二、策略模式（Strategy）
    // 定义一族算法，封装每一个，让它们可以互相替换
    // 场景：多种支付方式、多种排序算法、多种打折策略
    //       消灭大量 if-else 的利器
    // ============================================================

    // 策略接口：定义算法的骨架
    interface DiscountStrategy {
        double calculate(double originalPrice);
        String getName();
    }

    // 具体策略：各种打折方式
    static class NoDiscount implements DiscountStrategy {
        @Override public double calculate(double price) { return price; }
        @Override public String getName() { return "无折扣"; }
    }

    static class PercentageDiscount implements DiscountStrategy {
        private final double percent; // 如 0.8 表示八折

        public PercentageDiscount(double percent) { this.percent = percent; }

        @Override public double calculate(double price) { return price * percent; }
        @Override public String getName() { return (int)(percent*10) + "折"; }
    }

    static class FullReductionDiscount implements DiscountStrategy {
        private final double threshold; // 满多少
        private final double reduction; // 减多少

        public FullReductionDiscount(double threshold, double reduction) {
            this.threshold = threshold;
            this.reduction = reduction;
        }

        @Override
        public double calculate(double price) {
            return price >= threshold ? price - reduction : price;
        }

        @Override
        public String getName() { return "满" + (int)threshold + "减" + (int)reduction; }
    }

    static class VipDiscount implements DiscountStrategy {
        private final int level; // VIP等级

        public VipDiscount(int level) { this.level = level; }

        @Override
        public double calculate(double price) {
            double discount = 1.0 - level * 0.05; // 每级折扣多5%
            return price * Math.max(discount, 0.5); // 最低五折
        }

        @Override public String getName() { return "VIP" + level + "级折扣"; }
    }

    // 上下文：持有策略，通过策略完成计算
    static class ShoppingCart {
        private DiscountStrategy strategy = new NoDiscount(); // 默认无折扣

        public void setStrategy(DiscountStrategy strategy) {
            this.strategy = strategy;
        }

        public void checkout(double totalPrice) {
            double finalPrice = strategy.calculate(totalPrice);
            System.out.printf("  原价：¥%.1f，使用[%s]，实付：¥%.1f%n",
                    totalPrice, strategy.getName(), finalPrice);
        }
    }


    // 策略注册表：用 Map 替代 if-else 选择策略
    static class DiscountStrategyRegistry {
        private static final Map<String, DiscountStrategy> registry =
                new HashMap<String, DiscountStrategy>();

        static {
            registry.put("none",       new NoDiscount());
            registry.put("eight",      new PercentageDiscount(0.8));
            registry.put("full200-30", new FullReductionDiscount(200, 30));
            registry.put("vip3",       new VipDiscount(3));
        }

        public static DiscountStrategy get(String type) {
            DiscountStrategy s = registry.get(type);
            return s != null ? s : new NoDiscount();
        }
    }

    // ============================================================
    // 三、模板方法模式（Template Method）
    // 在抽象类中定义算法骨架，某些步骤延迟到子类实现
    // 场景：数据导入流程、报表生成、测试框架（setUp/tearDown）
    // （在抽象类那章已经接触过，这里结合业务场景再深化）
    // ============================================================

    static abstract class DataExporter {

        // 模板方法：导出流程固定，final 防止子类破坏流程
        public final void export(String destination) {
            System.out.println("  [导出] 开始...");
            List<String> data = fetchData();        // 1. 取数据（子类实现）
            List<String> processed = process(data); // 2. 处理数据（子类可覆盖）
            writeToFile(processed, destination);    // 3. 写文件（子类实现）
            sendNotification(destination);          // 4. 通知（有默认实现）
            System.out.println("  [导出] 完成 → " + destination);
        }

        // 抽象方法：必须由子类实现
        protected abstract List<String> fetchData();
        protected abstract void writeToFile(List<String> data, String dest);

        // 钩子方法：有默认实现，子类可选择覆盖
        protected List<String> process(List<String> data) {
            return data; // 默认不处理，直接返回
        }

        protected void sendNotification(String destination) {
            System.out.println("  [通知] 文件已生成：" + destination);
        }
    }

    static class CsvExporter extends DataExporter {
        @Override
        protected List<String> fetchData() {
            System.out.println("  [CSV] 从数据库查询数据");
            List<String> data = new ArrayList<String>();
            data.add("id,name,age");
            data.add("1,Alice,28");
            data.add("2,Bob,32");
            return data;
        }

        @Override
        protected void writeToFile(List<String> data, String dest) {
            System.out.println("  [CSV] 写入 " + data.size() + " 行到 " + dest);
        }
    }

    static class ExcelExporter extends DataExporter {
        @Override
        protected List<String> fetchData() {
            System.out.println("  [Excel] 从 API 获取数据");
            List<String> data = new ArrayList<String>();
            data.add("Alice|28");
            data.add("Bob|32");
            return data;
        }

        @Override
        protected List<String> process(List<String> data) {
            // 覆盖钩子方法：把 | 分隔符改成制表符
            List<String> result = new ArrayList<String>();
            for (String row : data) {
                result.add(row.replace("|", "\t"));
            }
            System.out.println("  [Excel] 数据格式化完成");
            return result;
        }

        @Override
        protected void writeToFile(List<String> data, String dest) {
            System.out.println("  [Excel] 写入 xlsx 到 " + dest);
        }

        @Override
        protected void sendNotification(String destination) {
            // 覆盖默认通知方式，改成邮件
            System.out.println("  [邮件] Excel 报告已发送：" + destination);
        }
    }

    // ============================================================
    // 四、责任链模式（Chain of Responsibility）
    // 把请求的处理者链接成一条链，请求沿链传递，直到被处理
    // 场景：权限校验、过滤器链（Servlet Filter）、审批流程
    // ============================================================

    // 请求对象
    static class Request {
        final String type;    // 审批类型
        final double amount;  // 金额
        final String user;    // 申请人

        Request(String type, double amount, String user) {
            this.type   = type;
            this.amount = amount;
            this.user   = user;
        }
    }

    // 处理者抽象类
    static abstract class Approver {
        protected Approver next; // 链中的下一个处理者

        public Approver setNext(Approver next) {
            this.next = next;
            return next; // 返回 next，支持链式设置
        }

        // 模板方法：处理请求，处理不了就传给下一个
        public final void handle(Request request) {
            if (canHandle(request)) {
                doHandle(request);
            } else if (next != null) {
                System.out.println("  " + getClass().getSimpleName()
                        + " 权限不足，转给上级");
                next.handle(request);
            } else {
                System.out.println("  没有人能处理这个请求：" + request.amount);
            }
        }

        protected abstract boolean canHandle(Request request);
        protected abstract void doHandle(Request request);
    }

    // 具体处理者：组长（可批 1000 以内）
    static class TeamLead extends Approver {
        @Override
        protected boolean canHandle(Request request) {
            return request.amount <= 1000;
        }

        @Override
        protected void doHandle(Request request) {
            System.out.println("  [组长] 批准 " + request.user
                    + " 的 " + request.type + " 申请：¥" + request.amount);
        }
    }

    // 经理（可批 5000 以内）
    static class Manager extends Approver {
        @Override
        protected boolean canHandle(Request request) {
            return request.amount <= 5000;
        }

        @Override
        protected void doHandle(Request request) {
            System.out.println("  [经理] 批准 " + request.user
                    + " 的 " + request.type + " 申请：¥" + request.amount);
        }
    }

    // 总监（可批 20000 以内）
    static class Director extends Approver {
        @Override
        protected boolean canHandle(Request request) {
            return request.amount <= 20000;
        }

        @Override
        protected void doHandle(Request request) {
            System.out.println("  [总监] 批准 " + request.user
                    + " 的 " + request.type + " 申请：¥" + request.amount);
        }
    }

    // CEO（全部可批）
    static class CEO extends Approver {
        @Override
        protected boolean canHandle(Request request) { return true; }

        @Override
        protected void doHandle(Request request) {
            System.out.println("  [CEO] 批准 " + request.user
                    + " 的 " + request.type + " 申请：¥" + request.amount);
        }
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) {
        System.out.println("=== 观察者模式 ===");
        OrderService orderService = new OrderService();
        orderService.addObserver(new EmailNotifier());
        orderService.addObserver(new InventoryUpdater());
        orderService.addObserver(new AccountingSystem());

        orderService.createOrder("ORD001", 299);
        System.out.println();
        orderService.payOrder("ORD001", 299);
        System.out.println();
        orderService.cancelOrder("ORD002");

        System.out.println("\n=== 策略模式 ===");
        ShoppingCart cart = new ShoppingCart();
        double price = 260;

        cart.setStrategy(new NoDiscount());
        cart.checkout(price);

        cart.setStrategy(new PercentageDiscount(0.8));
        cart.checkout(price);

        cart.setStrategy(new FullReductionDiscount(200, 30));
        cart.checkout(price);

        cart.setStrategy(new VipDiscount(3));
        cart.checkout(price);

        // 用注册表替代 if-else
        System.out.println("  --- 策略注册表 ---");
        String[] types = {"eight", "full200-30", "vip3", "unknown"};
        for (String type : types) {
            cart.setStrategy(DiscountStrategyRegistry.get(type));
            cart.checkout(price);
        }

        System.out.println("\n=== 模板方法模式 ===");
        DataExporter csv   = new CsvExporter();
        DataExporter excel = new ExcelExporter();
        csv.export("report.csv");
        System.out.println();
        excel.export("report.xlsx");

        System.out.println("\n=== 责任链模式 ===");
        // 构建审批链：组长 → 经理 → 总监 → CEO
        TeamLead lead = new TeamLead();
        lead.setNext(new Manager()).setNext(new Director()).setNext(new CEO());

        // 不同金额自动找到对应级别的审批人
        lead.handle(new Request("差旅费",    500,   "Alice"));
        lead.handle(new Request("设备采购",  3000,  "Bob"));
        lead.handle(new Request("市场推广",  12000, "Charlie"));
        lead.handle(new Request("并购投资",  500000, "CEO-自己批"));

        /*
         * 四个模式的选用场景：
         *
         * 观察者：一个事件需要通知多个地方，且通知方不想知道被通知方是谁
         *         → 解耦事件发布者和订阅者
         *
         * 策略：同一件事有多种做法，需要在运行时切换
         *       → 消灭 if-else 的最佳武器
         *       → 注册表（Map）+ 策略接口 是实战中最优雅的组合
         *
         * 模板方法：多个类有相同的流程骨架，但某些步骤不同
         *           → 父类管流程，子类管差异，避免重复代码
         *
         * 责任链：请求需要经过多个处理环节，且处理环节可动态调整
         *         → Servlet Filter、Spring Security 拦截器链都是这个思路
         */
    }
}
