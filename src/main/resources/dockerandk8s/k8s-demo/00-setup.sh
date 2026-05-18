#!/usr/bin/env bash
# ============================================================
# 00-setup.sh  环境安装脚本
# 支持：Ubuntu 20.04 / 22.04 / Debian 11/12
# 用法：sudo bash 00-setup.sh [--mode docker|k8s|all] [--k8s minikube|kubeadm]
# ============================================================
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/scripts/lib.sh"

MODE="all"          # docker | k8s | all
K8S_MODE="minikube" # minikube（单机）| kubeadm（多机）

while [[ $# -gt 0 ]]; do
  case $1 in
    --mode) MODE="$2"; shift 2 ;;
    --k8s)  K8S_MODE="$2"; shift 2 ;;
    *) shift ;;
  esac
done

title "环境安装脚本 | mode=$MODE k8s=$K8S_MODE"
[[ $EUID -ne 0 ]] && { err "请用 sudo 运行此脚本"; exit 1; }

OS=$(. /etc/os-release && echo "$ID")
ARCH=$(uname -m)
info "检测到系统: $OS / $ARCH"

# ── 基础工具 ──────────────────────────────────────────────
step "安装基础工具"
apt-get update -qq
apt-get install -y -qq \
  curl wget git vim jq tree htop net-tools \
  apt-transport-https ca-certificates gnupg lsb-release \
  iproute2 iptables iputils-ping dnsutils \
  cgroup-tools sysstat \
  > /dev/null
ok "基础工具安装完成"

# ── Docker ────────────────────────────────────────────────
install_docker() {
  step "安装 Docker"
  if command -v docker &>/dev/null; then
    ok "Docker 已安装: $(docker --version)"; return
  fi

  # 添加 Docker 官方 GPG key 和源
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/$OS/gpg \
    | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg

  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
    https://download.docker.com/linux/$OS $(lsb_release -cs) stable" \
    > /etc/apt/sources.list.d/docker.list

  apt-get update -qq
  apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-compose-plugin
  systemctl enable --now docker

  # 允许当前用户不用 sudo 运行 docker
  usermod -aG docker "${SUDO_USER:-$USER}" 2>/dev/null || true

  ok "Docker 安装完成: $(docker --version)"
}

