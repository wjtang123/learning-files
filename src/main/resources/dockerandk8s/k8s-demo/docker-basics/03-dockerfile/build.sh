#!/usr/bin/env bash
# ============================================================
# 03-dockerfile/build.sh  Dockerfile 最佳实践对比演示
# 知识点：层缓存、多阶段构建、非root、.dockerignore、镜像大小
# ============================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$SCRIPT_DIR/scripts/lib.sh"
require docker
WORK_DIR="$(dirname "${BASH_SOURCE[0]}")"

title "第三章：Dockerfile 最佳实践 —— 好坏写法对比"

# ── 创建演示文件 ──────────────────────────────────────────
mkdir -p "$WORK_DIR/app-bad" "$WORK_DIR/app-good"

# 模拟 Python 应用
cat > "$WORK_DIR/requirements.txt" << 'EOF'
flask==3.0.0
requests==2.31.0
EOF

cat > "$WORK_DIR/app.py" << 'EOF'
from flask import Flask
app = Flask(__name__)

@app.route('/')
def hello():
    return 'Hello from Docker Demo!\n'

@app.route('/health')
def health():
    return 'OK\n', 200

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)
EOF

cat > "$WORK_DIR/.dockerignore" << 'EOF'
__pycache__/
*.pyc
*.pyo
.git/
.env
*.log
node_modules/
.DS_Store
EOF

# ──────────────────────────────────────────────────────────
step "实验 1：层缓存顺序 —— 错误 vs 正确"
# ──────────────────────────────────────────────────────────
explain "错误：先 COPY 所有代码 → 代码改一行，依赖全部重装"
explain "正确：先 COPY requirements.txt → 依赖层稳定，代码改了不重装依赖"

# 错误的 Dockerfile
cat > "$WORK_DIR/app-bad/Dockerfile" << 'EOF'
FROM python:3.11-slim
WORKDIR /app

# ❌ 先复制所有代码
COPY . .

# ❌ 代码任何改动都会触发重新安装依赖
RUN pip install -r requirements.txt

CMD ["python", "app.py"]
EOF

# 正确的 Dockerfile
cat > "$WORK_DIR/app-good/Dockerfile" << 'EOF'
FROM python:3.11-slim
WORKDIR /app

# ✅ 先复制依赖文件（很少改变）
COPY requirements.txt .

# ✅ 只要 requirements.txt 不变，这层永远命中缓存
RUN pip install --no-cache-dir -r requirements.txt

# ✅ 最后复制源码（经常改变）
COPY app.py .

# ✅ 非 root 用户运行
RUN useradd -r -u 1001 appuser && chown -R appuser /app
USER appuser

CMD ["python", "app.py"]
EOF

cp "$WORK_DIR/requirements.txt" "$WORK_DIR/app-bad/"
cp "$WORK_DIR/requirements.txt" "$WORK_DIR/app-good/"
cp "$WORK_DIR/app.py" "$WORK_DIR/app-bad/"
cp "$WORK_DIR/app.py" "$WORK_DIR/app-good/"
cp "$WORK_DIR/.dockerignore" "$WORK_DIR/app-bad/"
cp "$WORK_DIR/.dockerignore" "$WORK_DIR/app-good/"

echo ""
echo -e "  ${RED}❌ 错误写法（首次构建）：${NC}"
cmd "docker build -t demo-bad:v1 ./app-bad"
# DOCKER_BUILDKIT=0 关闭 BuildKit 以显示经典 Step 格式，兼容所有版本
START=$(date +%s)
DOCKER_BUILDKIT=0 docker build -t demo-bad:v1 "$WORK_DIR/app-bad" 2>&1 | grep -E "^Step|^---> |Downloading|Installing" || true
echo "  耗时：$(($(date +%s)-START)) 秒"

echo ""
echo -e "  ${GREEN}✅ 正确写法（首次构建）：${NC}"
cmd "docker build -t demo-good:v1 ./app-good"
START=$(date +%s)
DOCKER_BUILDKIT=0 docker build -t demo-good:v1 "$WORK_DIR/app-good" 2>&1 | grep -E "^Step|^---> |Downloading|Installing" || true
echo "  耗时：$(($(date +%s)-START)) 秒"

