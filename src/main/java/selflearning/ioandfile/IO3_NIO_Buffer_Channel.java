package selflearning.ioandfile;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

/**
 * NIO 核心：Buffer（缓冲区）+ Channel（通道）
 *
 * NIO 和 BIO 的本质区别：
 *   BIO：流（Stream）是单向的，读流只能读，写流只能写
 *   NIO：通道（Channel）是双向的，既能读也能写
 *        数据必须先放进 Buffer，再通过 Channel 传输
 *
 * Buffer 三个关键属性（必须理解！）：
 *   capacity：缓冲区总容量，创建后不变
 *   position：下一次读/写的位置，随操作移动
 *   limit：可读/写的边界
 *
 *   写模式：position 从 0 开始，每写一个字节 position+1，limit=capacity
 *   读模式：flip() 后，limit=写入时的position，position=0，从头开始读
 *
 *   flip()：写 → 读 的切换（最容易忘！）
 *   clear()：读完后重置，准备下次写（position=0, limit=capacity）
 *   rewind()：position 归零，可以重新读一遍（limit 不变）
 */
public class IO3_NIO_Buffer_Channel {

    // ============================================================
    // 示例一：ByteBuffer 基本用法（彻底理解三个指针）
    // ============================================================
    static void bufferDemo() {
        System.out.println("--- ByteBuffer 状态变化演示 ---");

        // 创建容量为 10 的 ByteBuffer
        ByteBuffer buf = ByteBuffer.allocate(10);
        printState("初始状态", buf); // pos=0, limit=10, cap=10

        // 写入数据（写模式）
        buf.put((byte) 'H');
        buf.put((byte) 'e');
        buf.put((byte) 'l');
        buf.put((byte) 'l');
        buf.put((byte) 'o');
        printState("写入5字节后", buf); // pos=5, limit=10, cap=10

        // flip()：切换到读模式
        // limit = 当前 position（5），position = 0
        buf.flip();
        printState("flip() 后", buf); // pos=0, limit=5, cap=10

        // 读取数据
        while (buf.hasRemaining()) { // hasRemaining()：position < limit
            System.out.print((char) buf.get() + " ");
        }
        System.out.println();
        printState("读完后", buf); // pos=5, limit=5

        // clear()：重置，准备下次写
        buf.clear();
        printState("clear() 后", buf); // pos=0, limit=10, cap=10

        // compact()：把未读数据移到头部，继续写
        buf.put((byte) 'A');
        buf.put((byte) 'B');
        buf.put((byte) 'C');
        buf.flip();
        buf.get(); // 读掉一个 A
        buf.compact(); // B、C 移到头部，position=2
        printState("compact() 后", buf);
    }

    static void printState(String label, ByteBuffer buf) {
        System.out.printf("  %-20s pos=%d, limit=%d, cap=%d%n",
                label + "：", buf.position(), buf.limit(), buf.capacity());
    }

    // ============================================================
    // 示例二：FileChannel 读写文件（NIO 方式）
    // ============================================================
    static void fileChannelDemo() throws IOException {
        String path = "test_channel.txt";

        // 写文件（FileChannel 配合 ByteBuffer）
        try (FileChannel writeChannel = new FileOutputStream(path).getChannel()) {
            String content = "Hello NIO FileChannel!\n第二行内容";
            ByteBuffer buf = ByteBuffer.wrap(
                    content.getBytes(StandardCharsets.UTF_8));
            // wrap()：把已有的 byte[] 包装成 Buffer，position=0, limit=length
            writeChannel.write(buf);
        }
        System.out.println("FileChannel 写入完成");

        // 读文件
        try (FileChannel readChannel = new FileInputStream(path).getChannel()) {
            ByteBuffer buf = ByteBuffer.allocate(1024);

            // read() 把数据读进 Buffer，返回实际读到的字节数，-1 表示结束
            int bytesRead = readChannel.read(buf);
            System.out.println("读取了 " + bytesRead + " 字节");

            buf.flip(); // 切换读模式！
            String result = StandardCharsets.UTF_8.decode(buf).toString();
            System.out.println("内容：\n  " + result.replace("\n", "\n  "));
        }

        new File(path).delete();
    }

