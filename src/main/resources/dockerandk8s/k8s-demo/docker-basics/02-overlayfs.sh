#!/usr/bin/env bash
# ============================================================
# 02-overlayfs.sh  UnionFS / OverlayFS 镜像分层 & CoW 演示
# 知识点：lowerdir/upperdir/merged、写时复制、whiteout、层共享
# ============================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/scripts/lib.sh"
require docker

title "第二章：OverlayFS 镜像分层 & 写时复制（CoW）"

# ──────────────────────────────────────────────────────────
step "实验 1：镜像层结构 —— docker history"
# ──────────────────────────────────────────────────────────
explain "每条 Dockerfile 指令（RUN/COPY/ADD）产生一个新的只读层"

echo ""
docker pull nginx:alpine -q

cmd "docker history nginx:alpine --format 'table {{.CreatedBy}}\t{{.Size}}'"
docker history nginx:alpine \
  --format 'table {{.CreatedBy}}\t{{.Size}}' 2>/dev/null | head -20

echo ""
explain "每行对应一层，Size=0 的是元数据层（ENV/LABEL/EXPOSE），不产生文件变更"

pause

# ──────────────────────────────────────────────────────────
step "实验 2：镜像层共享 —— 拉取两个共享基础层的镜像"
# ──────────────────────────────────────────────────────────
explain "基于相同基础镜像的不同镜像，共享底层，磁盘只存一份"

echo ""
warn "拉取 node:20-alpine 和 python:3.11-alpine（都基于 alpine）..."
docker pull node:20-alpine -q 2>/dev/null &
docker pull python:3.11-alpine -q 2>/dev/null &
wait

echo ""
echo -e "  ${CYAN}node:20-alpine 的层：${NC}"
docker inspect node:20-alpine | jq -r '.[0].RootFS.Layers[]' | head -5

echo ""
echo -e "  ${CYAN}python:3.11-alpine 的层：${NC}"
docker inspect python:3.11-alpine | jq -r '.[0].RootFS.Layers[]' | head -5

echo ""
explain "输出中相同的 sha256 digest = 该层只在磁盘上存一份，被两个镜像共享"

pause

# ──────────────────────────────────────────────────────────
step "实验 3：OverlayFS 三层结构 —— 直接看文件系统"
# ──────────────────────────────────────────────────────────
explain "OverlayFS = lowerdir（只读）+ upperdir（可写）= merged（容器看到的）"

CNAME="overlay-demo-$$"
docker run -d --name "$CNAME" nginx:alpine > /dev/null
register_cleanup "docker rm -f $CNAME"

OVERLAY_DIR="/var/lib/docker/overlay2"

# 找到该容器的 overlay 挂载目录
MERGED_DIR=""
if [[ -d "$OVERLAY_DIR" ]]; then
  MERGED_DIR=$(docker inspect --format \
    '{{index .GraphDriver.Data "MergedDir"}}' "$CNAME" 2>/dev/null || true)
fi

echo ""
echo -e "  ${CYAN}容器的 OverlayFS 挂载信息：${NC}"
cmd "docker inspect $CNAME | jq '.[0].GraphDriver'"
docker inspect "$CNAME" | jq '.[0].GraphDriver' 2>/dev/null || true

if [[ -n "$MERGED_DIR" && -d "$MERGED_DIR" ]]; then
  echo ""
  echo -e "  ${CYAN}Merged 目录内容（容器内看到的文件系统）：${NC}"
  ls "$MERGED_DIR" 2>/dev/null | head -20
fi

echo ""
explain "lowerdir = 镜像所有只读层（用冒号分隔多个）"
explain "upperdir = 容器可写层（容器删除后消失）"
explain "merged = 叠加后的统一视图（容器内 / 看到的）"

pause

# ──────────────────────────────────────────────────────────
step "实验 4：写时复制（CoW）—— 修改文件的代价"
# ──────────────────────────────────────────────────────────
explain "第一次写只读层的文件 → 先复制到 upperdir → 再修改副本"
explain "lowerdir 原件永远不变！"

echo ""
explain "读取只读层文件（零代价）："
cmd "docker exec $CNAME cat /etc/nginx/nginx.conf | head -5"
docker exec "$CNAME" cat /etc/nginx/nginx.conf 2>/dev/null | head -5

echo ""
explain "修改文件（触发 copy-up）："
cmd "docker exec $CNAME sh -c 'echo test >> /etc/nginx/nginx.conf'"
docker exec "$CNAME" sh -c 'echo "# test" >> /etc/nginx/nginx.conf'