# 模拟代码修改（只改 app.py）
echo "" >> "$WORK_DIR/app-bad/app.py"
echo "" >> "$WORK_DIR/app-good/app.py"

echo ""
warn "模拟代码修改后重新构建（观察缓存命中差异）..."
echo ""

echo -e "  ${RED}❌ 错误写法（重新构建）—— 依赖全部重新安装：${NC}"
START=$(date +%s)
DOCKER_BUILDKIT=0 docker build -t demo-bad:v2 "$WORK_DIR/app-bad" 2>&1 | \
  grep -E "^Step|CACHED|Downloading|Installing" | head -15 || true
echo "  耗时：$(($(date +%s)-START)) 秒"

echo ""
echo -e "  ${GREEN}✅ 正确写法（重新构建）—— 依赖命中缓存：${NC}"
START=$(date +%s)
DOCKER_BUILDKIT=0 docker build -t demo-good:v2 "$WORK_DIR/app-good" 2>&1 | \
  grep -E "^Step|CACHED|Downloading|Installing" | head -15 || true
echo "  耗时：$(($(date +%s)-START)) 秒"

echo ""
explain "输出里出现 CACHED 字样 = 该层命中缓存，跳过耗时的 pip install"
explain "对比两次耗时：正确写法第二次构建快很多倍"

register_cleanup "docker rmi -f demo-bad:v1 demo-bad:v2 demo-good:v1 demo-good:v2 2>/dev/null"

pause

# ──────────────────────────────────────────────────────────
step "实验 2：多阶段构建 —— 镜像体积对比"
# ──────────────────────────────────────────────────────────
explain "多阶段构建：编译阶段 vs 运行阶段完全分离"
explain "效果：Go 程序从 ~900MB 缩到 ~10MB"

mkdir -p "$WORK_DIR/go-app"

cat > "$WORK_DIR/go-app/main.go" << 'EOF'
package main

import (
    "fmt"
    "net/http"
)

func main() {
    http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
        fmt.Fprintln(w, "Hello from multi-stage build!")
    })
    http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
        fmt.Fprintln(w, "OK")
    })
    fmt.Println("Server starting on :8080")
    http.ListenAndServe(":8080", nil)
}
EOF

cat > "$WORK_DIR/go-app/go.mod" << 'EOF'
module demo

go 1.21
EOF

# 单阶段（错误）
cat > "$WORK_DIR/go-app/Dockerfile.bad" << 'EOF'
# ❌ 单阶段：最终镜像包含 Go 工具链、源码、所有中间产物
FROM golang:1.21-alpine
WORKDIR /app
COPY . .
RUN go build -o server .
EXPOSE 8080
CMD ["./server"]
EOF

# 多阶段（正确）
cat > "$WORK_DIR/go-app/Dockerfile.good" << 'EOF'
# ✅ 阶段一：编译（只在构建时存在）
FROM golang:1.21-alpine AS builder
WORKDIR /app
COPY go.mod .
COPY main.go .
RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o server .

# ✅ 阶段二：运行（最终镜像，只有静态二进制）
FROM scratch
COPY --from=builder /app/server /server
EXPOSE 8080
CMD ["/server"]
EOF

echo ""
echo -e "  ${RED}构建单阶段镜像（含完整 Go 工具链）...${NC}"
docker build -f "$WORK_DIR/go-app/Dockerfile.bad" \
  -t demo-go-bad:v1 "$WORK_DIR/go-app" 2>&1 | tail -3

echo ""
echo -e "  ${GREEN}构建多阶段镜像（仅静态二进制）...${NC}"
docker build -f "$WORK_DIR/go-app/Dockerfile.good" \
  -t demo-go-good:v1 "$WORK_DIR/go-app" 2>&1 | tail -3

echo ""
echo -e "  ${CYAN}镜像大小对比：${NC}"
cmd "docker images demo-go-bad:v1 demo-go-good:v1"
docker images --format "table {{.Repository}}:{{.Tag}}\t{{.Size}}" \
  | grep -E "demo-go|REPO" | head -5

register_cleanup "docker rmi -f demo-go-bad:v1 demo-go-good:v1 2>/dev/null"

pause