    // ============================================================
    // 示例三：Channel transferTo（零拷贝文件复制）
    // ============================================================
    static void zeroCopyDemo() throws IOException {
        // 准备源文件
        String src  = "zero_copy_src.txt";
        String dst  = "zero_copy_dst.txt";
        Files.write(Paths.get(src), "零拷贝传输的内容，效率很高".getBytes(StandardCharsets.UTF_8));

        try (FileChannel srcChannel = new FileInputStream(src).getChannel();
             FileChannel dstChannel = new FileOutputStream(dst).getChannel()) {

            long size = srcChannel.size();
            // transferTo：OS 层面直接传输，不经过用户空间的 ByteBuffer
            // 比先 read 到 Buffer 再 write 更快（减少了两次数据拷贝）
            long transferred = srcChannel.transferTo(0, size, dstChannel);
            System.out.println("零拷贝传输：" + transferred + " 字节");
        }

        // 验证
        byte[] bytes = Files.readAllBytes(Paths.get(dst));
        System.out.println("目标文件内容：" + new String(bytes, StandardCharsets.UTF_8));

        new File(src).delete();
        new File(dst).delete();
    }

    // ============================================================
    // 示例四：MappedByteBuffer —— 内存映射文件
    // 适合大文件的随机访问，把文件直接映射到内存，读写极快
    // ============================================================
    static void memoryMappedDemo() throws IOException {
        String path = "test_mapped.txt";
        int size = 1024;

        // 写入初始内容
        RandomAccessFile raf = new RandomAccessFile(path, "rw");
        raf.setLength(size); // 设定文件大小

        try (FileChannel channel = raf.getChannel()) {
            // 把文件映射到内存（READ_WRITE：可读可写）
            MappedByteBuffer mappedBuf = channel.map(
                    FileChannel.MapMode.READ_WRITE, 0, size);

            // 直接像操作内存一样操作文件
            mappedBuf.put(0,  (byte) 'H');
            mappedBuf.put(1,  (byte) 'i');
            mappedBuf.put(2,  (byte) '!');

            // 读取
            byte b0 = mappedBuf.get(0);
            byte b1 = mappedBuf.get(1);
            byte b2 = mappedBuf.get(2);
            System.out.println("内存映射读取：" + (char)b0 + (char)b1 + (char)b2);
        }

        raf.close();
        new File(path).delete();
        System.out.println("内存映射文件演示完成");
    }

    // ============================================================
    // 示例五：ByteBuffer 的直接缓冲区 vs 堆缓冲区
    // ============================================================
    static void directVsHeapBuffer() {
        // 堆缓冲区：在 JVM 堆上分配，受 GC 管理
        ByteBuffer heapBuf = ByteBuffer.allocate(1024);

        // 直接缓冲区：在 JVM 堆外分配，不受 GC 管理
        // 分配/释放开销大，但 IO 操作更快（省去一次堆内→堆外的复制）
        ByteBuffer directBuf = ByteBuffer.allocateDirect(1024);

        System.out.println("堆缓冲区 isDirect：" + heapBuf.isDirect());   // false
        System.out.println("直接缓冲区 isDirect：" + directBuf.isDirect()); // true

        System.out.println("选择建议：");
        System.out.println("  频繁 IO 操作（网络/文件）→ 直接缓冲区（allocateDirect）");
        System.out.println("  临时用用、数据处理      → 堆缓冲区（allocate）");
    }

    // ============================================================
    // main
    // ============================================================
    static java.nio.file.Path Paths_get(String s) throws IOException {
        return java.nio.file.Paths.get(s);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== ByteBuffer 三指针演示 ===");
        bufferDemo();

        System.out.println("\n=== FileChannel 读写 ===");
        fileChannelDemo();

        System.out.println("\n=== 零拷贝 transferTo ===");
        zeroCopyDemo();

        System.out.println("\n=== 内存映射文件 ===");
        memoryMappedDemo();

        System.out.println("\n=== 直接缓冲区 vs 堆缓冲区 ===");
        directVsHeapBuffer();

        /*
         * 运行后最重要的收获：
         *
         * flip() 是 NIO 最容易忘的操作：
         *   写完数据之后，一定要先 flip() 再 read！
         *   忘了 flip() 的话，position 还在末尾，read 什么都读不到
         *
         * Buffer 状态转换口诀：
         *   写完 → flip()   → 读
         *   读完 → clear()  → 写（丢弃未读数据）
         *   读完 → compact() → 写（保留未读数据）
         *   再读 → rewind() → 重新读
         *
         * FileChannel.transferTo 是文件复制的最优方案：
         *   比 BIO 的 read/write 循环快，因为数据不经过 Java 层
         */
    }

    // 借用 Files，避免 import 冲突
    static class Files {
        static void write(java.nio.file.Path path, byte[] bytes) throws IOException {
            java.nio.file.Files.write(path, bytes);
        }
        static byte[] readAllBytes(java.nio.file.Path path) throws IOException {
            return java.nio.file.Files.readAllBytes(path);
        }
    }
}
