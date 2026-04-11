package selflearning.ioandfile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/**
 * 文件系统操作：File 类 vs NIO2 的 Path/Files
 *
 * File 类（JDK1.0）：老 API，功能有限，有些操作失败不抛异常只返回 false
 * Path + Files（JDK7 NIO2）：新 API，功能更强，失败抛异常，强烈推荐
 *
 * 实际项目建议：
 *   新代码优先用 Path/Files，老代码迁移时 File 可通过 file.toPath() 转换
 */
public class IO2_FileSystem {

    // ============================================================
    // 示例一：File 类基本操作（了解，知道老写法）
    // ============================================================
    static void fileClassDemo() {
        File dir  = new File("test_dir");
        File file = new File(dir, "hello.txt");

        // 创建目录
        if (!dir.exists()) {
            dir.mkdirs(); // mkdirs()：创建多级目录；mkdir()：只创建一级
        }

        // 创建文件
        try {
            boolean created = file.createNewFile(); // 文件已存在则返回 false
            System.out.println("文件创建：" + created);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // 获取文件信息
        System.out.println("绝对路径：" + file.getAbsolutePath());
        System.out.println("文件名：" + file.getName());
        System.out.println("父目录：" + file.getParent());
        System.out.println("是文件：" + file.isFile());
        System.out.println("是目录：" + file.isDirectory());
        System.out.println("文件大小：" + file.length() + " 字节");
        System.out.println("可读：" + file.canRead());

        // 列出目录内容
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                System.out.println("  子项：" + child.getName());
            }
        }