# ── kubectl ───────────────────────────────────────────────
install_kubectl() {
  step "安装 kubectl"
  if command -v kubectl &>/dev/null; then
    ok "kubectl 已安装: $(kubectl version --client --short 2>/dev/null)"; return
  fi
  K8S_VER=$(curl -fsSL https://dl.k8s.io/release/stable.txt)
  curl -fsSLO "https://dl.k8s.io/release/${K8S_VER}/bin/linux/amd64/kubectl"
  install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl
  rm kubectl
  ok "kubectl 安装完成: $(kubectl version --client --short 2>/dev/null)"
}

# ── minikube（单机 k8s）───────────────────────────────────
install_minikube() {
  step "安装 minikube"
  if command -v minikube &>/dev/null; then
    ok "minikube 已安装"; return
  fi
  curl -fsSLO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
  install minikube-linux-amd64 /usr/local/bin/minikube
  rm minikube-linux-amd64
  ok "minikube 安装完成"
}

start_minikube() {
  step "启动 minikube 集群"
  if minikube status 2>/dev/null | grep -q "Running"; then
    ok "minikube 已在运行"; return
  fi
  # 以非 root 用户启动（minikube 不允许 root）
  if [[ $EUID -eq 0 ]]; then
    su - "${SUDO_USER:-ubuntu}" -c \
      "minikube start --driver=docker --cpus=2 --memory=3g \
       # --kubernetes-version 留空使用最新稳定版 \
       --addons=metrics-server,ingress"
  else
    minikube start --driver=docker --cpus=2 --memory=3g \
      # --kubernetes-version 留空使用最新稳定版 \
      --addons=metrics-server,ingress
  fi
  ok "minikube 启动完成"
}

# ── kubeadm（多机 k8s）────────────────────────────────────
install_kubeadm() {
  step "安装 kubeadm / kubelet / kubectl"
  if command -v kubeadm &>/dev/null; then
    ok "kubeadm 已安装"; return
  fi

  # 关闭 swap（k8s 要求）
  swapoff -a
  sed -i '/ swap / s/^\(.*\)$/#\1/g' /etc/fstab

  # 加载必要内核模块
  cat > /etc/modules-load.d/k8s.conf << 'EOF'
overlay
br_netfilter
EOF
  modprobe overlay
  modprobe br_netfilter

  # 内核参数
  cat > /etc/sysctl.d/k8s.conf << 'EOF'
net.bridge.bridge-nf-call-iptables  = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward                 = 1
EOF
  sysctl --system > /dev/null

  # 安装 kubeadm 等
  curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.29/deb/Release.key \
    | gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg
  echo "deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] \
    https://pkgs.k8s.io/core:/stable:/v1.29/deb/ /" \
    > /etc/apt/sources.list.d/kubernetes.list

  apt-get update -qq
  apt-get install -y -qq kubelet kubeadm kubectl
  apt-mark hold kubelet kubeadm kubectl
  systemctl enable --now kubelet

  ok "kubeadm 安装完成"
}

init_master() {
  step "初始化 k8s 控制面（master 节点）"
  local POD_CIDR="10.244.0.0/16"
  local API_IP=$(hostname -I | awk '{print $1}')

  kubeadm init \
    --apiserver-advertise-address="$API_IP" \
    --pod-network-cidr="$POD_CIDR" \
    --kubernetes-version=1.29.0 \
    2>&1 | tail -30

  # 配置 kubectl
  mkdir -p /home/"${SUDO_USER:-root}"/.kube
  cp /etc/kubernetes/admin.conf /home/"${SUDO_USER:-root}"/.kube/config
  chown "${SUDO_USER:-root}":"${SUDO_USER:-root}" \
    /home/"${SUDO_USER:-root}"/.kube/config

  # 安装 Flannel CNI
  kubectl --kubeconfig=/etc/kubernetes/admin.conf apply -f \
    https://github.com/flannel-io/flannel/releases/latest/download/kube-flannel.yml

  ok "控制面初始化完成！"
  warn "请在 worker 节点运行上面输出的 kubeadm join 命令"

  # 安装 metrics-server（HPA 需要）
  kubectl --kubeconfig=/etc/kubernetes/admin.conf apply -f \
    https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
}

# ── 辅助工具 ──────────────────────────────────────────────
install_extras() {
  step "安装辅助工具"

  # helm
  if ! command -v helm &>/dev/null; then
    curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
    ok "helm 安装完成"
  fi

  # k9s（k8s TUI 管理工具）
  if ! command -v k9s &>/dev/null; then
    K9S_VER=$(curl -fsSL https://api.github.com/repos/derailed/k9s/releases/latest \
      | jq -r '.tag_name')
    curl -fsSL "https://github.com/derailed/k9s/releases/download/${K9S_VER}/k9s_Linux_amd64.tar.gz" \
      | tar -xz -C /usr/local/bin k9s
    ok "k9s 安装完成"
  fi

  # dive（镜像层分析工具）
  if ! command -v dive &>/dev/null; then
    DIVE_VER=$(curl -fsSL https://api.github.com/repos/wagoodman/dive/releases/latest \
      | jq -r '.tag_name' | tr -d v)
    curl -fsSLO "https://github.com/wagoodman/dive/releases/download/v${DIVE_VER}/dive_${DIVE_VER}_linux_amd64.deb"
    dpkg -i "dive_${DIVE_VER}_linux_amd64.deb" > /dev/null
    rm "dive_${DIVE_VER}_linux_amd64.deb"
    ok "dive 安装完成"
  fi
}

# ── 执行 ──────────────────────────────────────────────────
[[ "$MODE" == "docker" || "$MODE" == "all" ]] && install_docker
[[ "$MODE" == "k8s"    || "$MODE" == "all" ]] && install_kubectl

if [[ "$MODE" == "k8s" || "$MODE" == "all" ]]; then
  if [[ "$K8S_MODE" == "minikube" ]]; then
    install_minikube
    start_minikube
  else
    install_kubeadm
    # master/worker 判断由用户自行选择
    read -rp "  这是 master 节点吗？[y/N] " IS_MASTER
    [[ "$IS_MASTER" =~ ^[Yy]$ ]] && init_master
  fi
fi

install_extras

title "✅ 安装完成！"
echo "  Docker:    $(docker --version 2>/dev/null || echo '未安装')"
echo "  kubectl:   $(kubectl version --client --short 2>/dev/null || echo '未安装')"
echo "  minikube:  $(minikube version 2>/dev/null | head -1 || echo '未安装')"
echo ""
echo "  👉 下一步：bash docker-basics/01-namespace-cgroup.sh"
