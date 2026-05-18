#!/usr/bin/env bash
# ============================================================
# run-all.sh  交互式菜单 —— 选择运行哪个演示
# ============================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/scripts/lib.sh"

DEMOS=(
  "00-setup.sh|安装环境（Docker + kubectl + minikube）"
  "docker-basics/01-namespace-cgroup.sh|Namespace & Cgroup 实验"
  "docker-basics/02-overlayfs.sh|OverlayFS 镜像分层 & CoW"
  "docker-basics/03-dockerfile/build.sh|Dockerfile 最佳实践对比"
  "docker-basics/04-network.sh|Docker 四种网络模式"
  "docker-advanced/05-compose/up.sh|Docker Compose 多服务编排"
  "k8s-core/08-pod-deploy.sh|Pod / Deployment / 滚动更新"
  "k8s-core/09-service.sh|Service 四种类型"
  "k8s-arch/12-etcd-inspect.sh|etcd & Scheduler & Controller"
  "k8s-ops/15-hpa.sh|HPA / Probe / 故障排查综合演示"
  "scripts/cleanup.sh|清理所有演示资源"
)

while true; do
  title "Docker & Kubernetes 学习 Demo 套件"
  echo "  选择要运行的演示（输入数字，q 退出，a 全部运行）："
  echo ""
  for i in "${!DEMOS[@]}"; do
    IFS='|' read -r _ desc <<< "${DEMOS[$i]}"
    printf "  ${CYAN}%2d${NC}. %s\n" $((i+1)) "$desc"
  done
  echo ""
  read -rp "  请选择 [1-${#DEMOS[@]}/a/q]: " CHOICE

  case "$CHOICE" in
    q|Q|quit|exit) echo "再见！"; exit 0 ;;
    a|A|all)
      for demo in "${DEMOS[@]}"; do
        IFS='|' read -r script _ <<< "$demo"
        [[ -f "$SCRIPT_DIR/$script" ]] && bash "$SCRIPT_DIR/$script" || true
      done
      ;;
    [0-9]|[0-9][0-9])
      IDX=$((CHOICE - 1))
      if [[ $IDX -ge 0 && $IDX -lt ${#DEMOS[@]} ]]; then
        IFS='|' read -r script desc <<< "${DEMOS[$IDX]}"
        title "运行：$desc"
        bash "$SCRIPT_DIR/$script"
      else
        warn "无效选择，请输入 1-${#DEMOS[@]}"
      fi
      ;;
    *) warn "无效输入" ;;
  esac

  echo ""
  read -rp "  返回菜单？[Enter] 或 q 退出: " BACK
  [[ "$BACK" =~ ^[qQ]$ ]] && exit 0
done