echo ""
echo -e "  ${CYAN}查看 upperdir 中出现的 copy-up 文件：${NC}"
UPPER_DIR=$(docker inspect --format \
  '{{index .GraphDriver.Data "UpperDir"}}' "$CNAME" 2>/dev/null || true)

if [[ -n "$UPPER_DIR" && -d "$UPPER_DIR" ]]; then
  cmd "find $UPPER_DIR -type f | head -10"
  find "$UPPER_DIR" -type f 2>/dev/null | head -10
  explain "nginx.conf 已被复制到 upperdir，修改在这里，lowerdir 中的原件不变"
else
  warn "需要 root 权限查看 upperdir（Docker 数据目录）"
fi

pause

# ──────────────────────────────────────────────────────────
step "实验 5：Whiteout 文件 —— 容器删除只读文件的机制"
# ──────────────────────────────────────────────────────────
explain "删除只读层的文件 → OverlayFS 在 upperdir 创建 whiteout 标记文件"
explain "lowerdir 原件依然存在，只是被遮蔽！"

echo ""
cmd "docker exec $CNAME ls /usr/sbin/nginx"
docker exec "$CNAME" ls /usr/sbin/nginx 2>/dev/null

explain "尝试删除 nginx 二进制文件（演示 whiteout 机制）..."
cmd "docker exec $CNAME rm /usr/sbin/nginx"
docker exec "$CNAME" rm /usr/sbin/nginx 2>/dev/null || true

echo ""
echo -e "  ${CYAN}删除后 upperdir 中的 whiteout 文件：${NC}"
if [[ -n "$UPPER_DIR" && -d "$UPPER_DIR" ]]; then
  cmd "find $UPPER_DIR -name '.wh.*' 2>/dev/null"
  find "$UPPER_DIR" -name '.wh.*' 2>/dev/null || echo "  （需要 root 权限）"
fi

explain "whiteout 文件（.wh.nginx）遮蔽了 lowerdir 里的 nginx，容器内看不到了"
explain "但 lowerdir 里的 nginx 实际上完好无损！"

pause

# ──────────────────────────────────────────────────────────
step "实验 6：容器删除后数据消失 vs Volume 持久化"
# ──────────────────────────────────────────────────────────
explain "写进容器层（upperdir）的数据随容器删除而消失"
explain "写进 Volume 的数据永久保留"

echo ""
CVOL="vol-persist-$$"
VOL_NAME="demo-vol-$$"
docker volume create "$VOL_NAME" > /dev/null
register_cleanup "docker volume rm $VOL_NAME 2>/dev/null"

# 写入数据到 volume
docker run --rm \
  -v "$VOL_NAME:/data" \
  alpine sh -c 'echo "我是持久化数据" > /data/test.txt'

# 写入数据到容器层
docker run --name "$CVOL" alpine sh -c 'echo "我会消失" > /tmp/test.txt' 2>/dev/null || true

echo -e "  ${GREEN}✅ Volume 数据（容器删除后）：${NC}"
docker run --rm -v "$VOL_NAME:/data" alpine cat /data/test.txt

echo ""
echo -e "  ${RED}❌ 容器层数据（容器已删除）：${NC}"
docker rm -f "$CVOL" 2>/dev/null || true
echo "  容器已删除，数据不可恢复"

pause

# ──────────────────────────────────────────────────────────
step "实验 7：dive 工具 —— 可视化分析镜像层"
# ──────────────────────────────────────────────────────────
explain "dive 工具可以直观看到每一层里有哪些文件变更"

if command -v dive &>/dev/null; then
  cmd "dive nginx:alpine --ci --lowestEfficiency=0.9"
  dive nginx:alpine --ci --lowestEfficiency=0.9 2>/dev/null | tail -20 || true
else
  warn "dive 未安装，展示等效命令："
  cmd "docker save nginx:alpine | tar -xO | tar -tv 2>/dev/null | head -20"
fi

echo ""
explain "实际使用：dive <image> 进入交互模式，左侧看层，右侧看文件变更"

run_cleanup

title "✅ OverlayFS 实验完成！"
echo "  核心记忆："
echo "  • lowerdir = 镜像只读层（多个，用:分隔）"
echo "  • upperdir = 容器可写层（容器专属，删除即消失）"
echo "  • merged   = 统一视图（容器内看到的 /）"
echo "  • CoW：第一次写只读文件 → copy-up 到 upperdir → 修改副本"
echo "  • whiteout：删除只读文件 → 创建 .wh 标记 → lowerdir 原件不变"
echo ""
echo "  👉 下一步：bash docker-basics/03-dockerfile/build.sh"