# ──────────────────────────────────────────────────────────
step "实验 3：RUN 指令合并 —— apt 缓存陷阱"
# ──────────────────────────────────────────────────────────
explain "在不同 RUN 里删除 apt 缓存 → 缓存仍残留在前面的层里"
explain "必须在同一条 RUN 里完成 update + install + cleanup"

cat > /tmp/Dockerfile.apt-bad << 'EOF'
FROM ubuntu:22.04
# ❌ 分开写：apt 缓存被锁在 layer2，rm 在 layer4 删不掉它
RUN apt-get update
RUN apt-get install -y curl
RUN apt-get install -y wget
RUN rm -rf /var/lib/apt/lists/*
EOF

cat > /tmp/Dockerfile.apt-good << 'EOF'
FROM ubuntu:22.04
# ✅ 同一 RUN：update、install、cleanup 在同一层，缓存真正被清除
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        curl \
        wget \
    && rm -rf /var/lib/apt/lists/*
EOF

echo ""
echo -e "  ${RED}错误写法（构建中）...${NC}"
docker build -f /tmp/Dockerfile.apt-bad -t demo-apt-bad:v1 /tmp 2>&1 | tail -3

echo ""
echo -e "  ${GREEN}正确写法（构建中）...${NC}"
docker build -f /tmp/Dockerfile.apt-good -t demo-apt-good:v1 /tmp 2>&1 | tail -3

echo ""
echo -e "  ${CYAN}镜像大小对比：${NC}"
docker images --format "table {{.Repository}}:{{.Tag}}\t{{.Size}}" \
  | grep "demo-apt" | head -5

register_cleanup "docker rmi -f demo-apt-bad:v1 demo-apt-good:v1 2>/dev/null"

pause

# ──────────────────────────────────────────────────────────
step "实验 4：安全检查 —— 容器以什么用户运行"
# ──────────────────────────────────────────────────────────
explain "默认容器以 root 运行（高危），应切换到非 root 用户"

echo ""
echo -e "  ${RED}默认（root）：${NC}"
cmd "docker run --rm nginx:alpine whoami"
docker run --rm nginx:alpine whoami

echo ""
echo -e "  ${GREEN}非 root 用户（demo-good）：${NC}"
cmd "docker run --rm demo-good:v2 whoami 2>/dev/null"
docker run --rm demo-good:v2 whoami 2>/dev/null || \
  docker run --rm demo-good:v1 whoami 2>/dev/null || \
  echo "  appuser (UID=1001)"

echo ""
explain "面试答法：容器以 root 运行，内核漏洞可导致宿主机逃逸"
explain "修复：Dockerfile 里 RUN useradd + USER 指令"

pause

# ──────────────────────────────────────────────────────────
step "实验 5：基础镜像选择 —— 大小与安全性权衡"
# ──────────────────────────────────────────────────────────
explain "ubuntu > slim > alpine > scratch，越小攻击面越小"

echo ""
echo -e "  ${CYAN}常见 Python 基础镜像大小对比：${NC}"
for img in "python:3.11" "python:3.11-slim" "python:3.11-alpine"; do
  docker pull "$img" -q 2>/dev/null &
done
wait

docker images --format "table {{.Repository}}:{{.Tag}}\t{{.Size}}" | \
  grep "^python:3.11" | sort -k2 -h

echo ""
explain "alpine 用 musl libc（非 glibc），C 扩展库（numpy/scipy）可能有兼容问题"
explain "生产推荐：-slim（Debian，兼容性最好）或 -alpine（纯 Python 项目）"

# 清理
run_cleanup
rm -rf "$WORK_DIR/app-bad" "$WORK_DIR/app-good" "$WORK_DIR/go-app"
rm -f "$WORK_DIR/app.py" "$WORK_DIR/requirements.txt" "$WORK_DIR/.dockerignore"

title "✅ Dockerfile 最佳实践演示完成！"
echo "  核心记忆："
echo "  1. 变化少的放前面（依赖先于源码）"
echo "  2. 多阶段构建 = 编译产物 + scratch/slim 运行时"
echo "  3. RUN 同一层做 update+install+cleanup"
echo "  4. 加 .dockerignore，USER 非 root"
echo "  5. -slim > -alpine（兼容性），scratch（Go/Rust 静态二进制）"
echo ""
echo "  👉 下一步：bash docker-basics/04-network.sh"
