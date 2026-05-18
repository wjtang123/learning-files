#!/usr/bin/env bash
# ============================================================
# 公共函数库 - 所有演示脚本通过 source 引入
# ============================================================

# ── 颜色常量 ──────────────────────────────────────────────
RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

# ── 输出函数 ──────────────────────────────────────────────
info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()      { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()     { echo -e "${RED}[ERR]${NC}   $*" >&2; }
title()   { echo -e "\n${BOLD}${BLUE}══════════════════════════════════════════${NC}"; \
            echo -e "${BOLD}${BLUE}  $*${NC}"; \
            echo -e "${BOLD}${BLUE}══════════════════════════════════════════${NC}\n"; }
step()    { echo -e "\n${BOLD}▶ $*${NC}"; }
explain() { echo -e "${YELLOW}  💡 $*${NC}"; }
cmd()     { echo -e "${GREEN}  \$ $*${NC}"; }
sep()     { echo -e "${BLUE}  ──────────────────────────────────────${NC}"; }

# ── 暂停等待用户确认 ──────────────────────────────────────
pause() {
  local msg="${1:-按 Enter 继续下一步...}"
  echo -e "\n${YELLOW}  ⏸  ${msg}${NC}"
  read -r
}

# ── 等待条件满足 ──────────────────────────────────────────
wait_for() {
  local desc="$1"; shift
  local max="${1:-60}"; shift
  local interval="${1:-2}"; shift
  local cmd="$*"
  local elapsed=0
  echo -ne "${CYAN}  等待 ${desc}...${NC}"
  while ! eval "$cmd" &>/dev/null; do
    sleep "$interval"
    elapsed=$((elapsed + interval))
    echo -ne "."
    if [[ $elapsed -ge $max ]]; then
      echo -e " ${RED}超时！${NC}"; return 1
    fi
  done
  echo -e " ${GREEN}就绪！${NC}"
}

# ── 等待 k8s Pod 就绪 ─────────────────────────────────────
wait_pod_ready() {
  local label="$1"
  local ns="${2:-default}"
  local timeout="${3:-120}"
  wait_for "Pod($label) 就绪" "$timeout" 3 \
    "kubectl get pod -l $label -n $ns 2>/dev/null | grep -q Running"
}

# ── 等待 Deployment 完成 ──────────────────────────────────
wait_deploy_ready() {
  local name="$1"
  local ns="${2:-default}"
  kubectl rollout status deployment/"$name" -n "$ns" --timeout=120s
}

# ── 检查命令是否存在 ──────────────────────────────────────
require() {
  for cmd in "$@"; do
    if ! command -v "$cmd" &>/dev/null; then
      err "缺少依赖：$cmd，请先运行 00-setup.sh"
      exit 1
    fi
  done
}

# ── 清理函数注册（trap 用） ───────────────────────────────
CLEANUP_CMDS=()
register_cleanup() { CLEANUP_CMDS+=("$1"); }
run_cleanup() {
  info "清理演示资源..."
  for cmd in "${CLEANUP_CMDS[@]}"; do eval "$cmd" 2>/dev/null || true; done
  ok "清理完成"
}

# ── 运行命令并展示输出 ────────────────────────────────────
run_show() {
  local desc="$1"; shift
  echo -e "\n${CYAN}── ${desc} ──${NC}"
  cmd "$*"
  eval "$*"
  echo
}

# ── 对比两个命令的输出 ────────────────────────────────────
compare() {
  local title1="$1"; local cmd1="$2"
  local title2="$3"; local cmd2="$4"
  echo -e "\n${BOLD}  对比：${title1}  vs  ${title2}${NC}"
  sep
  echo -e "${RED}  ❌ ${title1}:${NC}"
  eval "$cmd1" 2>&1 | sed 's/^/     /'
  sep
  echo -e "${GREEN}  ✅ ${title2}:${NC}"
  eval "$cmd2" 2>&1 | sed 's/^/     /'
  sep
}
