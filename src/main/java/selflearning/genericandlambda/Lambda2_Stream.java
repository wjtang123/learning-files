package selflearning.genericandlambda;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * Stream API + 泛型与 Lambda 的综合运用
 *
 * Stream 是 Lambda 最主要的使用场景。
 * Stream 不是数据结构，是对集合数据的"流水线操作"：
 *   数据源 → 中间操作（可多个，懒执行）→ 终止操作（触发执行，只能一个）
 *
 * 三类操作：
 *   创建 Stream：collection.stream()、Arrays.stream()、Stream.of()
 *   中间操作：filter、map、sorted、distinct、limit、skip（返回新 Stream）
 *   终止操作：collect、forEach、count、reduce、findFirst（触发执行）
 */
public class Lambda2_Stream {

    // 测试数据
    static class Employee {
        String name;
        String dept;
        double salary;
        int age;

        Employee(String name, String dept, double salary, int age) {
            this.name   = name;
            this.dept   = dept;
            this.salary = salary;
            this.age    = age;
        }

        @Override
        public String toString() {
            return name + "(" + dept + "," + salary + ")";
        }
    }

    static List<Employee> employees() {
        return Arrays.asList(
            new Employee("Alice",   "研发", 15000, 28),
            new Employee("Bob",     "研发", 12000, 32),
            new Employee("Charlie", "销售", 10000, 25),
            new Employee("Diana",   "销售", 13000, 30),
            new Employee("Eve",     "研发", 18000, 35),
            new Employee("Frank",   "HR",   9000,  27)
        );
    }

    // ============================================================
    // 基础操作
    // ============================================================
    static void basicOperations() {
        List<Employee> list = employees();

        System.out.println("--- filter：过滤 ---");
        // 找出研发部员工
        List<Employee> devs = list.stream()
                .filter(e -> "研发".equals(e.dept))
                .collect(Collectors.toList());
        System.out.println("研发部：" + devs);

        System.out.println("\n--- map：转换 ---");
        // 提取所有员工姓名
        List<String> names = list.stream()
                .map(e -> e.name)
                .collect(Collectors.toList());
        System.out.println("所有姓名：" + names);

        System.out.println("\n--- filter + map 组合 ---");
        // 找出薪资超过1.2万的员工姓名，按薪资降序
        List<String> highPay = list.stream()
                .filter(e -> e.salary > 12000)
                .sorted((a, b) -> Double.compare(b.salary, a.salary)) // 降序
                .map(e -> e.name + "(" + e.salary + ")")
                .collect(Collectors.toList());
        System.out.println("高薪员工：" + highPay);

        System.out.println("\n--- distinct / limit / skip ---");
        List<String> depts = list.stream()
                .map(e -> e.dept)
                .distinct()             // 去重
                .collect(Collectors.toList());
        System.out.println("所有部门：" + depts);

        List<Integer> nums = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        List<Integer> page = nums.stream()
                .skip(3)    // 跳过前3个
                .limit(4)   // 取4个
                .collect(Collectors.toList());
        System.out.println("第2页（每页4条）：" + page); // [4,5,6,7]
    }

    // ============================================================
    // 终止操作
    // ============================================================
    static void terminalOperations() {
        List<Employee> list = employees();

        System.out.println("\n--- count / min / max ---");
        long count = list.stream().filter(e -> e.salary > 10000).count();
        System.out.println("薪资>1万的人数：" + count);

        Optional<Employee> richest = list.stream()
                .max(Comparator.comparingDouble(e -> e.salary));
        // Optional 避免 null，用 isPresent() / get() 取值
        if (richest.isPresent()) {
            System.out.println("最高薪：" + richest.get());
        }

        System.out.println("\n--- reduce：归约（手动聚合）---");
        // 计算所有薪资总和
        double totalSalary = list.stream()
                .map(e -> e.salary)
                .reduce(0.0, (acc, cur) -> acc + cur);
        // 等价方法引用写法：.reduce(0.0, Double::sum)
        System.out.println("薪资总和：" + totalSalary);

        System.out.println("\n--- findFirst / anyMatch / allMatch / noneMatch ---");
        Optional<Employee> first = list.stream()
                .filter(e -> e.age < 30)
                .findFirst();
        System.out.println("第一个30岁以下：" + first.orElse(null));

        boolean anyDev = list.stream().anyMatch(e -> "研发".equals(e.dept));
        boolean allHighPay = list.stream().allMatch(e -> e.salary > 8000);
        System.out.println("有研发？" + anyDev + "，都超8k？" + allHighPay);
    }