        // 删除（只能删空目录，非空目录需要递归删）
        file.delete();
        dir.delete();
    }

    // ============================================================
    // 示例二：Path/Files —— 推荐的现代写法
    // ============================================================
    static void pathFilesDemo() throws IOException {
        // Path：路径对象，不代表文件一定存在
        Path dir  = Paths.get("nio_test_dir");
        Path file = dir.resolve("hello.txt"); // resolve：拼接子路径

        // 创建目录（createDirectories 自动创建多级，目录已存在不报错）
        Files.createDirectories(dir);

        // 写文件（StandardCharsets：JDK7 提供的编码常量，避免拼写错误）
        String content = "第一行\n第二行\n第三行";
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        System.out.println("文件已写入：" + file.toAbsolutePath());

        // 读文件：一次性读所有行（小文件用这个，大文件用流式读）
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        System.out.println("读取到 " + lines.size() + " 行：");
        for (String line : lines) {
            System.out.println("  " + line);
        }

        // 读文件：一次性读全部内容为字符串（JDK11，JDK8 用上面的 readAllLines）
        // String all = Files.readString(file);

        // 文件属性
        BasicFileAttributes attrs = Files.readAttributes(
                file, BasicFileAttributes.class);
        System.out.println("文件大小：" + attrs.size() + " 字节");
        System.out.println("创建时间：" + attrs.creationTime());
        System.out.println("是否是目录：" + attrs.isDirectory());

        // 复制文件（REPLACE_EXISTING：目标已存在则替换）
        Path copy = dir.resolve("hello_copy.txt");
        Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("已复制到：" + copy.getFileName());

        // 移动/重命名
        Path moved = dir.resolve("hello_moved.txt");
        Files.move(copy, moved, StandardCopyOption.REPLACE_EXISTING);

        // 判断文件存在
        System.out.println("原文件存在：" + Files.exists(file));
        System.out.println("副本存在：" + Files.exists(copy));
        System.out.println("移动后存在：" + Files.exists(moved));

        // 追加写入
        Files.write(moved,
                "\n追加的内容".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);

        // 删除（文件不存在会抛异常，用 deleteIfExists 更安全）
        Files.deleteIfExists(file);
        Files.deleteIfExists(moved);
        Files.deleteIfExists(dir);
    }

    // ============================================================
    // 示例三：遍历目录（Files.walk / Files.list）
    // ============================================================
    static void traverseDirectory() throws IOException {
        // 创建测试目录结构
        Path root = Paths.get("walk_test");
        Files.createDirectories(root.resolve("sub1"));
        Files.createDirectories(root.resolve("sub2/deep"));
        Files.write(root.resolve("a.txt"), "aaa".getBytes());
        Files.write(root.resolve("sub1/b.txt"), "bbb".getBytes());
        Files.write(root.resolve("sub1/c.java"), "ccc".getBytes());
        Files.write(root.resolve("sub2/deep/d.txt"), "ddd".getBytes());

        // Files.walk：递归遍历所有层级（返回 Stream，JDK8）
        System.out.println("--- Files.walk 遍历所有文件 ---");
        // try-with-resources 关闭 Stream（walk 底层持有目录句柄）
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                System.out.println("  " + entry.getFileName()
                        + (Files.isDirectory(entry) ? "/" : ""));
            }
        }

        // 用 walkFileTree 递归遍历（JDK7，比 walk 更灵活）
        System.out.println("\n--- walkFileTree 遍历所有 .txt 文件 ---");
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(".txt")) {
                    System.out.println("  找到：" + file);
                }
                return FileVisitResult.CONTINUE; // 继续遍历
            }

            @Override
            public FileVisitResult visitFileFailed(Path file,
                    IOException exc) throws IOException {
                System.err.println("访问失败：" + file);
                return FileVisitResult.CONTINUE;
            }
        });

        // 递归删除非空目录（File.delete 无法删非空目录，walkFileTree 可以）
        System.out.println("\n--- 递归删除目录 ---");
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file,
                    BasicFileAttributes attrs) throws IOException {
                Files.delete(file); // 先删文件
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir,
                    IOException exc) throws IOException {
                Files.delete(dir); // 文件删完再删目录
                return FileVisitResult.CONTINUE;
            }
        });
        System.out.println("目录已删除：" + !Files.exists(root));
    }

    // ============================================================
    // 示例四：Path 的路径操作
    // ============================================================
    static void pathOperations() {
        Path p = Paths.get("/home/user/documents/report.pdf");

        System.out.println("完整路径：" + p);
        System.out.println("文件名：" + p.getFileName());         // report.pdf
        System.out.println("父路径：" + p.getParent());           // /home/user/documents
        System.out.println("根路径：" + p.getRoot());             // /
        System.out.println("路径段数：" + p.getNameCount());      // 4
        System.out.println("第2段：" + p.getName(1));             // user

        // 路径拼接
        Path base = Paths.get("/home/user");
        Path full = base.resolve("documents/report.pdf");
        System.out.println("拼接结果：" + full);

        // 相对路径计算
        Path from = Paths.get("/home/user/docs");
        Path to   = Paths.get("/home/user/downloads/file.txt");
        Path rel  = from.relativize(to);
        System.out.println("相对路径：" + rel); // ../downloads/file.txt

        // 路径规范化（消除 . 和 ..）
        Path messy = Paths.get("/home/user/../user/./docs");
        System.out.println("规范化：" + messy.normalize()); // /home/user/docs
    }

    // ============================================================
    // main
    // ============================================================
    public static void main(String[] args) throws Exception {
        System.out.println("=== File 类基本操作 ===");
        fileClassDemo();

        System.out.println("\n=== Path/Files 现代写法 ===");
        pathFilesDemo();

        System.out.println("\n=== 遍历目录 ===");
        traverseDirectory();

        System.out.println("\n=== Path 路径操作 ===");
        pathOperations();

        /*
         * 运行后观察：
         *   1. Files.write / readAllLines 几行代码完成文件读写，比 Stream 简洁得多
         *   2. walkFileTree 的 Visitor 模式：
         *      visitFile → 访问到文件时触发
         *      postVisitDirectory → 目录里所有内容访问完后触发（用于删目录）
         *   3. resolve()：路径拼接；relativize()：计算相对路径
         *   4. StandardCharsets.UTF_8 比字符串 "UTF-8" 更安全，编译期检查
         */
    }
}