    // ============================================================
    // Collectors 收集器（最实用的部分）
    // ============================================================
    static void collectorsDemo() {
        List<Employee> list = employees();

        System.out.println("\n--- groupingBy：按条件分组 ---");
        // 按部门分组
        Map<String, List<Employee>> byDept =
                list.stream().collect(Collectors.groupingBy(e -> e.dept));
        for (Map.Entry<String, List<Employee>> entry : byDept.entrySet()) {
            System.out.println("  " + entry.getKey() + ": " + entry.getValue());
        }

        System.out.println("\n--- groupingBy + 下游收集器 ---");
        // 各部门平均薪资
        Map<String, Double> avgSalaryByDept = list.stream()
                .collect(Collectors.groupingBy(
                        e -> e.dept,
                        Collectors.averagingDouble(e -> e.salary)));
        System.out.println("各部门平均薪资：" + avgSalaryByDept);

        // 各部门人数
        Map<String, Long> countByDept = list.stream()
                .collect(Collectors.groupingBy(
                        e -> e.dept,
                        Collectors.counting()));
        System.out.println("各部门人数：" + countByDept);

        System.out.println("\n--- partitioningBy：按条件分两组 ---");
        // 按是否高薪分组
        Map<Boolean, List<Employee>> partition = list.stream()
                .collect(Collectors.partitioningBy(e -> e.salary >= 13000));
        System.out.println("高薪（>=13k）：" + partition.get(true));
        System.out.println("普通：" + partition.get(false));

        System.out.println("\n--- joining：字符串拼接 ---");
        String nameList = list.stream()
                .map(e -> e.name)
                .collect(Collectors.joining(", ", "[", "]"));
        System.out.println("员工列表：" + nameList);

        System.out.println("\n--- toMap ---");
        Map<String, Double> nameSalary = list.stream()
                .collect(Collectors.toMap(
                        e -> e.name,    // key
                        e -> e.salary   // value
                ));
        System.out.println("name->salary：" + nameSalary);
    }

    // ============================================================
    // 泛型 + Lambda 组合：设计通用工具方法
    // ============================================================
    static void genericsWithLambda() {
        System.out.println("\n=== 泛型 + Lambda 组合：通用工具方法 ===");

        // 泛型方法接受 Predicate，实现通用过滤
        List<Employee> list = employees();

        List<Employee> result1 = filterList(list, e -> e.salary > 13000);
        List<Employee> result2 = filterList(list, e -> e.age < 30);
        System.out.println("薪资>13k：" + result1);
        System.out.println("年龄<30：" + result2);

        // 泛型方法接受 Function，实现通用转换
        List<String> names    = transform(list, e -> e.name);
        List<Double> salaries = transform(list, e -> e.salary);
        System.out.println("姓名列表：" + names);
        System.out.println("薪资列表：" + salaries);

        // 泛型方法接受 Comparator，实现通用排序
        List<Employee> byAge    = sortBy(list, (a, b) -> a.age - b.age);
        List<Employee> bySalary = sortBy(list, (a, b) -> Double.compare(b.salary, a.salary));
        System.out.println("按年龄升序：" + byAge);
        System.out.println("按薪资降序：" + bySalary);
    }

    // 通用过滤：<T> 泛型 + Predicate<T> Lambda
    static <T> List<T> filterList(List<T> list, Predicate<T> predicate) {
        List<T> result = new ArrayList<T>();
        for (T item : list) {
            if (predicate.test(item)) result.add(item);
        }
        return result;
    }

    // 通用转换：<T, R> 泛型 + Function<T,R> Lambda
    static <T, R> List<R> transform(List<T> list, Function<T, R> mapper) {
        List<R> result = new ArrayList<R>();
        for (T item : list) {
            result.add(mapper.apply(item));
        }
        return result;
    }

    // 通用排序（返回新列表，不修改原来的）
    static <T> List<T> sortBy(List<T> list, Comparator<T> comparator) {
        List<T> copy = new ArrayList<T>(list);
        Collections.sort(copy, comparator);
        return copy;
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) {
        System.out.println("=== Stream 基础操作 ===");
        basicOperations();

        System.out.println("\n=== 终止操作 ===");
        terminalOperations();

        System.out.println("\n=== Collectors 收集器 ===");
        collectorsDemo();

        genericsWithLambda();

        /*
         * 学习 Stream 的方法：
         *   1. 先记住三类操作的分类（创建/中间/终止），不要死记方法名
         *   2. filter 对应 if，map 对应转换，reduce 对应循环累积
         *   3. groupingBy 是最实用的，替代手写的 Map<K, List<V>> 分组逻辑
         *   4. Stream 是懒执行的：没有终止操作，中间操作什么都不做
         *
         * 泛型 + Lambda 的组合价值：
         *   filterList / transform / sortBy 这三个工具方法，
         *   接受任意类型的集合和任意 Lambda 逻辑，
         *   实现了"逻辑可插拔"的通用方法，
         *   这就是函数式编程的核心思想：把行为作为参数传递
         */
    }
}
